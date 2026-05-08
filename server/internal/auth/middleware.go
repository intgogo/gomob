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
// 鉴权来源优先级（详见 00-server-overview.md §5）：
//  1. **gateway 注入的 X-Gomob-User-Id**（生产路径；gateway 已校验 JWT）
//  2. Bearer access token（直连开发路径；当 auth 直接对外或单测时）
//
// 1 优先于 2 — 一旦 gateway 介入，下游不应再走 token 校验，避免双源信任风险。
func Required(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// 路径 1：gateway 注入
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

		// 路径 2：直连 Bearer token
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
