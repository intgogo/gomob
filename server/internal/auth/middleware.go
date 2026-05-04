package auth

import (
	"context"
	"net/http"
	"strings"

	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/token"
)

type ctxKey int

const (
	ctxUserID ctxKey = iota
	ctxRole
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

// Required 包装需要登录的 handler；token 缺失 / 无效返 40102。
// 路径白名单（注册 / 登录 / 刷新 / 健康检查）由调用方自行不套这层。
func Required(next http.Handler) http.Handler {
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
		ctx := context.WithValue(r.Context(), ctxUserID, c.UserID)
		ctx = context.WithValue(ctx, ctxRole, c.Role)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}
