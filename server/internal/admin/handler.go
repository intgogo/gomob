// admin BFF — 详见 docs/architecture/server/00-server-overview.md §7.x。
//
// 路径前缀都是 /admin/v1/*；统一鉴权（JWT + role=admin）。
//
// 本服务功能：
//
//   1) native 接口（直接落 PG / 业务）：
//      POST  /admin/v1/users/{id}/approve              注册审核通过
//      POST  /admin/v1/users/{id}/reject               驳回 pending
//      POST  /admin/v1/users/{id}/disable              禁用 active 用户
//      PATCH /admin/v1/users/{id}                      改 role / station_id
//      GET   /admin/v1/users?status=&role=&cursor=     分页列表
//      GET   /admin/v1/audit?user_id=&action=&from=&to=&cursor=  跨服务审计聚合
//
//   2) 反代到下游：
//      /admin/v1/catalog/*  → vehicle-catalog
//      /admin/v1/llm/*      → llmgateway
//      /admin/v1/models/*   → modelregistry
//
// mTLS / 管理网段访问控制由部署层（haproxy / istio sidecar）做；本服务做应用层 RBAC。
package admin

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/rbac"
	"io.gomob/server/pkg/repo"
)

type Config struct {
	CatalogTarget        string // http://127.0.0.1:18059
	LLMTarget            string // http://127.0.0.1:18811
	ModelRegistryTarget  string // http://127.0.0.1:18057
	VinRefTarget         string // http://127.0.0.1:18058
	ShapeRefTarget       string // http://127.0.0.1:18056
}

type Handler struct {
	cfg    Config
	users  *repo.UserRepo
	audits *repo.AuditRepo
	audit  audit.Recorder
}

func NewHandler(cfg Config, pool *pgxpool.Pool, audit audit.Recorder) *Handler {
	return &Handler{
		cfg:    cfg,
		users:  repo.NewUserRepo(pool),
		audits: repo.NewAuditRepo(pool),
		audit:  audit,
	}
}

func (h *Handler) Mount(mux *http.ServeMux) error {
	// native
	mux.Handle("POST /admin/v1/users/{id}/approve",
		Required(http.HandlerFunc(h.ApproveUser)))
	mux.Handle("POST /admin/v1/users/{id}/reject",
		Required(http.HandlerFunc(h.RejectUser)))
	mux.Handle("POST /admin/v1/users/{id}/disable",
		Required(http.HandlerFunc(h.DisableUser)))
	mux.Handle("PATCH /admin/v1/users/{id}",
		Required(http.HandlerFunc(h.PatchUser)))
	mux.Handle("GET /admin/v1/users",
		Required(http.HandlerFunc(h.ListUsers)))
	mux.Handle("GET /admin/v1/audit",
		Required(http.HandlerFunc(h.ListAudit)))

	// 反代。
	//
	// 路径优先级：vinref / shaperef 都形如 /admin/v1/catalog/vehicles/{vmid}/{vin-refs,shapes}/...，
	// 比 /admin/v1/catalog/ 更具体；Go 1.22 ServeMux 多模式匹配时优先选最具体的。三个独立路径并存，注册顺序无关。
	if h.cfg.VinRefTarget != "" {
		if err := h.mountVinRefProxy(mux); err != nil {
			return err
		}
	}
	if h.cfg.ShapeRefTarget != "" {
		if err := h.mountShapeRefProxy(mux); err != nil {
			return err
		}
	}
	if err := h.mountProxy(mux, "/admin/v1/catalog/", h.cfg.CatalogTarget); err != nil {
		return err
	}
	if err := h.mountProxy(mux, "/admin/v1/llm/", h.cfg.LLMTarget); err != nil {
		return err
	}
	if err := h.mountProxy(mux, "/admin/v1/models/", h.cfg.ModelRegistryTarget); err != nil {
		return err
	}
	return nil
}

// mountVinRefProxy 注册 vin-ref 反代。
//
// 路径模式：/admin/v1/catalog/vehicles/{vmid}/vin-refs/ 是 subtree 模式，匹配该前缀下所有子路径；
// 多注册一条 /admin/v1/catalog/vehicles/{vmid}/vin-refs（无尾斜杠）兜底集合根，
// 避免 ServeMux 默认 301 重定向。所有路径必须经 Required（admin 角色）。
//
// 注意：与 mountProxy("/admin/v1/catalog/", ...) 共存时，Go 1.22 ServeMux 会优先选更具体的模式。
func (h *Handler) mountVinRefProxy(mux *http.ServeMux) error {
	u, err := url.Parse(h.cfg.VinRefTarget)
	if err != nil {
		return err
	}
	proxy := httputil.NewSingleHostReverseProxy(u)
	mux.Handle("/admin/v1/catalog/vehicles/{vmid}/vin-refs/", Required(proxy))
	mux.Handle("/admin/v1/catalog/vehicles/{vmid}/vin-refs", Required(proxy))
	return nil
}

