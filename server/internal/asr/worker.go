// Package asr 把消息语音转写任务桥接到自托管 ASR 推理服务。
package asr

import (
	"context"
	"errors"
	"log/slog"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

type TranscriptNotifier interface {
	NotifyTranscriptUpdate(ctx context.Context, message *repo.Message) (int, error)
}

type Config struct {
	ServiceURL     string
	Engine         string
	Model          string
	Language       string
	MinIOEndpoint  string
	MinIOAccessKey string
	MinIOSecretKey string
	MinIOUseSSL    bool
	Bucket         string
	PollInterval   time.Duration
	RetryAfter     time.Duration
	MaxAttempts    int
	MaxAudioBytes  int64
}

func (c Config) normalized() Config {
	c.ServiceURL = strings.TrimRight(strings.TrimSpace(c.ServiceURL), "/")
	if c.Engine == "" {
		c.Engine = "fireredasr2"
	}
	if c.Model == "" {
		c.Model = "FireRedASR2-AED"
	}
	if c.Language == "" {
		c.Language = "zh"
	}
	if c.MinIOEndpoint == "" {
		c.MinIOEndpoint = "127.0.0.1:9000"
	}
	if c.MinIOAccessKey == "" {
		c.MinIOAccessKey = "gomob"
	}
	if c.MinIOSecretKey == "" {
		c.MinIOSecretKey = "gomob_dev_minio"
	}
	if c.Bucket == "" {
		c.Bucket = "gomob-assets"
	}
	if c.PollInterval <= 0 {
		c.PollInterval = 2 * time.Second
	}
	if c.RetryAfter <= 0 {
		c.RetryAfter = 30 * time.Second
	}
	if c.MaxAttempts <= 0 {
		c.MaxAttempts = 3
	}
	if c.MaxAudioBytes <= 0 {
		c.MaxAudioBytes = 50 * 1024 * 1024
	}
	return c
}

type Worker struct {
	cfg         Config
	transcripts *repo.TranscriptRepo
	assets      *repo.AssetRepo
	service     *ServiceClient
	notifier    TranscriptNotifier
	log         *slog.Logger
}

func NewWorker(pool *pgxpool.Pool, notifier TranscriptNotifier, cfg Config) (*Worker, error) {
	cfg = cfg.normalized()
	service, err := NewServiceClient(cfg)
	if err != nil {
		return nil, err
	}
	return &Worker{
		cfg: cfg,
		transcripts: repo.NewTranscriptRepo(pool, repo.TranscriptConfig{
			Engine:   cfg.Engine,
			Model:    cfg.Model,
			Language: cfg.Language,
		}),
		assets:   repo.NewAssetRepo(pool),
		service:  service,
		notifier: notifier,
		log:      logger.New("asr.worker"),
	}, nil
}

func (w *Worker) Start(ctx context.Context) {
	ticker := time.NewTicker(w.cfg.PollInterval)
	defer ticker.Stop()
	for {
		w.processOne(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

func (w *Worker) processOne(ctx context.Context) {
	tr, err := w.transcripts.ClaimNext(ctx, w.cfg.MaxAttempts)
	if err != nil {
		if !errors.Is(err, repo.ErrNotFound) {
			w.log.Warn("领取语音转写任务失败", "err", err)
		}
		return
	}
	asset, err := w.assets.FindAssetByID(ctx, tr.AssetID)
	if err != nil {
		w.fail(ctx, tr.ID, "语音资产不存在或不可读: "+err.Error())
		return
	}
	result, err := w.service.TranscribeAsset(ctx, asset)
	if err != nil {
		w.fail(ctx, tr.ID, "ASR 推理失败: "+err.Error())
		return
	}
	msg, err := w.transcripts.Complete(ctx, tr.ID, repo.TranscriptCompletion{
		Text:           result.Text,
		NormalizedText: result.NormalizedText,
		Segments:       result.Segments,
		Confidence:     result.Confidence,
		Engine:         result.Engine,
		Model:          result.Model,
		Language:       result.Language,
	})
	if err != nil {
		w.log.Warn("保存语音转写结果失败", "err", err, "transcript_id", tr.ID)
		return
	}
	w.notify(ctx, msg)
	w.log.Info("语音转写完成",
		"message_id", tr.MessageID,
		"asset_id", tr.AssetID,
		"engine", result.Engine,
		"model", result.Model,
	)
}

func (w *Worker) fail(ctx context.Context, transcriptID int64, reason string) {
	msg, err := w.transcripts.Fail(ctx, transcriptID, reason, w.cfg.RetryAfter, w.cfg.MaxAttempts)
	if err != nil {
		w.log.Warn("标记语音转写失败失败", "err", err, "transcript_id", transcriptID)
		return
	}
	w.notify(ctx, msg)
	w.log.Warn("语音转写失败", "transcript_id", transcriptID, "reason", reason)
}

func (w *Worker) notify(ctx context.Context, msg *repo.Message) {
	if w.notifier == nil || msg == nil {
		return
	}
	if _, err := w.notifier.NotifyTranscriptUpdate(ctx, msg); err != nil {
		w.log.Warn("推送语音转写状态失败", "err", err, "message_id", msg.ID)
	}
}
