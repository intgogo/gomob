// vin-ref 服务 HTTP handler — 车驾号字形参考库（详见 02-api-contract.md §13.4 / 00-server-overview.md §6.z）。
//
// 通路：
//
//	App ──▶ gateway ──▶ api（BFF 反代）──▶ vin-ref     仅 published 批次只读
//	admin BFF ──HTTP──▶ vin-ref                       写：批次 CRUD / 样本 CRUD / 状态机
//	cv-engine ──HTTP──▶ vin-ref                       拉对照样本（按 vehicle_model_id + character）
//
// 路径：
//
//	# 内部 / 写（admin only）
//	POST   /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches            创建 draft 批次
//	GET    /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches            列批次（全状态）
//	GET    /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}      批次详情
//	PATCH  /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}      改 draft 元数据
//	POST   /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/publish 发布（旧 active 自动 archive）
//	POST   /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/archive 归档
//	DELETE /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}      仅 draft 可删
//	POST   /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/samples  写样本
//	DELETE /admin/v1/catalog/vehicles/{vmid}/vin-refs/samples/{sid}      删样本（仅 draft 批次）
//
//	# 读（App / cv-engine）
//	GET    /v1/catalog/vehicles/{vmid}/vin-refs/active                   active 批次摘要
//	GET    /v1/catalog/vehicles/{vmid}/vin-refs/active/samples?character=X&position_hint=&limit=  active 批次的样本
//	GET    /v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/samples?character=X  指定批次的样本（admin 也能看）
package vinref

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/rbac"
	"io.gomob/server/pkg/repo"
)

type Handler struct {
	pool    *pgxpool.Pool
	batches *repo.VinGlyphBatchRepo
	samples *repo.VinGlyphSampleRepo
	audit   audit.Recorder
	log     *slog.Logger
}

func NewHandler(pool *pgxpool.Pool, auditRec audit.Recorder) *Handler {
	return &Handler{
		pool:    pool,
		batches: repo.NewVinGlyphBatchRepo(pool),
		samples: repo.NewVinGlyphSampleRepo(pool),
		audit:   auditRec,
		log:     logger.New("vinref.handler"),
	}
}

