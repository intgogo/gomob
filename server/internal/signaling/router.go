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
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

// callPeers 一次通话的两端用户。answer/ice/bye 中继前据此校验会话归属。
type callPeers struct {
	caller   int64
	callee   int64
	expireAt time.Time
}

// Router 持有所有依赖；Handler 用它来 Dispatch。
type Router struct {
	hub      *Hub
	pool     *pgxpool.Pool
	convRepo *repo.ConversationRepo
	msgRepo  *repo.MessageRepo
	callRepo *repo.PendingCallRepo
	trRepo   *repo.TranscriptRepo
	audit    audit.Recorder
	log      *slog.Logger
	// 配置
	pendingCallTTL time.Duration

	// 进程内通话归属表：invite 时登记 {call_id → caller/callee}，
	// answer/ice/bye 中继前据此校验中继方与目标都属于该 call，防 from_user 伪造越权注入。
	// 在线通话(常态)不落 pending_calls，DB 无记录，故必须用进程内表;
	// 进程重启后丢失的离线 invite 回退查 pending_calls 兜底。
	callsMu sync.Mutex
	calls   map[string]callPeers
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
		trRepo:         repo.NewTranscriptRepo(pool, repo.TranscriptConfig{}),
		audit:          auditRec,
		log:            logger.New("signaling.router"),
		pendingCallTTL: pendingCallTTL,
		calls:          map[string]callPeers{},
	}
}

func (r *Router) SetTranscriptConfig(cfg repo.TranscriptConfig) {
	r.trRepo = repo.NewTranscriptRepo(r.pool, cfg)
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
	ConversationID int64           `json:"conversation_id,omitempty"`
	ToUserID       int64           `json:"to_user_id"`
	Kind           string          `json:"kind"` // text / image / voice / video_clip
	Content        json.RawMessage `json:"content"`
	ClientMsgID    string          `json:"client_msg_id,omitempty"`
}

type msgDeliveredPayload struct {
	ClientMsgID    string `json:"client_msg_id,omitempty"`
	ConversationID int64  `json:"conversation_id"`
	ServerSeq      int64  `json:"server_seq"`
	MessageID      int64  `json:"message_id"`
	CreatedAt      string `json:"created_at"`
}

type msgRecvPayload struct {
	MessageID      int64           `json:"message_id"`
	ConversationID int64           `json:"conversation_id"`
	ServerSeq      int64           `json:"server_seq"`
	SenderID       int64           `json:"sender_id"`
	Kind           string          `json:"kind"`
	Content        json.RawMessage `json:"content"`
	ClientMsgID    string          `json:"client_msg_id,omitempty"`
	CreatedAt      string          `json:"created_at"`
}

type transcriptUpdatedPayload struct {
	MessageID      int64           `json:"message_id"`
	ConversationID int64           `json:"conversation_id"`
	ServerSeq      int64           `json:"server_seq"`
	Kind           string          `json:"kind"`
	Content        json.RawMessage `json:"content"`
	UpdatedAt      string          `json:"updated_at"`
}

func (r *Router) handleMsgSend(ctx context.Context, c *Conn, env Envelope) {
	var req msgSendReq
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		r.sendError(c, 10001, "msg.send payload 解析失败", env)
		return
	}
	if req.Kind == "" || len(req.Content) == 0 {
		r.sendError(c, 10001, "msg.send 参数缺失或非法", env)
		return
	}
	conversationID := req.ConversationID
	recipients := make([]int64, 0, 1)
	if conversationID > 0 {
		ok, err := r.convRepo.IsMember(ctx, conversationID, c.UserID)
		if err != nil {
			r.sendError(c, 50001, "服务端内部错误", env)
			return
		}
		if !ok {
			r.sendError(c, 40103, "无权访问该会话", env)
			return
		}
		recipients, err = r.convRepo.CounterpartIDs(ctx, conversationID, c.UserID)
		if err != nil {
			r.sendError(c, 50001, "服务端内部错误", env)
			return
		}
	} else {
		if req.ToUserID <= 0 || req.ToUserID == c.UserID {
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
		conversationID = conv.ID
		recipients = append(recipients, req.ToUserID)
	}
	// 落库（next_seq 行锁分配 server_seq）
	senderID := c.UserID
	m := &repo.Message{
		ConversationID: conversationID,
		SenderID:       &senderID,
		Kind:           req.Kind,
		Payload:        req.Content,
	}
	inserted, err := r.msgRepo.AppendIdempotent(ctx, m, req.ClientMsgID)
	if err != nil {
		r.log.Error("消息落库失败", "err", err, "conv", conversationID)
		r.sendError(c, 50001, "服务端内部错误", env)
		return
	}
	// 发送方回执
	delivered := msgDeliveredPayload{
		ClientMsgID:    req.ClientMsgID,
		ConversationID: conversationID,
		ServerSeq:      m.ServerSeq,
		MessageID:      m.ID,
		CreatedAt:      m.CreatedAt.UTC().Format(time.RFC3339Nano),
	}
	c.Send(Envelope{Type: "msg.delivered", Payload: mustJSON(delivered)})

	// 幂等重发只回执发送方，不重复推给收件人。
	if inserted {
		r.pushMessageToRecipients(recipients, senderID, m)
	}

	// audit（用独立 ctx 避免 conn 关闭传递取消）
	if r.audit != nil {
		afterRaw, _ := audit.Encode(map[string]any{
			"server_seq":      m.ServerSeq,
			"to_user_id":      req.ToUserID,
			"conversation_id": conversationID,
			"kind":            m.Kind,
		})
		ac, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		go func() {
			defer cancel()
			_ = r.audit.Record(ac, audit.Entry{
				UserID:   c.UserID,
				Action:   "message.send",
				Target:   "conversation:" + strconv.FormatInt(conversationID, 10),
				AfterRaw: afterRaw,
			})
		}()
	}
}

