// gomob-api — 业务主域：查验 / 复核 / 资产元数据。
//
// 监听 HTTP（默认 :8080，仅内网；通过 gateway 反代 /v1/inspections|reviews|... 给 App）。
// 详见 docs/architecture/server/02-api-contract.md §4 / §6 / §9。
//
// 环境变量：
//
//	GOMOB_API_HTTP_ADDR    监听地址（默认 :18080）
//	GOMOB_DB_DSN           PG 连接串（详见 pkg/repo/db.go）
//	GOMOB_CATALOG_TARGET   catalog 服务 base URL（默认 http://127.0.0.1:18059；空 = 禁用 catalog BFF）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"io.gomob/server/internal/api"
	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/rbac"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("api")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	addr := envOr("GOMOB_API_HTTP_ADDR", ":18080")

	auditRec := audit.NewPG(pool)
	enforcer := rbac.Baseline()
	h := api.NewHandler(pool, auditRec, enforcer)

	mux := http.NewServeMux()
	h.Mount(mux)
	catalogTarget := envOr("GOMOB_CATALOG_TARGET", "http://127.0.0.1:18059")
	vinrefTarget := envOr("GOMOB_VINREF_TARGET", "http://127.0.0.1:18058")
	shaperefTarget := envOr("GOMOB_SHAPEREF_TARGET", "http://127.0.0.1:18056")
	if catalogTarget != "" || vinrefTarget != "" || shaperefTarget != "" {
		if err := api.MountCatalogBFF(mux, catalogTarget, vinrefTarget, shaperefTarget); err != nil {
			log.Error("catalog BFF 挂载失败", "err", err)
			os.Exit(1)
		}
		log.Info("catalog BFF 已挂载",
			"catalog", catalogTarget, "vinref", vinrefTarget, "shaperef", shaperefTarget)
	}
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"ok":true}`))
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		c, cancel := context.WithTimeout(r.Context(), time.Second)
		defer cancel()
		if err := pool.Ping(c); err != nil {
			http.Error(w, "db unreachable", http.StatusServiceUnavailable)
			return
		}
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
		log.Info("HTTP 监听", "addr", addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("HTTP 异常退出", "err", err)
			cancel()
		}
	}()

	<-stop
	log.Info("收到退出信号，开始 graceful shutdown")
	shutdownCtx, cancelShutdown := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelShutdown()
	_ = srv.Shutdown(shutdownCtx)
	log.Info("退出完成")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
