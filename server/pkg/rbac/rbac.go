// RBAC 鉴权抽象（详见 00-server-overview.md §5）。
//
// 4 个角色：inspector / supervisor / reviewer / admin。
// M-S1 阶段切到 casbin 真实策略文件；M-S0 阶段用本包的 InMemory 把基线策略锁死，作为单测基准。
package rbac

import "sync"

// 资源动作三元组：role × resource × action → allow/deny。
// 例：("inspector", "inspection", "create") → true。
type Rule struct {
	Role     string
	Resource string
	Action   string
}

// Enforcer 鉴权决策。
type Enforcer interface {
	Allow(role, resource, action string) bool
}

// 已编目角色 — 与 02-api-contract.md / 00-server-overview.md §5 同步。
const (
	RoleInspector  = "inspector"
	RoleSupervisor = "supervisor"
	RoleReviewer   = "reviewer"
	RoleAdmin      = "admin"
)

// InMemory 在内存里存策略；线程安全。
type InMemory struct {
	mu    sync.RWMutex
	rules map[Rule]struct{}
}

func NewInMemory(rules ...Rule) *InMemory {
	m := &InMemory{rules: make(map[Rule]struct{}, len(rules))}
	for _, r := range rules {
		m.rules[r] = struct{}{}
	}
	return m
}

func (m *InMemory) Allow(role, resource, action string) bool {
	if role == RoleAdmin {
		return true // admin 全通行；具体管控由 admin 服务自身做（按用户组织 / mTLS 网段）
	}
	m.mu.RLock()
	defer m.mu.RUnlock()
	_, ok := m.rules[Rule{Role: role, Resource: resource, Action: action}]
	return ok
}

func (m *InMemory) Add(r Rule) {
	m.mu.Lock()
	m.rules[r] = struct{}{}
	m.mu.Unlock()
}

// Baseline 返回 M-S0 阶段锁定的最小可用策略集 — 后续 M-S1 用 casbin 文件替代。
func Baseline() *InMemory {
	return NewInMemory(
		// 查验员：自己创建 / 修改自己的查验
		Rule{RoleInspector, "inspection", "create"},
		Rule{RoleInspector, "inspection", "read_self"},
		Rule{RoleInspector, "inspection", "update_self"},
		Rule{RoleInspector, "asset", "upload_self"},
		Rule{RoleInspector, "message", "send"},
		// 监管：跨用户读
		Rule{RoleSupervisor, "inspection", "read_any"},
		Rule{RoleSupervisor, "user", "read"},
		Rule{RoleSupervisor, "message", "send"},
		// 复核员：抽查复核
		Rule{RoleReviewer, "review", "read"},
		Rule{RoleReviewer, "review", "decide"},
		Rule{RoleReviewer, "inspection", "read_any"},
		Rule{RoleReviewer, "message", "send"},
	)
}