// NotifyMessage 把 API/REST 已经落库的消息推给会话内其它在线成员。
//
// 只负责在线实时投递；离线补齐仍由 msg.fetch / REST 历史接口基于 messages 表完成。
func (r *Router) NotifyMessage(ctx context.Context, senderID int64, m *repo.Message) (int, error) {
	if m == nil {
		return 0, nil
	}
	if senderID <= 0 && m.SenderID != nil {
		senderID = *m.SenderID
	}
	recipients, err := r.convRepo.CounterpartIDs(ctx, m.ConversationID, senderID)
	if err != nil {
		return 0, err
	}
	return r.pushMessageToRecipients(recipients, senderID, m), nil
}

func (r *Router) NotifyTranscriptUpdate(ctx context.Context, m *repo.Message) (int, error) {
	if m == nil {
		return 0, nil
	}
	recipients, err := r.convRepo.CounterpartIDs(ctx, m.ConversationID, 0)
	if err != nil {
		return 0, err
	}
	payload := transcriptUpdatedPayload{
		MessageID:      m.ID,
		ConversationID: m.ConversationID,
		ServerSeq:      m.ServerSeq,
		Kind:           m.Kind,
		Content:        m.Payload,
		UpdatedAt:      time.Now().UTC().Format(time.RFC3339Nano),
	}
	delivered := 0
	for _, recipient := range recipients {
		delivered += r.hub.Push(recipient, Envelope{Type: "msg.transcript.updated", Payload: mustJSON(payload)})
	}
	return delivered, nil
}

func (r *Router) pushMessageToRecipients(recipients []int64, senderID int64, m *repo.Message) int {
	recv := messageToRecvPayload(m, senderID)
	delivered := 0
	for _, recipient := range recipients {
		delivered += r.hub.Push(recipient, Envelope{Type: "msg.recv", Payload: mustJSON(recv)})
	}
	return delivered
}

type msgRecallPayload struct {
	MessageID      int64  `json:"message_id"`
	ConversationID int64  `json:"conversation_id"`
	ServerSeq      int64  `json:"server_seq"`
	RecalledBy     int64  `json:"recalled_by"`
	DeletedAt      string `json:"deleted_at"`
}

// NotifyMessageRecall 把撤回事件推给会话内所有成员（含 sender 的其它端，便于多端同步）。
func (r *Router) NotifyMessageRecall(ctx context.Context, m *repo.Message, recalledBy int64) (int, error) {
	if m == nil {
		return 0, nil
	}
	// 包含 sender 自己 — 这样他在其它设备上的同会话也能同步撤回。
	memberIDs, err := r.convRepo.CounterpartIDs(ctx, m.ConversationID, 0)
	if err != nil {
		return 0, err
	}
	deletedAt := ""
	if m.DeletedAt != nil {
		deletedAt = m.DeletedAt.UTC().Format(time.RFC3339Nano)
	}
	payload := msgRecallPayload{
		MessageID:      m.ID,
		ConversationID: m.ConversationID,
		ServerSeq:      m.ServerSeq,
		RecalledBy:     recalledBy,
		DeletedAt:      deletedAt,
	}
	delivered := 0
	for _, recipient := range memberIDs {
		delivered += r.hub.Push(recipient, Envelope{Type: "msg.recall", Payload: mustJSON(payload)})
	}
	return delivered, nil
}

