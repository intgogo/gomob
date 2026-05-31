// gomob-signaling — WebSocket 消息中心 + WebRTC 视频信令。
//
// 详见 docs/architecture/server/00-server-overview.md §7 / §8、02-api-contract.md §7-§8。
//
// 环境变量：
//
//	GOMOB_SIGNALING_HTTP_ADDR    监听地址（默认 :18084）
//	GOMOB_DB_DSN                 PG 连接串
//	GOMOB_PENDING_CALL_TTL       离线 invite 过期时间（默认 60s）
//	GOMOB_PENDING_CALL_SWEEP     pending_calls 清扫周期（默认 30s）
//	GOMOB_JWT_SECRET             JWT 校验密钥（同 auth）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"io.gomob/server/internal/signaling"
	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/pubsub"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("signaling")

	rootCtx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := repo.NewPool(rootCtx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	hub := signaling.NewHub()
	auditRec := audit.NewPG(pool)
	router := signaling.NewRouter(pool, hub, auditRec, parseDuration("GOMOB_PENDING_CALL_TTL", 60*time.Second))
	handler := signaling.NewHandler(router, hub)

	// scan.fusion_done → ws 桥接(M3.15):fusionworker 完成融合后发 NATS 事件,这里按 owner_user_id
	// 推给该用户在线 ws 连接。NATS 不可用只降级(关实时推送,端侧可轮询),不拖垮消息/通话核心。
	if natsURL := strings.TrimSpace(os.Getenv("GOMOB_NATS_URL")); natsURL != "" {
		if pub, err := pubsub.NewNATS(natsURL); err != nil {
			log.Error("NATS 连接失败,scan.fusion_done 实时推送关闭", "err", err)
		} else {
			defer pub.Close()
			bridge, err := signaling.StartFusionBridge(pub.Conn(), hub, log)
			if err != nil {
				log.Error("scan.fusion_done 桥接启动失败", "err", err)
			} else {
				defer bridge.Close()
			}
		}
	} else {
		log.Info("GOMOB_NATS_URL 未配置,scan.fusion_done 实时推送关闭(端侧可轮询)")
	}

	// 后台清扫过期 invite
	sweepCtx, sweepCancel := context.WithCancel(rootCtx)
	defer sweepCancel()
	go router.SweepLoop(sweepCtx, parseDuration("GOMOB_PENDING_CALL_SWEEP", 30*time.Second))

	mux := http.NewServeMux()
	handler.Mount(mux)
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

	addr := envOr("GOMOB_SIGNALING_HTTP_ADDR", ":18084")
	srv := &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		// ws 握手后是长连接；写超时不能太短。我们的写超时在 Conn.writeLoop 内每帧用 SetWriteDeadline 单独控制。
		IdleTimeout:    0, // 长连接交给应用层心跳兜底
		MaxHeaderBytes: 1 << 16,
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
