// gomob-llmgateway — LLM 大模型网关（DeepSeek 起步）。
//
// 详见 docs/architecture/server/00-server-overview.md §6.v / 02-api-contract.md §15。
//
// Provider 选择：
//   - GOMOB_DEEPSEEK_API_KEY 设置 → 启用 DeepSeek（默认 provider）
//   - 未设置 → mock provider（默认）；模板里指定的 deepseek 也会 fallback 到 mock
//
// 环境变量：
//
//	GOMOB_LLM_HTTP_ADDR        监听地址（默认 :18811）
//	GOMOB_DB_DSN               PG 连接串
//	GOMOB_DEEPSEEK_API_KEY     DeepSeek API key（空 → 用 mock）
//	GOMOB_DEEPSEEK_ENDPOINT    覆盖默认 endpoint（一般不动）
//	GOMOB_DEEPSEEK_MODEL       覆盖默认 model（默认 deepseek-chat）
//	GOMOB_LLM_TIMEOUT          provider 调用超时（默认 60s）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"io.gomob/server/internal/llmgateway"
	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("llmgateway")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	// 构造 provider registry
	mock := llmgateway.NewMockProvider()
	var registry *llmgateway.Registry
	if key := os.Getenv("GOMOB_DEEPSEEK_API_KEY"); key != "" {
		ds := llmgateway.NewDeepSeekProvider(llmgateway.DeepSeekConfig{
			APIKey:   key,
			Endpoint: os.Getenv("GOMOB_DEEPSEEK_ENDPOINT"),
			Model:    os.Getenv("GOMOB_DEEPSEEK_MODEL"),
			Timeout:  parseDuration("GOMOB_LLM_TIMEOUT", 60*time.Second),
		})
		registry = llmgateway.NewRegistry(ds, mock) // DeepSeek 默认；mock 备用
		log.Info("provider", "default", "deepseek", "fallback", "mock")
	} else {
		registry = llmgateway.NewRegistry(mock)
		log.Warn("GOMOB_DEEPSEEK_API_KEY 未设置，仅启用 mock provider")
	}

	auditRec := audit.NewPG(pool)
	h := llmgateway.NewHandler(pool, registry, auditRec)

	addr := envOr("GOMOB_LLM_HTTP_ADDR", ":18811")
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
		// 流式响应可能很长；写超时设大一点。客户端断开时上游 ctx 取消即可。
		WriteTimeout:   10 * time.Minute,
		IdleTimeout:    2 * time.Minute,
		MaxHeaderBytes: 1 << 16,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	go func() {
		log.Info("HTTP 监听", "addr", addr, "providers", registry.Names())
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

func parseDuration(key string, def time.Duration) time.Duration {
	if v := os.Getenv(key); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			return d
		}
	}
	return def
}
