// 路由表 + 反向代理（详见 docs/architecture/server/00-server-overview.md §6.app）。
//
// 每条路由：路径前缀 → 后端 base URL + 是否公开（公开则跳过 JWT 校验）。
// 路径前缀按长度从长到短匹配，避免 "/v1/auth" 抢 "/v1/auth/refresh" 的具体路由。
package gateway

import (
	"net/http/httputil"
	"net/url"
	"sort"
	"strings"
)

// Route 一条路由规则。
type Route struct {
	Prefix string // 例 "/v1/auth/"  或 "/v1/me"
	Target string // 例 "http://127.0.0.1:8082"
	Public bool   // 公开路径（跳过 JWT 校验）；通常仅用于注册 / 登录 / 刷新 / 健康
}

// RouteTable 一组路由。Match 按 Prefix 长度从长到短匹配。
type RouteTable struct {
	rules []Route
	procs map[string]*httputil.ReverseProxy // target → 复用 proxy
}

// NewRouteTable 排序、构建 reverse proxy 池。
func NewRouteTable(rules []Route) (*RouteTable, error) {
	rt := &RouteTable{procs: make(map[string]*httputil.ReverseProxy)}

	// 长前缀优先
	sorted := make([]Route, len(rules))
	copy(sorted, rules)
	sort.SliceStable(sorted, func(i, j int) bool {
		return len(sorted[i].Prefix) > len(sorted[j].Prefix)
	})
	rt.rules = sorted

	for _, r := range sorted {
		if _, ok := rt.procs[r.Target]; ok {
			continue
		}
		u, err := url.Parse(r.Target)
		if err != nil {
			return nil, err
		}
		rt.procs[r.Target] = httputil.NewSingleHostReverseProxy(u)
	}
	return rt, nil
}

// Match 找到第一个匹配的路由；命中后返回 (rule, proxy, true)。
func (rt *RouteTable) Match(path string) (Route, *httputil.ReverseProxy, bool) {
	for _, r := range rt.rules {
		if r.Prefix == path || strings.HasPrefix(path, r.Prefix) {
			return r, rt.procs[r.Target], true
		}
	}
	return Route{}, nil, false
}

// DefaultRoutes 是 M-S1 阶段的硬编码路由；后续按 configs/gateway.yaml 加载。
//
// 仅 auth 服务在 M-S1 落地，其它路由提前登记 — 调用未实现的 target 时 ReverseProxy 返 502，
// 这也是希望的行为（让 App 端能在 M-S2/M-S3 切流量过来时不需要改 gateway）。
func DefaultRoutes() []Route {
	return []Route{
		// 公开路径
		{Prefix: "/v1/auth/register", Target: targetAuth, Public: true},
		{Prefix: "/v1/auth/login", Target: targetAuth, Public: true},
		{Prefix: "/v1/auth/refresh", Target: targetAuth, Public: true},

		// auth 受保护
		{Prefix: "/v1/auth/password", Target: targetAuth},
		{Prefix: "/v1/me", Target: targetAuth},

		// 业务主域 + 参考库 BFF（M-S2 起接 api）
		{Prefix: "/v1/inspections", Target: targetAPI},
		{Prefix: "/v1/reviews", Target: targetAPI},
		{Prefix: "/v1/messages", Target: targetAPI},
		{Prefix: "/v1/conversations", Target: targetAPI},
		{Prefix: "/v1/media/", Target: targetAPI},
		{Prefix: "/v1/live-sessions", Target: targetAPI},
		{Prefix: "/v1/livekit/", Target: targetAPI},
		{Prefix: "/v1/catalog/", Target: targetAPI},

		// 资产
		{Prefix: "/v1/assets/", Target: targetAsset},

		// 端侧日志同步（M3 调试期；要 JWT，不公开）
		{Prefix: "/v1/logs/", Target: targetAPI},

		// 设备 / 算法 / LLM（直达）
		{Prefix: "/v1/devices", Target: targetDevice},
		{Prefix: "/cv/ocr/v1/", Target: targetCvengine},
		{Prefix: "/v1/llm/", Target: targetLLM},

		// 信令 wss —— Public：浏览器/RN 不能设 Authorization header；
		// 走 ?token=<jwt> query 由 signaling 自己校验（详见 internal/signaling/handler.go）。
		{Prefix: "/v1/ws", Target: targetSignaling, Public: true},
		{Prefix: "/v1/signaling/", Target: targetSignaling, Public: true},
	}
}

// 默认目标（开发 / 本机）。统一用 18000 段，避免与 gogame / 其它项目的 80xx 段冲突。
// 后续放 configs/gateway.yaml。
const (
	targetAuth      = "http://127.0.0.1:18082"
	targetAPI       = "http://127.0.0.1:18080"
	targetAsset     = "http://127.0.0.1:18083"
	targetSignaling = "http://127.0.0.1:18084"
	targetDevice    = "http://127.0.0.1:18086"
	targetCvengine  = "http://127.0.0.1:18810"
	targetLLM       = "http://127.0.0.1:18811"
)
