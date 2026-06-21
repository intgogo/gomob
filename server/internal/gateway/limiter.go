// 基于 Redis 的滑动窗口限流（详见 02-api-contract.md §10）。
//
// 算法：固定窗口计数器 — 每 (window) 时间内每 key 不超过 limit 次。
// 实现简单、足够 M-S1 阶段；02-api-contract.md §10 列了多档配额，
// 后续把 (limit, window) 按路径前缀映射成一张表。
//
// key 取值：
//   - 公开路径：login_<remote_ip> / register_<remote_ip>
//   - 受保护路径：user_<user_id>
package gateway

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/redis/go-redis/v9"

	"io.gomob/server/pkg/httpx"
)

// Limiter 基于 Redis 的固定窗口计数器。
type Limiter struct {
	rdb    *redis.Client
	window time.Duration
	limit  int
}

func NewLimiter(rdb *redis.Client, window time.Duration, limit int) *Limiter {
	return &Limiter{rdb: rdb, window: window, limit: limit}
}

// Allow 增加一次计数，返回是否通过。
// key 形如 "rate:user_123:GET:/v1/me"；调用方决定粒度。
func (l *Limiter) Allow(ctx context.Context, key string) (bool, error) {
	if l == nil || l.rdb == nil {
		return true, nil // 限流不可用时降级放行（gateway 仍能服务）
	}
	rk := "rate:" + key
	pipe := l.rdb.TxPipeline()
	incr := pipe.Incr(ctx, rk)
	pipe.Expire(ctx, rk, l.window)
	if _, err := pipe.Exec(ctx); err != nil {
		return true, err // 失败时降级放行 + 记错（调用方决定如何告警）
	}
	return incr.Val() <= int64(l.limit), nil
}

// Middleware 返回一个 http.Handler 包装；超限直接返 ErrRateLimited。
//
// keyFn(r) 决定限流维度；返回空串表示跳过限流（如健康检查）。
func (l *Limiter) Middleware(keyFn func(r *http.Request) string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		key := keyFn(r)
		if key == "" {
			next.ServeHTTP(w, r)
			return
		}
		ok, err := l.Allow(r.Context(), key)
		if err != nil && !errors.Is(err, redis.Nil) {
			// 限流后端故障：放行 + 在响应头标注（调用方监控）
			w.Header().Set("X-Gomob-Limiter-Degraded", "1")
			next.ServeHTTP(w, r)
			return
		}
		if !ok {
			httpx.WriteError(w, httpx.ErrRateLimited)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// PerUserOrIP 默认 keyFn：受保护路径按 user_id；否则按 remote IP。
//
// 安全前提：只在 withJWT 之后挂载本 keyFn，此时 HeaderUserID 一定是
// gateway 校验 JWT 后注入的真实 user_id（withJWT 入口已无条件剥离客户端
// 自带的同名头）。若挂在 withJWT 之前，HeaderUserID 仍是客户端可控的，
// 攻击者可任意伪造 user_id 绕过限流——此时必须改用 PerIP。
//
// 实际生产用 X-Forwarded-For（须信任前置 proxy）；M-S1 直接用 RemoteAddr。
func PerUserOrIP(r *http.Request) string {
	if uid := r.Header.Get(HeaderUserID); uid != "" {
		return "user_" + uid
	}
	return "ip_" + clientIP(r)
}

// PerIP 公开路由专用 keyFn：只按客户端 IP 配额，绝不读任何客户端可伪造的头。
//
// 公开路由（login/register）在 withJWT 里只做剥离不做注入，因此限流维度
// 不能依赖 HeaderUserID（已被剥离为空，且本就客户端可控）；统一按 IP 限流，
// 防止攻击者通过伪造 user_id 把限流键打散来绕过公开路由配额。
func PerIP(r *http.Request) string {
	return "ip_" + clientIP(r)
}

func clientIP(r *http.Request) string {
	// 取第一段（host:port → host）
	addr := r.RemoteAddr
	for i := len(addr) - 1; i >= 0; i-- {
		if addr[i] == ':' {
			return addr[:i]
		}
	}
	return addr
}
