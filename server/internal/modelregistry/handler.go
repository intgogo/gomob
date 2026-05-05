// model-registry HTTP handler — AI 模型版本元数据 + canary 路由 + NATS 通知。
//
// 详见 docs/architecture/server/00-server-overview.md §6.y。
//
// 路径：
//
//	GET   /v1/models/active?name=xxx          worker / cv-engine 启动 / 热更时拉
//	GET   /v1/models/resolve?name=xxx&user_id=x   按 user_id + canary 比例返回应用版本
//	POST  /admin/v1/models                    admin 录入新版本（draft）
//	POST  /admin/v1/models/{id}/promote       draft/canary → canary
//	POST  /admin/v1/models/{id}/activate      *  → active（同 name 旧 active 自动归档）
//	POST  /admin/v1/models/{id}/archive       * → archived
//	GET   /admin/v1/models?name=xxx           列出某 name 全部版本
//	PUT   /admin/v1/models/{name}/route       配置 canary 路由（pct + user filter）
//
// 所有状态变更通过 NATS 发 `model.version.activated`：
//
//	{ "name": "yolo_vin", "version": "v1.2.0", "status": "active", "asset_uri": "..." }
package modelregistry

import (
	"context"
	"encoding/json"
	"errors"
	"hash/fnv"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/pubsub"
	"io.gomob/server/pkg/rbac"
	"io.gomob/server/pkg/repo"
)

const TopicModelActivated = "model.version.activated"

type Handler struct {
	pool   *pgxpool.Pool
	models *repo.ModelRepo
	routes *repo.ModelRouteRepo
	bus    pubsub.Publisher
	audit  audit.Recorder
	log    *slog.Logger
}

func NewHandler(pool *pgxpool.Pool, bus pubsub.Publisher, audit audit.Recorder) *Handler {
	return &Handler{
		pool:   pool,
		models: repo.NewModelRepo(pool),
		routes: repo.NewModelRouteRepo(pool),
		bus:    bus,
		audit:  audit,
		log:    logger.New("modelregistry.handler"),
	}
}

func (h *Handler) Mount(mux *http.ServeMux) {
	// 内部读路径（worker / cv-engine 调）
	mux.HandleFunc("GET /v1/models/active", h.GetActive)
	mux.HandleFunc("GET /v1/models/resolve", h.Resolve)

	// admin 写路径
	mux.HandleFunc("POST /admin/v1/models", h.Create)
	mux.HandleFunc("POST /admin/v1/models/{id}/promote", h.Promote)
	mux.HandleFunc("POST /admin/v1/models/{id}/activate", h.Activate)
	mux.HandleFunc("POST /admin/v1/models/{id}/archive", h.Archive)
	mux.HandleFunc("GET /admin/v1/models", h.ListAll)
	mux.HandleFunc("PUT /admin/v1/models/{name}/route", h.UpsertRoute)
	mux.HandleFunc("GET /admin/v1/models/{name}/route", h.GetRoute)
}

func callerUserID(r *http.Request) int64 {
	v := r.Header.Get("X-Gomob-User-Id")
	if v == "" {
		return 0
	}
	id, _ := strconv.ParseInt(v, 10, 64)
	return id
}

func mustAdmin(w http.ResponseWriter, r *http.Request) bool {
	if r.Header.Get("X-Gomob-Roles") != rbac.RoleAdmin {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return false
	}
	return true
}

// ----- DTO -----

type modelDTO struct {
	ID        string          `json:"id"`
	Name      string          `json:"name"`
	Version   string          `json:"version"`
	AssetURI  string          `json:"asset_uri"`
	SHA256    string          `json:"sha256"`
	Runtime   string          `json:"runtime"`
	Framework *string         `json:"framework,omitempty"`
	Metadata  json.RawMessage `json:"metadata,omitempty"`
	Status    string          `json:"status"`
	UpdatedAt string          `json:"updated_at"`
}

func toDTO(m *repo.Model) modelDTO {
	return modelDTO{
		ID:        strconv.FormatInt(m.ID, 10),
		Name:      m.Name,
		Version:   m.Version,
		AssetURI:  m.AssetURI,
		SHA256:    m.SHA256,
		Runtime:   m.Runtime,
		Framework: m.Framework,
		Metadata:  m.Metadata,
		Status:    m.Status,
		UpdatedAt: m.UpdatedAt.UTC().Format(time.RFC3339Nano),
	}
}

