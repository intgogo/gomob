package api

import (
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
	if _, err := h.messages.AppendIdempotent(r.Context(), msg, req.ClientMsgID); err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	h.recordAudit(r, "message.send", "conversation:"+strconv.FormatInt(id, 10), nil, map[string]any{
		"message_id":    msg.ID,
		"server_seq":    msg.ServerSeq,
		"client_msg_id": req.ClientMsgID,
	})
	httpx.OK(w, toMessageDTO(msg))
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
	case "text", "image", "video_clip":
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
	case "video_call":
		var p struct {
			DurationSec int `json:"duration_sec"`
		}
		if err := json.Unmarshal(payload, &p); err == nil && p.DurationSec > 0 {
			return "[视频通话 " + formatDuration(p.DurationSec) + "]"
		}
		return "[视频通话]"
	case "video_clip":
		return "[视频消息]"
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