// mountShapeRefProxy 注册 shape-ref 反代。
//
// 路径模式：/admin/v1/catalog/vehicles/{vmid}/shapes/* 与 /admin/v1/catalog/vehicles/{vmid}/shapes（无尾斜杠）。
// 与 vin-ref 同构，只是路径段不同。
func (h *Handler) mountShapeRefProxy(mux *http.ServeMux) error {
	u, err := url.Parse(h.cfg.ShapeRefTarget)
	if err != nil {
		return err
	}
	proxy := httputil.NewSingleHostReverseProxy(u)
	mux.Handle("/admin/v1/catalog/vehicles/{vmid}/shapes/", Required(proxy))
	mux.Handle("/admin/v1/catalog/vehicles/{vmid}/shapes", Required(proxy))
	return nil
}

func (h *Handler) mountProxy(mux *http.ServeMux, prefix, target string) error {
	if target == "" {
		return nil
	}
	u, err := url.Parse(target)
	if err != nil {
		return err
	}
	proxy := httputil.NewSingleHostReverseProxy(u)
	// 末尾斜杠版本：前缀匹配（含子路径）
	mux.Handle(prefix, Required(proxy))
	// 无尾斜杠版本：精确匹配集合根（POST /admin/v1/models 等），避免 ServeMux 默认 301
	if len(prefix) > 1 && prefix[len(prefix)-1] == '/' {
		mux.Handle(prefix[:len(prefix)-1], Required(proxy))
	}
	return nil
}

// ----- 鉴权 middleware -----

// Required 要求 JWT 已被 gateway / 上游 JWT 中间件解析过，X-Gomob-User-Id + X-Gomob-Roles 已注入。
//
// admin 服务的入口在生产由 mTLS + 管理网段做隔离；这里只做应用层 role 校验。
// 缺 header 直接 401（dev 用 admin 直连时也要带这两个头）。
func Required(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		uid := r.Header.Get("X-Gomob-User-Id")
		role := r.Header.Get("X-Gomob-Roles")
		if uid == "" || role == "" {
			httpx.WriteError(w, httpx.ErrTokenInvalid)
			return
		}
		if role != rbac.RoleAdmin {
			httpx.WriteError(w, httpx.ErrPermDenied)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func callerUserID(r *http.Request) int64 {
	id, _ := strconv.ParseInt(r.Header.Get("X-Gomob-User-Id"), 10, 64)
	return id
}

func parsePathID(r *http.Request) (int64, error) {
	return strconv.ParseInt(r.PathValue("id"), 10, 64)
}

// ----- 用户审核 + 管理 -----

type userDTO struct {
	ID          string  `json:"id"`
	Username    string  `json:"username"`
	RealName    string  `json:"real_name"`
	EmployeeID  string  `json:"employee_id"`
	Role        string  `json:"role"`
	Status      string  `json:"status"`
	StationID   *string `json:"station_id,omitempty"`
	Note        *string `json:"note,omitempty"`
	CreatedAt   string  `json:"created_at"`
	ActivatedAt *string `json:"activated_at,omitempty"`
}

func toUserDTO(u *repo.User) userDTO {
	dto := userDTO{
		ID:         strconv.FormatInt(u.ID, 10),
		Username:   u.Username,
		RealName:   u.RealName,
		EmployeeID: u.EmployeeID,
		Role:       u.Role,
		Status:     u.Status,
		Note:       u.Note,
		CreatedAt:  u.CreatedAt.UTC().Format(time.RFC3339Nano),
	}
	if u.StationID != nil {
		s := strconv.FormatInt(*u.StationID, 10)
		dto.StationID = &s
	}
	if u.ActivatedAt != nil {
		s := u.ActivatedAt.UTC().Format(time.RFC3339Nano)
		dto.ActivatedAt = &s
	}
	return dto
}

func (h *Handler) ApproveUser(w http.ResponseWriter, r *http.Request) {
	id, err := parsePathID(r)
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, _ := h.users.FindByID(r.Context(), id)
	if err := h.users.Activate(r.Context(), id); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "用户不在 pending 状态"))
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	after, _ := h.users.FindByID(r.Context(), id)
	h.recordAudit(r, "user.approve", "user:"+strconv.FormatInt(id, 10), before, after)
	httpx.OK(w, toUserDTO(after))
}

