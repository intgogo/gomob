// gomob-shaperef — 车型 3D 外廓参考库 HTTP 服务（详见 02-api-contract.md §13.4 / 00-server-overview.md §6.z）。
//
// 通路：
//
//	App ──▶ gateway ──▶ api（BFF 反代 /v1/catalog/vehicles/{vmid}/shape{,/url}）──▶ shape-ref
//	admin BFF ──HTTP──▶ shape-ref（写：版本 CRUD + 状态机）
//	cv-engine ──HTTP──▶ shape-ref（按 vehicle_model_id 拉对照外廓做扫描重建质量比对）
//
// 环境变量：
//
//	GOMOB_SHAPEREF_HTTP_ADDR  HTTP 监听地址（默认 :18056）
//	GOMOB_DB_DSN              PG 连接串
//	GOMOB_MINIO_ENDPOINT      默认 127.0.0.1:9000
//	GOMOB_MINIO_ACCESS_KEY    默认 gomob
//	GOMOB_MINIO_SECRET_KEY    默认 gomob_dev_minio
//	GOMOB_MINIO_BUCKET        默认 gomob-assets（与 asset 同 bucket）
//	GOMOB_MINIO_USE_SSL       true / false（默认 false）
//	GOMOB_SHAPEREF_PRESIGN    签名 URL TTL（默认 5m）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"io.gomob/server/internal/shaperef"
	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("shaperef")

	rootCtx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := repo.NewPool(rootCtx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	cfg := shaperef.DefaultConfig()
	cfg.MinIOEndpoint = envOr("GOMOB_MINIO_ENDPOINT", cfg.MinIOEndpoint)
	cfg.MinIOAccessKey = envOr("GOMOB_MINIO_ACCESS_KEY", cfg.MinIOAccessKey)
	cfg.MinIOSecretKey = envOr("GOMOB_MINIO_SECRET_KEY", cfg.MinIOSecretKey)
	cfg.Bucket = envOr("GOMOB_MINIO_BUCKET", cfg.Bucket)
	cfg.MinIOUseSSL = os.Getenv("GOMOB_MINIO_USE_SSL") == "true"
	cfg.PresignDuration = parseDuration("GOMOB_SHAPEREF_PRESIGN", cfg.PresignDuration)

	auditRec := audit.NewPG(pool)
	h, err := shaperef.NewHandler(cfg, pool, auditRec)
	if err != nil {
		log.Error("shaperef handler 初始化失败", "err", err)
		os.Exit(1)
	}

	addr := envOr("GOMOB_SHAPEREF_HTTP_ADDR", ":18056")
	mux := http.NewServeMux()
	h.Mount(mux)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"ok":true}`))
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		c, c2 := context.WithTimeout(r.Context(), time.Second)
		defer c2()
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
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       2 * time.Minute,
		MaxHeaderBytes:    1 << 16,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	go func() {
		log.Info("HTTP 监听", "addr", addr, "bucket", cfg.Bucket, "minio", cfg.MinIOEndpoint)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("HTTP 异常退出", "err", err)
			cancel()
		}
	}()

	<-stop
	log.Info("收到退出信号，开始 graceful shutdown")
	shutdownCtx, sc := context.WithTimeout(context.Background(), 5*time.Second)
	defer sc()
	_ = srv.Shutdown(shutdownCtx)
	log.Info("退出完成")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func parseDuration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return def
}