// ----- 读路径 -----

// GET /v1/models/active?name=xxx
func (h *Handler) GetActive(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("name")
	if name == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	m, err := h.models.FindActive(r.Context(), name)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	httpx.OK(w, toDTO(m))
}

// GET /v1/models/resolve?name=xxx&user_id=N
//
// 按 model_routes.canary_pct + canary_user_filter 决定返 canary 还是 active。
// 算法：
//  1. 命中 user_filter（白名单）→ 返 canary（如果 canary 存在）
//  2. 否则按 hash(name + user_id) % 100 < canary_pct → 返 canary（确定性，方便回放）
//  3. 否则返 active
//
// canary 不存在时一律返 active。
func (h *Handler) Resolve(w http.ResponseWriter, r *http.Request) {
	name := r.URL.Query().Get("name")
	if name == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	userID, _ := strconv.ParseInt(r.URL.Query().Get("user_id"), 10, 64)

	active, errA := h.models.FindActive(r.Context(), name)
	canary, errC := h.models.FindCanary(r.Context(), name)

	// 都没有 → 404
	if errors.Is(errA, repo.ErrNotFound) && errors.Is(errC, repo.ErrNotFound) {
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	}
	// 内部错
	if errA != nil && !errors.Is(errA, repo.ErrNotFound) {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if errC != nil && !errors.Is(errC, repo.ErrNotFound) {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	// 没 canary → 走 active
	if canary == nil {
		httpx.OK(w, map[string]any{"resolved": "active", "model": toDTO(active)})
		return
	}
	// 没 active → 走 canary
	if active == nil {
		httpx.OK(w, map[string]any{"resolved": "canary", "model": toDTO(canary)})
		return
	}

	route, err := h.routes.Find(r.Context(), name)
	if err != nil && !errors.Is(err, repo.ErrNotFound) {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	// 默认 0% canary
	pct := int16(0)
	var filter map[string]any
	if route != nil {
		pct = route.CanaryPct
		_ = json.Unmarshal(route.CanaryUserFilter, &filter)
	}

	// 1) 白名单
	if filter != nil {
		if list, ok := filter["user_ids"].([]any); ok {
			for _, v := range list {
				switch t := v.(type) {
				case float64:
					if int64(t) == userID {
						httpx.OK(w, map[string]any{"resolved": "canary", "reason": "user_whitelist", "model": toDTO(canary)})
						return
					}
				case string:
					if t == strconv.FormatInt(userID, 10) {
						httpx.OK(w, map[string]any{"resolved": "canary", "reason": "user_whitelist", "model": toDTO(canary)})
						return
					}
				}
			}
		}
	}

	// 2) hash 比例
	if pct > 0 && stableBucket(name, userID) < int(pct) {
		httpx.OK(w, map[string]any{"resolved": "canary", "reason": "pct", "model": toDTO(canary)})
		return
	}

	// 3) 走 active
	httpx.OK(w, map[string]any{"resolved": "active", "model": toDTO(active)})
}

// stableBucket 把 (name, user_id) 映射到 0..99；同一对永远相同 bucket。
func stableBucket(name string, userID int64) int {
	h := fnv.New32a()
	_, _ = h.Write([]byte(name))
	_, _ = h.Write([]byte{byte(userID), byte(userID >> 8), byte(userID >> 16), byte(userID >> 24),
		byte(userID >> 32), byte(userID >> 40), byte(userID >> 48), byte(userID >> 56)})
	return int(h.Sum32() % 100)
}

// ----- admin 写路径 -----

type createReq struct {
	Name      string          `json:"name"`
	Version   string          `json:"version"`
	AssetURI  string          `json:"asset_uri"`
	SHA256    string          `json:"sha256"`
	Runtime   string          `json:"runtime"`
	Framework *string         `json:"framework"`
	Metadata  json.RawMessage `json:"metadata"`
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
	if req.Name == "" || req.Version == "" || req.AssetURI == "" || req.SHA256 == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	m := &repo.Model{
		Name: req.Name, Version: req.Version, AssetURI: req.AssetURI, SHA256: req.SHA256,
		Runtime: req.Runtime, Framework: req.Framework, Metadata: req.Metadata,
	}
	if err := h.models.Create(r.Context(), m); err != nil {
		if errors.Is(err, repo.ErrConflict) {
			httpx.WriteError(w, httpx.NewError(40201, http.StatusConflict, "模型 (name, version) 已存在"))
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	h.recordAudit(r, "model.create", "model:"+strconv.FormatInt(m.ID, 10), nil, m)
	httpx.OK(w, toDTO(m))
}

func (h *Handler) Promote(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	h.transitionByAction(w, r, "promote_canary")
}

func (h *Handler) Activate(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	h.transitionByAction(w, r, "activate")
}

func (h *Handler) Archive(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	h.transitionByAction(w, r, "archive")
}

func (h *Handler) transitionByAction(w http.ResponseWriter, r *http.Request, action string) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, _ := h.models.FindByID(r.Context(), id)
	switch action {
	case "promote_canary":
		err = h.models.PromoteCanary(r.Context(), id)
	case "activate":
		err = h.models.Activate(r.Context(), id)
	case "archive":
		err = h.models.Archive(r.Context(), id)
	default:
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if err != nil {
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
	after, _ := h.models.FindByID(r.Context(), id)
	h.recordAudit(r, "model."+action, "model:"+strconv.FormatInt(id, 10), before, after)

	// NATS 广播：worker / cv-engine 据此热更
	h.publishActivated(r.Context(), after)
	httpx.OK(w, toDTO(after))
}

func (h *Handler) publishActivated(ctx context.Context, m *repo.Model) {
	if h.bus == nil || m == nil {
		return
	}
	payload := map[string]any{
		"id":        strconv.FormatInt(m.ID, 10),
		"name":      m.Name,
		"version":   m.Version,
		"status":    m.Status,
		"asset_uri": m.AssetURI,
		"sha256":    m.SHA256,
		"runtime":   m.Runtime,
		"ts":        time.Now().UTC().Format(time.RFC3339Nano),
	}
	if err := h.bus.Publish(ctx, TopicModelActivated, payload); err != nil {
		h.log.Warn("NATS publish 失败", "topic", TopicModelActivated, "err", err)
	}
}

func (h *Handler) ListAll(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	name := r.URL.Query().Get("name")
	if name == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	items, err := h.models.ListByName(r.Context(), name)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]modelDTO, 0, len(items))
	for i := range items {
		out = append(out, toDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{"items": out})
}

// ----- 灰度路由 -----

type routeReq struct {
	CanaryPct        int16           `json:"canary_pct"`
	CanaryUserFilter json.RawMessage `json:"canary_user_filter"`
}

func (h *Handler) UpsertRoute(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	name := r.PathValue("name")
	if name == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var req routeReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if req.CanaryPct < 0 || req.CanaryPct > 100 {
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	}
	ro := &repo.ModelRoute{Name: name, CanaryPct: req.CanaryPct, CanaryUserFilter: req.CanaryUserFilter}
	if err := h.routes.Upsert(r.Context(), ro); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	h.recordAudit(r, "model.route.update", "model_route:"+name, nil, ro)
	httpx.OK(w, map[string]any{
		"name":               ro.Name,
		"canary_pct":         ro.CanaryPct,
		"canary_user_filter": ro.CanaryUserFilter,
		"updated_at":         ro.UpdatedAt.UTC().Format(time.RFC3339Nano),
	})
}

func (h *Handler) GetRoute(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	name := r.PathValue("name")
	ro, err := h.routes.Find(r.Context(), name)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			// 默认 0%
			httpx.OK(w, map[string]any{"name": name, "canary_pct": 0, "canary_user_filter": json.RawMessage("{}")})
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	httpx.OK(w, map[string]any{
		"name":               ro.Name,
		"canary_pct":         ro.CanaryPct,
		"canary_user_filter": ro.CanaryUserFilter,
		"updated_at":         ro.UpdatedAt.UTC().Format(time.RFC3339Nano),
	})
}

// ----- audit -----

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
