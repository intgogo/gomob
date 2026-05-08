// Router —— 把客户端发上来的 Envelope 派发给业务 handler。
//
// 解耦原因：Conn 只关心传输，Hub 只关心连接索引；业务（落库、推送、状态机）
// 集中在这里，方便扩展和单测。
package signaling

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log/slog"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

// Router 持有所有依赖；Handler 用它来 Dispatch。
type Router struct {
	hub      *Hub
	pool     *pgxpool.Pool
	convRepo *repo.ConversationRepo
	msgRepo  *repo.MessageRepo
	callRepo *repo.PendingCallRepo
	audit    audit.Recorder
	log      *slog.Logger
	// 配置
	pendingCallTTL time.Duration
}

func NewRouter(pool *pgxpool.Pool, hub *Hub, auditRec audit.Recorder, pendingCallTTL time.Duration) *Router {
	if pendingCallTTL <= 0 {
		pendingCallTTL = 60 * time.Second
	}
	return &Router{
		hub:            hub,
		pool:           pool,
		convRepo:       repo.NewConversationRepo(pool),
		msgRepo:        repo.NewMessageRepo(pool),
		callRepo:       repo.NewPendingCallRepo(pool),
		audit:          auditRec,
		log:            logger.New("signaling.router"),
		pendingCallTTL: pendingCallTTL,
	}
}

// Dispatch 是 Conn.readLoop 的 callback 入口。
func (r *Router) Dispatch(ctx context.Context, c *Conn, env Envelope) {
	switch env.Type {
	case "msg.send":
		r.handleMsgSend(ctx, c, env)
	case "msg.fetch":
		r.handleMsgFetch(ctx, c, env)
	case "call.invite":
		r.handleCallInvite(ctx, c, env)
	case "call.answer":
		r.handleCallAnswer(ctx, c, env)
	case "call.ice":
		r.handleCallIce(ctx, c, env)
	case "call.bye":
		r.handleCallBye(ctx, c, env)
	default:
		r.sendError(c, 10001, "未知消息类型: "+env.Type, env)
	}
}

// ============================================================================
// 单聊消息
// ============================================================================

type msgSendReq struct {
	ToUserID    int64           `json:"to_user_id"`
	Kind        string          `json:"kind"` // text / image / video_clip
	Content     json.RawMessage `json:"content"`
	ClientMsgID string          `json:"client_msg_id,omitempty"`
}

type msgDeliveredPayload struct {
	ClientMsgID    string `json:"client_msg_id,omitempty"`
	ConversationID int64  `json:"conversation_id"`
	ServerSeq      int64  `json:"server_seq"`
	MessageID      int64  `json:"message_id"`
	CreatedAt      string `json:"created_at"`
}

type msgRecvPayload struct {
	ConversationID int64           `json:"conversation_id"`
	ServerSeq      int64           `json:"server_seq"`
	SenderID       int64           `json:"sender_id"`
	Kind           string          `json:"kind"`
	Content        json.RawMessage `json:"content"`
	CreatedAt      string          `json:"created_at"`
}

