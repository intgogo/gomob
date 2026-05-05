// LLM gateway HTTP handler — 详见 02-api-contract.md §15。
//
// 路径：
//
//	POST  /v1/llm/chat                 流式 / 非流式（按 stream 字段）
//	GET   /v1/llm/templates            列出 active 模板（任意已登录角色）
//	POST  /admin/v1/llm/templates      上传新模板（admin only）
//	POST  /admin/v1/llm/templates/{id}/activate
//	POST  /admin/v1/llm/templates/{id}/archive
//
// 流式实现：HTTP SSE（text/event-stream）。
// 客户端断开（CloseNotifier / ctx.Done）→ 通过 ctx 取消上游 provider。
package llmgateway

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/rbac"
	"io.gomob/server/pkg/repo"
)

type Handler struct {
	pool      *pgxpool.Pool
	templates *repo.LLMTemplateRepo
	calls     *repo.LLMCallLogRepo
	registry  *Registry
	audit     audit.Recorder
	log       *slog.Logger
}

func NewHandler(pool *pgxpool.Pool, registry *Registry, audit audit.Recorder) *Handler {
	return &Handler{
		pool:      pool,
		templates: repo.NewLLMTemplateRepo(pool),
		calls:     repo.NewLLMCallLogRepo(pool),
		registry:  registry,
		audit:     audit,
		log:       logger.New("llmgateway.handler"),
	}
}

func (h *Handler) Mount(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/llm/chat", h.Chat)
	mux.HandleFunc("GET /v1/llm/templates", h.ListTemplates)
	mux.HandleFunc("POST /admin/v1/llm/templates", h.CreateTemplate)
	mux.HandleFunc("POST /admin/v1/llm/templates/{id}/activate", h.ActivateTemplate)
	mux.HandleFunc("POST /admin/v1/llm/templates/{id}/archive", h.ArchiveTemplate)
	mux.HandleFunc("GET /admin/v1/llm/templates", h.ListAllTemplates)
}

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

func newRequestID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "llm_" + hex.EncodeToString(b)
}

// ==========================================================================
// /v1/llm/chat
// ==========================================================================

type chatReqDTO struct {
	TemplateName      string         `json:"template_name"`        // 优先用 active 版本
	TemplateID        string         `json:"template_id"`          // 指定具体版本（可选）
	Vars              map[string]any `json:"vars"`
	Stream            bool           `json:"stream"`
	PreferredProvider string         `json:"preferred_provider"`   // 覆盖模板的 provider（可选）
}

type chatRespDTO struct {
	RequestID string `json:"request_id"`
	Provider  string `json:"provider"`
	Model     string `json:"model"`
	Content   string `json:"content"`
	TokenIn   int    `json:"token_in"`
	TokenOut  int    `json:"token_out"`
	LatencyMS int    `json:"latency_ms"`
}

func (h *Handler) Chat(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	var req chatReqDTO
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}

	// 拉模板（id 优先；否则按 name 拿 active）
	var tpl *repo.LLMTemplate
	var err error
	switch {
	case req.TemplateID != "":
		id, perr := strconv.ParseInt(req.TemplateID, 10, 64)
		if perr != nil {
			httpx.WriteError(w, httpx.ErrBadParam)
			return
		}
		tpl, err = h.templates.FindByID(r.Context(), id)
	case req.TemplateName != "":
		tpl, err = h.templates.FindActive(r.Context(), req.TemplateName)
	default:
		httpx.WriteError(w, httpx.NewError(40601, http.StatusUnprocessableEntity,
			"必须提供 template_name 或 template_id"))
		return
	}
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.NewError(40601, http.StatusUnprocessableEntity,
				"LLM 模板不存在或已下线"))
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	// 渲染 user prompt
	userPrompt, err := Render(tpl.UserTemplate, req.Vars)
	if err != nil {
		httpx.WriteError(w, httpx.NewError(40601, http.StatusUnprocessableEntity,
			"模板渲染失败: "+err.Error()))
		return
	}

	// 选 provider：preferred_provider > tpl.PreferredProvider > registry default
	pref := req.PreferredProvider
	if pref == "" {
		pref = tpl.PreferredProvider
	}
	provider := h.registry.Pick(pref)

	model := ""
	if tpl.PreferredModel != nil {
		model = *tpl.PreferredModel
	}

	chatReq := ChatRequest{
		System: tpl.SystemPrompt,
		User:   userPrompt,
		Model:  model,
	}
	requestID := newRequestID()

	if req.Stream {
		h.handleStream(w, r, chatReq, provider, tpl, uid, requestID)
		return
	}
	h.handleNonStream(w, r, chatReq, provider, tpl, uid, requestID)
}

