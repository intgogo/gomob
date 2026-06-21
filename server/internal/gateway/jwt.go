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

// stripTrustedHeaders 无条件剥离客户端可能自带的受信注入头。
// 这些 header 只能由 gateway 在 JWT 校验通过后设置；任何从客户端进来的
// 同名 header 都视为伪造（冒充 admin / 越权），必须在入口先 Del 掉，
// 不论路由公开与否、也不论后续是否注入。
//
// 安全前提：下游服务（auth/api/...）无条件信任这些 header，所以网络层面
// 必须保证下游只接受 gateway 转发的流量（内网 / mTLS / service mesh）。
// 这一点属部署配置，不在代码内强制；TODO(deploy): 部署时把下游绑内网，
// 终态见 docs/architecture/server/00-server-overview.md §5/§8。
func stripTrustedHeaders(r *http.Request) {
	r.Header.Del(HeaderUserID)
	r.Header.Del(HeaderRoles)
	r.Header.Del(HeaderTrace)
}

// withJWT 在调用 next 前完成 token 解析 + header 注入。
// 无论路由是否公开，入口都先剥离客户端伪造的受信头；
// 公开路径剥离后直接放行，受保护路径校验 JWT 通过后再注入真实声明。
func withJWT(route Route, next http.Handler) http.Handler {
	if route.Public {
		// 公开路由也必须剥离：否则客户端自带的 X-Gomob-User-Id 会直达下游冒充身份。
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			stripTrustedHeaders(r)
			next.ServeHTTP(w, r)
		})
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 第一步：无条件剥离客户端伪造的受信头，再做校验/注入。
		stripTrustedHeaders(r)
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
		// 仅在 JWT 校验通过后设置真实声明。
		r.Header.Set(HeaderUserID, strconv.FormatInt(c.UserID, 10))
		r.Header.Set(HeaderRoles, c.Role)
		next.ServeHTTP(w, r)
	})
}
