// gomob-modelregistry — AI 模型版本元数据 / 灰度 / 回滚。
//
// 详见 docs/architecture/server/00-server-overview.md §6.y。
//
// 监听 HTTP（默认 :18057，仅内网；admin BFF / worker / cv-engine 调）。
// 状态变更通过 NATS 发 model.version.activated。
//
// 环境变量：
//
//	GOMOB_MODELREGISTRY_HTTP_ADDR  监听地址（默认 :18057）
//	GOMOB_DB_DSN                   PG 连接串
//	GOMOB_NATS_URL                 NATS 地址（默认 nats://127.0.0.1:4222；空 = 不发广播）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"io.gomob/server/internal/modelregistry"
	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/pubsub"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("modelregistry")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	// NATS publisher（不可达时降级为 nil；handler 会跳过广播）
	var bus pubsub.Publisher
	if natsURL := envOr("GOMOB_NATS_URL", "nats://127.0.0.1:4222"); natsURL != "" {
		np, err := pubsub.NewNATS(natsURL)
		if err != nil {
			log.Warn("NATS 不可达，禁用广播", "url", natsURL, "err", err)
		} else {
			bus = np
			defer np.Close()
		}
	}

	auditRec := audit.NewPG(pool)
	h := modelregistry.NewHandler(pool, bus, auditRec)

	addr := envOr("GOMOB_MODELREGISTRY_HTTP_ADDR", ":18057")
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
		log.Info("HTTP 监听", "addr", addr, "nats_enabled", bus != nil)
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
