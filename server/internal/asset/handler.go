// asset 服务的 HTTP 处理器 — 分片上传 + 签名 URL 下载（详见 02-api-contract.md §5）。
//
// 实现复用 MinIO 的 multipart upload API：
//
//	init     → core.NewMultipartUpload(...) 拿 s3_upload_id；写 upload_sessions
//	part     → core.PutObjectPart(...) 拿 etag；存 Redis hash upload:<id>:parts[n] = etag
//	complete → 从 Redis 拉 etag list → core.CompleteMultipartUpload → 写 inspection_assets + audit
//
// 客户端可断点续传：part 接口幂等，重复 PUT 同一 part_number 覆盖 etag；complete 时按
// 收到的 total_chunks 找全。
package asset

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/minio/minio-go/v7"
	miniocore "github.com/minio/minio-go/v7/pkg/credentials"
	"github.com/redis/go-redis/v9"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

// Config 启动时一次性传入。
type Config struct {
	MinIOEndpoint   string // 例 "127.0.0.1:9000"
	MinIOAccessKey  string
	MinIOSecretKey  string
	MinIOUseSSL     bool
	Bucket          string // 例 "gomob-assets"
	DefaultChunkMB  int    // 默认 4
	PresignDuration time.Duration
}

func DefaultConfig() Config {
	return Config{
		MinIOEndpoint:   "127.0.0.1:9000",
		MinIOAccessKey:  "gomob",
		MinIOSecretKey:  "gomob_dev_minio",
		MinIOUseSSL:     false,
		Bucket:          "gomob-assets",
		// S3 / MinIO 强制：除最后一片外，每片最小 5 MB。默认 8 MB 留余量。
		DefaultChunkMB:  8,
		PresignDuration: 5 * time.Minute,
	}
}

type Handler struct {
	cfg    Config
	pool   *pgxpool.Pool
	rdb    *redis.Client
	mc     *minio.Client // 高层 client（PresignedGetObject 用）
	core   *minio.Core   // 低层 core（multipart 用）
	assets *repo.AssetRepo
	insps  *repo.InspectionRepo
	audit  audit.Recorder
	log    *slog.Logger
}

func NewHandler(cfg Config, pool *pgxpool.Pool, rdb *redis.Client, audit audit.Recorder) (*Handler, error) {
	mc, err := minio.New(cfg.MinIOEndpoint, &minio.Options{
		Creds:  miniocore.NewStaticV4(cfg.MinIOAccessKey, cfg.MinIOSecretKey, ""),
		Secure: cfg.MinIOUseSSL,
	})
	if err != nil {
		return nil, err
	}
	core := &minio.Core{Client: mc}
	// 启动时确保 bucket 存在
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	exists, err := mc.BucketExists(ctx, cfg.Bucket)
	if err != nil {
		return nil, fmt.Errorf("bucket exists check: %w", err)
	}
	if !exists {
		if err := mc.MakeBucket(ctx, cfg.Bucket, minio.MakeBucketOptions{}); err != nil {
			return nil, fmt.Errorf("make bucket: %w", err)
		}
	}
	return &Handler{
		cfg:    cfg,
		pool:   pool,
		rdb:    rdb,
		mc:     mc,
		core:   core,
		assets: repo.NewAssetRepo(pool),
		insps:  repo.NewInspectionRepo(pool),
		audit:  audit,
		log:    logger.New("asset.handler"),
	}, nil
}

func (h *Handler) Mount(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/assets/upload/init", h.UploadInit)
	mux.HandleFunc("PUT /v1/assets/upload/{upload_id}/chunk/{n}", h.UploadPart)
	mux.HandleFunc("POST /v1/assets/upload/{upload_id}/complete", h.UploadComplete)
	mux.HandleFunc("POST /v1/assets/upload/{upload_id}/abort", h.UploadAbort)
	mux.HandleFunc("GET /v1/assets/{id}/url", h.PresignDownload)
}

// ---------- helpers ----------

func callerUserID(r *http.Request) int64 {
	v := r.Header.Get("X-Gomob-User-Id")
	if v == "" {
		return 0
	}
	id, _ := strconv.ParseInt(v, 10, 64)
	return id
}

func newUploadID() (string, error) {
	b := make([]byte, 12)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return "u_" + hex.EncodeToString(b), nil
}

