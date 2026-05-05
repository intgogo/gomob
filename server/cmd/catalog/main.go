// gomob-catalog — 车型档案库主数据。
//
// 监听 HTTP（默认 :18059，仅内网；api BFF 透传 / admin BFF 直接调）。
// 详见 docs/architecture/server/02-api-contract.md §13 / 00-server-overview.md §6.z。
//
// 环境变量：
//
//	GOMOB_CATALOG_HTTP_ADDR  监听地址（默认 :18059）
//	GOMOB_DB_DSN             PG 连接串
//	GOMOB_REDIS_ADDR         Redis 地址（默认 127.0.0.1:6379；空 = 不缓存）
//	GOMOB_CATALOG_CACHE_TTL  缓存 TTL（默认 10m）
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

	"io.gomob/server/internal/catalog"
	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("catalog")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	var rdb *redis.Client
	if redisAddr := envOr("GOMOB_REDIS_ADDR", "127.0.0.1:6379"); redisAddr != "" {
		rdb = redis.NewClient(&redis.Options{Addr: redisAddr})
		pingCtx, pingCancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer pingCancel()
		if err := rdb.Ping(pingCtx).Err(); err != nil {
			log.Warn("Redis 不可达，缓存禁用", "err", err)
			_ = rdb.Close()
			rdb = nil
		}
	}

	cfg := catalog.DefaultConfig()
	if v := os.Getenv("GOMOB_CATALOG_CACHE_TTL"); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			cfg.CacheTTL = d
		}
	}

	auditRec := audit.NewPG(pool)
	h := catalog.NewHandler(cfg, pool, rdb, auditRec)

	addr := envOr("GOMOB_CATALOG_HTTP_ADDR", ":18059")
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
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	go func() {
		log.Info("HTTP 监听", "addr", addr, "cache_ttl", cfg.CacheTTL, "redis_enabled", rdb != nil)
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
