package laser

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"sync"

	"io.gomob/server/pkg/repo"
)

// runner.go = 一次激光扫描会话的服务端编排（请求驱动）：
//
//	[devctl SCAN_START 两单元] → scanFn(cgo lidar_scan_live, 流式点) → 边采集边：累积三朵云 +
//	转发给 Sink(ws 实时预览) → "fusing" 置 MarkFusing → 三朵 PCD 落对象存储 → repo.Complete →
//	发 NATS scan.fusion_done(kind:laser) → [devctl SCAN_STOP 两单元]
//
// 依赖全用接口注入，故可不带 laser_cgo 标签、不依赖真 MinIO/NATS/DB 做单测（见 runner_test.go）。

// TopicFusionDone 复用 RGBD 融合完成事件 topic；signaling.FusionBridge 原样转发给端侧 ws，
// 端侧按 payload.kind 区分 laser/rgbd。故意与 fusion.TopicFusionDone 同名、不跨包引用。
const TopicFusionDone = "scan.fusion_done"

// FusionDoneEvent = 激光扫描完成事件载荷（kind:"laser"）。
//
// 安全契约：本事件经 FusionBridge 原样转发给端侧 ws，只能承载客户端可见信息（PCD 对象键
// 端侧自行 presign 下载 + 点数统计 + 归属人自身 id）。严禁内部密钥/他人隐私。
// result_object_key 字段名复用 RGBD 事件，端侧 gallery 已有处理（此处指融合 PCD）。
type FusionDoneEvent struct {
	Kind            string `json:"kind"` // 恒为 "laser"
	JobID           int64  `json:"job_id"`
	SessionKey      string `json:"session_key"`
	InspectionID    *int64 `json:"inspection_id,omitempty"`
	OwnerUserID     *int64 `json:"owner_user_id,omitempty"`
	ResultObjectKey string `json:"result_object_key"` // 融合 PCD
	UnitAObjectKey  string `json:"unit_a_object_key"`
	UnitBObjectKey  string `json:"unit_b_object_key"`
	CalibObjectKey  string `json:"calib_object_key,omitempty"`
	Points          int    `json:"points"` // 融合点数
	PtsA            int    `json:"pts_a"`
	PtsB            int    `json:"pts_b"`
	AlignMethod     string `json:"align_method"`
}

// ScanFunc = LiveScan / ReplayScan 的统一签名（供注入与测试）。
type ScanFunc func(aArg, bArg, align, siteJSON string, keepRatio float32, cb ScanCallbacks) (ScanResult, error)

// JobStore = runner 需要的 repo.LaserScanRepo 子集。
type JobStore interface {
	MarkFusing(ctx context.Context, id int64, ptsA, ptsB int) (*repo.LaserScanJob, error)
	Complete(ctx context.Context, id int64, c repo.LaserScanCompletion) (*repo.LaserScanJob, error)
	Fail(ctx context.Context, id int64, message string) (*repo.LaserScanJob, error)
}

// CloudStore = 把一朵点云编码为 PCD 存对象存储，返回 object key。
type CloudStore interface {
	PutCloud(ctx context.Context, sessionKey, name string, xyzMM []float32) (objectKey string, err error)
}

// Publisher = 发 NATS（对齐 pkg/pubsub.Publisher）。
type Publisher interface {
	Publish(ctx context.Context, topic string, payload any) error
}

// DeviceGate = live 下两单元的 SCAN_START / SCAN_STOP（replay/test 传 nil 跳过）。
type DeviceGate interface {
	Start(ctx context.Context) error
	Stop(ctx context.Context) error
}

// Sink = 采集过程的实时出口（ws 推点 + 状态）。两方法均可被并发调用，实现须自带同步。
type Sink interface {
	Points(PointFrame)
	Status(state string, framesA, framesB int)
}

// nopSink 默认空出口。
type nopSink struct{}

func (nopSink) Points(PointFrame)       {}
func (nopSink) Status(string, int, int) {}

// Runner 编排一次扫描。Live/Replay 默认指向包级 LiveScan/ReplayScan（带 laser_cgo 时为真实现，
// 否则为 stub）；单测注入 fake。
type Runner struct {
	Jobs      JobStore
	Clouds    CloudStore
	Publisher Publisher  // 可空（不发 NATS）
	Gate      DeviceGate // 可空（replay/test）
	Live      ScanFunc
	Replay    ScanFunc
	Log       *slog.Logger
}

// NewRunner 默认绑定真实包级扫描函数。
func NewRunner(jobs JobStore, clouds CloudStore, pub Publisher, log *slog.Logger) *Runner {
	if log == nil {
		log = slog.Default()
	}
	return &Runner{
		Jobs:      jobs,
		Clouds:    clouds,
		Publisher: pub,
		Live:      LiveScan,
		Replay:    ReplayScan,
		Log:       log,
	}
}

// RunSpec = 一次扫描的入参。
type RunSpec struct {
	JobID        int64
	SessionKey   string
	InspectionID *int64
	OwnerUserID  *int64
	UnitAIP      string
	UnitBIP      string
	Align        string
	SiteJSON     string
	KeepRatio    float32

	Replay bool // host 测试：用录制 .bin 取代实时
	BinA   string
	BinB   string
}

