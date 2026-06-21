package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/rs/xid"

	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/repo"
)

const liveKitTokenTTL = 10 * time.Minute

type liveKitConfig struct {
	// URL 是发给 client 的 ws URL（用于 SDK Room.connect）。
	URL string
	// InternalURL 是 server 自己调 RoomService Twirp API 用的。
	// emulator 场景下 client 走 ws://10.0.2.2:7880 / 127.0.0.1，
	// server 在 host 上需要用 127.0.0.1 — 两边可能不同。
	// 未配置时 fallback 到 URL。
	InternalURL string
	APIKey      string
	APISecret   string
}

func currentLiveKitConfig() liveKitConfig {
	return liveKitConfig{
		URL:         os.Getenv("GOMOB_LIVEKIT_URL"),
		InternalURL: os.Getenv("GOMOB_LIVEKIT_INTERNAL_URL"),
		APIKey:      os.Getenv("GOMOB_LIVEKIT_API_KEY"),
		APISecret:   os.Getenv("GOMOB_LIVEKIT_API_SECRET"),
	}
}

func (c liveKitConfig) apiURL() string {
	if c.InternalURL != "" {
		return c.InternalURL
	}
	return c.URL
}

func (c liveKitConfig) configured() bool {
	return c.URL != "" && c.APIKey != "" && c.APISecret != ""
}

type flexibleJSONID string

func (v flexibleJSONID) String() string {
	return string(v)
}

func (v *flexibleJSONID) UnmarshalJSON(raw []byte) error {
	s := strings.TrimSpace(string(raw))
	if s == "" || s == "null" {
		*v = ""
		return nil
	}
	if strings.HasPrefix(s, `"`) {
		var out string
		if err := json.Unmarshal(raw, &out); err != nil {
			return err
		}
		*v = flexibleJSONID(strings.TrimSpace(out))
		return nil
	}
	if _, err := strconv.ParseInt(s, 10, 64); err != nil {
		return err
	}
	*v = flexibleJSONID(s)
	return nil
}

type flexibleJSONIDs []string

func (v *flexibleJSONIDs) UnmarshalJSON(raw []byte) error {
	s := strings.TrimSpace(string(raw))
	if s == "" || s == "null" {
		*v = nil
		return nil
	}
	var raws []json.RawMessage
	if err := json.Unmarshal(raw, &raws); err != nil {
		return err
	}
	out := make([]string, 0, len(raws))
	for _, item := range raws {
		var id flexibleJSONID
		if err := json.Unmarshal(item, &id); err != nil {
			return err
		}
		if id.String() != "" {
			out = append(out, id.String())
		}
	}
	*v = out
	return nil
}

type createMediaRoomReq struct {
	Kind               string          `json:"kind"`
	SubjectKind        string          `json:"subject_kind,omitempty"`
	SubjectID          flexibleJSONID  `json:"subject_id,omitempty"`
	ConversationID     flexibleJSONID  `json:"conversation_id,omitempty"`
	Title              string          `json:"title,omitempty"`
	ParticipantUserIDs flexibleJSONIDs `json:"participant_user_ids,omitempty"`
}

type mediaRoomDTO struct {
	ID                string `json:"id"`
	Provider          string `json:"provider"`
	ProviderRoom      string `json:"provider_room"`
	Kind              string `json:"kind"`
	SubjectKind       string `json:"subject_kind,omitempty"`
	SubjectID         string `json:"subject_id,omitempty"`
	Status            string `json:"status"`
	LiveKitURL        string `json:"livekit_url,omitempty"`
	LiveKitConfigured bool   `json:"livekit_configured"`
	Message           string `json:"message,omitempty"`
	CallAccepted      bool   `json:"call_accepted,omitempty"`
	CreatedAt         string `json:"created_at"`
}

