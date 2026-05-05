// HTTP upgrade endpoint —— 把 GET /v1/ws?token=<jwt> 升级成 WebSocket 会话。
//
// 鉴权：
//
//	浏览器 / RN WebSocket 不能设自定义 header；约定用 query 参数 ?token=<access JWT>。
//	这里直接解 JWT（不依赖 gateway 注入 X-Gomob-User-Id），signaling 自包含。
//	生产环境 gateway 的 /v1/ws 路由可声明 Public 透传 ws upgrade，token 校验由 signaling 做。
//
// 鉴权失败 → 401 + 文本错误（ws upgrade 之前不能写 JSON envelope）。
// 鉴权成功 → 升级 + 推送 hello + 启动 read/write goroutine + 补发 pending_calls。
package signaling

import (
	"context"
	"net/http"
	"strconv"
	"time"

	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/token"
)

// Handler 持有 router；同时也直接路由 HTTP 健康检查路径。
type Handler struct {
	router *Router
	hub    *Hub
}

func NewHandler(router *Router, hub *Hub) *Handler {
	return &Handler{router: router, hub: hub}
}

// Mount 注册 ws 路径到 mux。
func (h *Handler) Mount(mux *http.ServeMux) {
	mux.HandleFunc("GET /v1/ws", h.upgrade)
	mux.HandleFunc("GET /v1/signaling/online", h.online) // 内部诊断
}

// online —— 当前在线 user_id 列表（仅 dev 调试 / 单测用，生产可关）。
func (h *Handler) online(w http.ResponseWriter, _ *http.Request) {
	users := h.hub.SnapshotUsers()
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(`{"users":[`))
	for i, u := range users {
		if i > 0 {
			_, _ = w.Write([]byte(","))
		}
		_, _ = w.Write([]byte(strconv.FormatInt(u, 10)))
	}
	_, _ = w.Write([]byte(`]}`))
}

// upgrade 主入口。
func (h *Handler) upgrade(w http.ResponseWriter, r *http.Request) {
	log := logger.New("signaling.handler")

	// 1. 解 token：query > Authorization Bearer
	raw := r.URL.Query().Get("token")
	if raw == "" {
		hdr := r.Header.Get("Authorization")
		if len(hdr) > 7 && hdr[:7] == "Bearer " {
			raw = hdr[7:]
		}
	}
	if raw == "" {
		http.Error(w, "missing token", http.StatusUnauthorized)
		return
	}
	c, err := token.Parse(raw)
	if err != nil || c.Kind != "access" {
		http.Error(w, "invalid token", http.StatusUnauthorized)
		return
	}

	// 2. 升级
	ws, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		// upgrader 已写过响应
		log.Debug("升级失败", "err", err)
		return
	}

	// 3. 注册到 Hub
	conn := newConn(ws, c.UserID, c.Role, log)
	h.hub.Register(conn)
	log.Info("ws 接入", "user_id", c.UserID, "role", c.Role)

	// 4. hello + 离线 pending_calls 补发
	conn.Send(Envelope{
		Type: "hello",
		Payload: mustJSON(map[string]any{
			"user_id":   c.UserID,
			"role":      c.Role,
			"server_ts": nowMillis(),
		}),
	})

	// 关键：dispatch 用 Background 派生的 ctx，不能用 r.Context()。
	// 原因：upgrade 函数返回后 r.Context() 立刻 Done；但此时 ws 连接还在，
	// 业务消息会被错误地标记为 "context canceled"。
	// 这里 ctx 寿命跟 ws 连接绑定（readLoop 退出时 cancel）。
	ctx, cancel := context.WithCancel(context.Background())

	// pending_calls 补发用独立短超时 ctx，不阻塞 readLoop
	go func() {
		dctx, dcancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer dcancel()
		h.router.DeliverPending(dctx, conn)
	}()

	// 5. 读写 goroutine
	go conn.writeLoop()
	go func() {
		conn.readLoop(ctx, h.router.Dispatch)
		h.hub.Unregister(conn)
		cancel()
		log.Info("ws 断开", "user_id", c.UserID)
	}()
}

func nowMillis() int64 {
	return time.Now().UnixMilli()
}
