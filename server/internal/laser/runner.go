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

// minSweepDeg = 空扫守卫阈值：单元 h_angle(扫掠角)跨度低于此即判「未真正扫掠」。
// 控制板掉线时 state 仍可能报 SCAN(captureSweep 的 sweep_seen 被误置)，但 PTZ 没转、
// 点云塌成单一角度的扁平剖面。失败比静默产出扁平云 + 误导性「测量不可用」诚实
// （用户拍板 2026-06-04；真机案例：.101/.102 控制板掉线 SCAN_START 502）。
const minSweepDeg = 10.0

// sweepSpan 累计单元扫掠角(h_angle_deg)的 min/max，用于空扫守卫。非并发安全，调用方持锁。
type sweepSpan struct {
	min, max float32
	seen     bool
}

func (s *sweepSpan) add(h float32) {
	if !s.seen {
		s.min, s.max, s.seen = h, h, true
		return
	}
	if h < s.min {
		s.min = h
	}
	if h > s.max {
		s.max = h
	}
}

func (s sweepSpan) span() float32 {
	if !s.seen {
		return 0
	}
	return s.max - s.min
}

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

	// 测量（M9.6，融合后对 fused 云算外廓 + GB7258 合规；docs/16 §3⑥/§8）。
	LengthMM     float32  `json:"length_mm,omitempty"`
	WidthMM      float32  `json:"width_mm,omitempty"`
	HeightMM     float32  `json:"height_mm,omitempty"`
	MeasureValid bool     `json:"measure_valid"`
	Compliant    bool     `json:"compliant"`
	Violations   []string `json:"violations,omitempty"`

	// 地面平面（端侧视角预设的"上"方向基准；nx*x+ny*y+nz*z+d=0，法向指向点云主体侧）。
	GroundNX    float32 `json:"ground_nx,omitempty"`
	GroundNY    float32 `json:"ground_ny,omitempty"`
	GroundNZ    float32 `json:"ground_nz,omitempty"`
	GroundD     float32 `json:"ground_d,omitempty"`
	GroundValid bool    `json:"ground_valid"`
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
// PutCloudXYZI 额外带每点属性（h_angle°），供单元云存采集角，端侧"圈 ROI→反算扫描角"用。
type CloudStore interface {
	PutCloud(ctx context.Context, sessionKey, name string, xyzMM []float32) (objectKey string, err error)
	PutCloudXYZI(ctx context.Context, sessionKey, name string, xyzMM, attr []float32) (objectKey string, err error)
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
	Publisher Publisher    // 可空（不发 NATS）
	Gate      DeviceGate   // 可空（replay/test）
	CropBoxes CropBoxStore // 可空（无则回退自动地面测量）：持久车位框，按 bayKey=unit_a_ip 取
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
	UnitAIP       string
	UnitBIP       string
	Align         string
	SiteJSON      string
	KeepRatio     float32
	VehicleTypeID int // 逆向 JCHY 车型编号（docs/16 §4.1）；-1=未选。驱动 carType 偏移 + 按型合规 + 记录

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
		angA, angB               []float32 // 每点采集 h_angle°（与 cloudA/cloudB 点一一对应），供 ROI→扫描角反算
		hA, hB                   sweepSpan // 各单元扫掠角跨度（空扫守卫）
		markedFusing             bool
	)

	cb := ScanCallbacks{
		OnPoints: func(f PointFrame) {
			mu.Lock()
			switch f.Unit {
			case 0:
				cloudA = append(cloudA, f.XYZmm...)
				angA = appendAngle(angA, f.HAngleDeg, f.Points())
				hA.add(f.HAngleDeg)
			case 1:
				cloudB = append(cloudB, f.XYZmm...)
				angB = appendAngle(angB, f.HAngleDeg, f.Points())
				hB.add(f.HAngleDeg)
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

	// 空扫守卫：h_angle 跨度过小 = 控制板没真转台（即便 sweep_seen 被误置），点云塌成扁平
	// 单角度剖面。此时失败并明确告知，而非静默产出扁平云 + 误导性「测量不可用」。
	mu.Lock()
	spanA, spanB, seenA, seenB := hA.span(), hB.span(), hA.seen, hB.seen
	mu.Unlock()
	if !seenA || !seenB || spanA < minSweepDeg || spanB < minSweepDeg {
		msg := fmt.Sprintf("未真正扫掠：A 扫掠 %.1f° / B %.1f°（阈值 %.0f°）——疑控制板未转台/掉线，须断电复位后重扫",
			spanA, spanB, minSweepDeg)
		if sink != nil {
			sink.Status("error", len(cloudA)/3, len(cloudB)/3)
		}
		r.failJob(ctx, spec.JobID, msg)
		return nil, fmt.Errorf("%s", msg)
	}

	// 三朵 PCD 落对象存储。
	fusedKey, err := r.Clouds.PutCloud(ctx, spec.SessionKey, "fused", cloudFus)
	if err != nil {
		r.failJob(ctx, spec.JobID, "存融合云失败: "+err.Error())
		return nil, err
	}
	// 单元云存 XYZI（intensity=每点采集 h_angle°），供端侧"圈 ROI→反算扫描角"（M9.11）。
	aKey, err := r.Clouds.PutCloudXYZI(ctx, spec.SessionKey, "unit_a", cloudA, angA)
	if err != nil {
		r.failJob(ctx, spec.JobID, "存 unitA 云失败: "+err.Error())
		return nil, err
	}
	bKey, err := r.Clouds.PutCloudXYZI(ctx, spec.SessionKey, "unit_b", cloudB, angB)
	if err != nil {
		r.failJob(ctx, spec.JobID, "存 unitB 云失败: "+err.Error())
		return nil, err
	}

	// 地面检测：融合后 RANSAC 拟合地面平面。一份数据两用：① 端侧视角预设的"上"方向基准；
	// ② 测量参考系（设备坐标原点随底座而变，硬编码 ROI 不通用 → 用真实地面做参考系）。
	// 非致命——检不到只记 valid=false，端侧回退 +Z，测量回退原厂设备系 ROI 路径。
	ground := DetectGround(cloudFus, DefaultGroundParams())
	if !ground.Valid {
		r.Log.Warn("地面检测失败(点云稀疏?)", "job", spec.JobID, "inlier_ratio", ground.InlierRatio)
	}

	// M9.11/M9.6/M9.10 测量：融合后对 fused 云算车长/宽/高 + GB7258 合规。优先级：
	//   ① 持久车位框(CropBox)——用户一次圈定、世界系定向、按深度隔离背景，不依赖自动地面（首选）；
	//   ② 地面相对——自动地面有效时坐标系无关路径（高度 band + 地面投影 OBB）；
	//   ③ 原厂设备系 ROI——回退基线（JCHY 真值 ≤2.5%）。
	// 非致命——测量无效不让 job 失败，只记 measure_valid=false（docs/16 §3⑥/§8）。
	mp := DefaultMeasureParams()
	measMode := "device_roi"
	if box, ok := r.loadCropBox(ctx, spec.UnitAIP); ok {
		mp = CropBoxMeasureParams(box)
		measMode = "crop_box"
	} else if ground.Valid {
		mp = GroundMeasureParams([3]float32{ground.NX, ground.NY, ground.NZ}, ground.D, 30, 5000)
		measMode = "ground"
	}
	// 车型 carType 偏移：选定车型把该型 (x,y,z) 偏移叠到测量区域（设备 ROI/裁剪框路径生效；地面路径暂不接）。
	mp.CarOffset = CarTypeOffset(spec.VehicleTypeID)
	dims := Measure(cloudFus, mp)
	// 合规按车型套限值（当前逐型限值未录入，LimitsForVehicleType 回退通用值，见其 TODO）。
	compl := CheckCompliance(dims, LimitsForVehicleType(spec.VehicleTypeID))
	if !dims.Valid {
		r.Log.Warn("测量无效(点云退化?)", "job", spec.JobID, "mode", measMode, "fused", res.Fused, "body", dims.BodyPts)
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
			"measure":         dims,
			"measure_mode":    measMode,
			"vehicle_type_id": spec.VehicleTypeID,
			"car_offset":      mp.CarOffset,
			"compliance":      compl,
			"ground":          ground,
		}),
	}
	job, err := r.Jobs.Complete(ctx, spec.JobID, comp)
	if err != nil {
		return nil, fmt.Errorf("repo.Complete 失败: %w", err)
	}

	r.publishDone(ctx, spec, job, res, fusedKey, aKey, bKey, dims, compl, ground)
	return job, nil
}

