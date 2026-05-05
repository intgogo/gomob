// shape-ref 服务 HTTP handler — 车型 3D 外廓参考库（详见 02-api-contract.md §13.4 / 00-server-overview.md §6.z）。
//
// 通路：
//
//	App ──▶ gateway ──▶ api（BFF 反代）──▶ shape-ref       仅 published 只读 + 签名 URL
//	admin BFF ──HTTP──▶ shape-ref                          写：版本 CRUD + 状态机
//
// 路径：
//
//	# admin 写
//	POST   /admin/v1/catalog/vehicles/{vmid}/shapes               创建 draft（mesh_object_key 已上传到 asset MinIO）
//	GET    /admin/v1/catalog/vehicles/{vmid}/shapes               列版本（全状态）
//	GET    /admin/v1/catalog/vehicles/{vmid}/shapes/{sid}         版本详情
//	PATCH  /admin/v1/catalog/vehicles/{vmid}/shapes/{sid}         改 draft 元数据
//	POST   /admin/v1/catalog/vehicles/{vmid}/shapes/{sid}/publish 发布（旧 active 自动 archive）
//	POST   /admin/v1/catalog/vehicles/{vmid}/shapes/{sid}/archive
//	DELETE /admin/v1/catalog/vehicles/{vmid}/shapes/{sid}         仅 draft 可删
//
//	# App 读（仅 published）
//	GET /v1/catalog/vehicles/{vmid}/shape                          active 版本元数据 + 签名 mesh URL（5 分钟）
//	GET /v1/catalog/vehicles/{vmid}/shapes/{sid}                   指定历史版本（仅 published 对 App）
//	GET /v1/catalog/vehicles/{vmid}/shape/url                      只要签名 URL（M-S9.x 大文件 Range 续传 / 带宽优化）
package shaperef

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/rbac"
	"io.gomob/server/pkg/repo"
)

// Config 决定 shape-ref 怎么和 MinIO 交互（与 asset 服务共用一组 env，不需要单独的 bucket）。
type Config struct {
	MinIOEndpoint   string
	MinIOAccessKey  string
	MinIOSecretKey  string
	MinIOUseSSL     bool
	Bucket          string
	PresignDuration time.Duration
}

func DefaultConfig() Config {
	return Config{
		MinIOEndpoint:   "127.0.0.1:9000",
		MinIOAccessKey:  "gomob",
		MinIOSecretKey:  "gomob_dev_minio",
		MinIOUseSSL:     false,
		Bucket:          "gomob-assets",
		PresignDuration: 5 * time.Minute,
	}
}

type Handler struct {
	cfg    Config
	pool   *pgxpool.Pool
	shapes *repo.VehicleShapeRepo
	mc     *minio.Client
	audit  audit.Recorder
	log    *slog.Logger
}

func NewHandler(cfg Config, pool *pgxpool.Pool, auditRec audit.Recorder) (*Handler, error) {
	mc, err := minio.New(cfg.MinIOEndpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.MinIOAccessKey, cfg.MinIOSecretKey, ""),
		Secure: cfg.MinIOUseSSL,
	})
	if err != nil {
		return nil, fmt.Errorf("minio client: %w", err)
	}
	return &Handler{
		cfg:    cfg,
		pool:   pool,
		shapes: repo.NewVehicleShapeRepo(pool),
		mc:     mc,
		audit:  auditRec,
		log:    logger.New("shaperef.handler"),
	}, nil
}

func (h *Handler) Mount(mux *http.ServeMux) {
	// admin 写
	mux.HandleFunc("POST /admin/v1/catalog/vehicles/{vmid}/shapes", h.CreateShape)
	mux.HandleFunc("GET /admin/v1/catalog/vehicles/{vmid}/shapes", h.ListShapesAdmin)
	mux.HandleFunc("GET /admin/v1/catalog/vehicles/{vmid}/shapes/{sid}", h.GetShapeAdmin)
	mux.HandleFunc("PATCH /admin/v1/catalog/vehicles/{vmid}/shapes/{sid}", h.PatchShape)
	mux.HandleFunc("POST /admin/v1/catalog/vehicles/{vmid}/shapes/{sid}/publish", h.PublishShape)
	mux.HandleFunc("POST /admin/v1/catalog/vehicles/{vmid}/shapes/{sid}/archive", h.ArchiveShape)
	mux.HandleFunc("DELETE /admin/v1/catalog/vehicles/{vmid}/shapes/{sid}", h.DeleteShape)

	// App 读
	mux.HandleFunc("GET /v1/catalog/vehicles/{vmid}/shape", h.GetActiveShape)
	mux.HandleFunc("GET /v1/catalog/vehicles/{vmid}/shape/url", h.GetActiveShapeURL)
	mux.HandleFunc("GET /v1/catalog/vehicles/{vmid}/shapes/{sid}", h.GetShapePublished)
}

