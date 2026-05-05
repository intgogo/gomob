// catalog 服务的 HTTP 处理器 — 车型档案库（vehicle_models）CRUD + 状态机。
//
// 详见 docs/architecture/server/02-api-contract.md §13 / 00-server-overview.md §6.z。
//
// 通路（决策见 §6.app）：
//
//	App ──▶ gateway ──▶ api ──http──▶ catalog（仅读）
//	admin ──gRPC/HTTP──────────────▶ catalog（写：录入 / 修订 / 发布 / 归档）
//
// 当前阶段（M-S7）：catalog 自己暴露完整 RESTful CRUD，写路径靠 RBAC（admin 角色）保护；
// 后续 M-S6 admin BFF 直接调本服务。
package catalog

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/rbac"
	"io.gomob/server/pkg/repo"
)

type Config struct {
	CacheTTL time.Duration // Redis LRU 缓存 TTL（默认 10 分钟）
}

func DefaultConfig() Config { return Config{CacheTTL: 10 * time.Minute} }

type Handler struct {
	cfg    Config
	models *repo.VehicleModelRepo
	rdb    *redis.Client
	audit  audit.Recorder
}

func NewHandler(cfg Config, pool *pgxpool.Pool, rdb *redis.Client, audit audit.Recorder) *Handler {
	return &Handler{
		cfg:    cfg,
		models: repo.NewVehicleModelRepo(pool),
		rdb:    rdb,
		audit:  audit,
	}
}

func (h *Handler) Mount(mux *http.ServeMux) {
	// 读路径（App / api BFF）
	mux.HandleFunc("GET /v1/catalog/vehicles", h.ListPublished)
	mux.HandleFunc("GET /v1/catalog/vehicles/{id}", h.GetVehicle)

	// 写路径（admin only；外部由 admin BFF 反代调用）
	mux.HandleFunc("POST /admin/v1/catalog/vehicles", h.Create)
	mux.HandleFunc("PATCH /admin/v1/catalog/vehicles/{id}", h.Patch)
	mux.HandleFunc("POST /admin/v1/catalog/vehicles/{id}/publish", h.Publish)
	mux.HandleFunc("POST /admin/v1/catalog/vehicles/{id}/archive", h.Archive)

	// admin 看全状态列表
	mux.HandleFunc("GET /admin/v1/catalog/vehicles", h.ListAll)
}

// ---------- 工具 ----------

func callerUserID(r *http.Request) int64 {
	v := r.Header.Get("X-Gomob-User-Id")
	if v == "" {
		return 0
	}
	id, _ := strconv.ParseInt(v, 10, 64)
	return id
}

func callerRole(r *http.Request) string { return r.Header.Get("X-Gomob-Roles") }

func parsePathID(r *http.Request) (int64, error) {
	return strconv.ParseInt(r.PathValue("id"), 10, 64)
}

func cacheKey(id int64) string  { return "catalog:vm:" + strconv.FormatInt(id, 10) }
func cacheTagAll() string       { return "catalog:vm:*" } // wildcard 仅 invalidate 用

// ---------- DTO ----------

type vehicleDTO struct {
	ID                   string          `json:"id"`
	Make                 string          `json:"make"`
	Series               string          `json:"series"`
	Year                 *int32          `json:"year,omitempty"`
	EngineType           *string         `json:"engine_type,omitempty"`
	OutlineFeatures      json.RawMessage `json:"outline_features,omitempty"`
	ComplianceCheckList  json.RawMessage `json:"compliance_check_list,omitempty"`
	ManufacturerDocURL   *string         `json:"manufacturer_doc_url,omitempty"`
	Status               string          `json:"status"`
	UpdatedAt            string          `json:"updated_at"`
}

func toDTO(m *repo.VehicleModel) vehicleDTO {
	dto := vehicleDTO{
		ID:                  strconv.FormatInt(m.ID, 10),
		Make:                m.Make,
		Series:              m.Series,
		Year:                m.Year,
		EngineType:          m.EngineType,
		OutlineFeatures:     m.OutlineFeatures,
		ComplianceCheckList: m.ComplianceCheckList,
		ManufacturerDocURL:  m.ManufacturerDocURL,
		Status:              m.Status,
		UpdatedAt:           m.UpdatedAt.UTC().Format(time.RFC3339Nano),
	}
	return dto
}

// ---------- 读路径 ----------

func (h *Handler) ListPublished(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	cursor, _ := strconv.ParseInt(q.Get("cursor"), 10, 64)
	yr, _ := strconv.Atoi(q.Get("year"))
	items, next, err := h.models.List(r.Context(), repo.VehicleListFilter{
		Make:             strings.TrimSpace(q.Get("make")),
		Series:           strings.TrimSpace(q.Get("series")),
		Year:             int32(yr),
		Keyword:          strings.TrimSpace(q.Get("keyword")),
		IncludeAllStatus: false,
		Limit:            limit,
		Cursor:           cursor,
	})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]vehicleDTO, 0, len(items))
	for i := range items {
		out = append(out, toDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{
		"items":       out,
		"next_cursor": strconv.FormatInt(next, 10),
		"has_more":    next != 0,
	})
}

