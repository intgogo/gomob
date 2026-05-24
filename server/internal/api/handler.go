// api 服务的 HTTP 处理器 — 查验主流程 + 抽查复核 + 资产元数据查询。
//
// 详见 docs/architecture/server/02-api-contract.md §4 / §6 / §9。
package api

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

type RealtimeMessageNotifier interface {
	NotifyMessage(ctx context.Context, senderID int64, message *repo.Message) (int, error)
}

type RealtimeMessageRecallNotifier interface {
	NotifyMessageRecall(ctx context.Context, message *repo.Message, recalledBy int64) (int, error)
}

type RealtimeTranscriptNotifier interface {
	NotifyTranscriptUpdate(ctx context.Context, message *repo.Message) (int, error)
}

type Handler struct {
	pool          *pgxpool.Pool
	inspections   *repo.InspectionRepo
	vehicles      *repo.VehicleRepo
	reviews       *repo.ReviewRepo
	assets        *repo.AssetRepo
	users         *repo.UserRepo
	conversations *repo.ConversationRepo
	messages      *repo.MessageRepo
	media         *repo.MediaRepo
	transcripts   *repo.TranscriptRepo
	audit         audit.Recorder
	enforcer      rbac.Enforcer
	realtime      RealtimeMessageNotifier
	log           *slog.Logger
}

func NewHandler(pool *pgxpool.Pool, audit audit.Recorder, enforcer rbac.Enforcer) *Handler {
	return &Handler{
		pool:          pool,
		inspections:   repo.NewInspectionRepo(pool),
		vehicles:      repo.NewVehicleRepo(pool),
		reviews:       repo.NewReviewRepo(pool),
		assets:        repo.NewAssetRepo(pool),
		users:         repo.NewUserRepo(pool),
		conversations: repo.NewConversationRepo(pool),
		messages:      repo.NewMessageRepo(pool),
		media:         repo.NewMediaRepo(pool),
		transcripts:   repo.NewTranscriptRepo(pool, defaultTranscriptConfig()),
		audit:         audit,
		enforcer:      enforcer,
		log:           logger.New("api.handler"),
	}
}

func (h *Handler) SetRealtimeMessageNotifier(notifier RealtimeMessageNotifier) {
	h.realtime = notifier
}

func (h *Handler) SetTranscriptConfig(cfg repo.TranscriptConfig) {
	h.transcripts = repo.NewTranscriptRepo(h.pool, cfg)
}

// Mount 把所有路由挂到 mux；调用方为受保护路径在外层套 Required（gateway 模式下 gateway 已校验）。
func (h *Handler) Mount(mux *http.ServeMux) {
	// 查验
	mux.HandleFunc("GET /v1/inspections", h.ListInspections)
	mux.HandleFunc("POST /v1/inspections", h.CreateInspection)
	mux.HandleFunc("GET /v1/inspections/{id}", h.GetInspection)
	mux.HandleFunc("POST /v1/inspections/{id}/start", h.StartInspection)
	mux.HandleFunc("PATCH /v1/inspections/{id}/result", h.UpdatePreliminary)
	mux.HandleFunc("POST /v1/inspections/{id}/submit", h.SubmitForReview)
	mux.HandleFunc("POST /v1/inspections/{id}/close", h.CloseInspection)
	mux.HandleFunc("GET /v1/inspections/{id}/assets", h.ListAssets)

	// 复核
	mux.HandleFunc("GET /v1/reviews", h.ListReviews)
	mux.HandleFunc("GET /v1/reviews/{id}", h.GetReview)
	mux.HandleFunc("POST /v1/reviews/{id}/decision", h.DecideReview)

	// 消息
	mux.HandleFunc("GET /v1/conversations", h.ListConversations)
	mux.HandleFunc("GET /v1/conversations/help-experts", h.ListHelpExperts)
	mux.HandleFunc("GET /v1/conversations/help-experts/{id}/cases", h.ListHelpExpertCases)
	mux.HandleFunc("POST /v1/conversations/help-room", h.OpenHelpRoom)
	mux.HandleFunc("GET /v1/contacts", h.ListContacts)
	mux.HandleFunc("POST /v1/conversations/p2p", h.OpenP2PConversation)
	mux.HandleFunc("POST /v1/conversations/ad-hoc", h.OpenAdHocGroup)
	mux.HandleFunc("GET /v1/conversations/{id}/messages", h.ListConversationMessages)
	mux.HandleFunc("POST /v1/conversations/{id}/messages", h.CreateConversationMessage)
	mux.HandleFunc("POST /v1/conversations/{id}/messages/{messageId}/recall", h.RecallConversationMessage)
	mux.HandleFunc("POST /v1/conversations/{id}/call-invites", h.CreateConversationCallInvite)
	mux.HandleFunc("POST /v1/conversations/{id}/read", h.MarkConversationRead)
	mux.HandleFunc("POST /v1/conversations/{id}/leave", h.LeaveConversation)
	mux.HandleFunc("POST /v1/messages/transcribe-draft", h.TranscribeDraftVoice)
	mux.HandleFunc("POST /v1/messages/{id}/transcript/retry", h.RetryMessageTranscript)

	// 媒体控制面
	mux.HandleFunc("GET /v1/media/rooms/{id}", h.GetMediaRoom)
	mux.HandleFunc("POST /v1/media/rooms", h.CreateMediaRoom)
	mux.HandleFunc("POST /v1/media/rooms/{id}/token", h.CreateMediaRoomToken)
	mux.HandleFunc("POST /v1/media/rooms/{id}/end", h.EndMediaRoom)
	mux.HandleFunc("GET /v1/live-sessions", h.ListLiveSessions)
	mux.HandleFunc("POST /v1/live-sessions", h.CreateLiveSession)
	mux.HandleFunc("POST /v1/livekit/webhook", h.LiveKitWebhook)
}

