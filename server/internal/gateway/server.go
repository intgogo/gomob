// gateway 服务的对外入口 — 把路由表 + JWT 中间件 + 限流串成一个 http.Handler。
package gateway

import (
	"context"
	"net/http"

	"github.com/redis/go-redis/v9"

	"io.gomob/server/pkg/httpx"
)

// NewServer 构造 gateway 的总 http.Handler。
//
//   routes  — 路由表（一般用 DefaultRoutes()）
//   limiter — 可为 nil；nil 时不限流
func NewServer(routes []Route, limiter *Limiter) (http.Handler, error) {
	rt, err := NewRouteTable(routes)
	if err != nil {
		return nil, err
	}

	// 健康检查直接由 gateway 自身回应（不反代）
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ready":true}`))
	})

	// 主入口：路径未匹配则 404；匹配后逐层套 JWT + 限流 + 反代
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		route, proxy, ok := rt.Match(r.URL.Path)
		if !ok {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		// 反代是终点 handler
		var h http.Handler = proxy

		// 限流（在 JWT 之后才能拿到 user_id 注入的 header；所以放 JWT 之外但调用顺序在 JWT 之后）
		if limiter != nil {
			h = limiter.Middleware(PerUserOrIP, h)
		}

		// JWT（公开路径会跳过）
		h = withJWT(route, h)

		h.ServeHTTP(w, r)
	})

	return mux, nil
}

// HealthCheckRedis 工具函数，启动时一次性探活，便于 main 决定是否禁用限流。
func HealthCheckRedis(ctx context.Context, rdb *redis.Client) error {
	if rdb == nil {
		return nil
	}
	return rdb.Ping(ctx).Err()
}