func (r *Runner) publishDone(ctx context.Context, spec RunSpec, job *repo.LaserScanJob,
	res ScanResult, fusedKey, aKey, bKey string, dims Dimensions, compl Compliance, ground GroundPlane) {
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
		LengthMM:        dims.LengthMM,
		WidthMM:         dims.WidthMM,
		HeightMM:        dims.HeightMM,
		MeasureValid:    dims.Valid,
		Compliant:       compl.Compliant,
		Violations:      compl.Violations,
		GroundNX:        ground.NX,
		GroundNY:        ground.NY,
		GroundNZ:        ground.NZ,
		GroundD:         ground.D,
		GroundValid:     ground.Valid,
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

// loadCropBox 取该装机点(bayKey=unit_a_ip)的持久车位框；无 store / 未设置 / 框退化 / 取错均回 ok=false
// （非致命：测量回退自动地面/设备系 ROI）。
func (r *Runner) loadCropBox(ctx context.Context, unitAIP string) (CropBox, bool) {
	if r.CropBoxes == nil || unitAIP == "" {
		return CropBox{}, false
	}
	box, ok, err := r.CropBoxes.GetCropBox(ctx, unitAIP)
	if err != nil {
		r.Log.Warn("取车位框失败", "err", err, "bay", unitAIP)
		return CropBox{}, false
	}
	if !ok || !box.Valid() {
		return CropBox{}, false
	}
	return box, true
}

// appendAngle 向 dst 追加 n 个 a（一帧的所有点共享该帧采集 h_angle）。
func appendAngle(dst []float32, a float32, n int) []float32 {
	for i := 0; i < n; i++ {
		dst = append(dst, a)
	}
	return dst
}

func mustJSON(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		return json.RawMessage(`{}`)
	}
	return b
}