func (h *Handler) Mount(mux *http.ServeMux) {
	// 写路径（admin）
	mux.HandleFunc("POST /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches", h.CreateBatch)
	mux.HandleFunc("GET /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches", h.ListBatchesAdmin)
	mux.HandleFunc("GET /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}", h.GetBatchAdmin)
	mux.HandleFunc("PATCH /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}", h.PatchBatch)
	mux.HandleFunc("POST /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/publish", h.PublishBatch)
	mux.HandleFunc("POST /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/archive", h.ArchiveBatch)
	mux.HandleFunc("DELETE /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}", h.DeleteBatch)
	mux.HandleFunc("POST /admin/v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/samples", h.CreateSample)
	mux.HandleFunc("DELETE /admin/v1/catalog/vehicles/{vmid}/vin-refs/samples/{sid}", h.DeleteSample)

	// 读路径（App / cv-engine）
	mux.HandleFunc("GET /v1/catalog/vehicles/{vmid}/vin-refs/active", h.GetActiveBatch)
	mux.HandleFunc("GET /v1/catalog/vehicles/{vmid}/vin-refs/active/samples", h.ListActiveSamples)
	mux.HandleFunc("GET /v1/catalog/vehicles/{vmid}/vin-refs/batches/{bid}/samples", h.ListSamplesByBatch)
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

// ============================================================================
// DTO
// ============================================================================

type batchDTO struct {
	ID             string  `json:"id"`
	VehicleModelID string  `json:"vehicle_model_id"`
	Name           string  `json:"name"`
	Description    *string `json:"description,omitempty"`
	CapturedAt     *string `json:"captured_at,omitempty"`
	CapturedBy     *string `json:"captured_by,omitempty"`
	SampleCount    int32   `json:"sample_count"`
	Status         string  `json:"status"`
	Note           *string `json:"note,omitempty"`
	CreatedBy      *string `json:"created_by,omitempty"`
	CreatedAt      string  `json:"created_at"`
	UpdatedAt      string  `json:"updated_at"`
	PublishedAt    *string `json:"published_at,omitempty"`
	ArchivedAt     *string `json:"archived_at,omitempty"`
}

func toBatchDTO(b *repo.VinGlyphBatch) batchDTO {
	d := batchDTO{
		ID:             strconv.FormatInt(b.ID, 10),
		VehicleModelID: strconv.FormatInt(b.VehicleModelID, 10),
		Name:           b.Name,
		Description:    b.Description,
		CapturedBy:     b.CapturedBy,
		SampleCount:    b.SampleCount,
		Status:         b.Status,
		Note:           b.Note,
		CreatedAt:      b.CreatedAt.UTC().Format(time.RFC3339Nano),
		UpdatedAt:      b.UpdatedAt.UTC().Format(time.RFC3339Nano),
	}
	if b.CapturedAt != nil {
		s := b.CapturedAt.UTC().Format(time.RFC3339Nano)
		d.CapturedAt = &s
	}
	if b.PublishedAt != nil {
		s := b.PublishedAt.UTC().Format(time.RFC3339Nano)
		d.PublishedAt = &s
	}
	if b.ArchivedAt != nil {
		s := b.ArchivedAt.UTC().Format(time.RFC3339Nano)
		d.ArchivedAt = &s
	}
	if b.CreatedBy != nil {
		s := strconv.FormatInt(*b.CreatedBy, 10)
		d.CreatedBy = &s
	}
	return d
}

type sampleDTO struct {
	ID                string   `json:"id"`
	BatchID           string   `json:"batch_id"`
	Character         string   `json:"character"`
	ArrMode           int16    `json:"arr_mode"`
	FontID            string   `json:"font_id"`
	FontFamilyID      *string  `json:"font_family_id,omitempty"`
	PositionHint      *int16   `json:"position_hint,omitempty"`
	AlphaObjectKey    string   `json:"alpha_object_key"`
	AlphaSHA256       string   `json:"alpha_sha256"`
	AlphaSizeBytes    int64    `json:"alpha_size_bytes"`
	OriginObjectKey   *string  `json:"origin_object_key,omitempty"`
	OriginSHA256      *string  `json:"origin_sha256,omitempty"`
	OriginSizeBytes   *int64   `json:"origin_size_bytes,omitempty"`
	FeatureVectorURI  *string  `json:"feature_vector_uri,omitempty"`
	QCScore           *float32 `json:"qc_score,omitempty"`
	CreatedAt         string   `json:"created_at"`
}

func toSampleDTO(s *repo.VinGlyphSample) sampleDTO {
	return sampleDTO{
		ID:               strconv.FormatInt(s.ID, 10),
		BatchID:          strconv.FormatInt(s.BatchID, 10),
		Character:        s.Character,
		ArrMode:          s.ArrMode,
		FontID:           s.FontID,
		FontFamilyID:     s.FontFamilyID,
		PositionHint:     s.PositionHint,
		AlphaObjectKey:   s.AlphaObjectKey,
		AlphaSHA256:      s.AlphaSHA256,
		AlphaSizeBytes:   s.AlphaSizeBytes,
		OriginObjectKey:  s.OriginObjectKey,
		OriginSHA256:     s.OriginSHA256,
		OriginSizeBytes:  s.OriginSizeBytes,
		FeatureVectorURI: s.FeatureVectorURI,
		QCScore:          s.QCScore,
		CreatedAt:        s.CreatedAt.UTC().Format(time.RFC3339Nano),
	}
}

// ============================================================================
// 批次 CRUD
// ============================================================================

type createBatchReq struct {
	Name        string  `json:"name"`
	Description *string `json:"description"`
	// captured_at 用 RFC3339；空字符串视作 nil
	CapturedAt *string `json:"captured_at"`
	CapturedBy *string `json:"captured_by"`
	Note       *string `json:"note"`
}

func (h *Handler) CreateBatch(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	vmid, err := parsePathInt(r, "vmid")
	if err != nil || vmid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var req createBatchReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	req.Name = strings.TrimSpace(req.Name)
	if req.Name == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	uid := callerUserID(r)
	b := &repo.VinGlyphBatch{
		VehicleModelID: vmid,
		Name:           req.Name,
		Description:    req.Description,
		CapturedBy:     req.CapturedBy,
		Note:           req.Note,
		CreatedBy:      &uid,
	}
	if req.CapturedAt != nil && *req.CapturedAt != "" {
		t, perr := time.Parse(time.RFC3339, *req.CapturedAt)
		if perr != nil {
			httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "captured_at 必须 RFC3339"))
			return
		}
		b.CapturedAt = &t
	}
	if err := h.batches.Create(r.Context(), b); err != nil {
		switch {
		case errors.Is(err, repo.ErrConflict):
			httpx.WriteError(w, httpx.NewError(40201, http.StatusConflict,
				"该车型下已存在同名批次"))
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound, "vehicle_model 不存在"))
		default:
			h.log.Error("CreateBatch 失败", "err", err)
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	h.recordAudit(r, "vinref.batch.create", batchTarget(b.ID), nil, b)
	httpx.OK(w, toBatchDTO(b))
}

func (h *Handler) ListBatchesAdmin(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	h.listBatches(w, r, true)
}