func (r *Router) handleMsgSend(ctx context.Context, c *Conn, env Envelope) {
	var req msgSendReq
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		r.sendError(c, 10001, "msg.send payload 解析失败", env)
		return
	}
	if req.ToUserID <= 0 || req.ToUserID == c.UserID || req.Kind == "" || len(req.Content) == 0 {
		r.sendError(c, 10001, "msg.send 参数缺失或非法", env)
		return
	}
	// 拿/建 p2p 会话
	conv, err := r.convRepo.GetOrCreateP2P(ctx, c.UserID, req.ToUserID)
	if err != nil {
		r.log.Error("p2p 会话获取失败", "err", err, "from", c.UserID, "to", req.ToUserID)
		r.sendError(c, 50001, "服务端内部错误", env)
		return
	}
	// 落库（next_seq 行锁分配 server_seq）
	senderID := c.UserID
	m := &repo.Message{
		ConversationID: conv.ID,
		SenderID:       &senderID,
		Kind:           req.Kind,
		Payload:        req.Content,
	}
	inserted, err := r.msgRepo.AppendIdempotent(ctx, m, req.ClientMsgID)
	if err != nil {
		r.log.Error("消息落库失败", "err", err, "conv", conv.ID)
		r.sendError(c, 50001, "服务端内部错误", env)
		return
	}
	// 发送方回执
	delivered := msgDeliveredPayload{
		ClientMsgID:    req.ClientMsgID,
		ConversationID: conv.ID,
		ServerSeq:      m.ServerSeq,
		MessageID:      m.ID,
		CreatedAt:      m.CreatedAt.UTC().Format(time.RFC3339Nano),
	}
	c.Send(Envelope{Type: "msg.delivered", Payload: mustJSON(delivered)})

	// 幂等重发只回执发送方，不重复推给收件人。
	if inserted {
		recv := msgRecvPayload{
			ConversationID: conv.ID,
			ServerSeq:      m.ServerSeq,
			SenderID:       senderID,
			Kind:           m.Kind,
			Content:        m.Payload,
			CreatedAt:      m.CreatedAt.UTC().Format(time.RFC3339Nano),
		}
		r.hub.Push(req.ToUserID, Envelope{Type: "msg.recv", Payload: mustJSON(recv)})
	}

	// audit（用独立 ctx 避免 conn 关闭传递取消）
	if r.audit != nil {
		afterRaw, _ := audit.Encode(map[string]any{
			"server_seq": m.ServerSeq,
			"to_user_id": req.ToUserID,
			"kind":       m.Kind,
		})
		ac, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		go func() {
			defer cancel()
			_ = r.audit.Record(ac, audit.Entry{
				UserID:   c.UserID,
				Action:   "message.send",
				Target:   "conversation:" + strconv.FormatInt(conv.ID, 10),
				AfterRaw: afterRaw,
			})
		}()
	}
}

type msgFetchReq struct {
	ConversationID int64 `json:"conversation_id"`
	SinceSeq       int64 `json:"since_seq"`
	Limit          int   `json:"limit"`
}

type msgFetchResult struct {
	ConversationID int64            `json:"conversation_id"`
	Items          []msgRecvPayload `json:"items"`
	NextSinceSeq   int64            `json:"next_since_seq"`
}

func (r *Router) handleMsgFetch(ctx context.Context, c *Conn, env Envelope) {
	var req msgFetchReq
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		r.sendError(c, 10001, "msg.fetch payload 解析失败", env)
		return
	}
	if req.ConversationID <= 0 {
		r.sendError(c, 10001, "msg.fetch conversation_id 缺失", env)
		return
	}
	ok, err := r.convRepo.IsMember(ctx, req.ConversationID, c.UserID)
	if err != nil {
		r.sendError(c, 50001, "服务端内部错误", env)
		return
	}
	if !ok {
		r.sendError(c, 40103, "无权访问该会话", env)
		return
	}
	items, err := r.msgRepo.ListSince(ctx, req.ConversationID, req.SinceSeq, req.Limit)
	if err != nil {
		r.sendError(c, 50001, "服务端内部错误", env)
		return
	}
	out := msgFetchResult{
		ConversationID: req.ConversationID,
		Items:          make([]msgRecvPayload, 0, len(items)),
		NextSinceSeq:   req.SinceSeq,
	}
	for _, m := range items {
		var sender int64
		if m.SenderID != nil {
			sender = *m.SenderID
		}
		out.Items = append(out.Items, msgRecvPayload{
			ConversationID: m.ConversationID,
			ServerSeq:      m.ServerSeq,
			SenderID:       sender,
			Kind:           m.Kind,
			Content:        m.Payload,
			CreatedAt:      m.CreatedAt.UTC().Format(time.RFC3339Nano),
		})
		if m.ServerSeq > out.NextSinceSeq {
			out.NextSinceSeq = m.ServerSeq
		}
	}
	c.Send(Envelope{Type: "msg.fetch_result", Payload: mustJSON(out)})
}

// ============================================================================
// WebRTC 信令
// ============================================================================

