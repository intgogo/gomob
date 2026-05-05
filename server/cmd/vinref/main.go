// gomob-vinref — 车驾号字形参考库 HTTP 服务（详见 02-api-contract.md §13.4 / 00-server-overview.md §6.z）。
//
// 通路：
//
//	App ──▶ gateway ──▶ api（BFF 反代 /v1/catalog/vehicles/{vmid}/vin-refs/...）──▶ vin-ref
//	admin BFF ──▶ vin-ref（写：/admin/v1/catalog/vehicles/{vmid}/vin-refs/...）
//	cv-engine ──▶ vin-ref（按 vehicle_model_id + character 拉对照样本）
//
// 环境变量：
//
//	GOMOB_VINREF_HTTP_ADDR  HTTP 监听地址（默认 :18058）
//	GOMOB_DB_DSN            PG 连接串
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"io.gomob/server/internal/vinref"
	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("vinref")

	rootCtx, cancel := context.WithCancel(context.Background())
	defer cancel()

	pool, err := repo.NewPool(rootCtx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	auditRec := audit.NewPG(pool)
	h := vinref.NewHandler(pool, auditRec)

	addr := envOr("GOMOB_VINREF_HTTP_ADDR", ":18058")
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
