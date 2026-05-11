package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/repo"
)

type conversationDTO struct {
	ID          string               `json:"id"`
	Kind        string               `json:"kind"`
	Title       string               `json:"title,omitempty"`
	Peer        *conversationPeerDTO `json:"peer,omitempty"`
	SubjectKind string               `json:"subject_kind,omitempty"`
	SubjectID   string               `json:"subject_id,omitempty"`
	LastMessage *lastMessageDTO      `json:"last_message,omitempty"`
	LastReadSeq int64                `json:"last_read_seq"`
	UnreadCount int64                `json:"unread_count"`
	CreatedAt   string               `json:"created_at"`
	UpdatedAt   string               `json:"updated_at"`
}

type conversationPeerDTO struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	EmployeeID string `json:"employee_id,omitempty"`
}

type lastMessageDTO struct {
	ID        string `json:"id"`
	SenderID  string `json:"sender_id,omitempty"`
	ServerSeq int64  `json:"server_seq"`
	Kind      string `json:"kind"`
	Preview   string `json:"preview"`
	CreatedAt string `json:"created_at"`
}

type messageDTO struct {
	ID             string          `json:"id"`
	ConversationID string          `json:"conversation_id"`
	ServerSeq      int64           `json:"server_seq"`
	SenderID       string          `json:"sender_id,omitempty"`
	Kind           string          `json:"kind"`
	Payload        json.RawMessage `json:"payload"`
	ClientMsgID    string          `json:"client_msg_id,omitempty"`
	CreatedAt      string          `json:"created_at"`
	EditedAt       string          `json:"edited_at,omitempty"`
}

func (h *Handler) ListConversations(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	cursor, _ := strconv.ParseInt(q.Get("cursor"), 10, 64)
	items, next, err := h.conversations.ListForUser(r.Context(), uid, limit, cursor)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]conversationDTO, 0, len(items))
	for i := range items {
		out = append(out, toConversationDTO(&items[i]))
	}
	httpx.OK(w, map[string]any{
		"items":       out,
		"next_cursor": strconv.FormatInt(next, 10),
		"has_more":    next != 0,
	})
}

func (h *Handler) ListConversationMessages(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	id, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	ok, err := h.conversations.IsMember(r.Context(), id, uid)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if !ok {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	q := r.URL.Query()
	since, _ := strconv.ParseInt(q.Get("since_seq"), 10, 64)
	limit, _ := strconv.Atoi(q.Get("limit"))
	items, err := h.messages.ListSince(r.Context(), id, since, limit)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]messageDTO, 0, len(items))
	nextSince := since
	for i := range items {
		out = append(out, toMessageDTO(&items[i]))
		if items[i].ServerSeq > nextSince {
			nextSince = items[i].ServerSeq
		}
	}
	httpx.OK(w, map[string]any{
		"items":          out,
		"next_since_seq": nextSince,
	})
}

type createConversationMessageReq struct {
	ClientMsgID string          `json:"client_msg_id"`
	Kind        string          `json:"kind"`
	Payload     json.RawMessage `json:"payload"`
}

