// gomob-auth — 注册（含审核流） / 登录 / 改密 / token 颁发 / RBAC。
//
// 监听 HTTP（默认 :8082，仅内网；通过 gateway 反代 /v1/auth/* 与 /v1/me 暴露给 App）。
// 详见 docs/architecture/server/02-api-contract.md §3。
//
// 环境变量：
//
//	GOMOB_AUTH_HTTP_ADDR        监听地址（默认 :18082）
//	GOMOB_AUTH_DEV_AUTOACTIVATE 注册立即激活（开发用；默认 true，GOMOB_ENV=prod 时强制 false）
//	GOMOB_ENV                   prod / dev（默认 dev）
//	GOMOB_DB_DSN                PG 连接串（详见 pkg/repo/db.go）
//	GOMOB_JWT_SECRET            JWT 密钥（详见 pkg/token/jwt.go）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"io.gomob/server/internal/auth"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("auth")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	addr := envOr("GOMOB_AUTH_HTTP_ADDR", ":18082")
	devAuto := envBool("GOMOB_AUTH_DEV_AUTOACTIVATE", true)
	if os.Getenv("GOMOB_ENV") == "prod" {
		devAuto = false
	}

	h := auth.NewHandler(pool, devAuto)

	mux := http.NewServeMux()
	// 公开路径
	mux.HandleFunc("POST /v1/auth/register", h.Register)
	mux.HandleFunc("POST /v1/auth/login", h.Login)
	mux.HandleFunc("POST /v1/auth/refresh", h.Refresh)
	// 受保护路径
	mux.Handle("POST /v1/auth/password", auth.Required(http.HandlerFunc(h.ChangePassword)))
	mux.Handle("GET /v1/me", auth.Required(http.HandlerFunc(h.Me)))
	// 健康检查
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		c, cancel := context.WithTimeout(r.Context(), time.Second)
		defer cancel()
		if err := pool.Ping(c); err != nil {
			http.Error(w, "db unreachable", http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ready":true}`))
	})

	srv := &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)

	go func() {
		log.Info("HTTP 监听", "addr", addr, "dev_auto_activate", devAuto)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("HTTP 异常退出", "err", err)
			cancel()
		}
	}()

	<-stop
	log.Info("收到退出信号，开始 graceful shutdown")

	shutdownCtx, cancelShutdown := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelShutdown()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Error("shutdown 失败", "err", err)
	}
	log.Info("退出完成")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envBool(key string, def bool) bool {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	switch v {
	case "1", "true", "TRUE", "True", "yes", "on":
		return true
	default:
		return false
	}
}
