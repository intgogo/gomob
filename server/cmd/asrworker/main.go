// gomob-asrworker — 语音消息转文字后台任务。
//
// 环境变量：
//
//	GOMOB_ASR_URL            ASR 推理服务地址，必填，例如 http://127.0.0.1:18091
//	GOMOB_ASR_ENGINE         默认 fireredasr2
//	GOMOB_ASR_MODEL          默认 FireRedASR2-AED
//	GOMOB_ASR_LANGUAGE       默认 zh
//	GOMOB_ASR_POLL_INTERVAL  默认 2s
//	GOMOB_ASR_RETRY_AFTER    默认 30s
//	GOMOB_ASR_MAX_ATTEMPTS   默认 3
package main

import (
	"context"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"io.gomob/server/internal/asr"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

func main() {
	log := logger.New("asrworker")
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	serviceURL := strings.TrimSpace(os.Getenv("GOMOB_ASR_URL"))
	if serviceURL == "" {
		log.Error("GOMOB_ASR_URL 未配置，语音转写 worker 不启动")
		os.Exit(1)
	}

	pool, err := repo.NewPool(ctx)
	if err != nil {
		log.Error("PG 连接失败", "err", err)
		os.Exit(1)
	}
	defer pool.Close()

	cfg := asr.Config{
		ServiceURL:     serviceURL,
		Engine:         envOr("GOMOB_ASR_ENGINE", "fireredasr2"),
		Model:          envOr("GOMOB_ASR_MODEL", "FireRedASR2-AED"),
		Language:       envOr("GOMOB_ASR_LANGUAGE", "zh"),
		MinIOEndpoint:  envOr("GOMOB_MINIO_ENDPOINT", "127.0.0.1:9000"),
		MinIOAccessKey: envOr("GOMOB_MINIO_ACCESS_KEY", "gomob"),
		MinIOSecretKey: envOr("GOMOB_MINIO_SECRET_KEY", "gomob_dev_minio"),
		MinIOUseSSL:    os.Getenv("GOMOB_MINIO_USE_SSL") == "true",
		Bucket:         envOr("GOMOB_MINIO_BUCKET", "gomob-assets"),
		PollInterval:   parseDuration("GOMOB_ASR_POLL_INTERVAL", 2*time.Second),
		RetryAfter:     parseDuration("GOMOB_ASR_RETRY_AFTER", 30*time.Second),
		MaxAttempts:    parseInt("GOMOB_ASR_MAX_ATTEMPTS", 3),
		MaxAudioBytes:  parseInt64("GOMOB_ASR_MAX_AUDIO_BYTES", 50*1024*1024),
	}
	worker, err := asr.NewWorker(pool, nil, cfg)
	if err != nil {
		log.Error("语音转写 worker 初始化失败", "err", err)
		os.Exit(1)
	}
	log.Info("语音转写 worker 启动",
		"url", cfg.ServiceURL,
		"engine", cfg.Engine,
		"model", cfg.Model,
		"language", cfg.Language,
	)
	worker.Start(ctx)
	log.Info("语音转写 worker 退出")
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

func parseInt64(key string, def int64) int64 {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.ParseInt(v, 10, 64); err == nil {
			return n
		}
	}
	return def
}