func (h *Handler) handleNonStream(
	w http.ResponseWriter, r *http.Request, req ChatRequest, provider Provider,
	tpl *repo.LLMTemplate, uid int64, requestID string,
) {
	content, usage, err := provider.Chat(r.Context(), req)
	status := "ok"
	var errMsg string
	if err != nil {
		status = classifyCallStatus(err)
		errMsg = err.Error()
	}
	h.recordCall(r.Context(), uid, tpl, provider.Name(), usage, status, errMsg, requestID)
	if err != nil {
		writeProviderError(w, err)
		return
	}
	httpx.OK(w, chatRespDTO{
		RequestID: requestID,
		Provider:  provider.Name(),
		Model:     usage.Model,
		Content:   content,
		TokenIn:   usage.TokenIn,
		TokenOut:  usage.TokenOut,
		LatencyMS: usage.LatencyMS,
	})
}

func (h *Handler) handleStream(
	w http.ResponseWriter, r *http.Request, req ChatRequest, provider Provider,
	tpl *repo.LLMTemplate, uid int64, requestID string,
) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no") // 关 nginx 缓冲
	w.Header().Set("Connection", "keep-alive")
	w.WriteHeader(http.StatusOK)

	// meta 事件先发
	writeSSE(w, "meta", map[string]any{
		"request_id": requestID,
		"provider":   provider.Name(),
	})
	flusher.Flush()

	// 客户端断开 → 上游取消（http.Server 在 ctx 上做了 CloseNotifier）
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	usage, err := provider.ChatStream(ctx, req, func(c Chunk) bool {
		// 发 delta；客户端断开时 Write 返回 error，我们停止
		if _, werr := fmt.Fprintf(w, "event: delta\ndata: %s\n\n", mustJSON(map[string]any{"content": c.Content})); werr != nil {
			cancel()
			return false
		}
		flusher.Flush()
		return true
	})

	status := "ok"
	var errMsg string
	if err != nil {
		if errors.Is(err, context.Canceled) {
			status = "cancelled"
			errMsg = "client disconnected"
		} else {
			status = classifyCallStatus(err)
			errMsg = err.Error()
		}
	}
	h.recordCall(ctx, uid, tpl, provider.Name(), usage, status, errMsg, requestID)

	// done 事件
	writeSSE(w, "done", map[string]any{
		"request_id": requestID,
		"provider":   provider.Name(),
		"model":      usage.Model,
		"token_in":   usage.TokenIn,
		"token_out":  usage.TokenOut,
		"latency_ms": usage.LatencyMS,
		"status":     status,
	})
	flusher.Flush()
}

// classifyCallStatus 把 err 映射成 audit status 字符串。
func classifyCallStatus(err error) string {
	switch {
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		return "cancelled"
	default:
		return "error"
	}
}

func writeProviderError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, ErrProviderRateLimit):
		httpx.WriteError(w, httpx.NewError(40602, http.StatusTooManyRequests, "LLM 配额超限"))
	case errors.Is(err, ErrProviderAuthFail):
		httpx.WriteError(w, httpx.NewError(40603, http.StatusBadGateway, "LLM 上游鉴权失败"))
	case errors.Is(err, ErrProviderUnavailable):
		httpx.WriteError(w, httpx.NewError(40603, http.StatusBadGateway, "LLM 上游不可用"))
	default:
		httpx.WriteError(w, httpx.NewError(40603, http.StatusBadGateway, "LLM 上游错误: "+err.Error()))
	}
}

// recordCall 写 llm_call_logs。
func (h *Handler) recordCall(
	ctx context.Context, uid int64, tpl *repo.LLMTemplate, provider string,
	usage Usage, status, errMsg, requestID string,
) {
	tplID := &tpl.ID
	tplName := &tpl.Name
	tplVer := &tpl.Version
	model := &usage.Model
	if usage.Model == "" {
		model = nil
	}
	var errPtr *string
	if errMsg != "" {
		errPtr = &errMsg
	}
	rid := requestID
	rec := &repo.LLMCallLog{
		UserID:       &uid,
		TemplateID:   tplID,
		TemplateName: tplName,
		TemplateVer:  tplVer,
		Provider:     provider,
		Model:        model,
		TokenIn:      int32(usage.TokenIn),
		TokenOut:     int32(usage.TokenOut),
		LatencyMS:    int32(usage.LatencyMS),
		Status:       status,
		Error:        errPtr,
		RequestID:    &rid,
	}
	// recordCall 用独立 ctx 避免 client cancel 后写不进表
	auditCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := h.calls.Insert(auditCtx, rec); err != nil {
		h.log.Error("llm_call_logs insert", "err", err, "request_id", requestID)
	}
}

// ==========================================================================
// 模板管理
// ==========================================================================

type tplDTO struct {
	ID                string          `json:"id"`
	Name              string          `json:"name"`
	Version           int32           `json:"version"`
	PreferredProvider string          `json:"preferred_provider"`
	PreferredModel    *string         `json:"preferred_model,omitempty"`
	SystemPrompt      string          `json:"system_prompt,omitempty"`
	UserTemplate      string          `json:"user_template,omitempty"`
	VarsSchema        json.RawMessage `json:"vars_schema,omitempty"`
	Status            string          `json:"status"`
	UpdatedAt         string          `json:"updated_at"`
}

