// gomob-asset — 资产分片上传 / 签名 URL 下载。
//
// 监听 HTTP（默认 :8083，仅内网；通过 gateway 反代 /v1/assets/* 给 App）。
// 详见 docs/architecture/server/02-api-contract.md §5。
//
// 环境变量：
//
//	GOMOB_ASSET_HTTP_ADDR    监听地址（默认 :18083）
//	GOMOB_DB_DSN             PG 连接串
//	GOMOB_REDIS_ADDR         Redis 地址（默认 127.0.0.1:6379）
//	GOMOB_MINIO_ENDPOINT     MinIO endpoint（默认 127.0.0.1:9000）
//	GOMOB_MINIO_ACCESS_KEY   默认 gomob
//	GOMOB_MINIO_SECRET_KEY   默认 gomob_dev_minio
//	GOMOB_MINIO_BUCKET       默认 gomob-assets
//	GOMOB_MINIO_USE_SSL      true / false（默认 false）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"

	"io.gomob/server/internal/asset"
	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("asset")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	rdb := redis.NewClient(&redis.Options{
		Addr: envOr("GOMOB_REDIS_ADDR", "127.0.0.1:6379"),
	})
	pingCtx, pingCancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer pingCancel()
	if err := rdb.Ping(pingCtx).Err(); err != nil {
		log.Warn("Redis 不可达，etag 缓存禁用（fallback 走 MinIO ListObjectParts）", "err", err)
		_ = rdb.Close()
		rdb = nil
	}

	cfg := asset.DefaultConfig()
	cfg.MinIOEndpoint = envOr("GOMOB_MINIO_ENDPOINT", cfg.MinIOEndpoint)
	cfg.MinIOAccessKey = envOr("GOMOB_MINIO_ACCESS_KEY", cfg.MinIOAccessKey)
	cfg.MinIOSecretKey = envOr("GOMOB_MINIO_SECRET_KEY", cfg.MinIOSecretKey)
	cfg.Bucket = envOr("GOMOB_MINIO_BUCKET", cfg.Bucket)
	cfg.MinIOUseSSL = os.Getenv("GOMOB_MINIO_USE_SSL") == "true"

	auditRec := audit.NewPG(pool)

	h, err := asset.NewHandler(cfg, pool, rdb, auditRec)
	if err != nil {
		log.Error("asset handler 初始化失败", "err", err)
		os.Exit(1)
	}

	addr := envOr("GOMOB_ASSET_HTTP_ADDR", ":18083")
	mux := http.NewServeMux()
	h.Mount(mux)
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
		// 不设 ReadTimeout / WriteTimeout — 大文件上传可能跨分钟
		IdleTimeout:    5 * time.Minute,
		MaxHeaderBytes: 1 << 16,
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
	shutdownCtx, cancelShutdown := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancelShutdown()
	_ = srv.Shutdown(shutdownCtx)
	if rdb != nil {
		_ = rdb.Close()
	}
	log.Info("退出完成")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