// ============================================================================
// 工具
// ============================================================================

func callerUserID(r *http.Request) int64 {
	v := r.Header.Get("X-Gomob-User-Id")
	if v == "" {
		return 0
	}
	id, _ := strconv.ParseInt(v, 10, 64)
	return id
}

func callerRole(r *http.Request) string { return r.Header.Get("X-Gomob-Roles") }

func mustAdmin(w http.ResponseWriter, r *http.Request) bool {
	if callerRole(r) != rbac.RoleAdmin {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return false
	}
	return true
}

func parsePathInt(r *http.Request, key string) (int64, error) {
	return strconv.ParseInt(r.PathValue(key), 10, 64)
}

func shapeTarget(id int64) string {
	return "vehicle_shape:" + strconv.FormatInt(id, 10)
}

func (h *Handler) presign(ctx context.Context, objectKey string) (string, time.Time, error) {
	if objectKey == "" {
		return "", time.Time{}, nil
	}
	u, err := h.mc.PresignedGetObject(ctx, h.cfg.Bucket, objectKey, h.cfg.PresignDuration, nil)
	if err != nil {
		return "", time.Time{}, err
	}
	return u.String(), time.Now().Add(h.cfg.PresignDuration), nil
}

// ============================================================================
// DTO
// ============================================================================

type bbox struct {
	MinX *float32 `json:"min_x,omitempty"`
	MinY *float32 `json:"min_y,omitempty"`
	MinZ *float32 `json:"min_z,omitempty"`
	MaxX *float32 `json:"max_x,omitempty"`
	MaxY *float32 `json:"max_y,omitempty"`
	MaxZ *float32 `json:"max_z,omitempty"`
}

type shapeDTO struct {
	ID             string  `json:"id"`
	VehicleModelID string  `json:"vehicle_model_id"`
	VersionName    string  `json:"version_name"`
	Description    *string `json:"description,omitempty"`
	Source         string  `json:"source"`
	CapturedAt     *string `json:"captured_at,omitempty"`
	CapturedBy     *string `json:"captured_by,omitempty"`

	MeshObjectKey   string  `json:"mesh_object_key"`
	MeshSHA256      string  `json:"mesh_sha256"`
	MeshSizeBytes   int64   `json:"mesh_size_bytes"`
	MeshFormat      string  `json:"mesh_format"`
	MeshDownloadURL *string `json:"mesh_download_url,omitempty"` // 仅 published + 读路径返回
	MeshURLExpireAt *string `json:"mesh_url_expire_at,omitempty"`

	TriangleCount *int64 `json:"triangle_count,omitempty"`
	PointCount    *int64 `json:"point_count,omitempty"`
	BBox          *bbox  `json:"bbox,omitempty"`

	Coverage *float32 `json:"coverage,omitempty"`
	QCScore  *float32 `json:"qc_score,omitempty"`
	QCNotes  *string  `json:"qc_notes,omitempty"`

	Status      string  `json:"status"`
	Note        *string `json:"note,omitempty"`
	CreatedBy   *string `json:"created_by,omitempty"`
	CreatedAt   string  `json:"created_at"`
	UpdatedAt   string  `json:"updated_at"`
	PublishedAt *string `json:"published_at,omitempty"`
	ArchivedAt  *string `json:"archived_at,omitempty"`
}