type callInviteReq struct {
	ToUserID int64           `json:"to_user_id"`
	SDP      json.RawMessage `json:"sdp"` // 完整 RTCSessionDescription offer
}

type callInviteAck struct {
	CallID string `json:"call_id"`
	Online bool   `json:"online"` // false → 入 pending_calls；true → 已 push 给 callee
	TTLSec int    `json:"ttl_sec,omitempty"`
}

type callInviteOut struct {
	CallID     string          `json:"call_id"`
	FromUserID int64           `json:"from_user_id"`
	SDP        json.RawMessage `json:"sdp"`
	Pending    bool            `json:"pending,omitempty"` // 离线缓存补发时为 true
	CreatedAt  string          `json:"created_at"`
}

type callAnswerReq struct {
	CallID   string          `json:"call_id"`
	ToUserID int64           `json:"to_user_id"` // 主叫 user_id
	SDP      json.RawMessage `json:"sdp"`        // answer
}

type callIceReq struct {
	CallID    string          `json:"call_id"`
	ToUserID  int64           `json:"to_user_id"`
	Candidate json.RawMessage `json:"candidate"`
}

type callByeReq struct {
	CallID   string `json:"call_id"`
	ToUserID int64  `json:"to_user_id"`
	Reason   string `json:"reason"`
}

func (r *Router) handleCallInvite(ctx context.Context, c *Conn, env Envelope) {
	var req callInviteReq
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		r.sendError(c, 10001, "call.invite payload 解析失败", env)
		return
	}
	if req.ToUserID <= 0 || req.ToUserID == c.UserID || len(req.SDP) == 0 {
		r.sendError(c, 10001, "call.invite 参数缺失或非法", env)
		return
	}
	callID := newCallID()

	// 在线优先：直接推 callee 的所有连接
	delivered := r.hub.Push(req.ToUserID, Envelope{
		Type: "call.invite",
		Payload: mustJSON(callInviteOut{
			CallID:     callID,
			FromUserID: c.UserID,
			SDP:        req.SDP,
			CreatedAt:  time.Now().UTC().Format(time.RFC3339Nano),
		}),
	})

	online := delivered > 0
	if !online {
		// 落库 60s TTL，等 callee 上线后补发
		expire := time.Now().Add(r.pendingCallTTL)
		pc := &repo.PendingCall{
			CallID:   callID,
			CallerID: c.UserID,
			CalleeID: req.ToUserID,
			SDPOffer: req.SDP,
			ExpireAt: expire,
		}
		if err := r.callRepo.Insert(ctx, pc); err != nil {
			r.log.Error("pending_call 落库失败", "err", err, "call_id", callID)
			r.sendError(c, 50001, "服务端内部错误", env)
			return
		}
	}

	// 主叫回执
	c.Send(Envelope{
		Type: "call.invite_ack",
		Payload: mustJSON(callInviteAck{
			CallID: callID,
			Online: online,
			TTLSec: int(r.pendingCallTTL.Seconds()),
		}),
	})

	// audit
	if r.audit != nil {
		afterRaw, _ := audit.Encode(map[string]any{
			"call_id": callID,
			"online":  online,
		})
		ac, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		go func() {
			defer cancel()
			_ = r.audit.Record(ac, audit.Entry{
				UserID:   c.UserID,
				Action:   "call.invite",
				Target:   "user:" + strconv.FormatInt(req.ToUserID, 10),
				AfterRaw: afterRaw,
			})
		}()
	}
}

func (r *Router) handleCallAnswer(_ context.Context, c *Conn, env Envelope) {
	var req callAnswerReq
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		r.sendError(c, 10001, "call.answer payload 解析失败", env)
		return
	}
	if req.ToUserID <= 0 || req.CallID == "" || len(req.SDP) == 0 {
		r.sendError(c, 10001, "call.answer 参数缺失", env)
		return
	}
	// 把 answer 透传给主叫（callee → caller）
	r.hub.Push(req.ToUserID, Envelope{
		Type: "call.answer",
		Payload: mustJSON(map[string]any{
			"call_id":      req.CallID,
			"from_user_id": c.UserID,
			"sdp":          req.SDP,
		}),
	})
}