func (h *Handler) RejectUser(w http.ResponseWriter, r *http.Request) {
	id, err := parsePathID(r)
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, _ := h.users.FindByID(r.Context(), id)
	if err := h.users.Reject(r.Context(), id); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "只能驳回 pending 用户"))
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	after, _ := h.users.FindByID(r.Context(), id)
	h.recordAudit(r, "user.reject", "user:"+strconv.FormatInt(id, 10), before, after)
	httpx.OK(w, toUserDTO(after))
}

func (h *Handler) DisableUser(w http.ResponseWriter, r *http.Request) {
	id, err := parsePathID(r)
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, _ := h.users.FindByID(r.Context(), id)
	if err := h.users.Disable(r.Context(), id); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "只能禁用 active 用户"))
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	after, _ := h.users.FindByID(r.Context(), id)
	h.recordAudit(r, "user.disable", "user:"+strconv.FormatInt(id, 10), before, after)
	httpx.OK(w, toUserDTO(after))
}

type patchUserReq struct {
	Role      *string `json:"role"`
	StationID *int64  `json:"station_id"` // -1 = 显式置 NULL
}

func (h *Handler) PatchUser(w http.ResponseWriter, r *http.Request) {
	id, err := parsePathID(r)
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var req patchUserReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if req.Role == nil && req.StationID == nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if req.Role != nil {
		switch *req.Role {
		case rbac.RoleInspector, rbac.RoleSupervisor, rbac.RoleReviewer, rbac.RoleAdmin:
		default:
			httpx.WriteError(w, httpx.NewError(httpx.ErrFieldRange.Code, http.StatusBadRequest, "role 取值不合法"))
			return
		}
	}
	before, _ := h.users.FindByID(r.Context(), id)
	if err := h.users.UpdateRoleAndStation(r.Context(), id, req.Role, req.StationID); err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	after, _ := h.users.FindByID(r.Context(), id)
	h.recordAudit(r, "user.patch", "user:"+strconv.FormatInt(id, 10), before, after)
	httpx.OK(w, toUserDTO(after))
}

func (h *Handler) ListUsers(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	cursor, _ := strconv.ParseInt(q.Get("cursor"), 10, 64)
	items, next, err := h.users.ListUsers(r.Context(), repo.ListUsersFilter{
		Status: q.Get("status"),
		Role:   q.Get("role"),
		Limit:  limit,
		Cursor: cursor,
	})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]userDTO, 0, len(items))
	for i := range items {
		out = append(out, toUserDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{
		"items":       out,
		"next_cursor": strconv.FormatInt(next, 10),
		"has_more":    next != 0,
	})
}

// ----- audit 聚合 -----

type auditDTO struct {
	ID        string          `json:"id"`
	UserID    *string         `json:"user_id,omitempty"`
	Action    string          `json:"action"`
	Target    *string         `json:"target,omitempty"`
	Before    json.RawMessage `json:"before,omitempty"`
	After     json.RawMessage `json:"after,omitempty"`
	IP        *string         `json:"ip,omitempty"`
	CreatedAt string          `json:"created_at"`
}

func toAuditDTO(e *repo.AuditLogEntry) auditDTO {
	d := auditDTO{
		ID:        strconv.FormatInt(e.ID, 10),
		Action:    e.Action,
		Target:    e.Target,
		Before:    e.Before,
		After:     e.After,
		IP:        e.IP,
		CreatedAt: e.CreatedAt.UTC().Format(time.RFC3339Nano),
	}
	if e.UserID != nil {
		s := strconv.FormatInt(*e.UserID, 10)
		d.UserID = &s
	}
	return d
}

func (h *Handler) ListAudit(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	uid, _ := strconv.ParseInt(q.Get("user_id"), 10, 64)
	limit, _ := strconv.Atoi(q.Get("limit"))
	cursor, _ := strconv.ParseInt(q.Get("cursor"), 10, 64)
	from, _ := time.Parse(time.RFC3339, q.Get("from"))
	to, _ := time.Parse(time.RFC3339, q.Get("to"))

	items, next, err := h.audits.List(r.Context(), repo.AuditFilter{
		UserID: uid,
		Action: q.Get("action"),
		Target: q.Get("target"),
		From:   from,
		To:     to,
		Limit:  limit,
		Cursor: cursor,
	})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]auditDTO, 0, len(items))
	for i := range items {
		out = append(out, toAuditDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{
		"items":       out,
		"next_cursor": strconv.FormatInt(next, 10),
		"has_more":    next != 0,
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
