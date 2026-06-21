package auth

import (
	"context"
	"net/http"
	"strconv"
	"strings"

	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/token"
)

type ctxKey int

const (
	ctxUserID ctxKey = iota
	ctxRole
)

// 与 internal/gateway/jwt.go 保持一致 — gateway 注入下来的 header 名。
const (
	hdrUserID = "X-Gomob-User-Id"
	hdrRoles  = "X-Gomob-Roles"
)

// allowBearerDirect 控制是否启用「直连 Bearer token」鉴权路径。
//
// 收敛单一信任源：
//   - 生产部署 auth 永远在 gateway 之后，只信 gateway 注入的 X-Gomob-User-Id，
//     此开关保持 false —— Bearer 直连路径被物理关闭，杜绝绕过 gateway 校验。
//   - 仅 devserver（auth 直接对外）/ 单测需要不经 gateway 自行解 token 时，
//     由进程启动方显式 EnableBearerDirect(true) 打开。
//
// 默认 false（生产安全）。详见 00-server-overview.md §5。
var allowBearerDirect = false

// EnableBearerDirect 由 devserver / 测试在启动时显式调用以打开 Bearer 直连路径。
// 生产进程不调用，保持单一信任源（仅 gateway 注入）。
func EnableBearerDirect(on bool) {
	allowBearerDirect = on
}

// UserIDFromCtx 由受保护 handler 调用，前提是请求经 Auth middleware 过滤过。
func UserIDFromCtx(ctx context.Context) (int64, bool) {
	v, ok := ctx.Value(ctxUserID).(int64)
	return v, ok
}

func RoleFromCtx(ctx context.Context) (string, bool) {
	v, ok := ctx.Value(ctxRole).(string)
	return v, ok
}

// Required 包装需要登录的 handler；token / 注入 header 缺失 / 无效返 40102。
//
// 鉴权信任源（详见 00-server-overview.md §5）：
// 生产唯一信任源 = gateway 注入的 X-Gomob-User-Id（gateway 已校验 JWT）；
// dev/单测在 EnableBearerDirect(true) 后额外允许直连 Bearer access token。
//
// 收敛为单一信任源——生产不开 Bearer 直连，杜绝「绕过 gateway 自带 token 直访
// 下游」的双源信任风险（devserver 可无条件绕过 gateway 校验的问题已关闭）。
func Required(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 信任源 1：gateway 注入（生产唯一路径）。
		if uidStr := r.Header.Get(hdrUserID); uidStr != "" {
			uid, err := strconv.ParseInt(uidStr, 10, 64)
			if err != nil {
				httpx.WriteError(w, httpx.ErrTokenInvalid)
				return
			}
			ctx := context.WithValue(r.Context(), ctxUserID, uid)
			ctx = context.WithValue(ctx, ctxRole, r.Header.Get(hdrRoles))
			next.ServeHTTP(w, r.WithContext(ctx))
			return
		}

		// 信任源 2：直连 Bearer token —— 仅在 dev 开关打开时启用。
		// 生产保持关闭，请求到这里即视为未携带受信注入头，直接拒绝。
		if !allowBearerDirect {
			httpx.WriteError(w, httpx.ErrTokenInvalid)
			return
		}
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
		r.Header.Set(hdrUserID, strconv.FormatInt(c.UserID, 10))
		r.Header.Set(hdrRoles, c.Role)
		ctx := context.WithValue(r.Context(), ctxUserID, c.UserID)
		ctx = context.WithValue(ctx, ctxRole, c.Role)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}