func (r *Router) handleCallIce(_ context.Context, c *Conn, env Envelope) {
	var req callIceReq
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		r.sendError(c, 10001, "call.ice payload 解析失败", env)
		return
	}
	if req.ToUserID <= 0 || req.CallID == "" || len(req.Candidate) == 0 {
		r.sendError(c, 10001, "call.ice 参数缺失", env)
		return
	}
	r.hub.Push(req.ToUserID, Envelope{
		Type: "call.ice",
		Payload: mustJSON(map[string]any{
			"call_id":      req.CallID,
			"from_user_id": c.UserID,
			"candidate":    req.Candidate,
		}),
	})
}

func (r *Router) handleCallBye(ctx context.Context, c *Conn, env Envelope) {
	var req callByeReq
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		r.sendError(c, 10001, "call.bye payload 解析失败", env)
		return
	}
	if req.CallID == "" {
		r.sendError(c, 10001, "call.bye 参数缺失", env)
		return
	}
	// 试着取消尚在 pending 的 invite
	if err := r.callRepo.Cancel(ctx, req.CallID, c.UserID); err != nil {
		// 不是 caller 或已投递；忽略
		if !errors.Is(err, repo.ErrNotFound) && !errors.Is(err, repo.ErrStateConflict) {
			r.log.Debug("call.bye cancel pending 失败（可忽略）", "err", err)
		}
	}
	if req.ToUserID > 0 {
		r.hub.Push(req.ToUserID, Envelope{
			Type: "call.bye",
			Payload: mustJSON(map[string]any{
				"call_id":      req.CallID,
				"from_user_id": c.UserID,
				"reason":       req.Reason,
			}),
		})
	}
}

// ============================================================================
// 离线 invite 上线补发
// ============================================================================

// DeliverPending 在 callee 刚 hello 时调用：拉未过期 pending invite 一次性下发，并 mark delivered。
func (r *Router) DeliverPending(ctx context.Context, c *Conn) {
	items, err := r.callRepo.ListPendingForCallee(ctx, c.UserID)
	if err != nil {
		r.log.Error("拉 pending_calls 失败", "err", err, "user_id", c.UserID)
		return
	}
	for _, it := range items {
		c.Send(Envelope{
			Type: "call.invite",
			Payload: mustJSON(callInviteOut{
				CallID:     it.CallID,
				FromUserID: it.CallerID,
				SDP:        it.SDPOffer,
				Pending:    true,
				CreatedAt:  it.CreatedAt.UTC().Format(time.RFC3339Nano),
			}),
		})
		if err := r.callRepo.MarkDelivered(ctx, it.CallID); err != nil {
			r.log.Debug("MarkDelivered 失败", "call_id", it.CallID, "err", err)
		}
	}
}

// SweepLoop 后台 ticker：把过期的 pending → expired，避免长期堆积。
// 由 main 在启动时调用。
func (r *Router) SweepLoop(ctx context.Context, interval time.Duration) {
	if interval <= 0 {
		interval = 30 * time.Second
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			n, err := r.callRepo.SweepExpired(ctx)
			if err != nil {
				r.log.Warn("SweepExpired 失败", "err", err)
				continue
			}
			if n > 0 {
				r.log.Info("过期 invite 清理", "count", n)
			}
		}
	}
}

// ============================================================================
// 工具
// ============================================================================

func newCallID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return "call_" + hex.EncodeToString(b)
}

func mustJSON(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		return json.RawMessage(`{}`)
	}
	return b
}

// sendError 给客户端返回错误帧；保留 in_reply_to 让客户端能跟自己的请求对上。
func (r *Router) sendError(c *Conn, code int, msg string, inReplyTo Envelope) {
	c.Send(Envelope{
		Type:    "error",
		Code:    code,
		Message: msg,
		Payload: mustJSON(map[string]any{
			"in_reply_to": inReplyTo.Type,
		}),
	})
}