// Run 阻塞执行一次扫描会话，返回完成后的 job。失败置 repo.Fail；被取消（用户 stop）则不改
// repo（取消转移由 stop handler 负责）直接回 *ScanError{Cancelled}。
func (r *Runner) Run(ctx context.Context, spec RunSpec, sink Sink) (*repo.LaserScanJob, error) {
	if sink == nil {
		sink = nopSink{}
	}
	if r.Live == nil {
		r.Live = LiveScan
	}
	if r.Replay == nil {
		r.Replay = ReplayScan
	}

	var (
		mu                       sync.Mutex
		cloudA, cloudB, cloudFus []float32
		markedFusing             bool
	)

	cb := ScanCallbacks{
		OnPoints: func(f PointFrame) {
			mu.Lock()
			switch f.Unit {
			case 0:
				cloudA = append(cloudA, f.XYZmm...)
			case 1:
				cloudB = append(cloudB, f.XYZmm...)
			case 2:
				cloudFus = append(cloudFus, f.XYZmm...)
			}
			mu.Unlock()
			sink.Points(f)
		},
		OnStatus: func(state string, a, b int) {
			// 进入 fusing：采集结束，回填原始点数 + 置 fusing 态供端侧显示「处理中」。
			if state == "fusing" {
				mu.Lock()
				na, nb := len(cloudA)/3, len(cloudB)/3
				already := markedFusing
				markedFusing = true
				mu.Unlock()
				if !already && r.Jobs != nil {
					if _, err := r.Jobs.MarkFusing(ctx, spec.JobID, na, nb); err != nil {
						r.Log.Warn("MarkFusing 失败", "err", err, "job", spec.JobID)
					}
				}
			}
			sink.Status(state, a, b)
		},
	}

	// live：先 SCAN_START 两单元（电机到 scan_start_angle 有机械延时，远大于 cgo 连接耗时，
	// 故 start-before 不漏点；E1 真机校验）。结束（含失败/取消）保证 SCAN_STOP。
	if !spec.Replay && r.Gate != nil {
		if err := r.Gate.Start(ctx); err != nil {
			r.failJob(ctx, spec.JobID, "SCAN_START 失败: "+err.Error())
			return nil, err
		}
		defer func() { _ = r.Gate.Stop(context.WithoutCancel(ctx)) }()
	}

	scanFn := r.Live
	aArg, bArg := spec.UnitAIP, spec.UnitBIP
	if spec.Replay {
		scanFn = r.Replay
		aArg, bArg = spec.BinA, spec.BinB
	}

	res, err := scanFn(aArg, bArg, spec.Align, spec.SiteJSON, spec.KeepRatio, cb)
	if err != nil {
		if se, ok := err.(*ScanError); ok && se.Cancelled() {
			r.Log.Info("扫描被取消", "job", spec.JobID, "session", spec.SessionKey)
			return nil, err // 取消态由 stop handler 负责写库
		}
		r.failJob(ctx, spec.JobID, err.Error())
		return nil, err
	}

	// 三朵 PCD 落对象存储。
	fusedKey, err := r.Clouds.PutCloud(ctx, spec.SessionKey, "fused", cloudFus)
	if err != nil {
		r.failJob(ctx, spec.JobID, "存融合云失败: "+err.Error())
		return nil, err
	}
	aKey, err := r.Clouds.PutCloud(ctx, spec.SessionKey, "unit_a", cloudA)
	if err != nil {
		r.failJob(ctx, spec.JobID, "存 unitA 云失败: "+err.Error())
		return nil, err
	}
	bKey, err := r.Clouds.PutCloud(ctx, spec.SessionKey, "unit_b", cloudB)
	if err != nil {
		r.failJob(ctx, spec.JobID, "存 unitB 云失败: "+err.Error())
		return nil, err
	}

	bToA, _ := json.Marshal(res.BToA)
	comp := repo.LaserScanCompletion{
		AlignMethod:    res.Align,
		PtsA:           res.PtsA,
		PtsB:           res.PtsB,
		Fused:          res.Fused,
		AfterCrop:      res.AfterCrop,
		FusedObjectKey: fusedKey,
		UnitAObjectKey: aKey,
		UnitBObjectKey: bKey,
		BToA:           bToA,
		Stats: mustJSON(map[string]any{
			"align_method": res.Align,
			"pts_a":        res.PtsA,
			"pts_b":        res.PtsB,
			"fused":        res.Fused,
			"keep_ratio":   spec.KeepRatio,
		}),
	}
	job, err := r.Jobs.Complete(ctx, spec.JobID, comp)
	if err != nil {
		return nil, fmt.Errorf("repo.Complete 失败: %w", err)
	}

	r.publishDone(ctx, spec, job, res, fusedKey, aKey, bKey)
	return job, nil
}

func (r *Runner) publishDone(ctx context.Context, spec RunSpec, job *repo.LaserScanJob,
	res ScanResult, fusedKey, aKey, bKey string) {
	if r.Publisher == nil {
		return
	}
	evt := FusionDoneEvent{
		Kind:            "laser",
		JobID:           job.ID,
		SessionKey:      spec.SessionKey,
		InspectionID:    spec.InspectionID,
		OwnerUserID:     spec.OwnerUserID,
		ResultObjectKey: fusedKey,
		UnitAObjectKey:  aKey,
		UnitBObjectKey:  bKey,
		Points:          res.Fused,
		PtsA:            res.PtsA,
		PtsB:            res.PtsB,
		AlignMethod:     res.Align,
	}
	if err := r.Publisher.Publish(ctx, TopicFusionDone, evt); err != nil {
		r.Log.Warn("发布 scan.fusion_done(laser) 失败", "err", err, "job", job.ID)
	}
}

func (r *Runner) failJob(ctx context.Context, id int64, msg string) {
	if r.Jobs == nil {
		return
	}
	if _, err := r.Jobs.Fail(context.WithoutCancel(ctx), id, msg); err != nil {
		r.Log.Warn("repo.Fail 失败", "err", err, "job", id)
	}
}

func mustJSON(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		return json.RawMessage(`{}`)
	}
	return b
}
