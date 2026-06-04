// gomob-laserworker — 双单元激光（LIDAR-PTZ）车辆外廓扫描服务（M8'）。
//
// 请求驱动的 HTTP 服务（非轮询 worker）：App 经 gateway 反代 POST /v1/scans/laser 起扫描，
// 本服务直接：探活 .101/.102:4000 → cgo 调已 byte 验证的 C++ 管线采集+ICP/site 融合（流式点经
// NATS laser.points 推 ws 预览）→ 三朵 PCD 落 MinIO → 写库 → 发 NATS scan.fusion_done(kind:laser)。
// 与 .101/.102 同网段（dev --network host）。形态对照 cmd/asset + cmd/fusionworker。
//
// ⚠️ 真实采集须以 `-tags laser_cgo` 构建（链 liblidar_scan.a；先跑 scripts/laser-cgo-setup.sh）。
// 不带标签时 LiveScan 为 stub（返错），仅供 monorepo 默认编译通过。
//
// 环境变量：
//
//	GOMOB_LASERWORKER_HTTP_ADDR  监听地址（默认 :18087）
//	GOMOB_LASER_UNIT_A_IP        默认 192.168.9.101
//	GOMOB_LASER_UNIT_B_IP        默认 192.168.9.102
//	GOMOB_LASER_ALIGN            默认 icp（icp|none|site）
//	GOMOB_LASER_KEEP_RATIO       融合降采样保留比，默认 1.0
//	GOMOB_NATS_URL               NATS 地址（配则发 laser.points/status + scan.fusion_done）
//	GOMOB_DB_DSN / GOMOB_MINIO_* 复用平台约定
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

	"io.gomob/server/internal/laser"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/pubsub"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("laserworker")
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	// NATS（可选）：实时推点 + 完成事件。
	var publisher laser.Publisher
	if natsURL := os.Getenv("GOMOB_NATS_URL"); natsURL != "" {
		p, err := pubsub.NewNATS(natsURL)
		if err != nil {
			log.Error("NATS 连接失败", "err", err)
			os.Exit(1)
		}
		defer p.Close()
		publisher = p
	} else {
		log.Warn("GOMOB_NATS_URL 未配置：无实时推点 / 无完成事件（端侧仅能轮询 GET 状态）")
	}

	// MinIO 点云存储。
	clouds, err := laser.NewMinIOCloudStore(laser.MinIOConfig{
		Endpoint:  envOr("GOMOB_MINIO_ENDPOINT", "127.0.0.1:9000"),
		AccessKey: envOr("GOMOB_MINIO_ACCESS_KEY", "gomob"),
		SecretKey: envOr("GOMOB_MINIO_SECRET_KEY", "gomob_dev_minio"),
		UseSSL:    os.Getenv("GOMOB_MINIO_USE_SSL") == "true",
		Bucket:    envOr("GOMOB_MINIO_BUCKET", "gomob-assets"),
	})
	if err != nil {
		log.Error("MinIO 初始化失败", "err", err)
		os.Exit(1)
	}

	jobs := repo.NewLaserScanRepo(pool)
	runner := laser.NewRunner(jobs, clouds, publisher, logger.New("laser.runner"))

	cfg := laser.Config{
		DefaultUnitAIP: envOr("GOMOB_LASER_UNIT_A_IP", "192.168.9.101"),
		DefaultUnitBIP: envOr("GOMOB_LASER_UNIT_B_IP", "192.168.9.102"),
		DefaultAlign:   envOr("GOMOB_LASER_ALIGN", "icp"),
		DefaultKeep:    float32(parseFloat("GOMOB_LASER_KEEP_RATIO", 1.0)),
	}
	h := laser.NewHandler(cfg, jobs, runner, publisher, log)
	h.SetCloudReader(clouds) // 同一 MinIO 实例兼作 PCD 下载读取器

	mux := http.NewServeMux()
	h.Mount(mux)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		_, _ = w.Write([]byte(`{"ok":true}`))
	})

	addr := envOr("GOMOB_LASERWORKER_HTTP_ADDR", ":18087")
	srv := &http.Server{Addr: addr, Handler: mux, ReadHeaderTimeout: 5 * time.Second}

	go func() {
		<-ctx.Done()
		shutCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutCtx)
	}()

	log.Info("laserworker 启动", "addr", addr, "unitA", cfg.DefaultUnitAIP, "unitB", cfg.DefaultUnitBIP,
		"align", cfg.DefaultAlign, "nats", publisher != nil)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Error("HTTP 服务退出", "err", err)
		os.Exit(1)
	}
	log.Info("laserworker 退出")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func parseFloat(key string, def float64) float64 {
	if v := os.Getenv(key); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil {
			return f
		}
	}
	return def
}