func (h *Handler) GetVehicle(w http.ResponseWriter, r *http.Request) {
	id, err := parsePathID(r)
	if err != nil || id <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}

	// 缓存命中
	if h.rdb != nil {
		if raw, err := h.rdb.Get(r.Context(), cacheKey(id)).Result(); err == nil && raw != "" {
			w.Header().Set("X-Gomob-Cache", "hit")
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(raw))
			return
		}
	}

	m, err := h.models.FindByID(r.Context(), id)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound, "车型档案不存在或未发布"))
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	// App 端只能看 published（admin 看全状态走 /admin/...）
	if m.Status != "published" && callerRole(r) != rbac.RoleAdmin {
		httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound, "车型档案不存在或未发布"))
		return
	}

	body, _ := json.Marshal(httpx.Envelope{Code: 0, Data: toDTO(m)})
	if h.rdb != nil {
		_ = h.rdb.Set(r.Context(), cacheKey(id), string(body), h.cfg.CacheTTL).Err()
	}
	w.Header().Set("X-Gomob-Cache", "miss")
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(body)
}

// ---------- 写路径（admin） ----------

func mustAdmin(w http.ResponseWriter, r *http.Request) bool {
	if callerRole(r) != rbac.RoleAdmin {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return false
	}
	return true
}

type createReq struct {
	Make                string          `json:"make"`
	Series              string          `json:"series"`
	Year                *int32          `json:"year"`
	EngineType          *string         `json:"engine_type"`
	OutlineFeatures     json.RawMessage `json:"outline_features"`
	ComplianceCheckList json.RawMessage `json:"compliance_check_list"`
	ManufacturerDocURL  *string         `json:"manufacturer_doc_url"`
}

func (h *Handler) Create(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	var req createReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	req.Make = strings.TrimSpace(req.Make)
	req.Series = strings.TrimSpace(req.Series)
	if req.Make == "" || req.Series == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	m := &repo.VehicleModel{
		Make:                req.Make,
		Series:              req.Series,
		Year:                req.Year,
		EngineType:          req.EngineType,
		OutlineFeatures:     req.OutlineFeatures,
		ComplianceCheckList: req.ComplianceCheckList,
		ManufacturerDocURL:  req.ManufacturerDocURL,
	}
	if err := h.models.Create(r.Context(), m); err != nil {
		if errors.Is(err, repo.ErrConflict) {
			httpx.WriteError(w, httpx.NewError(40201, http.StatusConflict, "车型档案已存在（make/series/year 三元组冲突）"))
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	h.recordAudit(r, "catalog.create", "vehicle_model:"+strconv.FormatInt(m.ID, 10), nil, m)
	httpx.OK(w, toDTO(m))
}

func (h *Handler) Patch(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	id, err := parsePathID(r)
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var p repo.VehicleModelPatch
	if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, _ := h.models.FindByID(r.Context(), id)
	if err := h.models.Patch(r.Context(), id, p); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "已发布或归档的档案不可改"))
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	h.invalidate(r.Context(), id)
	after, _ := h.models.FindByID(r.Context(), id)
	h.recordAudit(r, "catalog.patch", "vehicle_model:"+strconv.FormatInt(id, 10), before, after)
	httpx.OK(w, toDTO(after))
}

func (h *Handler) Publish(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	h.transition(w, r, []string{"draft"}, "published", "catalog.publish")
}

func (h *Handler) Archive(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	h.transition(w, r, []string{"draft", "published"}, "archived", "catalog.archive")
}

func (h *Handler) transition(w http.ResponseWriter, r *http.Request, from []string, to, action string) {
	id, err := parsePathID(r)
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, _ := h.models.FindByID(r.Context(), id)
	if err := h.models.Transition(r.Context(), id, from, to); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "状态机不允许该操作"))
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	h.invalidate(r.Context(), id)
	after, _ := h.models.FindByID(r.Context(), id)
	h.recordAudit(r, action, "vehicle_model:"+strconv.FormatInt(id, 10), before, after)
	httpx.OK(w, toDTO(after))
}

func (h *Handler) ListAll(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	cursor, _ := strconv.ParseInt(q.Get("cursor"), 10, 64)
	yr, _ := strconv.Atoi(q.Get("year"))
	items, next, err := h.models.List(r.Context(), repo.VehicleListFilter{
		Make:             strings.TrimSpace(q.Get("make")),
		Series:           strings.TrimSpace(q.Get("series")),
		Year:             int32(yr),
		Keyword:          strings.TrimSpace(q.Get("keyword")),
		IncludeAllStatus: true,
		Limit:            limit,
		Cursor:           cursor,
	})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]vehicleDTO, 0, len(items))
	for i := range items {
		out = append(out, toDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{
		"items":       out,
		"next_cursor": strconv.FormatInt(next, 10),
		"has_more":    next != 0,
	})
}

// ---------- 缓存 + 审计 ----------

func (h *Handler) invalidate(ctx context.Context, id int64) {
	if h.rdb == nil {
		return
	}
	_ = h.rdb.Del(ctx, cacheKey(id)).Err()
}

func (h *Handler) recordAudit(r *http.Request, action, target string, before, after any) {
	if h.audit == nil {
		return
	}
	bs, _ := audit.Encode(before)
	as, _ := audit.Encode(after)
	_ = h.audit.Record(r.Context(), audit.Entry{
		UserID:    callerUserID(r),
		Action:    action,
		Target:    target,
		BeforeRaw: bs,
		AfterRaw:  as,
		IP:        r.RemoteAddr,
	})
}
