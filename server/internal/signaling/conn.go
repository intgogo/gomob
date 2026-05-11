// Conn —— 单个 WebSocket 会话。
//
// 设计：
//   - 一个 read goroutine：阻塞 ReadJSON → 投递给 router；
//   - 一个 write goroutine：从 send chan 取消息写出；
//   - 心跳：write goroutine 定期发 ping，read goroutine 设 pong deadline；
//   - Close：任意 goroutine 触发 once 关闭，唤醒对侧 goroutine 退出。
//
// 不在 Conn 里做业务路由：业务（msg.send / call.invite / ...）走 Router.Dispatch。
package signaling

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

const (
	// 写一帧的超时
	writeWait = 10 * time.Second
	// pong 等待超时（read goroutine 在每次 SetReadDeadline 时用）
	pongWait = 60 * time.Second
	// 心跳 ping 周期；要 < pongWait
	pingPeriod = 25 * time.Second
	// 单帧最大长度（512KB；够装 SDP / ICE / 文本消息）
	maxMessageSize = 512 << 10
	// send chan 缓冲；满了说明客户端慢，主动关连接
	sendBufSize = 64
)

// Envelope —— signaling 协议帧。所有 client↔server 消息都用这个外壳。
//
// type:
//
//	hello                  服务端在握手成功后下发 (S→C)
//	ping/pong              心跳（gorilla 内置 ping/pong frame；这里的 type 是应用层心跳预留）
//	error                  错误（S→C，含 code/message）
//
//	msg.send               会话发送 (C→S)；payload: {conversation_id 或 to_user_id, kind, content}
//	msg.delivered          server 落库回执 (S→C)；payload: {client_msg_id, conversation_id, server_seq, message_id}
//	msg.recv               推送给收件人 (S→C)；payload: {message_id, conversation_id, server_seq, sender_id, kind, content}
//	msg.fetch              拉取离线 (C→S)；payload: {conversation_id, since_seq}
//	msg.fetch_result       离线补齐 (S→C)；payload: {conversation_id, items: [...]}
//
//	call.invite            视频邀请 (双向)；C→S: {to_user_id, sdp}；S→C: {call_id, from_user_id, sdp}
//	call.invite_ack        服务端落库 + 投递结果 (S→C)；payload: {call_id, online}
//	call.answer            被叫应答 (双向)；payload: {call_id, sdp}
//	call.ice               ICE candidate 透传 (双向)；payload: {call_id, to_user_id, candidate}
//	call.bye               挂断 (双向)；payload: {call_id, reason}
//
// 客户端可在 payload 内放 client_msg_id 用于和回执匹配。
type Envelope struct {
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload,omitempty"`
	// 服务端写出时填，单调递增（不同于消息 server_seq；这是连接级帧序号，便于客户端调试）
	FrameSeq int64 `json:"frame_seq,omitempty"`
	// 错误专用
	Code    int    `json:"code,omitempty"`
	Message string `json:"message,omitempty"`
}

type Conn struct {
	UserID    int64
	Role      string
	ws        *websocket.Conn
	send      chan Envelope
	closed    chan struct{}
	closeOnce sync.Once
	frameSeq  int64
	log       *slog.Logger
}

func newConn(ws *websocket.Conn, userID int64, role string, log *slog.Logger) *Conn {
	return &Conn{
		UserID: userID,
		Role:   role,
		ws:     ws,
		send:   make(chan Envelope, sendBufSize),
		closed: make(chan struct{}),
		log:    log,
	}
}

// Send 非阻塞投递到 send chan；满则丢弃（客户端慢）并发起关闭。
// 返回是否成功入队。
func (c *Conn) Send(env Envelope) bool {
	select {
	case <-c.closed:
		return false
	case c.send <- env:
		return true
	default:
		c.log.Warn("send buf 满，关连接", "user_id", c.UserID)
		c.Close()
		return false
	}
}

// Close 幂等关闭。
func (c *Conn) Close() {
	c.closeOnce.Do(func() {
		close(c.closed)
		_ = c.ws.Close()
	})
}

// readLoop 由 Router 启动；阻塞接收 JSON 并 dispatch。
func (c *Conn) readLoop(ctx context.Context, dispatch func(context.Context, *Conn, Envelope)) {
	defer c.Close()
	c.ws.SetReadLimit(maxMessageSize)
	_ = c.ws.SetReadDeadline(time.Now().Add(pongWait))
	c.ws.SetPongHandler(func(string) error {
		return c.ws.SetReadDeadline(time.Now().Add(pongWait))
	})

	for {
		var env Envelope
		if err := c.ws.ReadJSON(&env); err != nil {
			if !websocket.IsCloseError(err,
				websocket.CloseNormalClosure, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				c.log.Debug("ws 读断开", "user_id", c.UserID, "err", err)
			}
			return
		}
		// dispatch 不阻塞 read（业务可能慢；起子 goroutine）
		go dispatch(ctx, c, env)
	}
}

// writeLoop 由 Router 启动；负责心跳 + send chan → ws。
func (c *Conn) writeLoop() {
	ticker := time.NewTicker(pingPeriod)
	defer func() {
		ticker.Stop()
		c.Close()
	}()
	for {
		select {
		case <-c.closed:
			return
		case env := <-c.send:
			c.frameSeq++
			env.FrameSeq = c.frameSeq
			_ = c.ws.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.ws.WriteJSON(env); err != nil {
				c.log.Debug("ws 写失败", "user_id", c.UserID, "err", err)
				return
			}
		case <-ticker.C:
			_ = c.ws.SetWriteDeadline(time.Now().Add(writeWait))
			if err := c.ws.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

// upgrader 给 Handler 用；CheckOrigin 在 dev 阶段允许任意来源（生产用 nginx CORS）。
var upgrader = websocket.Upgrader{
	HandshakeTimeout: 10 * time.Second,
	ReadBufferSize:   8 << 10,
	WriteBufferSize:  8 << 10,
	CheckOrigin: func(r *http.Request) bool {
		return true
	},
}
