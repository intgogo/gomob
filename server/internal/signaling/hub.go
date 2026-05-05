// Hub —— signaling 服务的中枢会话注册表。
//
// 职责：
//   - 维护 user_id → 多个 *Conn 的映射（同一用户可多端登录）
//   - 提供 Push(toUserID, env) 把消息发给该用户的所有在线连接
//   - Conn 关闭时从注册表摘除
//
// 单进程内存即可（M-S4 阶段）；多副本部署留给 M-S5+（NATS 跨副本桥接）。
package signaling

import (
	"sync"
)

// Hub 单实例 / 进程级。
type Hub struct {
	mu    sync.RWMutex
	users map[int64]map[*Conn]struct{} // user_id → conn set
}

func NewHub() *Hub {
	return &Hub{users: make(map[int64]map[*Conn]struct{})}
}

// Register 把 conn 挂到 user_id。
func (h *Hub) Register(c *Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	set, ok := h.users[c.UserID]
	if !ok {
		set = make(map[*Conn]struct{})
		h.users[c.UserID] = set
	}
	set[c] = struct{}{}
}

// Unregister 移除 conn；若该用户最后一个连接被摘除，从 map 删 key。
func (h *Hub) Unregister(c *Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	set, ok := h.users[c.UserID]
	if !ok {
		return
	}
	delete(set, c)
	if len(set) == 0 {
		delete(h.users, c.UserID)
	}
}

// Push 把 envelope 推给 toUserID 的所有连接；返回成功投递的连接数（即"是否在线"指标）。
//
// 不阻塞调用方：Conn.Send 走带缓冲 chan + 非阻塞 select；满了直接丢弃并标记该 conn 不健康。
func (h *Hub) Push(toUserID int64, env Envelope) int {
	h.mu.RLock()
	conns := make([]*Conn, 0)
	for c := range h.users[toUserID] {
		conns = append(conns, c)
	}
	h.mu.RUnlock()

	delivered := 0
	for _, c := range conns {
		if c.Send(env) {
			delivered++
		}
	}
	return delivered
}

// IsOnline 返回 user_id 是否至少有一个活跃连接。
func (h *Hub) IsOnline(userID int64) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	_, ok := h.users[userID]
	return ok
}

// SnapshotUsers 调试 / 监控 — 当前在线 user_id 列表。
func (h *Hub) SnapshotUsers() []int64 {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]int64, 0, len(h.users))
	for uid := range h.users {
		out = append(out, uid)
	}
	return out
}