// ---------- 通用工具 ----------

// callerUserID 从 gateway 注入的 header 拿用户 id；缺失返 0。
func callerUserID(r *http.Request) int64 {
	v := r.Header.Get("X-Gomob-User-Id")
	if v == "" {
		return 0
	}
	id, _ := strconv.ParseInt(v, 10, 64)
	return id
}

func callerRole(r *http.Request) string { return r.Header.Get("X-Gomob-Roles") }

func parsePathID(r *http.Request, name string) (int64, error) {
	raw := r.PathValue(name)
	if raw == "" {
		return 0, errors.New("missing path id")
	}
	return strconv.ParseInt(raw, 10, 64)
}

// ==========================================================================
// 查验
// ==========================================================================

type inspectionDTO struct {
	ID                 string   `json:"id"`
	VehicleID          string   `json:"vehicle_id"`
	InspectorID        string   `json:"inspector_id"`
	StationID          string   `json:"station_id"`
	Status             string   `json:"status"`
	PreliminaryVerdict *string  `json:"preliminary_verdict,omitempty"`
	PreliminaryReasons []string `json:"preliminary_reasons,omitempty"`
	CreatedAt          string   `json:"created_at"`
	ClosedAt           *string  `json:"closed_at,omitempty"`
}

func toInspectionDTO(ins *repo.Inspection) inspectionDTO {
	dto := inspectionDTO{
		ID:                 strconv.FormatInt(ins.ID, 10),
		VehicleID:          strconv.FormatInt(ins.VehicleID, 10),
		InspectorID:        strconv.FormatInt(ins.InspectorID, 10),
		StationID:          strconv.FormatInt(ins.StationID, 10),
		Status:             ins.Status,
		PreliminaryVerdict: ins.PreliminaryVerdict,
		CreatedAt:          ins.CreatedAt.UTC().Format(time.RFC3339Nano),
	}
	if ins.ClosedAt != nil {
		s := ins.ClosedAt.UTC().Format(time.RFC3339Nano)
		dto.ClosedAt = &s
	}
	if len(ins.PreliminaryReasons) > 0 {
		_ = json.Unmarshal(ins.PreliminaryReasons, &dto.PreliminaryReasons)
	}
	return dto
}

// GET /v1/inspections?inspector_id=&status=&limit=&cursor=
func (h *Handler) ListInspections(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	cursor, _ := strconv.ParseInt(q.Get("cursor"), 10, 64)
	inspectorID, _ := strconv.ParseInt(q.Get("inspector_id"), 10, 64)
	// 默认拉自己的（除非 supervisor/admin 角色显式指定）
	role := callerRole(r)
	if inspectorID == 0 && role != rbac.RoleSupervisor && role != rbac.RoleAdmin {
		inspectorID = callerUserID(r)
	}
	// 按 RBAC 校验：非自己的列表必须 supervisor / admin
	if inspectorID != callerUserID(r) {
		if !h.enforcer.Allow(role, "inspection", "read_any") {
			httpx.WriteError(w, httpx.ErrPermDenied)
			return
		}
	}

	items, next, err := h.inspections.List(r.Context(), repo.ListFilter{
		InspectorID: inspectorID,
		Status:      q.Get("status"),
		Limit:       limit,
		Cursor:      cursor,
	})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]inspectionDTO, 0, len(items))
	for i := range items {
		out = append(out, toInspectionDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{
		"items":       out,
		"next_cursor": strconv.FormatInt(next, 10),
		"has_more":    next != 0,
	})
}