func messageToRecvPayload(m *repo.Message, senderID int64) msgRecvPayload {
	clientMsgID := ""
	if m.ClientMsgID != nil {
		clientMsgID = *m.ClientMsgID
	}
	return msgRecvPayload{
		MessageID:      m.ID,
		ConversationID: m.ConversationID,
		ServerSeq:      m.ServerSeq,
		SenderID:       senderID,
		Kind:           m.Kind,
		Content:        m.Payload,
		ClientMsgID:    clientMsgID,
		CreatedAt:      m.CreatedAt.UTC().Format(time.RFC3339Nano),
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
		clientMsgID := ""
		if m.ClientMsgID != nil {
			clientMsgID = *m.ClientMsgID
		}
		out.Items = append(out.Items, msgRecvPayload{
			MessageID:      m.ID,
			ConversationID: m.ConversationID,
			ServerSeq:      m.ServerSeq,
			SenderID:       sender,
			Kind:           m.Kind,
			Content:        m.Payload,
			ClientMsgID:    clientMsgID,
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
	// 登记通话归属，供后续 answer/ice/bye 校验中继方是否属于该 call。
	r.registerCall(callID, c.UserID, req.ToUserID)

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

// registerCall 在 invite 时登记通话两端；过期项顺手清理。
func (r *Router) registerCall(callID string, caller, callee int64) {
	r.callsMu.Lock()
	defer r.callsMu.Unlock()
	now := time.Now()
	// 机会式清理过期项，避免 map 长期膨胀（无独立 ticker）。
	for id, p := range r.calls {
		if now.After(p.expireAt) {
			delete(r.calls, id)
		}
	}
	r.calls[callID] = callPeers{caller: caller, callee: callee, expireAt: now.Add(r.pendingCallTTL + time.Hour)}
}

// dropCall 通话结束时移除登记。
func (r *Router) dropCall(callID string) {
	r.callsMu.Lock()
	defer r.callsMu.Unlock()
	delete(r.calls, callID)
}

// authorizeRelay 校验 from(中继发起方)与 to(中继目标)同属 callID 这次通话，
// 且互为对端。命中进程内登记表则直接判定;未命中(进程重启丢登记)回退查 pending_calls。
// 返回 false 表示无权中继(伪造 from_user / 张冠李戴 call_id)。
func (r *Router) authorizeRelay(ctx context.Context, callID string, from, to int64) bool {
	r.callsMu.Lock()
	p, ok := r.calls[callID]
	r.callsMu.Unlock()
	if ok {
		return (from == p.caller && to == p.callee) || (from == p.callee && to == p.caller)
	}
	// 进程内无登记：可能是重启后仍存活的离线 invite，查 DB 兜底。
	pc, err := r.callRepo.FindByCallID(ctx, callID)
	if err != nil || pc == nil {
		return false
	}
	return (from == pc.CallerID && to == pc.CalleeID) || (from == pc.CalleeID && to == pc.CallerID)
}

func (r *Router) handleCallAnswer(ctx context.Context, c *Conn, env Envelope) {
	var req callAnswerReq
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		r.sendError(c, 10001, "call.answer payload 解析失败", env)
		return
	}
	if req.ToUserID <= 0 || req.CallID == "" || len(req.SDP) == 0 {
		r.sendError(c, 10001, "call.answer 参数缺失", env)
		return
	}
	if !r.authorizeRelay(ctx, req.CallID, c.UserID, req.ToUserID) {
		r.sendError(c, 40103, "无权操作该通话", env)
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

func (r *Router) handleCallIce(ctx context.Context, c *Conn, env Envelope) {
	var req callIceReq
	if err := json.Unmarshal(env.Payload, &req); err != nil {
		r.sendError(c, 10001, "call.ice payload 解析失败", env)
		return
	}
	if req.ToUserID <= 0 || req.CallID == "" || len(req.Candidate) == 0 {
		r.sendError(c, 10001, "call.ice 参数缺失", env)
		return
	}
	if !r.authorizeRelay(ctx, req.CallID, c.UserID, req.ToUserID) {
		r.sendError(c, 40103, "无权操作该通话", env)
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
		// 中继挂断前同样校验归属，防伪造 from_user 给任意人塞 bye。
		if !r.authorizeRelay(ctx, req.CallID, c.UserID, req.ToUserID) {
			r.sendError(c, 40103, "无权操作该通话", env)
			return
		}
		r.hub.Push(req.ToUserID, Envelope{
			Type: "call.bye",
			Payload: mustJSON(map[string]any{
				"call_id":      req.CallID,
				"from_user_id": c.UserID,
				"reason":       req.Reason,
			}),
		})
	}
	// 通话结束，移除进程内归属登记。
	r.dropCall(req.CallID)
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