func toShapeDTO(s *repo.VehicleShape) shapeDTO {
	d := shapeDTO{
		ID:             strconv.FormatInt(s.ID, 10),
		VehicleModelID: strconv.FormatInt(s.VehicleModelID, 10),
		VersionName:    s.VersionName,
		Description:    s.Description,
		Source:         s.Source,
		CapturedBy:     s.CapturedBy,

		MeshObjectKey: s.MeshObjectKey,
		MeshSHA256:    s.MeshSHA256,
		MeshSizeBytes: s.MeshSizeBytes,
		MeshFormat:    s.MeshFormat,

		TriangleCount: s.TriangleCount,
		PointCount:    s.PointCount,

		Coverage: s.Coverage,
		QCScore:  s.QCScore,
		QCNotes:  s.QCNotes,

		Status:    s.Status,
		Note:      s.Note,
		CreatedAt: s.CreatedAt.UTC().Format(time.RFC3339Nano),
		UpdatedAt: s.UpdatedAt.UTC().Format(time.RFC3339Nano),
	}
	if s.CapturedAt != nil {
		v := s.CapturedAt.UTC().Format(time.RFC3339Nano)
		d.CapturedAt = &v
	}
	if s.PublishedAt != nil {
		v := s.PublishedAt.UTC().Format(time.RFC3339Nano)
		d.PublishedAt = &v
	}
	if s.ArchivedAt != nil {
		v := s.ArchivedAt.UTC().Format(time.RFC3339Nano)
		d.ArchivedAt = &v
	}
	if s.CreatedBy != nil {
		v := strconv.FormatInt(*s.CreatedBy, 10)
		d.CreatedBy = &v
	}
	if s.BBoxMinX != nil || s.BBoxMaxX != nil {
		d.BBox = &bbox{
			MinX: s.BBoxMinX, MinY: s.BBoxMinY, MinZ: s.BBoxMinZ,
			MaxX: s.BBoxMaxX, MaxY: s.BBoxMaxY, MaxZ: s.BBoxMaxZ,
		}
	}
	return d
}

// withDownloadURL 在 DTO 上附加签名 URL（仅在 App 读路径调用）。
func (h *Handler) withDownloadURL(ctx context.Context, d *shapeDTO, objectKey string) error {
	url, expireAt, err := h.presign(ctx, objectKey)
	if err != nil {
		return err
	}
	d.MeshDownloadURL = &url
	exp := expireAt.UTC().Format(time.RFC3339Nano)
	d.MeshURLExpireAt = &exp
	return nil
}

// ============================================================================
// 写路径（admin）
// ============================================================================

type createShapeReq struct {
	VersionName     string   `json:"version_name"`
	Description     *string  `json:"description"`
	Source          string   `json:"source"` // factory_cad / scan_high_res / manual_modeled / unknown
	CapturedAt      *string  `json:"captured_at"`
	CapturedBy      *string  `json:"captured_by"`
	MeshObjectKey   string   `json:"mesh_object_key"`
	MeshSHA256      string   `json:"mesh_sha256"`
	MeshSizeBytes   int64    `json:"mesh_size_bytes"`
	MeshFormat      string   `json:"mesh_format"` // glb / ply / stl / obj / gltf
	TriangleCount   *int64   `json:"triangle_count"`
	PointCount      *int64   `json:"point_count"`
	BBox            *bbox    `json:"bbox"`
	Coverage        *float32 `json:"coverage"`
	QCScore         *float32 `json:"qc_score"`
	QCNotes         *string  `json:"qc_notes"`
	Note            *string  `json:"note"`
}

func (h *Handler) CreateShape(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	vmid, err := parsePathInt(r, "vmid")
	if err != nil || vmid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var req createShapeReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	req.VersionName = strings.TrimSpace(req.VersionName)
	if req.VersionName == "" || req.MeshObjectKey == "" || req.MeshSHA256 == "" ||
		req.MeshSizeBytes <= 0 || req.MeshFormat == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	uid := callerUserID(r)
	s := &repo.VehicleShape{
		VehicleModelID: vmid,
		VersionName:    req.VersionName,
		Description:    req.Description,
		Source:         strings.TrimSpace(req.Source),
		CapturedBy:     req.CapturedBy,
		MeshObjectKey:  req.MeshObjectKey,
		MeshSHA256:     req.MeshSHA256,
		MeshSizeBytes:  req.MeshSizeBytes,
		MeshFormat:     strings.ToLower(strings.TrimSpace(req.MeshFormat)),
		TriangleCount:  req.TriangleCount,
		PointCount:     req.PointCount,
		Coverage:       req.Coverage,
		QCScore:        req.QCScore,
		QCNotes:        req.QCNotes,
		Note:           req.Note,
		CreatedBy:      &uid,
	}
	if req.CapturedAt != nil && *req.CapturedAt != "" {
		t, perr := time.Parse(time.RFC3339, *req.CapturedAt)
		if perr != nil {
			httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "captured_at 必须 RFC3339"))
			return
		}
		s.CapturedAt = &t
	}
	if req.BBox != nil {
		s.BBoxMinX, s.BBoxMinY, s.BBoxMinZ = req.BBox.MinX, req.BBox.MinY, req.BBox.MinZ
		s.BBoxMaxX, s.BBoxMaxY, s.BBoxMaxZ = req.BBox.MaxX, req.BBox.MaxY, req.BBox.MaxZ
	}
	if err := h.shapes.Create(r.Context(), s); err != nil {
		switch {
		case errors.Is(err, repo.ErrConflict):
			httpx.WriteError(w, httpx.NewError(40201, http.StatusConflict,
				"该车型下已存在同名版本"))
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound, "vehicle_model 不存在"))
		case errors.Is(err, repo.ErrFieldRange):
			httpx.WriteError(w, httpx.ErrFieldRange)
		default:
			h.log.Error("CreateShape 失败", "err", err)
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	h.recordAudit(r, "shaperef.create", shapeTarget(s.ID), nil, s)
	httpx.OK(w, toShapeDTO(s))
}