func (h *Handler) GetMediaRoom(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	roomID, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	room, err := h.media.FindRoom(r.Context(), roomID)
	if err != nil {
		if err == repo.ErrNotFound {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if !h.canAccessMediaRoom(r.Context(), room, uid, "viewer") {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	dto := toMediaRoomDTO(room, currentLiveKitConfig(), "")
	if room.Kind == "call" {
		accepted, err := h.media.HasJoinedCounterpart(r.Context(), room.ID, uid)
		if err != nil {
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
		dto.CallAccepted = accepted
	}
	httpx.OK(w, dto)
}

func (h *Handler) CreateMediaRoom(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	var req createMediaRoomReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	room, msg, err := h.createMediaRoom(r.Context(), uid, req)
	if err != nil {
		httpx.WriteError(w, err)
		return
	}
	httpx.OK(w, toMediaRoomDTO(room, currentLiveKitConfig(), msg))
}

type mediaRoomTokenReq struct {
	Role string `json:"role"`
}

type mediaRoomTokenDTO struct {
	RoomID       string `json:"room_id"`
	ProviderRoom string `json:"provider_room"`
	URL          string `json:"url"`
	Token        string `json:"token"`
	Identity     string `json:"identity"`
	Role         string `json:"role"`
	TTLSeconds   int64  `json:"ttl_sec"`
}

func (h *Handler) CreateMediaRoomToken(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	roomID, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var req mediaRoomTokenReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	role := normalizeMediaRole(req.Role)
	if role == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	room, err := h.media.FindRoom(r.Context(), roomID)
	if err != nil {
		if err == repo.ErrNotFound {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if room.Status == "failed" || room.Status == "ended" {
		httpx.WriteError(w, httpx.NewError(40502, http.StatusConflict, "媒体房间不可加入："+room.Status))
		return
	}
	if !h.canAccessMediaRoom(r.Context(), room, uid, role) {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	cfg := currentLiveKitConfig()
	if !cfg.configured() {
		httpx.WriteError(w, httpx.NewError(40501, http.StatusServiceUnavailable, "LiveKit 未配置：请设置 GOMOB_LIVEKIT_URL/API_KEY/API_SECRET"))
		return
	}

	identity := "user:" + strconv.FormatInt(uid, 10)
	token, err := issueLiveKitToken(cfg, liveKitToken{
		Identity: identity,
		Room:     room.ProviderRoom,
		Metadata: mustJSON(map[string]any{
			"user_id": strconv.FormatInt(uid, 10),
			"role":    role,
			"room_id": strconv.FormatInt(room.ID, 10),
		}),
		TTL:            liveKitTokenTTL,
		CanPublish:     role == "publisher" || role == "moderator",
		CanSubscribe:   true,
		CanPublishData: true,
		RoomJoin:       true,
		RoomCreate:     false,
		RoomAdmin:      false,
	})
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	now := time.Now()
	if err := h.media.UpsertParticipant(r.Context(), repo.MediaParticipant{
		RoomID:   room.ID,
		UserID:   uid,
		Role:     role,
		JoinedAt: &now,
	}); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	httpx.OK(w, mediaRoomTokenDTO{
		RoomID:       strconv.FormatInt(room.ID, 10),
		ProviderRoom: room.ProviderRoom,
		URL:          cfg.URL,
		Token:        token,
		Identity:     identity,
		Role:         role,
		TTLSeconds:   int64(liveKitTokenTTL.Seconds()),
	})
}

func (h *Handler) EndMediaRoom(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	roomID, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	room, err := h.media.FindRoom(r.Context(), roomID)
	if err != nil {
		if err == repo.ErrNotFound {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if room.CreatedBy != uid && callerRole(r) != "admin" {
		if room.Kind != "call" || !h.canAccessMediaRoom(r.Context(), room, uid, "viewer") {
			httpx.WriteError(w, httpx.ErrPermDenied)
			return
		}
	}
	if room.CreatedBy != uid && callerRole(r) != "admin" && room.Kind != "call" {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	cfg := currentLiveKitConfig()
	msg := ""
	if cfg.configured() {
		if err := callLiveKitRoomService(r.Context(), cfg, "DeleteRoom", map[string]any{"room": room.ProviderRoom}); err != nil {
			msg = err.Error()
		}
	}
	metadata := mergeMetadata(room.Metadata, map[string]any{"end_error": msg})
	if err := h.media.SetRoomStatus(r.Context(), room.ID, "ended", metadata); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	endedAt := time.Now()
	if room.EndedAt != nil {
		endedAt = *room.EndedAt
	}
	room.Status = "ended"
	room.EndedAt = &endedAt
	room.Metadata = metadata
	if room.Kind == "call" {
		if err := h.finalizeCallRoom(r.Context(), room, uid); err != nil {
			if h.log != nil {
				h.log.Error("通话结束记录写入失败", "err", err, "room_id", room.ID)
			}
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
	}
	httpx.OK(w, toMediaRoomDTO(room, cfg, msg))
}

type createLiveSessionReq struct {
	Title          string `json:"title"`
	InspectionID   string `json:"inspection_id,omitempty"`
	ConversationID string `json:"conversation_id,omitempty"`
}

type liveSessionDTO struct {
	ID          string `json:"id"`
	MediaRoomID string `json:"media_room_id"`
	PublisherID string `json:"publisher_id"`
	Title       string `json:"title"`
	Status      string `json:"status"`
	StartedAt   string `json:"started_at,omitempty"`
	UpdatedAt   string `json:"updated_at"`
}

func (h *Handler) CreateLiveSession(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	var req createLiveSessionReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	title := strings.TrimSpace(req.Title)
	if title == "" {
		title = "第一视角直播"
	}
	room, msg, err := h.createMediaRoom(r.Context(), uid, createMediaRoomReq{
		Kind:           "first_person_live",
		ConversationID: flexibleJSONID(req.ConversationID),
		Title:          title,
	})
	if err != nil {
		httpx.WriteError(w, err)
		return
	}
	var inspectionID *int64
	if req.InspectionID != "" {
		id, parseErr := strconv.ParseInt(req.InspectionID, 10, 64)
		if parseErr != nil || id <= 0 {
			httpx.WriteError(w, httpx.ErrBadParam)
			return
		}
		inspectionID = &id
	}
	status := "live"
	if room.Status != "active" {
		status = "failed"
	}
	session := &repo.LiveSession{
		MediaRoomID:  room.ID,
		InspectionID: inspectionID,
		PublisherID:  uid,
		Title:        title,
		Status:       status,
		Metadata: mergeMetadata(nil, map[string]any{
			"room_status": room.Status,
			"message":     msg,
		}),
	}
	if err := h.media.CreateLiveSession(r.Context(), session); err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	httpx.OK(w, toLiveSessionDTO(session))
}

func (h *Handler) ListLiveSessions(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	status := r.URL.Query().Get("status")
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	items, err := h.media.ListLiveSessions(r.Context(), status, limit)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]liveSessionDTO, 0, len(items))
	for i := range items {
		out = append(out, toLiveSessionDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{"items": out})
}

// LiveKitWebhook 接收 LiveKit server 的 webhook 回调（room_started / participant_joined /
// track_published 等）。LiveKit 的验签方式：请求体由 Authorization 头携带一个 HS256 JWT，
// JWT 的 sha256 claim = base64(SHA256(body))，签名密钥 = APISecret。这里完整复用 issueLiveKitToken
// 同款 jwt + HS256 设施做真实验签；事件入库落地见下方 TODO（依赖 repo 侧新方法，本轮不做结构改动）。
//
// LiveKit 未配置（无 API_KEY/API_SECRET）时无法验签：返回 501 Not Implemented，绝不返回 200
// 假装已处理 —— 否则会把未经鉴别的外部 POST 当成可信事件处理（伪造房间/参与者）。
func (h *Handler) LiveKitWebhook(w http.ResponseWriter, r *http.Request) {
	cfg := currentLiveKitConfig()
	if !cfg.configured() {
		httpx.WriteError(w, httpx.NewError(40501, http.StatusNotImplemented,
			"LiveKit 未配置：无法验证 webhook 签名（需 GOMOB_LIVEKIT_API_KEY/API_SECRET）"))
		return
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20)) // webhook body 上限 1MB
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}

	if err := verifyLiveKitWebhook(cfg, r.Header.Get("Authorization"), body); err != nil {
		h.log.Warn("livekit webhook 验签失败", "err", err)
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}

	var ev liveKitWebhookEvent
	if err := json.Unmarshal(body, &ev); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}

	// 验签通过 → 事件可信。按 event 类型驱动对应 live_session 状态机：
	// room_started → 会话置 live；room_finished → 会话收尾 ended（并同步 media_room 状态）。
	// 二者都按 provider room 名反查会话；未知房间/无关事件仅记日志。
	// 任何分支都返回 2xx ACK —— webhook 语义下非 2xx 会触发 LiveKit 重试，这里事件已观测/已处理。
	//
	// TODO(deferred-structural M-media): 事件流明细表（live_session_events，记录每条 webhook 的全量负载/时序）
	// 仍待引入；当前只把状态推进落到 live_sessions，逐事件审计流是后续结构改动。
	h.log.Info("livekit webhook 已验签",
		"event", ev.Event, "room", ev.Room.Name,
		"participant", ev.Participant.Identity, "id", ev.ID)

	switch ev.Event {
	case "room_started", "room_finished":
		session, err := h.media.FindLiveSessionByProviderRoom(r.Context(), ev.Room.Name)
		if err != nil {
			if err == repo.ErrNotFound {
				h.log.Info("livekit webhook 房间无对应 live_session，仅 ACK",
					"event", ev.Event, "room", ev.Room.Name)
				break
			}
			h.log.Error("livekit webhook 反查 live_session 失败", "err", err, "room", ev.Room.Name)
			break
		}
		if ev.Event == "room_started" {
			if err := h.media.MarkLiveSessionLive(r.Context(), session.ID); err != nil {
				h.log.Error("livekit webhook 置 live_session=live 失败", "err", err, "session", session.ID)
			}
		} else {
			if err := h.media.EndLiveSession(r.Context(), session.ID); err != nil {
				h.log.Error("livekit webhook 置 live_session=ended 失败", "err", err, "session", session.ID)
			}
			// 会话所属 media_room 一并收尾，保持两张表状态一致。
			if err := h.media.SetRoomStatus(r.Context(), session.MediaRoomID, "ended", nil); err != nil && err != repo.ErrNotFound {
				h.log.Error("livekit webhook 置 media_room=ended 失败", "err", err, "room_id", session.MediaRoomID)
			}
		}
	default:
		// participant_joined / track_published 等：暂不驱动状态机，已记日志即满足观测。
	}

	httpx.OK(w, map[string]any{"received": true, "event": ev.Event})
}

// liveKitWebhookEvent 是 LiveKit webhook 负载的子集（只取落地需要的字段）。
type liveKitWebhookEvent struct {
	Event string `json:"event"`
	Room  struct {
		Name string `json:"name"`
	} `json:"room"`
	Participant struct {
		Identity string `json:"identity"`
	} `json:"participant"`
	ID        string `json:"id"`
	CreatedAt int64  `json:"createdAt"`
}

// verifyLiveKitWebhook 校验 LiveKit webhook 的 Authorization JWT：
//   - 签名密钥 = APISecret（HS256，与 issueLiveKitToken 一致）
//   - JWT 的 sha256 claim 必须 = base64-std(SHA256(body))，绑定请求体防重放/篡改
func verifyLiveKitWebhook(cfg liveKitConfig, authHeader string, body []byte) error {
	tokenStr := strings.TrimSpace(strings.TrimPrefix(authHeader, "Bearer "))
	if tokenStr == "" {
		return errors.New("缺少 Authorization 头")
	}
	claims := jwt.MapClaims{}
	_, err := jwt.ParseWithClaims(tokenStr, claims, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("非预期签名算法：%v", t.Header["alg"])
		}
		return []byte(cfg.APISecret), nil
	}, jwt.WithValidMethods([]string{"HS256"}))
	if err != nil {
		return fmt.Errorf("JWT 校验失败：%w", err)
	}
	wantHash, ok := claims["sha256"].(string)
	if !ok || wantHash == "" {
		return errors.New("JWT 缺少 sha256 claim")
	}
	sum := sha256.Sum256(body)
	gotHash := base64.StdEncoding.EncodeToString(sum[:])
	if subtle.ConstantTimeCompare([]byte(gotHash), []byte(wantHash)) != 1 {
		return errors.New("body sha256 与 JWT claim 不匹配")
	}
	return nil
}

func (h *Handler) createMediaRoom(ctx context.Context, uid int64, req createMediaRoomReq) (*repo.MediaRoom, string, error) {
	kind := normalizeMediaKind(req.Kind)
	if kind == "" {
		return nil, "", httpx.ErrBadParam
	}
	var subjectKind *string
	var subjectID *int64
	participants := []repo.MediaParticipant{{UserID: uid, Role: "publisher"}}
	if req.ConversationID.String() != "" {
		convID, err := strconv.ParseInt(req.ConversationID.String(), 10, 64)
		if err != nil || convID <= 0 {
			return nil, "", httpx.ErrBadParam
		}
		ok, err := h.conversations.IsMember(ctx, convID, uid)
		if err != nil {
			return nil, "", httpx.ErrInternal
		}
		if !ok {
			return nil, "", httpx.ErrPermDenied
		}
		sk := "conversation"
		subjectKind = &sk
		subjectID = &convID
		others, err := h.conversations.CounterpartIDs(ctx, convID, uid)
		if err != nil {
			return nil, "", httpx.ErrInternal
		}
		for _, id := range others {
			participants = append(participants, repo.MediaParticipant{UserID: id, Role: "viewer"})
		}
	}
	for _, raw := range req.ParticipantUserIDs {
		id, err := strconv.ParseInt(raw, 10, 64)
		if err == nil && id > 0 && id != uid {
			participants = append(participants, repo.MediaParticipant{UserID: id, Role: "viewer"})
		}
	}
	providerRoom := "gomob_" + kind + "_" + xid.New().String()
	cfg := currentLiveKitConfig()
	status := "active"
	msg := ""
	if !cfg.configured() {
		status = "failed"
		msg = "LiveKit 未配置：请设置 GOMOB_LIVEKIT_URL/API_KEY/API_SECRET"
	} else if err := callLiveKitRoomService(ctx, cfg, "CreateRoom", map[string]any{
		"name":     providerRoom,
		"metadata": mustJSON(map[string]any{"title": req.Title, "kind": kind}),
	}); err != nil {
		status = "failed"
		msg = "LiveKit 创建房间失败：" + err.Error()
	}
	room := &repo.MediaRoom{
		ProviderRoom: providerRoom,
		Kind:         kind,
		SubjectKind:  subjectKind,
		SubjectID:    subjectID,
		CreatedBy:    uid,
		Status:       status,
		Metadata: mergeMetadata(nil, map[string]any{
			"title":              req.Title,
			"livekit_configured": cfg.configured(),
			"message":            msg,
		}),
	}
	if err := h.media.CreateRoom(ctx, room, participants); err != nil {
		return nil, "", httpx.ErrInternal
	}
	return room, msg, nil
}

func (h *Handler) canAccessMediaRoom(ctx context.Context, room *repo.MediaRoom, uid int64, role string) bool {
	if room.CreatedBy == uid {
		return true
	}
	if room.Kind == "first_person_live" && role == "viewer" {
		return true
	}
	if room.Kind == "call" {
		ok, err := h.media.IsParticipant(ctx, room.ID, uid)
		if err == nil && ok {
			return true
		}
		if room.SubjectKind != nil && *room.SubjectKind == "conversation" && room.SubjectID != nil {
			ok, err = h.conversations.IsMember(ctx, *room.SubjectID, uid)
			return err == nil && ok
		}
		return false
	}
	if role == "publisher" {
		return false
	}
	ok, err := h.media.IsParticipant(ctx, room.ID, uid)
	if err == nil && ok {
		return true
	}
	if room.SubjectKind != nil && *room.SubjectKind == "conversation" && room.SubjectID != nil {
		ok, err = h.conversations.IsMember(ctx, *room.SubjectID, uid)
		return err == nil && ok
	}
	return false
}

func (h *Handler) finalizeCallRoom(ctx context.Context, room *repo.MediaRoom, endedBy int64) error {
	if room == nil || room.Kind != "call" {
		return nil
	}
	if room.SubjectKind == nil || *room.SubjectKind != "conversation" || room.SubjectID == nil {
		return nil
	}
	conversationID := *room.SubjectID
	callees, err := h.conversations.CounterpartIDs(ctx, conversationID, room.CreatedBy)
	if err != nil {
		return err
	}
	if len(callees) == 0 {
		return nil
	}
	calleeID := callees[0]
	startedAt := room.CreatedAt
	if room.StartedAt != nil {
		startedAt = *room.StartedAt
	}
	endedAt := time.Now()
	if room.EndedAt != nil {
		endedAt = *room.EndedAt
	}
	durationSec := int(endedAt.Sub(startedAt).Seconds())
	if durationSec < 0 {
		durationSec = 0
	}
	status := "missed"
	accepted, err := h.media.HasJoinedCounterpart(ctx, room.ID, room.CreatedBy)
	if err != nil {
		return err
	}
	if accepted {
		status = "completed"
	}
	reason := ""
	if !accepted {
		reason = firstNonBlank(mediaMetadataString(room.Metadata, "end_error"), defaultCallFailureReason(status))
	}
	roomID := room.ID
	if _, err = h.media.InsertCallLogOnce(ctx, &repo.CallLog{
		RoomID:         &roomID,
		ConversationID: &conversationID,
		CallerID:       room.CreatedBy,
		CalleeID:       calleeID,
		StartedAt:      startedAt,
		EndedAt:        &endedAt,
		DurationSec:    durationSec,
		Status:         status,
	}); err != nil {
		return err
	}
	payload := map[string]any{
		"room_id":       strconv.FormatInt(room.ID, 10),
		"provider_room": room.ProviderRoom,
		"caller_id":     strconv.FormatInt(room.CreatedBy, 10),
		"callee_id":     strconv.FormatInt(calleeID, 10),
		"ended_by":      strconv.FormatInt(endedBy, 10),
		"status":        status,
		"duration_sec":  durationSec,
		"started_at":    startedAt.UTC().Format(time.RFC3339Nano),
		"ended_at":      endedAt.UTC().Format(time.RFC3339Nano),
	}
	if reason != "" {
		payload["reason"] = reason
	}
	msg, err := h.messages.UpdateCallInvitePayload(ctx, conversationID, room.ID, mustRawJSON(payload))
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			if h.log != nil {
				h.log.Warn("通话邀请消息不存在，已只写入 call_logs",
					"conversation_id", conversationID,
					"room_id", room.ID,
				)
			}
			return nil
		}
		return err
	}
	h.notifyTranscriptUpdate(ctx, msg)
	return nil
}

func mediaMetadataString(raw json.RawMessage, key string) string {
	var obj map[string]any
	if err := json.Unmarshal(raw, &obj); err != nil {
		return ""
	}
	value, ok := obj[key]
	if !ok {
		return ""
	}
	switch v := value.(type) {
	case string:
		return strings.TrimSpace(v)
	default:
		return strings.TrimSpace(fmt.Sprint(v))
	}
}

func normalizeMediaKind(kind string) string {
	switch strings.TrimSpace(kind) {
	case "call", "video_call":
		return "call"
	case "first_person_live", "live":
		return "first_person_live"
	default:
		return ""
	}
}

func normalizeMediaRole(role string) string {
	switch strings.TrimSpace(role) {
	case "", "viewer":
		return "viewer"
	case "publisher":
		return "publisher"
	case "moderator":
		return "moderator"
	default:
		return ""
	}
}

func toMediaRoomDTO(room *repo.MediaRoom, cfg liveKitConfig, msg string) mediaRoomDTO {
	dto := mediaRoomDTO{
		ID:                strconv.FormatInt(room.ID, 10),
		Provider:          room.Provider,
		ProviderRoom:      room.ProviderRoom,
		Kind:              room.Kind,
		Status:            room.Status,
		LiveKitURL:        cfg.URL,
		LiveKitConfigured: cfg.configured(),
		Message:           msg,
		CreatedAt:         formatTime(room.CreatedAt),
	}
	if room.SubjectKind != nil {
		dto.SubjectKind = *room.SubjectKind
	}
	if room.SubjectID != nil {
		dto.SubjectID = strconv.FormatInt(*room.SubjectID, 10)
	}
	return dto
}

func toLiveSessionDTO(s *repo.LiveSession) liveSessionDTO {
	dto := liveSessionDTO{
		ID:          strconv.FormatInt(s.ID, 10),
		MediaRoomID: strconv.FormatInt(s.MediaRoomID, 10),
		PublisherID: strconv.FormatInt(s.PublisherID, 10),
		Title:       s.Title,
		Status:      s.Status,
		UpdatedAt:   formatTime(s.CreatedAt),
	}
	if s.StartedAt != nil {
		dto.StartedAt = formatTime(*s.StartedAt)
	}
	return dto
}

type liveKitToken struct {
	Identity       string
	Room           string
	Metadata       string
	TTL            time.Duration
	RoomJoin       bool
	RoomCreate     bool
	RoomAdmin      bool
	CanPublish     bool
	CanSubscribe   bool
	CanPublishData bool
}

func issueLiveKitToken(cfg liveKitConfig, in liveKitToken) (string, error) {
	now := time.Now()
	claims := jwt.MapClaims{
		"iss": cfg.APIKey,
		"sub": in.Identity,
		"nbf": now.Unix(),
		"exp": now.Add(in.TTL).Unix(),
		"video": map[string]any{
			"room":           in.Room,
			"roomJoin":       in.RoomJoin,
			"roomCreate":     in.RoomCreate,
			"roomAdmin":      in.RoomAdmin,
			"canPublish":     in.CanPublish,
			"canSubscribe":   in.CanSubscribe,
			"canPublishData": in.CanPublishData,
		},
	}
	if in.Metadata != "" {
		claims["metadata"] = in.Metadata
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, claims).SignedString([]byte(cfg.APISecret))
}

func callLiveKitRoomService(ctx context.Context, cfg liveKitConfig, method string, body map[string]any) error {
	token, err := issueLiveKitToken(cfg, liveKitToken{
		Identity:   "gomob-api",
		TTL:        liveKitTokenTTL,
		RoomJoin:   false,
		RoomCreate: true,
		RoomAdmin:  true,
	})
	if err != nil {
		return err
	}
	raw, _ := json.Marshal(body)
	req, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		liveKitHTTPBase(cfg.apiURL())+"/twirp/livekit.RoomService/"+method,
		bytes.NewReader(raw),
	)
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := (&http.Client{Timeout: 5 * time.Second}).Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return nil
	}
	if method == "CreateRoom" && resp.StatusCode == http.StatusConflict {
		return nil
	}
	return fmt.Errorf("livekit %s http=%d", method, resp.StatusCode)
}

func liveKitHTTPBase(raw string) string {
	base := strings.TrimRight(raw, "/")
	if rest, ok := strings.CutPrefix(base, "ws://"); ok {
		return "http://" + rest
	}
	if rest, ok := strings.CutPrefix(base, "wss://"); ok {
		return "https://" + rest
	}
	return base
}

func mustJSON(v any) string {
	raw, _ := json.Marshal(v)
	return string(raw)
}

func mergeMetadata(existing json.RawMessage, updates map[string]any) json.RawMessage {
	out := map[string]any{}
	if len(existing) > 0 {
		_ = json.Unmarshal(existing, &out)
	}
	for k, v := range updates {
		if s, ok := v.(string); ok && s == "" {
			continue
		}
		out[k] = v
	}
	raw, _ := json.Marshal(out)
	return raw
}
