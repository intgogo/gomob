// gomob-fusionworker — 多视角 RGBD 云端融合后台任务。
//
// 从 DB 队列 scan_fusion_jobs 领任务 → 从 MinIO 拉 RgbdShot bundle → POST fusion_service /fuse
// → GLB 存回 MinIO → 写 DB + 发 NATS scan.fusion_done。形态对标 asrworker。
//
// 环境变量：
//
//	GOMOB_FUSION_URL              fusion_service 地址，必填，例 http://127.0.0.1:18092
//	GOMOB_NATS_URL                NATS 地址，可选；配则完成发 scan.fusion_done
//	GOMOB_FUSION_POLL_INTERVAL    默认 2s
//	GOMOB_FUSION_RETRY_AFTER      默认 30s
//	GOMOB_FUSION_MAX_ATTEMPTS     默认 3
//	GOMOB_FUSION_CONF_THRESHOLD   conf 阈值预掩码，默认 80
//	GOMOB_FUSION_ENABLE_CONF      默认 true
//	GOMOB_FUSION_VOXEL_MM         TSDF voxel，默认 6
//	GOMOB_FUSION_TEXTURE          烘焙 UV-atlas 纹理(default true;false 则仅顶点色 GLB)
//	GOMOB_FUSION_TEX_SIZE         纹理分辨率,默认 1024
//	GOMOB_MINIO_* / GOMOB_DB_DSN  复用平台约定
package main

import (
	"context"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"io.gomob/server/internal/fusion"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/pubsub"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("fusionworker")
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	serviceURL := strings.TrimSpace(os.Getenv("GOMOB_FUSION_URL"))
	if serviceURL == "" {
		log.Error("GOMOB_FUSION_URL 未配置，融合 worker 不启动")
		os.Exit(1)
	}

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	var publisher pubsub.Publisher
	if natsURL := strings.TrimSpace(os.Getenv("GOMOB_NATS_URL")); natsURL != "" {
		p, err := pubsub.NewNATS(natsURL)
		if err != nil {
			log.Error("NATS 连接失败", "err", err)
			os.Exit(1)
		}
		defer p.Close()
		publisher = p
	}

	cfg := fusion.Config{
		ServiceURL:       serviceURL,
		MinIOEndpoint:    envOr("GOMOB_MINIO_ENDPOINT", "127.0.0.1:9000"),
		MinIOAccessKey:   envOr("GOMOB_MINIO_ACCESS_KEY", "gomob"),
		MinIOSecretKey:   envOr("GOMOB_MINIO_SECRET_KEY", "gomob_dev_minio"),
		MinIOUseSSL:      os.Getenv("GOMOB_MINIO_USE_SSL") == "true",
		Bucket:           envOr("GOMOB_MINIO_BUCKET", "gomob-assets"),
		PollInterval:     parseDuration("GOMOB_FUSION_POLL_INTERVAL", 2*time.Second),
		RetryAfter:       parseDuration("GOMOB_FUSION_RETRY_AFTER", 30*time.Second),
		MaxAttempts:      parseInt("GOMOB_FUSION_MAX_ATTEMPTS", 3),
		ConfThreshold:    parseInt("GOMOB_FUSION_CONF_THRESHOLD", 80),
		EnableConfidence: envOr("GOMOB_FUSION_ENABLE_CONF", "true") != "false",
		VoxelSizeMm:      parseFloat("GOMOB_FUSION_VOXEL_MM", 6.0),
		Texture:          envOr("GOMOB_FUSION_TEXTURE", "true") != "false",
		TexSize:          parseInt("GOMOB_FUSION_TEX_SIZE", 1024),
	}
	worker, err := fusion.NewWorker(pool, publisher, cfg)
	if err != nil {
		log.Error("融合 worker 初始化失败", "err", err)
		os.Exit(1)
	}
	log.Info("融合 worker 启动", "url", cfg.ServiceURL, "conf_threshold", cfg.ConfThreshold,
		"enable_conf", cfg.EnableConfidence, "voxel_mm", cfg.VoxelSizeMm,
		"texture", cfg.Texture, "tex_size", cfg.TexSize, "nats", publisher != nil)
	worker.Start(ctx)
	log.Info("融合 worker 退出")
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

func parseInt(key string, def int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
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