func (h *Handler) ListShapesAdmin(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	h.listShapes(w, r, true)
}

func (h *Handler) GetShapeAdmin(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	sid, err := parsePathInt(r, "sid")
	if err != nil || sid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	s, err := h.shapes.FindByID(r.Context(), sid)
	if err != nil {
		writeShapeErr(w, err)
		return
	}
	httpx.OK(w, toShapeDTO(s))
}

func (h *Handler) listShapes(w http.ResponseWriter, r *http.Request, allStatus bool) {
	vmid, err := parsePathInt(r, "vmid")
	if err != nil || vmid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	cursor, _ := strconv.ParseInt(q.Get("cursor"), 10, 64)
	status := strings.TrimSpace(q.Get("status"))
	if !allStatus {
		status = "published"
	}
	items, next, err := h.shapes.List(r.Context(), repo.ShapeListFilter{
		VehicleModelID: vmid,
		Status:         status,
		Limit:          limit,
		Cursor:         cursor,
	})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]shapeDTO, 0, len(items))
	for i := range items {
		out = append(out, toShapeDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{
		"items":       out,
		"next_cursor": strconv.FormatInt(next, 10),
		"has_more":    next != 0,
	})
}

func (h *Handler) PatchShape(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	sid, err := parsePathInt(r, "sid")
	if err != nil || sid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var raw struct {
		VersionName *string  `json:"version_name"`
		Description *string  `json:"description"`
		Source      *string  `json:"source"`
		CapturedAt  *string  `json:"captured_at"`
		CapturedBy  *string  `json:"captured_by"`
		Coverage    *float32 `json:"coverage"`
		QCScore     *float32 `json:"qc_score"`
		QCNotes     *string  `json:"qc_notes"`
		Note        *string  `json:"note"`
	}
	if err := json.NewDecoder(r.Body).Decode(&raw); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	p := repo.VehicleShapePatch{
		VersionName: raw.VersionName,
		Description: raw.Description,
		Source:      raw.Source,
		CapturedBy:  raw.CapturedBy,
		Coverage:    raw.Coverage,
		QCScore:     raw.QCScore,
		QCNotes:     raw.QCNotes,
		Note:        raw.Note,
	}
	if raw.CapturedAt != nil && *raw.CapturedAt != "" {
		t, perr := time.Parse(time.RFC3339, *raw.CapturedAt)
		if perr != nil {
			httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "captured_at 必须 RFC3339"))
			return
		}
		p.CapturedAt = &t
	}
	before, _ := h.shapes.FindByID(r.Context(), sid)
	if err := h.shapes.Patch(r.Context(), sid, p); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict,
				"已发布或归档的版本不可改"))
		case errors.Is(err, repo.ErrConflict):
			httpx.WriteError(w, httpx.NewError(40201, http.StatusConflict, "版本名冲突"))
		case errors.Is(err, repo.ErrFieldRange):
			httpx.WriteError(w, httpx.ErrFieldRange)
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	after, _ := h.shapes.FindByID(r.Context(), sid)
	h.recordAudit(r, "shaperef.patch", shapeTarget(sid), before, after)
	httpx.OK(w, toShapeDTO(after))
}

func (h *Handler) PublishShape(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	h.shapeTransition(w, r, "publish", "shaperef.publish")
}

func (h *Handler) ArchiveShape(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	h.shapeTransition(w, r, "archive", "shaperef.archive")
}

