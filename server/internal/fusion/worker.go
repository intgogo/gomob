package fusion

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/pubsub"
	"io.gomob/server/pkg/repo"
)

// TopicFusionDone 融合完成事件;端侧 gallery 订阅它拉 GLB 回看。
const TopicFusionDone = "scan.fusion_done"

// FusionDoneEvent scan.fusion_done 载荷。
type FusionDoneEvent struct {
	JobID           int64  `json:"job_id"`
	SessionKey      string `json:"session_key"`
	InspectionID    *int64 `json:"inspection_id,omitempty"`
	ResultObjectKey string `json:"result_object_key"`
	Vertices        int    `json:"vertices"`
	Triangles       int    `json:"triangles"`
	FrameCount      int    `json:"frame_count"`
}

type Worker struct {
	cfg       Config
	jobs      *repo.ScanFusionRepo
	client    *ServiceClient
	publisher pubsub.Publisher
	log       *slog.Logger
}

func NewWorker(pool *pgxpool.Pool, publisher pubsub.Publisher, cfg Config) (*Worker, error) {
	cfg = cfg.normalized()
	client, err := NewServiceClient(cfg)
	if err != nil {
		return nil, err
	}
	return &Worker{
		cfg:       cfg,
		jobs:      repo.NewScanFusionRepo(pool),
		client:    client,
		publisher: publisher,
		log:       logger.New("fusion.worker"),
	}, nil
}

func (w *Worker) Start(ctx context.Context) {
	ticker := time.NewTicker(w.cfg.PollInterval)
	defer ticker.Stop()
	for {
		w.ProcessOne(ctx)
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// ProcessOne 领一条任务并跑完整链路;无任务静默返回。导出便于 harness 单步驱动。
func (w *Worker) ProcessOne(ctx context.Context) {
	job, err := w.jobs.ClaimNext(ctx, w.cfg.MaxAttempts)
	if err != nil {
		if !errors.Is(err, repo.ErrNotFound) {
			w.log.Warn("领取融合任务失败", "err", err)
		}
		return
	}
	bundle, err := w.client.ReadBundle(ctx, job.InputObjectKey)
	if err != nil {
		w.fail(ctx, job, "读取 bundle 失败: "+err.Error())
		return
	}
	res, err := w.client.Fuse(ctx, bundle)
	if err != nil {
		w.fail(ctx, job, "融合失败: "+err.Error())
		return
	}
	resultKey := fmt.Sprintf("scan_fusion/%s/result.glb", job.SessionKey)
	if err := w.client.PutResult(ctx, resultKey, res.GLB); err != nil {
		w.fail(ctx, job, "GLB 存储失败: "+err.Error())
		return
	}
	stats, _ := json.Marshal(map[string]any{
		"frame_count": res.FrameCount,
		"fusion_ms":   res.FusionMs,
		"glb_bytes":   len(res.GLB),
	})
	done, err := w.jobs.Complete(ctx, job.ID, repo.ScanFusionCompletion{
		ResultObjectKey: resultKey,
		Vertices:        res.Vertices,
		Triangles:       res.Triangles,
		Stats:           stats,
	})
	if err != nil {
		w.log.Warn("保存融合结果失败", "err", err, "job_id", job.ID)
		return
	}
	w.publish(ctx, done)
	w.log.Info("融合完成",
		"job_id", done.ID, "session", done.SessionKey,
		"vertices", res.Vertices, "triangles", res.Triangles,
		"frames", res.FrameCount, "fusion_ms", res.FusionMs, "result", resultKey)
}

func (w *Worker) fail(ctx context.Context, job *repo.ScanFusionJob, reason string) {
	if _, err := w.jobs.Fail(ctx, job.ID, reason, w.cfg.RetryAfter, w.cfg.MaxAttempts); err != nil {
		w.log.Warn("标记融合失败失败", "err", err, "job_id", job.ID)
	}
	w.log.Warn("融合失败", "job_id", job.ID, "session", job.SessionKey, "reason", reason)
}

func (w *Worker) publish(ctx context.Context, job *repo.ScanFusionJob) {
	if w.publisher == nil || job == nil {
		return
	}
	resultKey := ""
	if job.ResultObjectKey != nil {
		resultKey = *job.ResultObjectKey
	}
	vertices, triangles := 0, 0
	if job.Vertices != nil {
		vertices = *job.Vertices
	}
	if job.Triangles != nil {
		triangles = *job.Triangles
	}
	evt := FusionDoneEvent{
		JobID:           job.ID,
		SessionKey:      job.SessionKey,
		InspectionID:    job.InspectionID,
		ResultObjectKey: resultKey,
		Vertices:        vertices,
		Triangles:       triangles,
		FrameCount:      job.FrameCount,
	}
	if err := w.publisher.Publish(ctx, TopicFusionDone, evt); err != nil {
		w.log.Warn("发布 scan.fusion_done 失败", "err", err, "job_id", job.ID)
	}
}
