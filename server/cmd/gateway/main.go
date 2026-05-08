// gomob-gateway — 反向代理 + 限流 + 认证 token 校验 + wss 升级。
//
// 详见 docs/architecture/server/00-server-overview.md §8 / §6.app。
//
// 环境变量：
//
//	GOMOB_GATEWAY_ADDR     监听地址（默认 :18808）
//	GOMOB_DISCOVERY_ADDR   UDP 服务发现监听地址（默认 :18809；空字符串禁用）
//	GOMOB_DISCOVERY_NAME   服务发现展示名称（默认 gomob-gateway）
//	GOMOB_REDIS_ADDR       Redis 地址（默认 127.0.0.1:6379；空字符串 = 禁用限流）
//	GOMOB_RATE_LIMIT       每分钟每用户上限（默认 1000）
//	GOMOB_JWT_SECRET       JWT 密钥（与 auth 服务共享）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"

	"io.gomob/server/internal/gateway"
	"io.gomob/server/pkg/logger"
)

func main() {
	log := logger.New("gateway")

	addr := envOr("GOMOB_GATEWAY_ADDR", ":18808")
	discoveryAddr := envOrAllowEmpty("GOMOB_DISCOVERY_ADDR", gateway.DefaultDiscoveryAddr)
	discoveryName := envOr("GOMOB_DISCOVERY_NAME", "gomob-gateway")

	// Redis（限流后端）— 不可达时降级为不限流
	var rdb *redis.Client
	if redisAddr := envOrAllowEmpty("GOMOB_REDIS_ADDR", "127.0.0.1:6379"); redisAddr != "" {
		rdb = redis.NewClient(&redis.Options{Addr: redisAddr})
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := rdb.Ping(ctx).Err(); err != nil {
			log.Warn("Redis 不可达，限流降级为不限流", "addr", redisAddr, "err", err)
			rdb = nil
		}
	}

	limit := envInt("GOMOB_RATE_LIMIT", 1000)
	var limiter *gateway.Limiter
	if rdb != nil {
		limiter = gateway.NewLimiter(rdb, time.Minute, limit)
	}

	handler, err := gateway.NewServer(gateway.DefaultRoutes(), limiter)
	if err != nil {
		log.Error("路由初始化失败", "err", err)
		os.Exit(1)
	}

	srv := &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	discoveryCtx, stopDiscovery := context.WithCancel(context.Background())
	defer stopDiscovery()
	if err := gateway.StartDiscoveryResponder(discoveryCtx, discoveryAddr, addr, discoveryName, log); err != nil {
		log.Warn("UDP 服务发现不可用", "addr", discoveryAddr, "err", err)
	}

	go func() {
		log.Info("HTTP 监听", "addr", addr, "rate_limit_per_min", limit, "redis_enabled", rdb != nil)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("HTTP 异常退出", "err", err)
			os.Exit(1)
		}
	}()

	<-stop
	log.Info("收到退出信号，开始 graceful shutdown")
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
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

func envOrAllowEmpty(key, def string) string {
	if v, ok := os.LookupEnv(key); ok {
		return v
	}
	return def
}

func envInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return def
}
