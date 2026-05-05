// gomob-worker —— 异步任务工人（M-S5.3）。
//
// 当前职责：消费 NATS inspection.scan_completed 事件 →
// 调 cv-engine 厂家库比对 → 写 inspection.preliminary_verdict +
// 推 inspection.preliminary_done。
//
// 后续：缩略图生成 / PDF 渲染 / 视频转码（其它 NATS 主题）。
//
// 环境变量：
//
//	GOMOB_WORKER_HEALTH_ADDR   /healthz 监听（默认 :18085；纯探活，无业务接口）
//	GOMOB_DB_DSN               PG 连接串
//	GOMOB_NATS_URL             默认 nats://127.0.0.1:4222
//	GOMOB_CVENGINE_TARGET      默认 http://127.0.0.1:18810
//	GOMOB_MINIO_ENDPOINT/...   MinIO 配置（同 asset / shape-ref）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/nats-io/nats.go"

	"io.gomob/server/internal/worker"
	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/hmacauth"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/pubsub"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("worker")

	rootCtx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := repo.NewPool(rootCtx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	natsURL := envOr("GOMOB_NATS_URL", "nats://127.0.0.1:4222")
	nc, err := nats.Connect(natsURL,
		nats.Timeout(5*time.Second),
		nats.ReconnectWait(2*time.Second),
		nats.MaxReconnects(-1),
	)
	if err != nil {
		log.Error("NATS 连接失败", "err", err, "url", natsURL)
		os.Exit(1)
	}
	defer nc.Close()

	pub := pubsub.NewNATSPublisher(nc)

	// HTTP 客户端：若设了 GOMOB_HMAC_SECRET，自动给 cvengine 调用加签（M-S10.2c）。
	hmacSecret := os.Getenv("GOMOB_HMAC_SECRET")
	cvHTTP := &http.Client{
		Timeout:   30 * time.Second,
		Transport: hmacauth.NewSigningTransport(http.DefaultTransport, hmacSecret),
	}
	if hmacSecret != "" {
		log.Info("HMAC 客户端签名已启用（worker → cv-engine）")
	}

	w, err := worker.New(worker.Config{
		NATSConn:        nc,
		Pool:            pool,
		CVEngineTarget:  envOr("GOMOB_CVENGINE_TARGET", "http://127.0.0.1:18810"),
		HTTPClient:      cvHTTP,
		Audit:           audit.NewPG(pool),
		Publisher:       pub,
		MinIOEndpoint:   envOr("GOMOB_MINIO_ENDPOINT", "127.0.0.1:9000"),
		MinIOAccessKey:  envOr("GOMOB_MINIO_ACCESS_KEY", "gomob"),
		MinIOSecretKey:  envOr("GOMOB_MINIO_SECRET_KEY", "gomob_dev_minio"),
		MinIOUseSSL:     os.Getenv("GOMOB_MINIO_USE_SSL") == "true",
		Bucket:          envOr("GOMOB_MINIO_BUCKET", "gomob-assets"),
		Log:             log,
	})
	if err != nil {
		log.Error("worker 初始化失败", "err", err)
		os.Exit(1)
	}

	addr := envOr("GOMOB_WORKER_HEALTH_ADDR", ":18085")
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Write([]byte(`{"ok":true}`))
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		c, c2 := context.WithTimeout(r.Context(), time.Second)
		defer c2()
		if err := pool.Ping(c); err != nil {
			http.Error(w, "db unreachable", http.StatusServiceUnavailable)
			return
		}
		if !nc.IsConnected() {
			http.Error(w, "nats disconnected", http.StatusServiceUnavailable)
			return
		}
		w.Write([]byte(`{"ready":true}`))
	})
	srv := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 10 * time.Second}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)

	go func() {
		log.Info("HTTP 监听", "addr", addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("HTTP 异常退出", "err", err)
			cancel()
		}
	}()
	go func() {
		if err := w.Run(rootCtx); err != nil {
			log.Error("worker.Run 退出", "err", err)
			cancel()
		}
	}()

	<-stop
	log.Info("收到退出信号")
	c, c2 := context.WithTimeout(context.Background(), 5*time.Second)
	defer c2()
	_ = srv.Shutdown(c)
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
