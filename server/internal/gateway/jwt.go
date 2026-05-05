// gateway 的 JWT 校验 — 受保护路径必须带 Bearer access token；
// 校验通过后注入 X-Gomob-User-Id / X-Gomob-Roles header 给下游服务，
// 下游不再自己校验 JWT，信任 gateway 注入的 header。
//
// 详见 docs/architecture/server/00-server-overview.md §5 / §8。
package gateway

import (
	"net/http"
	"strconv"
	"strings"

	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/token"
)

// 透传给下游的 header 名 — 下游服务（auth/api/...）从这里读 user_id / role。
const (
	HeaderUserID = "X-Gomob-User-Id"
	HeaderRoles  = "X-Gomob-Roles"
	HeaderTrace  = "X-Gomob-Trace-Id"
)

// withJWT 在调用 next 前完成 token 解析 + header 注入。
// 如果路由是公开路径（route.Public），直接放行。
func withJWT(route Route, next http.Handler) http.Handler {
	if route.Public {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := r.Header.Get("Authorization")
		if !strings.HasPrefix(header, "Bearer ") {
			httpx.WriteError(w, httpx.ErrTokenInvalid)
			return
		}
		raw := strings.TrimPrefix(header, "Bearer ")
		c, err := token.Parse(raw)
		if err != nil || c.Kind != "access" {
			httpx.WriteError(w, httpx.ErrTokenInvalid)
			return
		}
		// 删除原 Authorization，避免下游又解一次（性能浪费 + 多源信任风险）
		r.Header.Del("Authorization")
		r.Header.Set(HeaderUserID, strconv.FormatInt(c.UserID, 10))
		r.Header.Set(HeaderRoles, c.Role)
		next.ServeHTTP(w, r)
	})
}