func (h *Handler) partsKey(uploadID string) string { return "upload:" + uploadID + ":parts" }

// ---------- 1. init ----------

type uploadInitReq struct {
	InspectionID string `json:"inspection_id,omitempty"`
	Kind         string `json:"kind"`         // scan3d / vin_plate / nameplate / exterior / video / pdf
	SizeBytes    int64  `json:"size_bytes"`
	SHA256       string `json:"sha256"`
	MIME         string `json:"mime"`
	ChunkMB      int    `json:"chunk_mb,omitempty"` // 缺省 cfg.DefaultChunkMB
}

type uploadInitResp struct {
	UploadID  string `json:"upload_id"`
	ChunkSize int    `json:"chunk_size"` // 字节
}

func (h *Handler) UploadInit(w http.ResponseWriter, r *http.Request) {
	userID := callerUserID(r)
	if userID == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	var req uploadInitReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if req.Kind == "" || req.SizeBytes <= 0 || len(req.SHA256) != 64 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if req.MIME == "" {
		req.MIME = "application/octet-stream"
	}
	chunkMB := req.ChunkMB
	if chunkMB <= 0 {
		chunkMB = h.cfg.DefaultChunkMB
	}
	chunkSize := chunkMB * 1024 * 1024

	// inspection_id 可选；提供时校验权限（必须是自己的 OR admin）
	var inspectionID *int64
	if req.InspectionID != "" {
		id, err := strconv.ParseInt(req.InspectionID, 10, 64)
		if err != nil {
			httpx.WriteError(w, httpx.ErrBadParam)
			return
		}
		ins, err := h.insps.FindByID(r.Context(), id)
		if err != nil {
			if errors.Is(err, repo.ErrNotFound) {
				httpx.WriteError(w, httpx.ErrNotFound)
				return
			}
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
		if ins.InspectorID != userID && r.Header.Get("X-Gomob-Roles") != "admin" {
			httpx.WriteError(w, httpx.ErrPermDenied)
			return
		}
		inspectionID = &id
	}

	uploadID, err := newUploadID()
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	objectKey := buildObjectKey(req.Kind, inspectionID, uploadID)

	// 启动 MinIO multipart upload
	s3UploadID, err := h.core.NewMultipartUpload(r.Context(), h.cfg.Bucket, objectKey, minio.PutObjectOptions{
		ContentType: req.MIME,
	})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	sess := &repo.UploadSession{
		UploadID:       uploadID,
		S3UploadID:     s3UploadID,
		UserID:         userID,
		InspectionID:   inspectionID,
		Bucket:         h.cfg.Bucket,
		ObjectKey:      objectKey,
		Kind:           req.Kind,
		ExpectedSize:   req.SizeBytes,
		ExpectedSHA256: strings.ToLower(req.SHA256),
		MIME:           req.MIME,
		ChunkSize:      int32(chunkSize),
	}
	if err := h.assets.CreateUploadSession(r.Context(), sess); err != nil {
		// 清理已分配的 multipart upload，避免存储留垃圾
		_ = h.core.AbortMultipartUpload(r.Context(), h.cfg.Bucket, objectKey, s3UploadID)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	httpx.OK(w, uploadInitResp{UploadID: uploadID, ChunkSize: chunkSize})
}

func buildObjectKey(kind string, inspectionID *int64, uploadID string) string {
	if inspectionID != nil {
		return fmt.Sprintf("inspection/%d/%s/%s.bin", *inspectionID, kind, uploadID)
	}
	return fmt.Sprintf("orphan/%s/%s.bin", kind, uploadID)
}

// ---------- 2. part ----------

func (h *Handler) UploadPart(w http.ResponseWriter, r *http.Request) {
	uploadID := r.PathValue("upload_id")
	n, err := strconv.Atoi(r.PathValue("n"))
	if err != nil || n < 1 || n > 10000 { // S3 multipart 上限 10000
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	}
	sess, err := h.assets.FindUploadSession(r.Context(), uploadID)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if sess.UserID != callerUserID(r) {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	if sess.Status != "pending" {
		httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "上传会话已结束"))
		return
	}

	// 流式 PUT 到 MinIO
	contentLen := r.ContentLength
	if contentLen <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	part, err := h.core.PutObjectPart(r.Context(), sess.Bucket, sess.ObjectKey, sess.S3UploadID,
		n, r.Body, contentLen, minio.PutObjectPartOptions{})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	// 缓存 etag → Redis hash
	if h.rdb != nil {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()
		_ = h.rdb.HSet(ctx, h.partsKey(uploadID), strconv.Itoa(n), part.ETag).Err()
		_ = h.rdb.Expire(ctx, h.partsKey(uploadID), 24*time.Hour).Err()
	}

	httpx.OK(w, map[string]any{
		"part_number": n,
		"etag":        strings.Trim(part.ETag, `"`),
		"size":        part.Size,
	})
}

// ---------- 3. complete ----------

type uploadCompleteReq struct {
	TotalChunks int `json:"total_chunks"`
}

type assetDTO struct {
	AssetID     string `json:"asset_id"`
	ObjectKey   string `json:"object_key"`
	DownloadURL string `json:"download_url"`
}

func (h *Handler) UploadComplete(w http.ResponseWriter, r *http.Request) {
	uploadID := r.PathValue("upload_id")
	var req uploadCompleteReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if req.TotalChunks < 1 {
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	}
	sess, err := h.assets.FindUploadSession(r.Context(), uploadID)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if sess.UserID != callerUserID(r) {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	if sess.Status != "pending" {
		httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "上传会话已结束"))
		return
	}

	// 优先从 Redis 拉 etag；fallback 到 MinIO ListObjectParts。
	parts := make([]minio.CompletePart, 0, req.TotalChunks)
	if h.rdb != nil {
		etagsMap, err := h.rdb.HGetAll(r.Context(), h.partsKey(uploadID)).Result()
		if err == nil && len(etagsMap) >= req.TotalChunks {
			for n := 1; n <= req.TotalChunks; n++ {
				et, ok := etagsMap[strconv.Itoa(n)]
				if !ok {
					httpx.WriteError(w, httpx.NewError(40402, http.StatusBadRequest,
						fmt.Sprintf("chunk %d 缺失", n)))
					return
				}
				parts = append(parts, minio.CompletePart{PartNumber: n, ETag: et})
			}
		}
	}
	if len(parts) == 0 {
		// fallback
		listed, err := h.core.ListObjectParts(r.Context(), sess.Bucket, sess.ObjectKey,
			sess.S3UploadID, 0, 10000)
		if err != nil {
			h.log.Error("ListObjectParts 失败", "upload_id", uploadID, "err", err)
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
		got := make(map[int]string, len(listed.ObjectParts))
		for _, p := range listed.ObjectParts {
			got[p.PartNumber] = p.ETag
		}
		for n := 1; n <= req.TotalChunks; n++ {
			et, ok := got[n]
			if !ok {
				httpx.WriteError(w, httpx.NewError(40402, http.StatusBadRequest,
					fmt.Sprintf("chunk %d 缺失", n)))
				return
			}
			parts = append(parts, minio.CompletePart{PartNumber: n, ETag: et})
		}
	}

	if _, err := h.core.CompleteMultipartUpload(r.Context(), sess.Bucket, sess.ObjectKey,
		sess.S3UploadID, parts, minio.PutObjectOptions{}); err != nil {
		h.log.Error("CompleteMultipartUpload 失败", "upload_id", uploadID, "object", sess.ObjectKey, "parts", len(parts), "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	// 校验 size 与 sha256（StatObject 给 size；sha256 由调用方在 init 时声明、本服务不做内容校验，
	// 是对客户端的契约 — 后续 worker / cv-engine 加载时如发现不一致可拒绝）
	stat, err := h.mc.StatObject(r.Context(), sess.Bucket, sess.ObjectKey, minio.StatObjectOptions{})
	if err != nil {
		h.log.Error("StatObject 失败", "upload_id", uploadID, "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if stat.Size != sess.ExpectedSize {
		// 让 client 重传；不删 MinIO 数据，便于排错
		httpx.WriteError(w, httpx.NewError(40403, http.StatusBadRequest,
			fmt.Sprintf("总大小不符：期望 %d 实际 %d", sess.ExpectedSize, stat.Size)))
		return
	}

	// 写 inspection_assets
	var inspectionID int64
	if sess.InspectionID != nil {
		inspectionID = *sess.InspectionID
	}
	asset := &repo.InspectionAsset{
		InspectionID: inspectionID,
		Kind:         sess.Kind,
		ObjectKey:    sess.ObjectKey,
		SHA256:       sess.ExpectedSHA256,
		SizeBytes:    stat.Size,
		MIME:         sess.MIME,
	}
	if asset.InspectionID > 0 {
		if err := h.assets.CreateInspectionAsset(r.Context(), asset); err != nil {
			h.log.Error("CreateInspectionAsset 失败", "upload_id", uploadID, "err", err)
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
		_ = h.assets.CompleteUploadSession(r.Context(), uploadID, asset.ID)
	} else {
		// 没有 inspection 关联（如标定用图）：不写 inspection_assets，仅更新 session
		_ = h.assets.CompleteUploadSession(r.Context(), uploadID, 0)
	}

	// audit
	if h.audit != nil {
		_ = h.audit.Record(r.Context(), audit.Entry{
			UserID: callerUserID(r),
			Action: "asset.upload_complete",
			Target: "asset:" + strconv.FormatInt(asset.ID, 10),
			IP:     r.RemoteAddr,
		})
	}

	// 清 Redis
	if h.rdb != nil {
		_ = h.rdb.Del(r.Context(), h.partsKey(uploadID)).Err()
	}

	// 直接给一个签名 URL 方便客户端立即拉
	url, _ := h.mc.PresignedGetObject(r.Context(), sess.Bucket, sess.ObjectKey, h.cfg.PresignDuration, nil)
	dlURL := ""
	if url != nil {
		dlURL = url.String()
	}
	httpx.OK(w, assetDTO{
		AssetID:     strconv.FormatInt(asset.ID, 10),
		ObjectKey:   sess.ObjectKey,
		DownloadURL: dlURL,
	})
}

// ---------- 4. abort ----------

func (h *Handler) UploadAbort(w http.ResponseWriter, r *http.Request) {
	uploadID := r.PathValue("upload_id")
	sess, err := h.assets.FindUploadSession(r.Context(), uploadID)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if sess.UserID != callerUserID(r) {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	if sess.Status != "pending" {
		httpx.OK(w, nil) // 已结束，幂等
		return
	}
	_ = h.core.AbortMultipartUpload(r.Context(), sess.Bucket, sess.ObjectKey, sess.S3UploadID)
	_ = h.assets.AbortUploadSession(r.Context(), uploadID)
	if h.rdb != nil {
		_ = h.rdb.Del(r.Context(), h.partsKey(uploadID)).Err()
	}
	httpx.OK(w, nil)
}

// ---------- 5. presign download ----------

func (h *Handler) PresignDownload(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	a, err := h.assets.FindAssetByID(r.Context(), id)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	// 权限：只允许本查验所属 inspector 或 supervisor/reviewer/admin
	uid := callerUserID(r)
	role := r.Header.Get("X-Gomob-Roles")
	if a.InspectionID > 0 && role == "" {
		// 不带 token 直接拒
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	if a.InspectionID > 0 {
		ins, err := h.insps.FindByID(r.Context(), a.InspectionID)
		if err == nil {
			if ins.InspectorID != uid && role != "supervisor" && role != "reviewer" && role != "admin" {
				httpx.WriteError(w, httpx.ErrPermDenied)
				return
			}
		}
	}

	url, err := h.mc.PresignedGetObject(r.Context(), h.cfg.Bucket, a.ObjectKey, h.cfg.PresignDuration, nil)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	httpx.OK(w, map[string]any{
		"asset_id":     strconv.FormatInt(a.ID, 10),
		"download_url": url.String(),
		"expires_in":   int64(h.cfg.PresignDuration.Seconds()),
	})
}

// 预留：完整下载（dev 联调用）— 通过 server 流式中转，不走签名 URL
// 生产路径用 PresignDownload；该方法仅用于无外网 / 无 DNS 的场景。
func (h *Handler) Download(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	a, err := h.assets.FindAssetByID(r.Context(), id)
	if err != nil {
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	}
	obj, err := h.mc.GetObject(r.Context(), h.cfg.Bucket, a.ObjectKey, minio.GetObjectOptions{})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	defer obj.Close()
	w.Header().Set("Content-Type", a.MIME)
	_, _ = io.Copy(w, obj)
}