func (h *Handler) shapeTransition(w http.ResponseWriter, r *http.Request, op, action string) {
	sid, err := parsePathInt(r, "sid")
	if err != nil || sid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, _ := h.shapes.FindByID(r.Context(), sid)
	switch op {
	case "publish":
		err = h.shapes.Publish(r.Context(), sid)
	case "archive":
		err = h.shapes.Archive(r.Context(), sid)
	}
	if err != nil {
		writeShapeErr(w, err)
		return
	}
	after, _ := h.shapes.FindByID(r.Context(), sid)
	h.recordAudit(r, action, shapeTarget(sid), before, after)
	httpx.OK(w, toShapeDTO(after))
}

func (h *Handler) DeleteShape(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	sid, err := parsePathInt(r, "sid")
	if err != nil || sid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, _ := h.shapes.FindByID(r.Context(), sid)
	if err := h.shapes.DeleteDraft(r.Context(), sid); err != nil {
		writeShapeErr(w, err)
		return
	}
	h.recordAudit(r, "shaperef.delete", shapeTarget(sid), before, nil)
	httpx.OK(w, map[string]any{"deleted": true})
}

// ============================================================================
// 读路径（App / cv-engine）
// ============================================================================

func (h *Handler) GetActiveShape(w http.ResponseWriter, r *http.Request) {
	vmid, err := parsePathInt(r, "vmid")
	if err != nil || vmid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	s, err := h.shapes.FindActive(r.Context(), vmid)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"该车型暂无 published 3D 外廓版本"))
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	d := toShapeDTO(s)
	if err := h.withDownloadURL(r.Context(), &d, s.MeshObjectKey); err != nil {
		h.log.Warn("presign 失败", "err", err, "object_key", s.MeshObjectKey)
	}
	httpx.OK(w, d)
}

// GetActiveShapeURL 只返回签名 URL（带宽 / 客户端只想刷新链接时用）。
func (h *Handler) GetActiveShapeURL(w http.ResponseWriter, r *http.Request) {
	vmid, err := parsePathInt(r, "vmid")
	if err != nil || vmid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	s, err := h.shapes.FindActive(r.Context(), vmid)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"该车型暂无 published 3D 外廓版本"))
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	url, expireAt, err := h.presign(r.Context(), s.MeshObjectKey)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	httpx.OK(w, map[string]any{
		"shape_id":           strconv.FormatInt(s.ID, 10),
		"mesh_object_key":    s.MeshObjectKey,
		"mesh_sha256":        s.MeshSHA256,
		"mesh_size_bytes":    s.MeshSizeBytes,
		"mesh_format":        s.MeshFormat,
		"mesh_download_url":  url,
		"mesh_url_expire_at": expireAt.UTC().Format(time.RFC3339Nano),
	})
}

func (h *Handler) GetShapePublished(w http.ResponseWriter, r *http.Request) {
	sid, err := parsePathInt(r, "sid")
	if err != nil || sid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	s, err := h.shapes.FindByID(r.Context(), sid)
	if err != nil {
		writeShapeErr(w, err)
		return
	}
	// App 端只看 published；admin 看全状态
	if s.Status != "published" && callerRole(r) != rbac.RoleAdmin {
		httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
			"版本未发布或不存在"))
		return
	}
	d := toShapeDTO(s)
	// 给签名 URL（admin 也方便预览）
	if err := h.withDownloadURL(r.Context(), &d, s.MeshObjectKey); err != nil {
		h.log.Warn("presign 失败", "err", err, "object_key", s.MeshObjectKey)
	}
	httpx.OK(w, d)
}

// ============================================================================
// 工具
// ============================================================================

func writeShapeErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, repo.ErrNotFound):
		httpx.WriteError(w, httpx.ErrNotFound)
	case errors.Is(err, repo.ErrStateConflict):
		httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict,
			"版本状态不允许此操作"))
	case errors.Is(err, repo.ErrConflict):
		httpx.WriteError(w, httpx.NewError(40201, http.StatusConflict, "版本冲突"))
	default:
		httpx.WriteError(w, httpx.ErrInternal)
	}
}

func (h *Handler) recordAudit(r *http.Request, action, target string, before, after any) {
	if h.audit == nil {
		return
	}
	bs, _ := audit.Encode(before)
	as, _ := audit.Encode(after)
	auditCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	_ = h.audit.Record(auditCtx, audit.Entry{
		UserID:    callerUserID(r),
		Action:    action,
		Target:    target,
		BeforeRaw: bs,
		AfterRaw:  as,
		IP:        r.RemoteAddr,
	})
}
