// gomob-devserver — 开发模式合体进程。
// 当前装载 auth + me 路由（接 PostgreSQL）。后续 api / asset / signaling / worker
// 各自实现成熟后再合并；保持 devserver 永远是"全部已实现路由"的并集。
package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"io.gomob/server/internal/auth"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("devserver")
	log.Info("gomob-devserver starting", "version", "0.1.0")

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("connect pg failed", "err", err)
		os.Exit(1)
	}
	defer pool.Close()
	log.Info("pg connected")

	devAutoActivate := os.Getenv("GOMOB_DEV_AUTO_ACTIVATE") != "false"
	authH := auth.NewHandler(pool, devAutoActivate)

	mux := http.NewServeMux()

	// 公共 endpoints
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, _ *http.Request) {
		fmt.Fprintln(w, "ok")
	})
	mux.HandleFunc("/v1/version", func(w http.ResponseWriter, _ *http.Request) {
		httpx.OK(w, map[string]string{
			"name":             "gomob-devserver",
			"version":          "0.1.0",
			"auto_activate":    boolStr(devAutoActivate),
		})
	})

	// 不需登录的 auth 路由
	mux.HandleFunc("POST /v1/auth/register", authH.Register)
	mux.HandleFunc("POST /v1/auth/login", authH.Login)
	mux.HandleFunc("POST /v1/auth/refresh", authH.Refresh)

	// 受保护路由（包一层 Required）
	protected := http.NewServeMux()
	protected.HandleFunc("POST /v1/auth/password", authH.ChangePassword)
	protected.HandleFunc("GET /v1/me", authH.Me)
	mux.Handle("/v1/auth/password", auth.Required(http.HandlerFunc(authH.ChangePassword)))
	mux.Handle("/v1/me", auth.Required(http.HandlerFunc(authH.Me)))

	addr := os.Getenv("GOMOB_LISTEN")
	if addr == "" {
		addr = ":8808"
	}

	srv := &http.Server{
		Addr:              addr,
		Handler:           withCORS(withLog(mux, log)),
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		log.Info("listening", "addr", srv.Addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Error("server error", "err", err)
			os.Exit(1)
		}
	}()

	<-ctx.Done()
	log.Info("shutting down")
	shutCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = srv.Shutdown(shutCtx)
	log.Info("bye")
}

// 简易访问日志中间件
func withLog(next http.Handler, log interface{ Info(string, ...any) }) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		ww := &statusRec{ResponseWriter: w, status: 200}
		next.ServeHTTP(ww, r)
		log.Info("http",
			"method", r.Method,
			"path", r.URL.Path,
			"status", ww.status,
			"dur_ms", time.Since(start).Milliseconds(),
			"ip", clientIP(r),
		)
	})
}

func withCORS(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Gomob-Client, X-Gomob-Trace-Id")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

type statusRec struct {
	http.ResponseWriter
	status int
}

func (r *statusRec) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

func clientIP(r *http.Request) string {
	if v := r.Header.Get("X-Forwarded-For"); v != "" {
		if i := strings.IndexByte(v, ','); i > 0 {
			return v[:i]
		}
		return v
	}
	return r.RemoteAddr
}

func boolStr(b bool) string {
	if b {
		return "true"
	}
	return "false"
}