func toTplDTO(t *repo.LLMTemplate, includePrompts bool) tplDTO {
	d := tplDTO{
		ID:                strconv.FormatInt(t.ID, 10),
		Name:              t.Name,
		Version:           t.Version,
		PreferredProvider: t.PreferredProvider,
		PreferredModel:    t.PreferredModel,
		VarsSchema:        t.VarsSchema,
		Status:            t.Status,
		UpdatedAt:         t.UpdatedAt.UTC().Format(time.RFC3339Nano),
	}
	if includePrompts {
		d.SystemPrompt = t.SystemPrompt
		d.UserTemplate = t.UserTemplate
	}
	return d
}

func (h *Handler) ListTemplates(w http.ResponseWriter, r *http.Request) {
	items, err := h.templates.ListActive(r.Context())
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]tplDTO, 0, len(items))
	for i := range items {
		out = append(out, toTplDTO(&items[i], false))
	}
	httpx.OK(w, map[string]any{"items": out})
}

func (h *Handler) ListAllTemplates(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	rows, err := h.pool.Query(r.Context(), `
		SELECT id, name, version, preferred_provider, preferred_model, system_prompt,
		       user_template, vars_schema, status, created_at, updated_at
		FROM llm_templates ORDER BY name, version DESC`)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	defer rows.Close()
	var items []tplDTO
	for rows.Next() {
		var t repo.LLMTemplate
		if err := rows.Scan(&t.ID, &t.Name, &t.Version, &t.PreferredProvider, &t.PreferredModel,
			&t.SystemPrompt, &t.UserTemplate, &t.VarsSchema, &t.Status, &t.CreatedAt, &t.UpdatedAt); err != nil {
			continue
		}
		items = append(items, toTplDTO(&t, true))
	}
	httpx.OK(w, map[string]any{"items": items})
}

type createTplReq struct {
	Name              string          `json:"name"`
	Version           int32           `json:"version"`
	PreferredProvider string          `json:"preferred_provider"`
	PreferredModel    *string         `json:"preferred_model"`
	SystemPrompt      string          `json:"system_prompt"`
	UserTemplate      string          `json:"user_template"`
	VarsSchema        json.RawMessage `json:"vars_schema"`
}

func (h *Handler) CreateTemplate(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	var req createTplReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if req.Name == "" || req.Version <= 0 || req.UserTemplate == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	t := &repo.LLMTemplate{
		Name:              req.Name,
		Version:           req.Version,
		PreferredProvider: req.PreferredProvider,
		PreferredModel:    req.PreferredModel,
		SystemPrompt:      req.SystemPrompt,
		UserTemplate:      req.UserTemplate,
		VarsSchema:        req.VarsSchema,
	}
	if err := h.templates.Create(r.Context(), t); err != nil {
		if errors.Is(err, repo.ErrConflict) {
			httpx.WriteError(w, httpx.NewError(40201, http.StatusConflict,
				"模板 (name, version) 已存在"))
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if h.audit != nil {
		bs, _ := audit.Encode(t)
		_ = h.audit.Record(r.Context(), audit.Entry{
			UserID: callerUserID(r),
			Action: "llm.template.create",
			Target: "llm_template:" + strconv.FormatInt(t.ID, 10),
			AfterRaw: bs,
			IP: r.RemoteAddr,
		})
	}
	httpx.OK(w, toTplDTO(t, true))
}

func (h *Handler) ActivateTemplate(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if err := h.templates.Activate(r.Context(), id); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "模板状态不允许激活"))
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	t, _ := h.templates.FindByID(r.Context(), id)
	if h.audit != nil && t != nil {
		_ = h.audit.Record(r.Context(), audit.Entry{
			UserID: callerUserID(r),
			Action: "llm.template.activate",
			Target: "llm_template:" + strconv.FormatInt(id, 10),
			IP:     r.RemoteAddr,
		})
	}
	httpx.OK(w, toTplDTO(t, true))
}

func (h *Handler) ArchiveTemplate(w http.ResponseWriter, r *http.Request) {
	if !mustAdmin(w, r) {
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if err := h.templates.Archive(r.Context(), id); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "模板已归档"))
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	t, _ := h.templates.FindByID(r.Context(), id)
	if h.audit != nil {
		_ = h.audit.Record(r.Context(), audit.Entry{
			UserID: callerUserID(r),
			Action: "llm.template.archive",
			Target: "llm_template:" + strconv.FormatInt(id, 10),
			IP:     r.RemoteAddr,
		})
	}
	httpx.OK(w, toTplDTO(t, true))
}

// ==========================================================================
// SSE 工具
// ==========================================================================

func writeSSE(w http.ResponseWriter, event string, data any) {
	_, _ = fmt.Fprintf(w, "event: %s\ndata: %s\n\n", event, mustJSON(data))
}

func mustJSON(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{}`
	}
	return string(b)
}