// GET /v1/inspections/:id
func (h *Handler) GetInspection(w http.ResponseWriter, r *http.Request) {
	id, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	ins, err := h.inspections.FindByID(r.Context(), id)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	// 权限：自己的 OR supervisor/admin
	if ins.InspectorID != callerUserID(r) {
		if !h.enforcer.Allow(callerRole(r), "inspection", "read_any") {
			httpx.WriteError(w, httpx.ErrPermDenied)
			return
		}
	}
	httpx.OK(w, toInspectionDTO(ins))
}

type createInspectionReq struct {
	VIN       string `json:"vin"`
	PlateNo   string `json:"plate_no"`
	Brand     string `json:"brand"`
	StationID string `json:"station_id"`
}

// POST /v1/inspections —— 按 VIN upsert 车辆，创建查验，状态 created。
func (h *Handler) CreateInspection(w http.ResponseWriter, r *http.Request) {
	if !h.enforcer.Allow(callerRole(r), "inspection", "create") {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	var req createInspectionReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	req.VIN = strings.ToUpper(strings.TrimSpace(req.VIN))
	if len(req.VIN) != 17 {
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	}
	stationID, _ := strconv.ParseInt(req.StationID, 10, 64)
	if stationID <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}

	v := &repo.Vehicle{VIN: req.VIN}
	if req.PlateNo != "" {
		v.PlateNo = &req.PlateNo
	}
	if req.Brand != "" {
		v.Brand = &req.Brand
	}
	if err := h.vehicles.Upsert(r.Context(), v); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	ins := &repo.Inspection{
		VehicleID:   v.ID,
		InspectorID: callerUserID(r),
		StationID:   stationID,
	}
	if err := h.inspections.Create(r.Context(), ins); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	h.recordAudit(r, "inspection.create", "inspection:"+strconv.FormatInt(ins.ID, 10), nil, ins)
	httpx.OK(w, toInspectionDTO(ins))
}

// POST /v1/inspections/:id/start —— created → scanning。
func (h *Handler) StartInspection(w http.ResponseWriter, r *http.Request) {
	h.transitionInspection(w, r, []string{"created"}, "scanning", "inspection.start")
}

// POST /v1/inspections/:id/submit —— preliminary → pending_review。
func (h *Handler) SubmitForReview(w http.ResponseWriter, r *http.Request) {
	h.transitionInspection(w, r, []string{"preliminary"}, "pending_review", "inspection.submit")
}

// POST /v1/inspections/:id/close —— 任意非 closed → closed（提前结束允许）。
func (h *Handler) CloseInspection(w http.ResponseWriter, r *http.Request) {
	h.transitionInspection(w, r, []string{"created", "scanning", "preliminary", "pending_review"}, "closed", "inspection.close")
}

