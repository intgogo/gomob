// gomob-llmgateway — LLM 大模型网关（DeepSeek 起步）。
//
// 详见 docs/architecture/server/00-server-overview.md §6.v / 02-api-contract.md §15。
//
// Provider 选择：
//   - GOMOB_DEEPSEEK_API_KEY 设置 → 启用 DeepSeek（默认 provider）
//   - 未设置 → mock provider（默认）；模板里指定的 deepseek 也会 fallback 到 mock
//
// Failover 链（M-S11.7）：
//   - GOMOB_LLM_FALLBACK_CHAIN="deepseek,mock" 时把 default provider 包成 FallbackProvider
//   - 主 fail / 限流 / 鉴权失败 → 自动切下一个；流到一半失败不切（避免风格突变）
//   - 不设默认就是单 provider；与现有行为兼容
//
// 配额（M-S11.6）：按用户 / 按模板的日级预算（UTC 日切）。
//   - GOMOB_LLM_USER_DAILY_BUDGET=N 每用户每日 N 次（0 或不设 = 不限）
//   - GOMOB_LLM_TPL_DAILY_BUDGET=N 每模板每日 N 次（同上）
//   - GOMOB_REDIS_ADDR 设了才启用（INCR 计数）；无 redis = 自动放行
//   - 超限 → 40602
//
// 环境变量：
//
//	GOMOB_LLM_HTTP_ADDR             监听地址（默认 :18811）
//	GOMOB_DB_DSN                    PG 连接串
//	GOMOB_DEEPSEEK_API_KEY          DeepSeek API key（空 → 用 mock）
//	GOMOB_DEEPSEEK_ENDPOINT         覆盖默认 endpoint（一般不动）
//	GOMOB_DEEPSEEK_MODEL            覆盖默认 model（默认 deepseek-chat）
//	GOMOB_LLM_TIMEOUT               provider 调用超时（默认 60s）
//	GOMOB_LLM_FALLBACK_CHAIN        逗号分隔 provider 名链，例 "deepseek,mock"（默认空）
//	GOMOB_REDIS_ADDR                Redis 地址（默认 127.0.0.1:6379；用于配额计数）
//	GOMOB_LLM_USER_DAILY_BUDGET     每用户日预算（默认 0=不限）
//	GOMOB_LLM_TPL_DAILY_BUDGET      每模板日预算（默认 0=不限）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"

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

	// M-S11.7 failover 链：把 default provider 替换为 FallbackProvider 包装链
	if chainEnv := os.Getenv("GOMOB_LLM_FALLBACK_CHAIN"); chainEnv != "" {
		names := splitCSV(chainEnv)
		fp := llmgateway.BuildFallbackChain(registry, names, log)
		if fp != nil {
			// 用 fp 替换 fallback；保留 providers map 让 Pick(name) 单走仍可单 provider
			registry = llmgateway.NewRegistryWithFallback(fp, registry.AllProviders()...)
			log.Info("LLM fallback 链已启用", "chain", names, "fallback_name", fp.Name())
		} else {
			log.Warn("GOMOB_LLM_FALLBACK_CHAIN 配置无效（找不到任何匹配 provider），保持原 registry", "chain", names)
		}
	}

	auditRec := audit.NewPG(pool)
	h := llmgateway.NewHandler(pool, registry, auditRec)

	// M-S11.6 配额检查器（用 Redis 计数）；任一 budget>0 才算启用
	userBudget := envAtoi("GOMOB_LLM_USER_DAILY_BUDGET", 0)
	tplBudget := envAtoi("GOMOB_LLM_TPL_DAILY_BUDGET", 0)
	if userBudget > 0 || tplBudget > 0 {
		rdb := redis.NewClient(&redis.Options{
			Addr:        envOr("GOMOB_REDIS_ADDR", "127.0.0.1:6379"),
			Password:    os.Getenv("GOMOB_REDIS_PASSWORD"),
			DialTimeout: 500 * time.Millisecond, // 快速失败：调用时短超时，QuotaChecker 内部已经"redis 错按放行"降级
			MaxRetries:  -1,                     // 不重试；调用层降级即可
		})
		quota := llmgateway.NewQuotaChecker(rdb, userBudget, tplBudget, log)
		h.SetQuota(quota)
		log.Info("LLM 配额已启用",
			"user_budget", userBudget, "tpl_budget", tplBudget,
			"redis_addr", envOr("GOMOB_REDIS_ADDR", "127.0.0.1:6379"))
	}

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

func envAtoi(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}

func splitCSV(s string) []string {
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}