func (h *Handler) CreateConversationMessage(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	if !h.enforcer.Allow(callerRole(r), "message", "send") {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	id, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var req createConversationMessageReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if !validClientMessageKind(req.Kind) || len(req.Payload) == 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	ok, err := h.conversations.IsMember(r.Context(), id, uid)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if !ok {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	senderID := uid
	msg := &repo.Message{
		ConversationID: id,
		SenderID:       &senderID,
		Kind:           req.Kind,
		Payload:        req.Payload,
	}
	inserted, err := h.messages.AppendIdempotent(r.Context(), msg, req.ClientMsgID)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if inserted {
		if _, _, err := h.transcripts.EnsureForVoiceMessage(r.Context(), msg); err != nil {
			if h.log != nil {
				h.log.Error("语音转写任务创建失败", "err", err, "message_id", msg.ID)
			}
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
	}
	h.notifyRealtimeMessage(r.Context(), uid, msg, inserted)
	h.recordAudit(r, "message.send", "conversation:"+strconv.FormatInt(id, 10), nil, map[string]any{
		"message_id":    msg.ID,
		"server_seq":    msg.ServerSeq,
		"client_msg_id": req.ClientMsgID,
	})
	httpx.OK(w, toMessageDTO(msg))
}

type createConversationCallInviteReq struct {
	ClientMsgID string `json:"client_msg_id"`
	Title       string `json:"title,omitempty"`
}

type conversationCallInviteDTO struct {
	Room    mediaRoomDTO `json:"room"`
	Message messageDTO   `json:"message"`
}

func (h *Handler) CreateConversationCallInvite(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	if !h.enforcer.Allow(callerRole(r), "message", "send") {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	id, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var req createConversationCallInviteReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	clientMsgID := strings.TrimSpace(req.ClientMsgID)
	if clientMsgID == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	ok, err := h.conversations.IsMember(r.Context(), id, uid)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if !ok {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	title := strings.TrimSpace(req.Title)
	if title == "" {
		title = "视频通话"
	}
	room, msg, err := h.createMediaRoom(r.Context(), uid, createMediaRoomReq{
		Kind:           "video_call",
		ConversationID: flexibleJSONID(strconv.FormatInt(id, 10)),
		Title:          title,
	})
	if err != nil {
		httpx.WriteError(w, err)
		return
	}
	if room.Status != "active" {
		if msg == "" {
			msg = "视频通话媒体房间未就绪"
		}
		httpx.WriteError(w, httpx.NewError(40501, http.StatusServiceUnavailable, msg))
		return
	}
	cfg := currentLiveKitConfig()
	payload := mustRawJSON(map[string]any{
		"call_id":            strconv.FormatInt(room.ID, 10),
		"room_id":            strconv.FormatInt(room.ID, 10),
		"provider":           room.Provider,
		"provider_room":      room.ProviderRoom,
		"status":             "ringing",
		"title":              title,
		"started_by":         strconv.FormatInt(uid, 10),
		"livekit_url":        cfg.URL,
		"livekit_configured": cfg.configured(),
	})
	senderID := uid
	message := &repo.Message{
		ConversationID: id,
		SenderID:       &senderID,
		Kind:           "call_invite",
		Payload:        payload,
	}
	inserted, err := h.messages.AppendIdempotent(r.Context(), message, clientMsgID)
	if err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	h.notifyRealtimeMessage(r.Context(), uid, message, inserted)
	h.recordAudit(r, "message.call_invite", "conversation:"+strconv.FormatInt(id, 10), nil, map[string]any{
		"message_id":    message.ID,
		"server_seq":    message.ServerSeq,
		"client_msg_id": clientMsgID,
		"room_id":       room.ID,
	})
	httpx.OK(w, conversationCallInviteDTO{
		Room:    toMediaRoomDTO(room, cfg, msg),
		Message: toMessageDTO(message),
	})
}

func (h *Handler) notifyRealtimeMessage(ctx context.Context, senderID int64, message *repo.Message, inserted bool) {
	if !inserted || h.realtime == nil || message == nil {
		return
	}
	delivered, err := h.realtime.NotifyMessage(ctx, senderID, message)
	if err != nil {
		if h.log != nil {
			h.log.Warn("REST 消息实时推送失败",
				"err", err,
				"conversation_id", message.ConversationID,
				"message_id", message.ID,
				"server_seq", message.ServerSeq,
			)
		}
		return
	}
	if h.log != nil {
		h.log.Debug("REST 消息已实时推送",
			"conversation_id", message.ConversationID,
			"message_id", message.ID,
			"server_seq", message.ServerSeq,
			"delivered_connections", delivered,
		)
	}
}

type markConversationReadReq struct {
	LastReadSeq int64 `json:"last_read_seq"`
}

func (h *Handler) MarkConversationRead(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	id, err := parsePathID(r, "id")
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	var req markConversationReadReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if req.LastReadSeq < 0 {
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	}
	unread, err := h.conversations.MarkRead(r.Context(), id, uid, req.LastReadSeq)
	if err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrPermDenied)
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	httpx.OK(w, map[string]any{
		"conversation_id": strconv.FormatInt(id, 10),
		"last_read_seq":   req.LastReadSeq,
		"unread_count":    unread,
	})
}

func toConversationDTO(s *repo.ConversationSummary) conversationDTO {
	dto := conversationDTO{
		ID:          strconv.FormatInt(s.Conversation.ID, 10),
		Kind:        s.Conversation.Kind,
		LastReadSeq: s.LastReadSeq,
		UnreadCount: s.UnreadCount,
		CreatedAt:   formatTime(s.Conversation.CreatedAt),
		UpdatedAt:   formatTime(s.Conversation.UpdatedAt),
	}
	if s.Conversation.Title != nil {
		dto.Title = *s.Conversation.Title
	}
	if s.Conversation.SubjectKind != nil {
		dto.SubjectKind = *s.Conversation.SubjectKind
	}
	if s.Conversation.SubjectID != nil {
		dto.SubjectID = strconv.FormatInt(*s.Conversation.SubjectID, 10)
	}
	if s.Peer != nil {
		dto.Peer = &conversationPeerDTO{
			ID:         strconv.FormatInt(s.Peer.ID, 10),
			Name:       s.Peer.RealName,
			EmployeeID: s.Peer.EmployeeID,
		}
		if dto.Title == "" {
			dto.Title = s.Peer.RealName
		}
	}
	if s.LastMessage != nil {
		dto.LastMessage = toLastMessageDTO(s.LastMessage)
	}
	return dto
}

func toLastMessageDTO(m *repo.Message) *lastMessageDTO {
	dto := &lastMessageDTO{
		ID:        strconv.FormatInt(m.ID, 10),
		ServerSeq: m.ServerSeq,
		Kind:      m.Kind,
		Preview:   messagePreview(m.Kind, m.Payload),
		CreatedAt: formatTime(m.CreatedAt),
	}
	if m.SenderID != nil {
		dto.SenderID = strconv.FormatInt(*m.SenderID, 10)
	}
	return dto
}

func toMessageDTO(m *repo.Message) messageDTO {
	dto := messageDTO{
		ID:             strconv.FormatInt(m.ID, 10),
		ConversationID: strconv.FormatInt(m.ConversationID, 10),
		ServerSeq:      m.ServerSeq,
		Kind:           m.Kind,
		Payload:        m.Payload,
		CreatedAt:      formatTime(m.CreatedAt),
	}
	if m.SenderID != nil {
		dto.SenderID = strconv.FormatInt(*m.SenderID, 10)
	}
	if m.ClientMsgID != nil {
		dto.ClientMsgID = *m.ClientMsgID
	}
	if m.EditedAt != nil {
		dto.EditedAt = formatTime(*m.EditedAt)
	}
	return dto
}

func validClientMessageKind(kind string) bool {
	switch kind {
	case "text", "image", "voice", "video_clip", "inspection_card":
		return true
	default:
		return false
	}
}

func messagePreview(kind string, payload json.RawMessage) string {
	switch kind {
	case "text":
		var p struct {
			Text string `json:"text"`
		}
		if err := json.Unmarshal(payload, &p); err == nil && p.Text != "" {
			return trimPreview(p.Text, 48)
		}
		var text string
		if err := json.Unmarshal(payload, &text); err == nil && text != "" {
			return trimPreview(text, 48)
		}
		return "[文字]"
	case "image":
		return "[图片]"
	case "voice":
		var p struct {
			MediaState       string `json:"media_state"`
			DurationSec      int    `json:"duration_sec"`
			TranscriptStatus string `json:"transcript_status"`
			TranscriptText   string `json:"transcript_normalized_text"`
			TranscriptRaw    string `json:"transcript_text"`
		}
		if err := json.Unmarshal(payload, &p); err == nil {
			if p.TranscriptStatus == "done" {
				if p.TranscriptText != "" {
					return "[语音转文字] " + trimPreview(p.TranscriptText, 42)
				}
				if p.TranscriptRaw != "" {
					return "[语音转文字] " + trimPreview(p.TranscriptRaw, 42)
				}
			}
			if p.TranscriptStatus == "processing" || p.TranscriptStatus == "pending" {
				return "[语音转写中]"
			}
			if p.TranscriptStatus == "failed" {
				return "[语音转写失败]"
			}
			if p.MediaState == "awaiting_asset_upload" {
				return "[语音待上传]"
			}
			if p.DurationSec > 0 {
				return "[语音 " + formatDuration(p.DurationSec) + "]"
			}
		}
		return "[语音消息]"
	case "video_call":
		var p struct {
			DurationSec int `json:"duration_sec"`
		}
		if err := json.Unmarshal(payload, &p); err == nil && p.DurationSec > 0 {
			return "[视频通话 " + formatDuration(p.DurationSec) + "]"
		}
		return "[视频通话]"
	case "video_clip":
		var p struct {
			MediaState string `json:"media_state"`
		}
		if err := json.Unmarshal(payload, &p); err == nil && p.MediaState == "awaiting_asset_upload" {
			return "[视频待上传]"
		}
		return "[视频消息]"
	case "inspection_card":
		var p struct {
			VIN string `json:"vin"`
		}
		if err := json.Unmarshal(payload, &p); err == nil && p.VIN != "" {
			return "[流水] " + trimPreview(p.VIN, 32)
		}
		return "[业务流水]"
	case "call_invite":
		var p struct {
			Title string `json:"title"`
		}
		if err := json.Unmarshal(payload, &p); err == nil && p.Title != "" {
			return "[视频通话] " + trimPreview(p.Title, 32)
		}
		return "[视频通话邀请]"
	case "system":
		return "[系统消息]"
	default:
		return "[" + kind + "]"
	}
}

func trimPreview(s string, maxRunes int) string {
	s = strings.TrimSpace(s)
	rs := []rune(s)
	if len(rs) <= maxRunes {
		return s
	}
	return string(rs[:maxRunes]) + "…"
}

func formatDuration(sec int) string {
	if sec < 0 {
		sec = 0
	}
	return strconv.Itoa(sec/60) + ":" + twoDigits(sec%60)
}

func twoDigits(n int) string {
	if n < 10 {
		return "0" + strconv.Itoa(n)
	}
	return strconv.Itoa(n)
}

func formatTime(t time.Time) string {
	if t.IsZero() {
		return ""
	}
	return t.UTC().Format(time.RFC3339Nano)
}

func mustRawJSON(v any) json.RawMessage {
	raw, _ := json.Marshal(v)
	return raw
}
