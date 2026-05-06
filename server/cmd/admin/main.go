// gomob-admin — 管理后台 BFF。
//
// 详见 docs/architecture/server/00-server-overview.md §7.x。
//
// 监听 HTTP（默认 :19090，仅管理网段；生产前置 mTLS）。
// 鉴权：JWT + role=admin（gateway / 上游须注入 X-Gomob-User-Id + X-Gomob-Roles）。
// 反代：/admin/v1/{catalog,llm,models}/* 透传到对应服务。
//
// 环境变量：
//
//	GOMOB_ADMIN_HTTP_ADDR        监听地址（默认 :19090）
//	GOMOB_DB_DSN                 PG
//	GOMOB_CATALOG_TARGET         默认 http://127.0.0.1:18059
//	GOMOB_LLM_TARGET             默认 http://127.0.0.1:18811
//	GOMOB_MODELREGISTRY_TARGET   默认 http://127.0.0.1:18057
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"io.gomob/server/internal/admin"
	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("admin")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	cfg := admin.Config{
		CatalogTarget:       envOr("GOMOB_CATALOG_TARGET", "http://127.0.0.1:18059"),
		LLMTarget:           envOr("GOMOB_LLM_TARGET", "http://127.0.0.1:18811"),
		ModelRegistryTarget: envOr("GOMOB_MODELREGISTRY_TARGET", "http://127.0.0.1:18057"),
		VinRefTarget:        envOr("GOMOB_VINREF_TARGET", "http://127.0.0.1:18058"),
		ShapeRefTarget:      envOr("GOMOB_SHAPEREF_TARGET", "http://127.0.0.1:18056"),
		DeviceTarget:        envOr("GOMOB_DEVICE_TARGET", "http://127.0.0.1:18086"),
	}

	auditRec := audit.NewPG(pool)
	h := admin.NewHandler(cfg, pool, auditRec)

	addr := envOr("GOMOB_ADMIN_HTTP_ADDR", ":19090")
	mux := http.NewServeMux()
	if err := h.Mount(mux); err != nil {
		log.Error("admin handler 挂载失败", "err", err)
		os.Exit(1)
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
		WriteTimeout:      60 * time.Second, // 反代下游可能有大响应
		IdleTimeout:       2 * time.Minute,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	go func() {
		log.Info("HTTP 监听", "addr", addr,
			"catalog", cfg.CatalogTarget,
			"llm", cfg.LLMTarget,
			"modelregistry", cfg.ModelRegistryTarget,
			"vinref", cfg.VinRefTarget,
			"shaperef", cfg.ShapeRefTarget,
			"device", cfg.DeviceTarget)
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