func (h *Handler) transitionInspection(w http.ResponseWriter, r *http.Request, from []string, to, action string) {
	id, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	// 权限：必须是 inspector 自己 OR admin
	ins, err := h.inspections.FindByID(r.Context(), id)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if ins.InspectorID != callerUserID(r) && callerRole(r) != rbac.RoleAdmin {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	before := *ins
	if err := h.inspections.Transition(r.Context(), id, from, to); err != nil {
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
	after, _ := h.inspections.FindByID(r.Context(), id)
	h.recordAudit(r, action, "inspection:"+strconv.FormatInt(id, 10), &before, after)
	httpx.OK(w, toInspectionDTO(after))
}

type updatePreliminaryReq struct {
	Verdict string   `json:"verdict"`
	Reasons []string `json:"reasons"`
}

// PATCH /v1/inspections/:id/result —— 写预审结果，并把状态机推到 preliminary。
func (h *Handler) UpdatePreliminary(w http.ResponseWriter, r *http.Request) {
	id, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var req updatePreliminaryReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	switch req.Verdict {
	case "pass", "warning", "fail", "pending":
	default:
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	}
	ins, err := h.inspections.FindByID(r.Context(), id)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if ins.InspectorID != callerUserID(r) && callerRole(r) != rbac.RoleAdmin {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	before := *ins
	if err := h.inspections.UpdatePreliminary(r.Context(), id, req.Verdict, req.Reasons); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	// 状态机推进 scanning → preliminary
	if err := h.inspections.Transition(r.Context(), id, []string{"scanning", "preliminary"}, "preliminary"); err != nil {
		// 若已是 preliminary，重复写入可接受；其它状态机错就报
		if !errors.Is(err, repo.ErrStateConflict) {
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
	}
	after, _ := h.inspections.FindByID(r.Context(), id)
	h.recordAudit(r, "inspection.update_result", "inspection:"+strconv.FormatInt(id, 10), &before, after)
	httpx.OK(w, toInspectionDTO(after))
}

// GET /v1/inspections/:id/assets
func (h *Handler) ListAssets(w http.ResponseWriter, r *http.Request) {
	id, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	ins, err := h.inspections.FindByID(r.Context(), id)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if ins.InspectorID != callerUserID(r) && !h.enforcer.Allow(callerRole(r), "inspection", "read_any") {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	items, err := h.assets.ListByInspection(r.Context(), id)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	type assetDTO struct {
		ID        string `json:"id"`
		Kind      string `json:"kind"`
		ObjectKey string `json:"object_key"`
		SHA256    string `json:"sha256"`
		SizeBytes int64  `json:"size_bytes"`
		MIME      string `json:"mime"`
		CreatedAt string `json:"created_at"`
	}
	out := make([]assetDTO, 0, len(items))
	for _, a := range items {
		out = append(out, assetDTO{
			ID:        strconv.FormatInt(a.ID, 10),
			Kind:      a.Kind,
			ObjectKey: a.ObjectKey,
			SHA256:    a.SHA256,
			SizeBytes: a.SizeBytes,
			MIME:      a.MIME,
			CreatedAt: a.CreatedAt.UTC().Format(time.RFC3339Nano),
		})
	}
	httpx.OK(w, map[string]any{"items": out})
}

// ==========================================================================
// 复核
// ==========================================================================

type reviewDTO struct {
	ID           string  `json:"id"`
	InspectionID string  `json:"inspection_id"`
	ReviewerID   *string `json:"reviewer_id,omitempty"`
	Decision     *string `json:"decision,omitempty"`
	Reason       *string `json:"reason,omitempty"`
	AssignedAt   string  `json:"assigned_at"`
	DecidedAt    *string `json:"decided_at,omitempty"`
	ExpireAt     *string `json:"expire_at,omitempty"`
}

func toReviewDTO(rv *repo.Review) reviewDTO {
	dto := reviewDTO{
		ID:           strconv.FormatInt(rv.ID, 10),
		InspectionID: strconv.FormatInt(rv.InspectionID, 10),
		Decision:     rv.Decision,
		Reason:       rv.Reason,
		AssignedAt:   rv.AssignedAt.UTC().Format(time.RFC3339Nano),
	}
	if rv.ReviewerID != nil {
		s := strconv.FormatInt(*rv.ReviewerID, 10)
		dto.ReviewerID = &s
	}
	if rv.DecidedAt != nil {
		s := rv.DecidedAt.UTC().Format(time.RFC3339Nano)
		dto.DecidedAt = &s
	}
	if rv.ExpireAt != nil {
		s := rv.ExpireAt.UTC().Format(time.RFC3339Nano)
		dto.ExpireAt = &s
	}
	return dto
}

func (h *Handler) ListReviews(w http.ResponseWriter, r *http.Request) {
	if !h.enforcer.Allow(callerRole(r), "review", "read") {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	q := r.URL.Query()
	bucket := repo.ReviewBucket(q.Get("bucket"))
	if bucket == "" {
		bucket = repo.BucketPending
	}
	limit, _ := strconv.Atoi(q.Get("limit"))
	cursor, _ := strconv.ParseInt(q.Get("cursor"), 10, 64)
	items, next, err := h.reviews.ListByReviewer(r.Context(), callerUserID(r), bucket, limit, cursor)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]reviewDTO, 0, len(items))
	for i := range items {
		out = append(out, toReviewDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{
		"items":       out,
		"next_cursor": strconv.FormatInt(next, 10),
		"has_more":    next != 0,
	})
}

func (h *Handler) GetReview(w http.ResponseWriter, r *http.Request) {
	id, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	rv, err := h.reviews.FindByID(r.Context(), id)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	// 权限：是被分派的 reviewer，或 supervisor/admin
	uid := callerUserID(r)
	if rv.ReviewerID == nil || *rv.ReviewerID != uid {
		if !h.enforcer.Allow(callerRole(r), "inspection", "read_any") {
			httpx.WriteError(w, httpx.ErrPermDenied)
			return
		}
	}
	httpx.OK(w, toReviewDTO(rv))
}

type decideReviewReq struct {
	Decision string `json:"decision"`
	Reason   string `json:"reason"`
}

func (h *Handler) DecideReview(w http.ResponseWriter, r *http.Request) {
	if !h.enforcer.Allow(callerRole(r), "review", "decide") {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	id, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var req decideReviewReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	switch req.Decision {
	case "correct", "incorrect", "skipped":
	default:
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	}
	before, _ := h.reviews.FindByID(r.Context(), id)
	if err := h.reviews.Decide(r.Context(), id, callerUserID(r), req.Decision, req.Reason); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "复核已完成或不归你领"))
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	after, _ := h.reviews.FindByID(r.Context(), id)
	h.recordAudit(r, "review.decide", "review:"+strconv.FormatInt(id, 10), before, after)
	httpx.OK(w, toReviewDTO(after))
}

// ---------- audit ----------

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