func (h *Handler) GetBatchAdmin(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	bid, err := parsePathInt(r, "bid")
	if err != nil || bid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	b, err := h.batches.FindByID(r.Context(), bid)
	if err != nil {
		writeBatchErr(w, err)
		return
	}
	httpx.OK(w, toBatchDTO(b))
}

func (h *Handler) listBatches(w http.ResponseWriter, r *http.Request, allStatus bool) {
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
	items, next, err := h.batches.List(r.Context(), repo.VinBatchListFilter{
		VehicleModelID: vmid,
		Status:         status,
		Limit:          limit,
		Cursor:         cursor,
	})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]batchDTO, 0, len(items))
	for i := range items {
		out = append(out, toBatchDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{
		"items":       out,
		"next_cursor": strconv.FormatInt(next, 10),
		"has_more":    next != 0,
	})
}

func (h *Handler) PatchBatch(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	bid, err := parsePathInt(r, "bid")
	if err != nil || bid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var raw struct {
		Name        *string `json:"name"`
		Description *string `json:"description"`
		CapturedAt  *string `json:"captured_at"`
		CapturedBy  *string `json:"captured_by"`
		Note        *string `json:"note"`
	}
	if err := json.NewDecoder(r.Body).Decode(&raw); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	p := repo.VinGlyphBatchPatch{
		Name:        raw.Name,
		Description: raw.Description,
		CapturedBy:  raw.CapturedBy,
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
	before, _ := h.batches.FindByID(r.Context(), bid)
	if err := h.batches.Patch(r.Context(), bid, p); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict,
				"已发布或归档的批次不可改"))
		case errors.Is(err, repo.ErrConflict):
			httpx.WriteError(w, httpx.NewError(40201, http.StatusConflict,
				"批次名冲突"))
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	after, _ := h.batches.FindByID(r.Context(), bid)
	h.recordAudit(r, "vinref.batch.patch", batchTarget(bid), before, after)
	httpx.OK(w, toBatchDTO(after))
}

func (h *Handler) PublishBatch(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	h.batchTransition(w, r, "publish", "vinref.batch.publish")
}

func (h *Handler) ArchiveBatch(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	h.batchTransition(w, r, "archive", "vinref.batch.archive")
}

func (h *Handler) batchTransition(w http.ResponseWriter, r *http.Request, op, action string) {
	bid, err := parsePathInt(r, "bid")
	if err != nil || bid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, _ := h.batches.FindByID(r.Context(), bid)
	switch op {
	case "publish":
		err = h.batches.Publish(r.Context(), bid)
	case "archive":
		err = h.batches.Archive(r.Context(), bid)
	}
	if err != nil {
		writeBatchErr(w, err)
		return
	}
	after, _ := h.batches.FindByID(r.Context(), bid)
	h.recordAudit(r, action, batchTarget(bid), before, after)
	httpx.OK(w, toBatchDTO(after))
}

func (h *Handler) DeleteBatch(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	bid, err := parsePathInt(r, "bid")
	if err != nil || bid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, _ := h.batches.FindByID(r.Context(), bid)
	if err := h.batches.DeleteDraft(r.Context(), bid); err != nil {
		writeBatchErr(w, err)
		return
	}
	h.recordAudit(r, "vinref.batch.delete", batchTarget(bid), before, nil)
	httpx.OK(w, map[string]any{"deleted": true})
}

// ============================================================================
// 样本 CRUD
// ============================================================================

type createSampleReq struct {
	Character        string   `json:"character"`
	ArrMode          int16    `json:"arr_mode"`
	FontID           string   `json:"font_id"`
	FontFamilyID     *string  `json:"font_family_id"`
	PositionHint     *int16   `json:"position_hint"`
	AlphaObjectKey   string   `json:"alpha_object_key"`
	AlphaSHA256      string   `json:"alpha_sha256"`
	AlphaSizeBytes   int64    `json:"alpha_size_bytes"`
	OriginObjectKey  *string  `json:"origin_object_key"`
	OriginSHA256     *string  `json:"origin_sha256"`
	OriginSizeBytes  *int64   `json:"origin_size_bytes"`
	FeatureVectorURI *string  `json:"feature_vector_uri"`
	QCScore          *float32 `json:"qc_score"`
}

func (h *Handler) CreateSample(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	bid, err := parsePathInt(r, "bid")
	if err != nil || bid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var req createSampleReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if req.Character == "" || req.AlphaObjectKey == "" || req.AlphaSHA256 == "" || req.AlphaSizeBytes <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	fontID := req.FontID
	if fontID == "" {
		fontID = "*"
	}
	s := &repo.VinGlyphSample{
		BatchID:          bid,
		Character:        req.Character,
		ArrMode:          req.ArrMode,
		FontID:           fontID,
		FontFamilyID:     req.FontFamilyID,
		PositionHint:     req.PositionHint,
		AlphaObjectKey:   req.AlphaObjectKey,
		AlphaSHA256:      req.AlphaSHA256,
		AlphaSizeBytes:   req.AlphaSizeBytes,
		OriginObjectKey:  req.OriginObjectKey,
		OriginSHA256:     req.OriginSHA256,
		OriginSizeBytes:  req.OriginSizeBytes,
		FeatureVectorURI: req.FeatureVectorURI,
		QCScore:          req.QCScore,
	}
	if err := h.samples.Insert(r.Context(), s); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound, "批次不存在"))
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict,
				"非 draft 批次不可写样本"))
		case errors.Is(err, repo.ErrFieldRange):
			httpx.WriteError(w, httpx.ErrFieldRange)
		default:
			h.log.Error("CreateSample 失败", "err", err)
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	h.recordAudit(r, "vinref.sample.create",
		"vin_glyph_sample:"+strconv.FormatInt(s.ID, 10), nil, s)
	httpx.OK(w, toSampleDTO(s))
}

func (h *Handler) DeleteSample(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	sid, err := parsePathInt(r, "sid")
	if err != nil || sid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if err := h.samples.Delete(r.Context(), sid); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict,
				"非 draft 批次不可删样本"))
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	h.recordAudit(r, "vinref.sample.delete",
		"vin_glyph_sample:"+strconv.FormatInt(sid, 10), nil, nil)
	httpx.OK(w, map[string]any{"deleted": true})
}

// ============================================================================
// 读路径（App / cv-engine）
// ============================================================================

func (h *Handler) GetActiveBatch(w http.ResponseWriter, r *http.Request) {
	vmid, err := parsePathInt(r, "vmid")
	if err != nil || vmid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	b, err := h.batches.FindActive(r.Context(), vmid)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"该车型暂无 published 字形参考批次"))
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	// 附带按字符的样本计数（cv-engine 健康检查 / admin 查看用）
	cnt, _ := h.samples.CountByCharacter(r.Context(), b.ID)
	httpx.OK(w, map[string]any{
		"batch":            toBatchDTO(b),
		"counts_by_char":   cnt,
	})
}

func (h *Handler) ListActiveSamples(w http.ResponseWriter, r *http.Request) {
	vmid, err := parsePathInt(r, "vmid")
	if err != nil || vmid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	b, err := h.batches.FindActive(r.Context(), vmid)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"该车型暂无 published 字形参考批次"))
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	h.writeSamplesByBatch(w, r, b.ID, b)
}

func (h *Handler) ListSamplesByBatch(w http.ResponseWriter, r *http.Request) {
	bid, err := parsePathInt(r, "bid")
	if err != nil || bid <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	b, err := h.batches.FindByID(r.Context(), bid)
	if err != nil {
		writeBatchErr(w, err)
		return
	}
	// App 端只能看 published；admin 看全状态
	if b.Status != "published" && callerRole(r) != rbac.RoleAdmin {
		httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
			"批次未发布或不存在"))
		return
	}
	h.writeSamplesByBatch(w, r, bid, b)
}

func (h *Handler) writeSamplesByBatch(w http.ResponseWriter, r *http.Request, bid int64, b *repo.VinGlyphBatch) {
	q := r.URL.Query()
	char := strings.ToUpper(strings.TrimSpace(q.Get("character")))
	pos, _ := strconv.Atoi(q.Get("position_hint"))
	limit, _ := strconv.Atoi(q.Get("limit"))
	items, err := h.samples.ListByBatch(r.Context(), repo.ListSamplesFilter{
		BatchID:      bid,
		Character:    char,
		PositionHint: int16(pos),
		Limit:        limit,
	})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]sampleDTO, 0, len(items))
	for i := range items {
		out = append(out, toSampleDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{
		"batch_id":   strconv.FormatInt(bid, 10),
		"batch_name": b.Name,
		"status":     b.Status,
		"items":      out,
	})
}

// ============================================================================
// 内部工具
// ============================================================================

func batchTarget(id int64) string {
	return "vin_glyph_batch:" + strconv.FormatInt(id, 10)
}

func writeBatchErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, repo.ErrNotFound):
		_ = err
		httpx.WriteError(w, httpx.ErrNotFound)
	case errors.Is(err, repo.ErrStateConflict):
		httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict,
			"批次状态不允许此操作"))
	case errors.Is(err, repo.ErrConflict):
		httpx.WriteError(w, httpx.NewError(40201, http.StatusConflict,
			"批次冲突"))
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
