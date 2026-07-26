package laser

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"io.gomob/server/pkg/repo"
)

// runner.go = 一次激光扫描会话的服务端编排（请求驱动）：
//
//	scanFn 预连接数据流 → [devctl SCAN_START 两单元] → 边采集边：累积三朵云 +
//	转发给 Sink(ws 实时预览) → "fusing" 置 MarkFusing → 三朵 PCD 落对象存储 → repo.Complete →
//	发 NATS scan.fusion_done(kind:laser) → [devctl SCAN_STOP 两单元]
//
// 依赖全用接口注入，故可不带 laser_cgo 标签、不依赖真 MinIO/NATS/DB 做单测（见 runner_test.go）。

// TopicFusionDone 复用 RGBD 融合完成事件 topic；signaling.FusionBridge 原样转发给端侧 ws，
// 端侧按 payload.kind 区分 laser/rgbd。故意与 fusion.TopicFusionDone 同名、不跨包引用。
const TopicFusionDone = "scan.fusion_done"

// minSweepDeg = 扫掠角诊断阈值。当前控制板/PTS 角度源会出现回零、跨域和状态角不一致，
// 不能再用它阻断 PCD 写入；只保留日志诊断。
const minSweepDeg = 10.0

// minExpectedSweepPointCoverage = 扫掠角诊断比例；不作为失败条件。
const minExpectedSweepPointCoverage = 0.80

const (
	maxProductionRefineDeltaTransMM = 50.0
	maxProductionRefineDeltaRotDeg  = 1.0
	// job207 真实工位基线 RMS=10.17mm；15mm 留出采样噪声余量，超过即不再信任精修。
	maxProductionRefineRMSMM    = 15.0
	maxProductionGroundDriftDeg = 1.5
	maxProductionGroundDriftMM  = 50.0
)

const realtimePreviewFlushInterval = 120 * time.Millisecond
const realtimePreviewBatchMaxPoints = 24_000

func valueOrNil[T any](value *T) any {
	if value == nil {
		return nil
	}
	return *value
}

// sweepSpan 按时间顺序展开单元扫掠角(h_angle_deg)，用于空扫守卫。
// 设备角度在 -180/+180 边界附近会跳变；直接 max-min 会把连续扫掠误判成 360°。
// 非并发安全，调用方持锁。
type sweepSpan struct {
	min, max  float32
	lastRaw   float32
	unwrapped float32
	seen      bool
}

func (s *sweepSpan) add(h float32) {
	if !s.seen {
		s.min, s.max, s.lastRaw, s.unwrapped, s.seen = h, h, h, h, true
		return
	}
	s.unwrapped += normalizedAngleDelta32(h, s.lastRaw)
	s.lastRaw = h
	if s.unwrapped < s.min {
		s.min = s.unwrapped
	}
	if s.unwrapped > s.max {
		s.max = s.unwrapped
	}
}

func (s sweepSpan) span() float32 {
	if !s.seen {
		return 0
	}
	return s.max - s.min
}

func normalizedAngleDelta32(current, previous float32) float32 {
	delta := current - previous
	for delta > 180 {
		delta -= 360
	}
	for delta < -180 {
		delta += 360
	}
	return delta
}

// FusionDoneEvent = 激光扫描完成事件载荷（kind:"laser"）。
//
// 安全契约：本事件经 FusionBridge 原样转发给端侧 ws，只能承载客户端可见信息（PCD 对象键
// 端侧自行 presign 下载 + 点数统计 + 归属人自身 id）。严禁内部密钥/他人隐私。
// result_object_key 字段名复用 RGBD 事件，端侧 gallery 已有处理（此处指融合 PCD）。
type FusionDoneEvent struct {
	Kind                      string `json:"kind"` // 恒为 "laser"
	JobID                     int64  `json:"job_id"`
	SessionKey                string `json:"session_key"`
	InspectionID              *int64 `json:"inspection_id,omitempty"`
	OwnerUserID               *int64 `json:"owner_user_id,omitempty"`
	ResultObjectKey           string `json:"result_object_key"` // 融合 PCD
	UnitAObjectKey            string `json:"unit_a_object_key"`
	UnitBObjectKey            string `json:"unit_b_object_key"`
	MeasuredObjectKey         string `json:"measured_object_key,omitempty"`
	CalibObjectKey            string `json:"calib_object_key,omitempty"`
	Points                    int    `json:"points"` // 融合点数
	PtsA                      int    `json:"pts_a"`
	PtsB                      int    `json:"pts_b"`
	AlignMethod               string `json:"align_method"`
	SiteRevision              string `json:"site_revision,omitempty"`
	RegionRevision            string `json:"region_revision,omitempty"`
	SiteQualityVerified       bool   `json:"site_quality_verified"`
	SiteQualityOverride       bool   `json:"site_quality_override"`
	SiteQualityOverrideReason string `json:"site_quality_override_reason,omitempty"`
	ProductionEligible        bool   `json:"production_eligible"`

	// 测量（M9.6，融合后对 fused 云算外廓 + GB7258 合规；docs/16 §3⑥/§8）。
	LengthMM             float32  `json:"length_mm,omitempty"`
	WidthMM              float32  `json:"width_mm,omitempty"`
	HeightMM             float32  `json:"height_mm,omitempty"`
	MeasureValid         bool     `json:"measure_valid"`
	ComplianceDetermined bool     `json:"compliance_determined"`
	ComplianceReason     string   `json:"compliance_reason,omitempty"`
	Compliant            bool     `json:"compliant"`
	Violations           []string `json:"violations,omitempty"`

	// 抠车隔离方式诊断（路 B 背景相减）：measMode=bg_subtract/crop_box/no_isolation/background_captured。
	// BackgroundCaptured=本次是采集空工位背景（非测量）；BackgroundSet=本工位已有背景可减；FgPoints=减后前景点数。
	MeasMode               string                 `json:"meas_mode,omitempty"`
	MeasureReason          string                 `json:"measure_reason,omitempty"`
	BackgroundCaptured     bool                   `json:"background_captured,omitempty"`
	BackgroundSet          bool                   `json:"background_set,omitempty"`
	BackgroundCompatible   bool                   `json:"background_compatible"`
	BackgroundIncompatible bool                   `json:"background_incompatible"`
	BackgroundReason       string                 `json:"background_reason,omitempty"`
	BackgroundRevisionID   int64                  `json:"background_revision_id,omitempty"`
	BackgroundSchema       string                 `json:"background_schema,omitempty"`
	FgPoints               int                    `json:"fg_points,omitempty"`
	MeasuredPoints         int                    `json:"measured_points,omitempty"`
	MeasuredArtifact       *MeasuredCloudArtifact `json:"measured_artifact,omitempty"`

	// 轴距/前后悬（M9.4，几何贴地接触带检测；docs/16 §3⑥ caluteDeepWheel 等价）。
	NumAxles         int       `json:"num_axles,omitempty"`
	WheelbasesMM     []float32 `json:"wheelbases_mm,omitempty"`
	TotalWheelbaseMM float32   `json:"total_wheelbase_mm,omitempty"`
	FrontOverhangMM  float32   `json:"front_overhang_mm,omitempty"`
	RearOverhangMM   float32   `json:"rear_overhang_mm,omitempty"`
	AxleValid        bool      `json:"axle_valid"`

	// 货箱（M9.4b，几何分割+外尺寸；docs/16 §3⑥ calutePickingBox/getCarBoardDeep 等价）。
	HasCargoBox      bool    `json:"has_cargo_box"`
	BoxOuterLengthMM float32 `json:"box_outer_length_mm,omitempty"`
	BoxOuterWidthMM  float32 `json:"box_outer_width_mm,omitempty"`
	BoxDepthMM       float32 `json:"box_depth_mm,omitempty"`
	BoxInnerWidthMM  float32 `json:"box_inner_width_mm,omitempty"`

	// 叠加几何（M9.4c，世界系车体框/货箱框/轴线，供网页 3D 叠加可视分割）。
	Overlay *VehicleOverlay `json:"overlay,omitempty"`

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

// BackgroundCaptureFinalizer 把来源任务完成与背景 active 指针切换收进同一数据库事务。
type BackgroundCaptureFinalizer interface {
	ActivateAndComplete(
		ctx context.Context,
		jobID int64,
		completion repo.LaserScanCompletion,
		revision repo.LaserBackgroundRevision,
	) (*repo.LaserScanJob, *repo.LaserBackgroundRevision, error)
}

// CloudStore = 把一朵点云编码为 PCD 存对象存储，返回 object key。
// PutCloudXYZI 额外带每点属性（h_angle°），供单元云存采集角，端侧"圈 ROI→反算扫描角"用。
type CloudStore interface {
	PutCloud(ctx context.Context, sessionKey, name string, xyzMM []float32) (objectKey string, err error)
	PutMeasuredCloud(ctx context.Context, sessionKey, name string, xyzMM []float32, artifact MeasuredCloudArtifact) (objectKey string, err error)
	PutCloudXYZI(ctx context.Context, sessionKey, name string, xyzMM, attr []float32) (objectKey string, err error)
	PutCloudXYZRGB(ctx context.Context, sessionKey, name string, xyzMM []float32, rgb []uint32) (objectKey string, err error)
	PutCloudXYZRGBI(ctx context.Context, sessionKey, name string, xyzMM []float32, rgb []uint32, attr []float32) (objectKey string, err error)
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

// Sink = 采集过程的实时出口（ws 推点 + 状态 + 相机 RGB 预览帧）。方法均可被并发调用，实现须自带同步。
type Sink interface {
	Points(PointFrame)
	Status(state string, framesA, framesB int)
	Image(ImageFrame)
}

// nopSink 默认空出口。
type nopSink struct{}

func (nopSink) Points(PointFrame)       {}
func (nopSink) Status(string, int, int) {}
func (nopSink) Image(ImageFrame)        {}

// Runner 编排一次扫描。Live/Replay 默认指向包级 LiveScan/ReplayScan（带 laser_cgo 时为真实现，
// 否则为 stub）；单测注入 fake。
type Runner struct {
	Jobs                JobStore
	Clouds              CloudStore
	Reader              CloudReader                // 可空：按 revision 对象键读回 A/B 单元背景；注入同一 MinIO 实例
	Publisher           Publisher                  // 可空（不发 NATS）
	Gate                DeviceGate                 // 可空（replay/test）
	CropBoxes           CropBoxStore               // 可空（无则回退自动地面测量）：持久车位框，按 bayKey=unit_a_ip 取
	Grounds             GroundStore                // 可空（无则回退逐扫描 RANSAC）：持久地面平面，背景采集时拟合入库（M13）
	BackgroundFinalizer BackgroundCaptureFinalizer // 背景任务 done 与 active revision 的原子提交入口
	Live                ScanFunc
	Replay              ScanFunc
	// FlipVertical：设备出云竖直翻转的硬件约定（雷达倒装），true 则对每点 z 取反。用户拍板「默认相机
	// 扫描到的点云就需要上下翻转」。零值 false → host 测试不翻转、断言原始坐标不变；main.go 经
	// GOMOB_LASER_FLIP_VERTICAL（默认 true）开启。对 A/B/fused 三流一致施加（见 flipZmm）。
	FlipVertical bool
	Log          *slog.Logger
}

// flipZmm 原地取反每点 z（mm）。对 cloudA/cloudB/cloudFus 三流一致翻转 → 显示/地面/测量/车位框
// 同处一个翻转系、自洽；纯反射不改任何长度，L/W/H 量测不变。
// 注意：当前 align=none(BToA=identity)，与 fused 流自洽。若日后启用对齐，runner 测量处对 cloudB 的
// BToA 再变换需按 F=diag(1,1,-1) 共轭（F·BToA·F），届时一并处理。
func flipZmm(xyz []float32) {
	for i := 2; i < len(xyz); i += 3 {
		xyz[i] = -xyz[i]
	}
}

const fusedUnitANeutralRGB uint32 = 0x0072777d

// buildFusedRGB 按当前融合实现的 union 顺序(A 后接 B)给融合云补颜色：A 段用 101 相机投影色、
// B 段用 102 相机投影色；某单元颜色缺失/对不上时该段退回中性灰。两单元都无色则返回 nil(无色 fused)。
// 若启用 keep_ratio 降采样或未来融合顺序改变导致点数对不上，返回 nil 回退无色 fused。
func buildFusedRGB(cloudA, cloudB, cloudFus []float32, rgbA, rgbB []uint32) []uint32 {
	na, nb, nf := len(cloudA)/3, len(cloudB)/3, len(cloudFus)/3
	haveA := na > 0 && len(rgbA) == na
	haveB := nb > 0 && len(rgbB) == nb
	if nf == 0 || nf != na+nb || (!haveA && !haveB) {
		return nil
	}
	rgb := make([]uint32, nf)
	if haveA {
		copy(rgb[:na], rgbA)
	} else {
		for i := 0; i < na; i++ {
			rgb[i] = fusedUnitANeutralRGB
		}
	}
	if haveB {
		copy(rgb[na:], rgbB)
	} else {
		for i := na; i < nf; i++ {
			rgb[i] = fusedUnitANeutralRGB
		}
	}
	return rgb
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
	JobID              int64
	SessionKey         string
	InspectionID       *int64
	OwnerUserID        *int64
	UnitAIP            string
	UnitBIP            string
	Align              string
	SiteJSON           string
	SiteCalibration    SiteCalibrationSnapshot
	RegionCalibration  RegionCalibrationSnapshot
	UnitAProfile       UnitAcquisitionProfile
	UnitBProfile       UnitAcquisitionProfile
	BackgroundRevision *repo.LaserBackgroundRevision
	KeepRatio          float32
	VehicleTypeID      int // 逆向 JCHY 车型编号（docs/16 §4.1）；-1=未选。驱动 carType 偏移 + 按型合规 + 记录

	Replay bool // host 测试：用录制 .bin 取代实时
	BinA   string
	BinB   string

	ExpectedSweepADeg float32 // 当前 unitA 配置的线性目标扫掠角，仅用于诊断日志
	ExpectedSweepBDeg float32 // 当前 unitB 配置的线性目标扫掠角，仅用于诊断日志

	RegionFilter PointRegionFilter // 可选：工位区域墙过滤，只保留墙内点云。

	// MarkAsBackground：把服务端区域裁剪后的 A/B 设备系云激活为不可变空工位背景 revision，不做测量。
	MarkAsBackground bool
}

// SiteCalibrationSnapshot 随扫描任务保存外参来源，便于追溯实际使用的正式版本。
type SiteCalibrationSnapshot struct {
	Source          string     `json:"source,omitempty"`
	UpdatedAt       *time.Time `json:"updated_at,omitempty"`
	UpdatedBy       *int64     `json:"updated_by,omitempty"`
	SourceScanID    *int64     `json:"source_scan_id,omitempty"`
	RMSErrorMM      *float64   `json:"rms_error_mm,omitempty"`
	CommonMarkers   *int       `json:"common_markers,omitempty"`
	MatrixSHA256    string     `json:"matrix_sha256,omitempty"`
	QualityVerified *bool      `json:"quality_verified,omitempty"`
	QualityOverride string     `json:"quality_override,omitempty"`
}

// qualityVerified 对新任务使用显式快照；旧快照只有 RMS/common 真实达标才可追认。
// 仅有 revision 不能证明质量，现场历史数据恰好存在“有 revision 但缺证据”。
func (s SiteCalibrationSnapshot) qualityVerified() bool {
	if s.QualityVerified != nil {
		return *s.QualityVerified
	}
	return validateProductionSiteQuality(s.RMSErrorMM, s.CommonMarkers) == nil
}

func (s SiteCalibrationSnapshot) qualityOverrideEnabled() bool {
	return s.QualityOverride != ""
}

func (s SiteCalibrationSnapshot) productionEligible() bool {
	return s.qualityVerified() && !s.qualityOverrideEnabled()
}

func (s SiteCalibrationSnapshot) qualityUnverified() bool {
	return !s.productionEligible()
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
	if spec.MarkAsBackground && r.BackgroundFinalizer == nil {
		err := errors.New("空工位背景原子完成器未配置")
		r.failJob(ctx, spec.JobID, err.Error())
		return nil, err
	}
	regionFilter, err := spec.RegionFilter.Normalized()
	if err != nil {
		r.failJob(ctx, spec.JobID, "点云区域过滤配置无效: "+err.Error())
		return nil, err
	}
	spec.RegionFilter = regionFilter
	siteArg, cleanupSiteArg, err := prepareSiteExtrinsicArg(spec.Align, spec.SiteJSON)
	if err != nil {
		r.failJob(ctx, spec.JobID, err.Error())
		return nil, err
	}
	defer cleanupSiteArg()
	if spec.Align == "site" {
		r.Log.Info("加载工位权威外参", "job", spec.JobID, "unit_a_ip", spec.UnitAIP, "unit_b_ip", spec.UnitBIP,
			"source", spec.SiteCalibration.Source, "updated_at", valueOrNil(spec.SiteCalibration.UpdatedAt),
			"updated_by", valueOrNil(spec.SiteCalibration.UpdatedBy), "source_scan_id", valueOrNil(spec.SiteCalibration.SourceScanID),
			"rms_error_mm", valueOrNil(spec.SiteCalibration.RMSErrorMM),
			"common_markers", valueOrNil(spec.SiteCalibration.CommonMarkers), "matrix_sha256", spec.SiteCalibration.MatrixSHA256)
	}

	var (
		mu                       sync.Mutex
		cloudA, cloudB, cloudFus []float32
		colorAXYZ                []float32
		rgbA                     []uint32 // 101 相机纹理投影颜色（0xRRGGBB），与 colorAXYZ 点数一致时写入 unit_a
		colorBXYZ                []float32
		rgbB                     []uint32  // 102 相机纹理投影颜色（0xRRGGBB），与 colorBXYZ 点数一致时写入 unit_b
		hA, hB                   sweepSpan // 各单元扫掠角跨度，仅作诊断日志
		rawCounts                [3]int    // cgo 原始回调点数，用于和 ScanResult 对账。
		markedFusing             bool
		markFusingErr            error
	)
	collectCh := make(chan PointFrame, 256)
	previewCh := make(chan PointFrame, 2048)
	var pointWorkers sync.WaitGroup
	pointWorkers.Add(2)
	go func() {
		defer pointWorkers.Done()
		for f := range collectCh {
			raw := f.Points()
			mu.Lock()
			switch f.Unit {
			case 0:
				rawCounts[0] += raw
				cloudA = append(cloudA, f.XYZmm...)
				hA.add(f.HAngleDeg)
			case 1:
				rawCounts[1] += raw
				cloudB = append(cloudB, f.XYZmm...)
				hB.add(f.HAngleDeg)
			case 2:
				rawCounts[2] += raw
				cloudFus = append(cloudFus, f.XYZmm...)
			}
			mu.Unlock()
		}
	}()
	go func() {
		defer pointWorkers.Done()
		ticker := time.NewTicker(realtimePreviewFlushInterval)
		defer ticker.Stop()
		var pending [2]PointFrame
		flushUnit := func(unit int) {
			if unit < 0 || unit > 1 || len(pending[unit].XYZmm) == 0 {
				return
			}
			sink.Points(pending[unit])
			pending[unit].XYZmm = nil
		}
		flushAll := func() {
			flushUnit(0)
			flushUnit(1)
		}
		for {
			select {
			case f, ok := <-previewCh:
				if !ok {
					flushAll()
					return
				}
				if f.Unit != 0 && f.Unit != 1 {
					continue
				}
				p := &pending[f.Unit]
				p.Unit = f.Unit
				p.HAngleDeg = f.HAngleDeg
				p.XYZmm = append(p.XYZmm, f.XYZmm...)
				if len(p.XYZmm)/3 >= realtimePreviewBatchMaxPoints {
					flushUnit(f.Unit)
				}
			case <-ticker.C:
				flushAll()
			}
		}
	}()
	closePointWorkers := func() {
		close(collectCh)
		close(previewCh)
		pointWorkers.Wait()
	}
	liveWithGate := !spec.Replay && r.Gate != nil
	var gateOnce sync.Once
	var gateMu sync.Mutex
	var gateStarted bool
	var gateStartErr error
	startGate := func() {
		if !liveWithGate {
			return
		}
		gateOnce.Do(func() {
			if err := r.Gate.Start(ctx); err != nil {
				gateMu.Lock()
				gateStartErr = err
				gateMu.Unlock()
				r.Log.Warn("SCAN_START 失败", "err", err, "job", spec.JobID)
				CancelScan()
				return
			}
			gateMu.Lock()
			gateStarted = true
			gateMu.Unlock()
		})
	}
	defer func() {
		if !liveWithGate {
			return
		}
		gateMu.Lock()
		started := gateStarted
		gateMu.Unlock()
		if started {
			_ = r.Gate.Stop(context.WithoutCancel(ctx))
		}
	}()

	cb := ScanCallbacks{
		OnPoints: func(f PointFrame) {
			// cgo/parser 回调只做轻量分发：完整累积和实时预览分属两个 goroutine，避免渲染/推流反压采集。
			if r.FlipVertical {
				flipZmm(f.XYZmm)
			}
			collectCh <- f
			if f.Unit == 0 || f.Unit == 1 {
				filtered := filterPointFrame(f, spec.RegionFilter)
				if filtered.Points() > 0 {
					previewCh <- filtered
				}
			}
		},
		OnColorPoints: func(f ColorPointFrame) {
			if (f.Unit != 0 && f.Unit != 1) || f.Points() == 0 || len(f.RGB) != f.Points() {
				return
			}
			if r.FlipVertical {
				flipZmm(f.XYZmm)
			}
			mu.Lock()
			if f.Unit == 0 {
				colorAXYZ = append(colorAXYZ[:0], f.XYZmm...)
				rgbA = append(rgbA[:0], f.RGB...)
			} else {
				colorBXYZ = append(colorBXYZ[:0], f.XYZmm...)
				rgbB = append(rgbB[:0], f.RGB...)
			}
			mu.Unlock()
		},
		OnImage: func(f ImageFrame) {
			// 采集中相机 RGB 预览帧 → 实时出口（点云页小窗）。轻量分发，不阻塞采集。
			sink.Image(f)
		},
		OnStatus: func(state string, a, b int) {
			if state == "armed" {
				startGate()
			}
			// 进入 fusing：采集结束，回填原始点数 + 置 fusing 态供端侧显示「处理中」。
			if state == "fusing" {
				mu.Lock()
				already := markedFusing
				markedFusing = true
				mu.Unlock()
				if !already && r.Jobs != nil {
					if _, err := r.Jobs.MarkFusing(ctx, spec.JobID, a, b); err != nil {
						mu.Lock()
						markFusingErr = err
						mu.Unlock()
						r.Log.Warn("MarkFusing 失败", "err", err, "job", spec.JobID)
					}
				}
			}
			sink.Status(state, a, b)
		},
		ExpectedSweepADeg: spec.ExpectedSweepADeg,
		ExpectedSweepBDeg: spec.ExpectedSweepBDeg,
	}

	scanFn := r.Live
	aArg, bArg := spec.UnitAIP, spec.UnitBIP
	if spec.Replay {
		scanFn = r.Replay
		aArg, bArg = spec.BinA, spec.BinB
	}

	res, err := scanFn(aArg, bArg, spec.Align, siteArg, spec.KeepRatio, cb)
	closePointWorkers()
	gateMu.Lock()
	startErr := gateStartErr
	gateMu.Unlock()
	if startErr != nil {
		r.failJob(ctx, spec.JobID, "SCAN_START 失败: "+startErr.Error())
		return nil, startErr
	}
	if err != nil {
		if se, ok := err.(*ScanError); ok && se.Cancelled() {
			r.Log.Info("扫描被取消", "job", spec.JobID, "session", spec.SessionKey)
			return nil, err // 取消态由 stop handler 负责写库
		}
		r.failJob(ctx, spec.JobID, err.Error())
		return nil, err
	}
	mu.Lock()
	transitionErr := markFusingErr
	mu.Unlock()
	if transitionErr != nil {
		if errors.Is(transitionErr, repo.ErrNotFound) {
			r.Log.Info("任务已在进入融合前被取消，停止后处理", "job", spec.JobID)
			return nil, transitionErr
		}
		r.failJob(ctx, spec.JobID, "进入融合状态失败: "+transitionErr.Error())
		return nil, fmt.Errorf("MarkFusing 失败: %w", transitionErr)
	}
	if spec.Align == "site" && res.Align != "site" {
		err := fmt.Errorf("外参对齐未生效：native 返回 align_method=%q，已阻止写入错误融合点云", res.Align)
		r.failJob(ctx, spec.JobID, err.Error())
		return nil, err
	}
	if r.FlipVertical {
		res.BToA = flipVerticalBToA(res.BToA)
	}

	mu.Lock()
	rawA, rawB, rawFus := rawCounts[0], rawCounts[1], rawCounts[2]
	mu.Unlock()
	if rawA != res.PtsA || rawB != res.PtsB || rawFus != res.Fused {
		msg := fmt.Sprintf("点云回调累计与结果统计不一致：A %d/%d，B %d/%d，融合 %d/%d——疑似采集/回调丢点，已阻止写入不完整 PCD",
			rawA, res.PtsA, rawB, res.PtsB, rawFus, res.Fused)
		if sink != nil {
			sink.Status("error", rawA, rawB)
		}
		r.failJob(ctx, spec.JobID, msg)
		return nil, fmt.Errorf("%s", msg)
	}
	// 背景相减域使用稳定的 canonical site 区域裁剪。区域外房间点不进入背景对象；单次 refine
	// 只负责相减后把 B 前景放入 A 系，不能改变每扫的背景裁剪域，否则墙边界会产生静态假前景。
	cloudA = filterXYZForUnit(cloudA, 0, spec.RegionFilter)
	regionCloudB := filterXYZForUnit(cloudB, 1, spec.RegionFilter)
	// B→A 点到面精修（M13）：从服务端权威 site 外参直接求最终变换。两单元看到的是同一物体/家具的**对立面**，
	// 点到点会把对立面往一起拉，产生表面厚度量级的系统性偏置（真机实测沿车长轴错位 ~67mm →
	// 车长 +3.5%），因此 native site 路径不再做点到点 ICP。点到面 + 法向相容性拒绝天然排除对立面配对；对已围栏粗滤的分镜云跑，
	// 真机验证从任意初值收敛到同一变换、外廓达真值 <1%。守卫超限/对应不足时沿用 site 初值（非致命）。
	var refineStats RefineBToAStats
	refineAccepted := true
	refineReason := "not_required"
	rfB := spec.RegionFilter
	if spec.Align == "site" {
		res.BToA, refineStats = RefineBToA(cloudA, regionCloudB, res.BToA, DefaultRefineBToAParams())
		backgroundSchema := ""
		if spec.BackgroundRevision != nil {
			backgroundSchema = spec.BackgroundRevision.CoordinateSchema
		}
		refineAccepted, refineReason = productionRefineAcceptanceForBackground(refineStats, backgroundSchema)
		if refineStats.Applied {
			if !refineAccepted {
				r.Log.Warn("工位外参精修未通过生产门，已禁止车辆测量", "job", spec.JobID, "pairs", refineStats.Pairs,
					"rms_mm", refineStats.RMSMM, "delta_trans_mm", refineStats.DeltaTransMM, "delta_rot_deg", refineStats.DeltaRotDeg)
			} else {
				r.Log.Info("B→A 点到面精修", "job", spec.JobID, "pairs", refineStats.Pairs,
					"rms_mm", refineStats.RMSMM, "delta_trans_mm", refineStats.DeltaTransMM, "delta_rot_deg", refineStats.DeltaRotDeg)
			}
		} else {
			r.Log.Warn("B→A 点到面精修未采纳，已禁止车辆测量", "job", spec.JobID,
				"reason", refineStats.Reason, "pairs", refineStats.Pairs)
		}
		// 围栏一致性：B 的围栏裁剪与融合摆放必须用同一 B→A（精修后），否则围栏对 B 的有效位置
		// 漂移一个精修量（旧实现请求外参裁、精修外参摆，实测差 ~44mm）。仅 site 融合有跨单元外参。
		rfB = regionFilterWithBToA(spec.RegionFilter, res.BToA)
	}
	cloudB = filterXYZForUnit(cloudB, 1, rfB)
	// 融合云稍后从已过滤的分镜云重建（见 gotFus 前），不再独立过滤 native 融合云：分镜云在各自设备系、
	// 融合云在世界系被独立过滤，region 墙边界点可能差几个，导致 nf≠na+nb 让融合色按段对齐失败→全无色。
	if len(colorAXYZ)/3 == len(rgbA) && len(rgbA) > 0 {
		colorXYZ, colorRGB := filterXYZRGBForUnit(colorAXYZ, rgbA, 0, spec.RegionFilter)
		if len(colorXYZ)/3 == len(cloudA)/3 {
			cloudA = colorXYZ
			rgbA = colorRGB
		} else {
			r.Log.Warn("101 纹理过滤后点数与 A 点云不一致，回退写 XYZ", "job", spec.JobID, "rgb", len(colorRGB), "pts_a", len(cloudA)/3)
			rgbA = nil
		}
	} else if len(rgbA) > 0 {
		r.Log.Warn("101 纹理原始点数与 XYZ 不一致，回退写 XYZ", "job", spec.JobID, "rgb", len(rgbA), "xyz", len(colorAXYZ)/3)
		rgbA = nil
	}
	if len(colorBXYZ)/3 == len(rgbB) && len(rgbB) > 0 {
		// 与 cloudB 同一过滤器（site 时含精修后 B→A），保证点数逐一对齐（颜色按段对齐依赖 nf==na+nb）。
		colorXYZ, colorRGB := filterXYZRGBForUnit(colorBXYZ, rgbB, 1, rfB)
		if len(colorXYZ)/3 == len(cloudB)/3 {
			cloudB = colorXYZ
			rgbB = colorRGB
		} else {
			r.Log.Warn("102 纹理过滤后点数与 B 点云不一致，回退写 XYZ", "job", spec.JobID, "rgb", len(colorRGB), "pts_b", len(cloudB)/3)
			rgbB = nil
		}
	} else if len(rgbB) > 0 {
		r.Log.Warn("102 纹理原始点数与 XYZ 不一致，回退写 XYZ", "job", spec.JobID, "rgb", len(rgbB), "xyz", len(colorBXYZ)/3)
		rgbB = nil
	}
	rawOnly := spec.Align == "raw"
	// 融合云 = 已过滤分镜云 A ∪ (BToA·B)，与分镜云点数/过滤严格一致（几何已数值验证 fused_B==BToA·B）。
	// 这样 nf==na+nb 恒成立，buildFusedRGB 按 A 段(101 色)/B 段(102 色)对齐稳定携带颜色，不再因边界差点回退无色。
	if !rawOnly {
		fusedXYZ := make([]float32, 0, len(cloudA)+len(cloudB))
		fusedXYZ = append(fusedXYZ, cloudA...)
		fusedXYZ = append(fusedXYZ, transformPoints(cloudB, res.BToA)...)
		cloudFus = fusedXYZ
	}
	gotA, gotB, gotFus := len(cloudA)/3, len(cloudB)/3, len(cloudFus)/3

	// 扫掠角只做诊断。当前真实设备上 status latest_angle / PTS h_angle 与配置角存在基准差，
	// 用它阻断会丢掉真实扫到的 AB 点云。
	mu.Lock()
	spanA, spanB, seenA, seenB := hA.span(), hB.span(), hA.seen, hB.seen
	mu.Unlock()
	if !seenA || !seenB || spanA < minSweepDeg || spanB < minSweepDeg {
		r.Log.Warn("扫掠角诊断不足，继续写入原始点云", "job", spec.JobID, "span_a", spanA, "span_b", spanB, "seen_a", seenA, "seen_b", seenB)
	}
	if spec.ExpectedSweepADeg > 0 && spanA < spec.ExpectedSweepADeg*minExpectedSweepPointCoverage ||
		spec.ExpectedSweepBDeg > 0 && spanB < spec.ExpectedSweepBDeg*minExpectedSweepPointCoverage {
		r.Log.Warn("有效点扫掠角诊断不足，继续写入原始点云", "job", spec.JobID,
			"span_a", spanA, "expected_a", spec.ExpectedSweepADeg,
			"span_b", spanB, "expected_b", spec.ExpectedSweepBDeg)
	}

	// PCD 落对象存储。raw 模式表示未标定：只保存 A/B 分镜点云，不产出任何融合云。
	var fusedKey string
	if !rawOnly {
		// 融合云优先携带 101/102 相机纹理，供默认漫游也有真实色彩；某单元颜色无法对齐时该段退回中性灰。
		if fusedRGB := buildFusedRGB(cloudA, cloudB, cloudFus, rgbA, rgbB); fusedRGB != nil {
			fusedKey, err = r.Clouds.PutCloudXYZRGB(ctx, spec.SessionKey, "fused", cloudFus, fusedRGB)
		} else {
			fusedKey, err = r.Clouds.PutCloud(ctx, spec.SessionKey, "fused", cloudFus)
		}
		if err != nil {
			r.failJob(ctx, spec.JobID, "存融合云失败: "+err.Error())
			return nil, err
		}
	}
	// 单元云存原始 XYZ/RGB。当前设备角度字段与真实机械角比例不一致，不能写成“实采角”误导端侧。
	var aKey string
	if len(rgbA) == len(cloudA)/3 && len(rgbA) > 0 {
		aKey, err = r.Clouds.PutCloudXYZRGB(ctx, spec.SessionKey, "unit_a", cloudA, rgbA)
	} else {
		if len(rgbA) > 0 {
			r.Log.Warn("101 纹理点数与 A 点云不一致，回退写 XYZ", "job", spec.JobID, "rgb", len(rgbA), "pts_a", len(cloudA)/3)
		}
		aKey, err = r.Clouds.PutCloud(ctx, spec.SessionKey, "unit_a", cloudA)
	}
	if err != nil {
		r.failJob(ctx, spec.JobID, "存 unitA 云失败: "+err.Error())
		return nil, err
	}
	var bKey string
	if len(rgbB) == len(cloudB)/3 {
		bKey, err = r.Clouds.PutCloudXYZRGB(ctx, spec.SessionKey, "unit_b", cloudB, rgbB)
	} else {
		if len(rgbB) > 0 {
			r.Log.Warn("102 纹理点数与 B 点云不一致，回退写 XYZ", "job", spec.JobID, "rgb", len(rgbB), "pts_b", len(cloudB)/3)
		}
		bKey, err = r.Clouds.PutCloud(ctx, spec.SessionKey, "unit_b", cloudB)
	}
	if err != nil {
		r.failJob(ctx, spec.JobID, "存 unitB 云失败: "+err.Error())
		return nil, err
	}

	var ground GroundPlane
	var dims Dimensions
	var axle AxleResult
	var cargo CargoBox
	var overlay VehicleOverlay
	var compl Compliance
	measMode := "unfused"
	bgCaptured := false
	bgSet := spec.BackgroundRevision != nil
	bgCompatible := false
	bgReason := "not_set"
	bgRevisionID := int64(0)
	bgSchema := ""
	fgPts := 0
	measuredPts := 0
	groundSource := ""
	groundStable := true
	groundReason := "ready"
	var groundDriftDeg, groundDriftMM float32 = -1, -1 // -1 = 未算（无持久地面/重拟合无效）
	carOffset := CarTypeOffset(spec.VehicleTypeID)
	var backgroundA, backgroundB, backgroundFused []float32
	var measuredCloud []float32
	var pendingBackground *repo.LaserBackgroundRevision
	if spec.BackgroundRevision != nil {
		bgRevisionID = spec.BackgroundRevision.ID
		bgSchema = spec.BackgroundRevision.CoordinateSchema
	}
	if !rawOnly && !spec.MarkAsBackground {
		bgCompatible, bgReason = backgroundRevisionCompatibility(
			spec.BackgroundRevision,
			spec.SiteCalibration.MatrixSHA256,
			spec.RegionCalibration.PointsSHA256,
			spec.UnitAProfile,
			spec.UnitBProfile,
		)
		if bgCompatible {
			var loadReason string
			switch spec.BackgroundRevision.CoordinateSchema {
			case repo.LaserBackgroundSchemaLegacyVerifiedFused:
				// 修改前网页端长期使用的背景就是“区域裁剪后的融合云”。保留该显式 legacy
				// 坐标域，按旧算法直接与本次区域裁剪融合云相减；不把它伪装成 A/B 背景。
				backgroundFused, loadReason = r.loadLegacyFusedBackground(ctx, spec.BackgroundRevision)
			default:
				backgroundA, backgroundB, loadReason = r.loadBackgroundUnitClouds(ctx, spec.BackgroundRevision)
				if loadReason == "ready" {
					// 历史 raw schema 仍需按当前 canonical region 裁剪；新 cropped schema
					// 已在采集时固化同一裁剪域，region revision 不一致会在兼容门提前拒绝。
					if spec.BackgroundRevision.CoordinateSchema == repo.LaserBackgroundSchemaRawUnitFramesV1 {
						backgroundA = filterXYZForUnit(backgroundA, 0, spec.RegionFilter)
						backgroundB = filterXYZForUnit(backgroundB, 1, spec.RegionFilter)
					}
					// 地面/支撑云按本次最终 B→A 重建；背景相减本身仍使用 canonical 裁剪域。
					groundBackgroundA := filterXYZForUnit(backgroundA, 0, spec.RegionFilter)
					groundBackgroundB := filterXYZForUnit(backgroundB, 1, rfB)
					backgroundFused = append(append([]float32(nil), groundBackgroundA...), transformPoints(groundBackgroundB, res.BToA)...)
				}
			}
			if loadReason != "ready" {
				bgCompatible = false
				bgReason = loadReason
			}
		}
	}
	switch {
	case rawOnly:
		r.Log.Info("工位未标定，已跳过融合和外廓测量", "job", spec.JobID, "pts_a", gotA, "pts_b", gotB)
	case spec.MarkAsBackground:
		// 新背景保存 canonical region 裁剪后的 A/B 设备系云；active 指针与 job done
		// 在末尾同一事务提交。区域外无效点不再占对象存储、下载和解码内存。
		croppedAKey, putErr := r.Clouds.PutCloud(ctx, spec.SessionKey, "background_unit_a_region", cloudA)
		if putErr != nil {
			r.failJob(ctx, spec.JobID, "存 unitA 区域背景失败: "+putErr.Error())
			return nil, putErr
		}
		croppedBKey, putErr := r.Clouds.PutCloud(ctx, spec.SessionKey, "background_unit_b_region", regionCloudB)
		if putErr != nil {
			r.failJob(ctx, spec.JobID, "存 unitB 区域背景失败: "+putErr.Error())
			return nil, putErr
		}
		sourceScanID := spec.JobID
		deviceHashA, deviceHashB := spec.UnitAProfile.DeviceConfigSHA256, spec.UnitBProfile.DeviceConfigSHA256
		scanHashA, scanHashB := spec.UnitAProfile.ScanConfigSHA256, spec.UnitBProfile.ScanConfigSHA256
		checksumA, checksumB := cloudFloatSHA256(cloudA), cloudFloatSHA256(regionCloudB)
		revision := repo.LaserBackgroundRevision{
			UnitAIP: spec.UnitAIP, UnitBIP: spec.UnitBIP,
			UnitAObjectKey: &croppedAKey, UnitBObjectKey: &croppedBKey, SourceScanID: &sourceScanID,
			SiteRevision:   &spec.SiteCalibration.MatrixSHA256,
			RegionRevision: &spec.RegionCalibration.PointsSHA256,
			UnitAPoints:    int64(len(cloudA) / 3), UnitBPoints: int64(len(regionCloudB) / 3),
			UnitAChecksum: &checksumA, UnitBChecksum: &checksumB,
			UnitAIdentity: spec.UnitAProfile.identityJSON(), UnitBIdentity: spec.UnitBProfile.identityJSON(),
			UnitADeviceConfigHash: &deviceHashA, UnitBDeviceConfigHash: &deviceHashB,
			UnitAScanConfigHash: &scanHashA, UnitBScanConfigHash: &scanHashB,
			CoordinateSchema: repo.LaserBackgroundSchemaRegionCroppedUnitV1,
			CapturedBy:       spec.OwnerUserID, CapturedAt: time.Now().UTC(),
		}
		pendingBackground = &revision
		ground = DetectGround(cloudFus, DefaultGroundParams())
		if !ground.Valid {
			err := errors.New("空工位背景地面检测失败，拒绝激活不可用于生产测量的背景")
			r.failJob(ctx, spec.JobID, err.Error())
			return nil, err
		}
		bgCaptured = true
		bgSet = true
		bgCompatible = true
		bgReason = "ready"
		bgSchema = revision.CoordinateSchema
		measMode = "background_captured"
		r.Log.Info("已准备 A/B 区域空工位背景，等待任务终态原子提交", "job", spec.JobID,
			"pts_a", len(cloudA)/3, "pts_b", len(regionCloudB)/3,
			"region_revision", spec.RegionCalibration.PointsSHA256)
	default:
		// 地面优先从本次 A/B 背景按最终外参重建，确保支撑面和背景相减处于同一几何域。
		// 无可用背景时，裁剪框路径仍可复用历史持久地面；最后才退回当前 live 云拟合。
		refit := DetectGround(cloudFus, DefaultGroundParams())
		if bgCompatible && len(backgroundFused) >= 3 {
			ground = DetectGround(backgroundFused, DefaultGroundParams())
			groundSource = "background_revision"
		} else if pg, ok := r.loadGround(ctx, spec.UnitAIP); ok {
			ground = pg
			groundSource = "persisted"
		} else {
			ground = refit
			groundSource = "refit"
		}
		switch {
		case !ground.Valid:
			groundStable = false
			groundReason = "ground_invalid"
			r.Log.Warn("工位地面检测无效，已禁止车辆测量", "job", spec.JobID, "source", groundSource,
				"inlier_ratio", ground.InlierRatio)
		case groundSource != "refit" && !refit.Valid:
			groundStable = false
			groundReason = "ground_refit_invalid"
			r.Log.Warn("当前扫描无法重拟合地面，无法验证工位漂移，已禁止车辆测量", "job", spec.JobID,
				"source", groundSource, "inlier_ratio", refit.InlierRatio)
		case groundSource != "refit":
			groundDriftDeg, groundDriftMM = groundDrift(ground, refit)
			if groundDriftDeg > maxProductionGroundDriftDeg || groundDriftMM > maxProductionGroundDriftMM {
				groundStable = false
				groundReason = "ground_drift"
				r.Log.Warn("当前点云与背景地面漂移超生产门，已禁止车辆测量",
					"job", spec.JobID, "source", groundSource, "drift_deg", groundDriftDeg, "drift_mm", groundDriftMM)
			}
		}

		// 抠车隔离优先级：兼容的 A/B 单元背景 revision 是生产测量真理源；旧车位框只在尚无
		// 可用背景时作为显式 fallback，绝不能静默绕过背景兼容性并压过同域相减结果。
		mp := DefaultMeasureParams()
		measMode = "no_isolation"
		measCloud := cloudFus
		doMeasure := false
		boxA, okA := r.loadCropBox(ctx, spec.UnitAIP, "a")
		boxB, okB := r.loadCropBox(ctx, spec.UnitAIP, "b")
		switch {
		case spec.Align == "site" && !refineAccepted:
			measMode = "refine_unaccepted"
			r.Log.Warn("B→A 精修未通过，保留诊断点云但不生成车辆测量点云", "job", spec.JobID, "reason", refineReason)
		case !groundStable:
			measMode = groundReason
			r.Log.Warn("工位地面质量门未通过，保留诊断点云但不生成车辆测量点云", "job", spec.JobID,
				"reason", groundReason, "drift_deg", groundDriftDeg, "drift_mm", groundDriftMM)
		case bgCompatible:
			if spec.BackgroundRevision.CoordinateSchema == repo.LaserBackgroundSchemaLegacyVerifiedFused {
				// 历史兼容：背景和 live 都是区域裁剪后的 A 系融合云，严格重放修改前网页端路径。
				measCloud = SubtractBackground(cloudFus, backgroundFused, DefaultBackgroundParams())
			} else {
				// 新背景同域相减：A/B 分别在自身设备系相减，再以本次最终 B→A 合并。
				fgA := SubtractBackground(cloudA, backgroundA, DefaultBackgroundParams())
				fgB := SubtractBackground(regionCloudB, backgroundB, DefaultBackgroundParams())
				measCloud = append(append([]float32(nil), fgA...), transformPoints(fgB, res.BToA)...)
			}
			fgPts = len(measCloud) / 3
			if ground.Valid {
				mp = GroundMeasureParams([3]float32{ground.NX, ground.NY, ground.NZ}, ground.D, 30, 5000)
				mp.SupportBG = backgroundFused
			} else {
				mp = DefaultMeasureParams()
				mp.UseROI = false // 前景已隔离到车，直接整云主簇→OBB
			}
			mp.WidthSupportFrac = 0.15
			mp.WidthBinMM = 1
			mp.SpanTrimPct = 0.5
			measMode, doMeasure = "bg_subtract", true
		case bgSet:
			measMode = "background_incompatible"
			r.Log.Warn("空工位背景与当前采集不兼容，已阻止输出错误外廓", "job", spec.JobID,
				"revision", bgRevisionID, "schema", bgSchema, "reason", bgReason)
		case okA && okB:
			// 按镜头双框：各单元云按各自框去背景 → unitB 经 B→A 并入世界系 → 对隔离并集测量。
			measCloud = append(CropToBox(cloudA, boxA), transformPoints(CropToBox(cloudB, boxB), res.BToA)...)
			if ground.Valid {
				mp = GroundMeasureParams([3]float32{ground.NX, ground.NY, ground.NZ}, ground.D, 30, 5000)
			} else {
				mp = CropBoxMeasureParams(boxA)
			}
			measMode, doMeasure = "crop_box_dual", true
		case okA:
			mp = CropBoxMeasureParams(boxA)
			measMode, doMeasure = "crop_box", true
		case okB && ground.Valid:
			measCloud = transformPoints(CropToBox(cloudB, boxB), res.BToA)
			mp = GroundMeasureParams([3]float32{ground.NX, ground.NY, ground.NZ}, ground.D, 30, 5000)
			measMode, doMeasure = "crop_box_b", true
		default:
			r.Log.Info("无隔离手段(无空工位背景/无车位框)，跳过测量，提示先采集背景", "job", spec.JobID, "fused", gotFus)
		}
		if doMeasure {
			measuredPts = len(measCloud) / 3
			measuredCloud = append([]float32(nil), measCloud...)
			// 车型 carType 偏移叠到测量区域（裁剪框路径生效；地面/背景路径暂不接）。
			mp.CarOffset = carOffset
			// MeasureFull 一遍出外廓 LWH + 轴距/前后悬 + 货箱（同一车体点、同一 OBB 帧；docs/16 §3⑥）。
			dims, axle, cargo, overlay = MeasureFullWithOverlay(measCloud, mp, DefaultAxleParams())
			if !dims.Valid {
				r.Log.Warn("测量无效(点云退化?)", "job", spec.JobID, "mode", measMode, "fused", res.Fused, "body", dims.BodyPts, "fg", fgPts)
			}
			if axle.Valid {
				r.Log.Info("轴距测量", "job", spec.JobID, "axles", axle.NumAxles,
					"wheelbases", axle.WheelbasesMM, "front_over", axle.FrontOverhangMM, "rear_over", axle.RearOverhangMM)
			}
			if cargo.Valid && cargo.HasBox {
				r.Log.Info("货箱测量", "job", spec.JobID, "outer_len", cargo.OuterLengthMM,
					"outer_w", cargo.OuterWidthMM, "depth", cargo.DepthMM)
			}
		}
		// 法规规则未按车型、适用条件和版本核验前只能返回“未判定”。
		compl = ComplianceForVehicleType(dims, spec.VehicleTypeID)
		if spec.SiteCalibration.qualityUnverified() {
			compl = Compliance{Reason: "site_quality_unverified"}
		}
	}

	var measuredKey string
	var measuredArtifact *MeasuredCloudArtifact
	if len(measuredCloud) >= 3 {
		artifact := newMeasuredCloudArtifact(
			measuredCloud,
			res.BToA,
			spec.SiteCalibration.MatrixSHA256,
			spec.RegionCalibration.PointsSHA256,
			bgRevisionID,
		)
		if !artifact.validContentIdentity() {
			err = errors.New("车辆测量点云缺少 site/region/B→A 内容身份，已拒绝写入")
			r.failJob(ctx, spec.JobID, err.Error())
			return nil, err
		}
		measuredKey, err = r.Clouds.PutMeasuredCloud(ctx, spec.SessionKey, "measured", measuredCloud, artifact)
		if err != nil {
			r.failJob(ctx, spec.JobID, "存测量同源车辆点云失败: "+err.Error())
			return nil, err
		}
		measuredArtifact = &artifact
	}

	bToA, _ := json.Marshal(res.BToA)
	afterCrop := gotFus
	if measuredPts > 0 {
		afterCrop = measuredPts
	} else if !rawOnly && !spec.MarkAsBackground {
		afterCrop = 0
	}
	comp := repo.LaserScanCompletion{
		AlignMethod:       res.Align,
		PtsA:              gotA,
		PtsB:              gotB,
		Fused:             gotFus,
		AfterCrop:         afterCrop,
		FusedObjectKey:    fusedKey,
		UnitAObjectKey:    aKey,
		UnitBObjectKey:    bKey,
		MeasuredObjectKey: measuredKey,
		BToA:              bToA,
		Stats: mustJSON(map[string]any{
			"align_method":           res.Align,
			"pts_a":                  gotA,
			"pts_b":                  gotB,
			"fused":                  gotFus,
			"raw_pts_a":              res.PtsA,
			"raw_pts_b":              res.PtsB,
			"raw_fused":              res.Fused,
			"region_filter":          spec.RegionFilter,
			"region_calibration":     spec.RegionCalibration,
			"keep_ratio":             spec.KeepRatio,
			"unit_a_profile":         spec.UnitAProfile,
			"unit_b_profile":         spec.UnitBProfile,
			"measure":                dims,
			"axle":                   axle,
			"cargo_box":              cargo,
			"overlay":                overlay,
			"measure_mode":           measMode,
			"measure_reason":         measurementReason(measMode, dims.Valid),
			"bg_set":                 bgSet,
			"bg_captured":            bgCaptured,
			"fg_pts":                 fgPts,
			"fg_points":              fgPts,
			"measured_points":        measuredPts,
			"measured_artifact":      measuredArtifact,
			"background_compatible":  bgCompatible,
			"background_reason":      bgReason,
			"background_revision_id": bgRevisionID,
			"background_schema":      bgSchema,
			"vehicle_type_id":        spec.VehicleTypeID,
			"car_offset":             carOffset,
			"compliance":             compl,
			"ground":                 ground,
			// M13 精度收敛监控：地面来源/漂移 + B→A 精修量（标定健康度，漂移大 = 工位被动过）
			"ground_source":    groundSource,
			"ground_reason":    groundReason,
			"ground_drift_deg": groundDriftDeg,
			"ground_drift_mm":  groundDriftMM,
			"ground_stable":    groundStable,
			"b_to_a_refine":    refineStats,
			"refine_accepted":  refineAccepted,
			"refine_reason":    refineReason,
			"site_calibration": spec.SiteCalibration,
		}),
	}
	var job *repo.LaserScanJob
	if pendingBackground != nil {
		var activated *repo.LaserBackgroundRevision
		job, activated, err = r.BackgroundFinalizer.ActivateAndComplete(ctx, spec.JobID, comp, *pendingBackground)
		if err != nil {
			return nil, fmt.Errorf("背景任务原子完成失败: %w", err)
		}
		bgRevisionID = activated.ID
		if ground.Valid && r.Grounds != nil {
			if gerr := r.Grounds.SaveGround(context.WithoutCancel(ctx), spec.UnitAIP, ground); gerr != nil {
				r.Log.Warn("背景任务已完成，但持久化地面失败；后续仍从 A/B 背景重建地面", "job", spec.JobID, "err", gerr)
			}
		}
		r.Log.Info("已原子激活 A/B 区域空工位背景", "job", spec.JobID, "revision", activated.ID,
			"pts_a", activated.UnitAPoints, "pts_b", activated.UnitBPoints)
	} else {
		job, err = r.Jobs.Complete(ctx, spec.JobID, comp)
		if err != nil {
			return nil, fmt.Errorf("repo.Complete 失败: %w", err)
		}
	}

	publishRes := res
	publishRes.PtsA = gotA
	publishRes.PtsB = gotB
	publishRes.Fused = gotFus
	publishRes.AfterCrop = afterCrop
	r.publishDone(ctx, spec, job, publishRes, fusedKey, aKey, bKey, measuredKey, measuredArtifact, dims, axle, cargo, overlay, compl, ground,
		measMode, bgCaptured, bgSet, bgCompatible, bgReason, bgRevisionID, bgSchema, fgPts, measuredPts)
	return job, nil
}

func (r *Runner) publishDone(ctx context.Context, spec RunSpec, job *repo.LaserScanJob,
	res ScanResult, fusedKey, aKey, bKey, measuredKey string, measuredArtifact *MeasuredCloudArtifact, dims Dimensions, axle AxleResult, cargo CargoBox, overlay VehicleOverlay, compl Compliance, ground GroundPlane,
	measMode string, bgCaptured, bgSet, bgCompatible bool, bgReason string, bgRevisionID int64, bgSchema string, fgPts, measuredPts int) {
	if r.Publisher == nil {
		return
	}
	evt := FusionDoneEvent{
		Kind:                      "laser",
		JobID:                     job.ID,
		SessionKey:                spec.SessionKey,
		InspectionID:              spec.InspectionID,
		OwnerUserID:               spec.OwnerUserID,
		ResultObjectKey:           fusedKey,
		UnitAObjectKey:            aKey,
		UnitBObjectKey:            bKey,
		MeasuredObjectKey:         measuredKey,
		Points:                    res.Fused,
		PtsA:                      res.PtsA,
		PtsB:                      res.PtsB,
		AlignMethod:               res.Align,
		SiteRevision:              spec.SiteCalibration.MatrixSHA256,
		RegionRevision:            spec.RegionCalibration.PointsSHA256,
		SiteQualityVerified:       spec.SiteCalibration.qualityVerified(),
		SiteQualityOverride:       spec.SiteCalibration.qualityOverrideEnabled(),
		SiteQualityOverrideReason: spec.SiteCalibration.QualityOverride,
		ProductionEligible:        spec.SiteCalibration.productionEligible(),
		LengthMM:                  dims.LengthMM,
		WidthMM:                   dims.WidthMM,
		HeightMM:                  dims.HeightMM,
		MeasureValid:              dims.Valid,
		ComplianceDetermined:      compl.Determined,
		ComplianceReason:          compl.Reason,
		Compliant:                 compl.Compliant,
		Violations:                compl.Violations,
		MeasMode:                  measMode,
		MeasureReason:             measurementReason(measMode, dims.Valid),
		BackgroundCaptured:        bgCaptured,
		BackgroundSet:             bgSet,
		BackgroundCompatible:      bgCompatible,
		BackgroundIncompatible:    bgSet && !bgCompatible,
		BackgroundReason:          bgReason,
		BackgroundRevisionID:      bgRevisionID,
		BackgroundSchema:          bgSchema,
		FgPoints:                  fgPts,
		MeasuredPoints:            measuredPts,
		MeasuredArtifact:          measuredArtifact,
		NumAxles:                  axle.NumAxles,
		WheelbasesMM:              axle.WheelbasesMM,
		TotalWheelbaseMM:          axle.TotalWheelbaseMM,
		FrontOverhangMM:           axle.FrontOverhangMM,
		RearOverhangMM:            axle.RearOverhangMM,
		AxleValid:                 axle.Valid,
		HasCargoBox:               cargo.Valid && cargo.HasBox,
		BoxOuterLengthMM:          cargo.OuterLengthMM,
		BoxOuterWidthMM:           cargo.OuterWidthMM,
		BoxDepthMM:                cargo.DepthMM,
		BoxInnerWidthMM:           cargo.InnerWidthMM,
		Overlay:                   overlayPtr(overlay),
		GroundNX:                  ground.NX,
		GroundNY:                  ground.NY,
		GroundNZ:                  ground.NZ,
		GroundD:                   ground.D,
		GroundValid:               ground.Valid,
	}
	if err := r.Publisher.Publish(ctx, TopicFusionDone, evt); err != nil {
		r.Log.Warn("发布 scan.fusion_done(laser) 失败", "err", err, "job", job.ID)
	}
}

func measurementReason(mode string, valid bool) string {
	if valid {
		return ""
	}
	switch mode {
	case "background_captured", "background_incompatible", "no_isolation", "background_capture_failed",
		"refine_unaccepted", "ground_invalid", "ground_refit_invalid", "ground_drift":
		return mode
	case "unfused", "raw":
		return "raw"
	default:
		return "measurement_invalid"
	}
}

func productionRefineAcceptance(stats RefineBToAStats) (bool, string) {
	return productionRefineAcceptanceWithLimits(
		stats,
		maxProductionRefineDeltaTransMM,
		maxProductionRefineDeltaRotDeg,
	)
}

func productionRefineAcceptanceForBackground(stats RefineBToAStats, backgroundSchema string) (bool, string) {
	if backgroundSchema == repo.LaserBackgroundSchemaLegacyVerifiedFused {
		// 修改前网页链路会使用本次点到面最终解重建 fused，再减已裁剪旧背景；旧 site 缺少生产质量
		// 证据，精修量本身不能作为拒绝依据。完整 compatibility binding 命中时仍要求 applied、
		// pairs、RMS，并保留 RefineBToA 自身 150mm/5° 发散守卫；新 A/B schema 继续使用 50mm/1°。
		guard := DefaultRefineBToAParams()
		return productionRefineAcceptanceWithLimits(
			stats,
			guard.MaxDeltaTrans,
			guard.MaxDeltaRotDe,
		)
	}
	return productionRefineAcceptance(stats)
}

func productionRefineAcceptanceWithLimits(stats RefineBToAStats, maxDeltaTransMM, maxDeltaRotDeg float32) (bool, string) {
	if !stats.Applied {
		if stats.Reason != "" {
			return false, stats.Reason
		}
		return false, "not_applied"
	}
	if stats.Pairs < DefaultRefineBToAParams().MinPairs {
		return false, "insufficient_pairs"
	}
	rms := float64(stats.RMSMM)
	if math.IsNaN(rms) || math.IsInf(rms, 0) || stats.RMSMM <= 0 || stats.RMSMM > maxProductionRefineRMSMM {
		return false, "rms_too_large"
	}
	if stats.DeltaTransMM > maxDeltaTransMM {
		return false, "translation_delta_too_large"
	}
	if stats.DeltaRotDeg > maxDeltaRotDeg {
		return false, "rotation_delta_too_large"
	}
	return true, "accepted"
}

func (r *Runner) failJob(ctx context.Context, id int64, msg string) {
	if r.Jobs == nil {
		return
	}
	if _, err := r.Jobs.Fail(context.WithoutCancel(ctx), id, msg); err != nil {
		r.Log.Warn("repo.Fail 失败", "err", err, "job", id)
	}
}

func prepareSiteExtrinsicArg(align, siteJSON string) (string, func(), error) {
	noop := func() {}
	if align != "site" {
		return "", noop, nil
	}
	raw := strings.TrimSpace(siteJSON)
	if raw == "" {
		return "", noop, fmt.Errorf("align=site 需要 site_json")
	}
	if json.Valid([]byte(raw)) {
		if err := validateSiteExtrinsicJSON(raw); err != nil {
			return "", noop, err
		}
		return materializeSiteExtrinsicJSON(raw)
	}
	if _, err := os.Stat(raw); err == nil {
		return raw, noop, nil
	}
	return "", noop, fmt.Errorf("site_json 必须是合法 JSON 或可读取文件路径")
}

func validateSiteExtrinsicJSON(raw string) error {
	_, err := parseSiteMatrix(raw)
	return err
}

func materializeSiteExtrinsicJSON(raw string) (string, func(), error) {
	dir := filepath.Join(".dev", "laser-site")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", func() {}, fmt.Errorf("创建 site 外参目录失败: %w", err)
	}
	f, err := os.CreateTemp(dir, "site-*.json")
	if err != nil {
		return "", func() {}, fmt.Errorf("创建 site 外参文件失败: %w", err)
	}
	name := f.Name()
	var writeErr error
	if _, err := f.WriteString(raw); err != nil {
		writeErr = err
	} else if _, err := f.WriteString("\n"); err != nil {
		writeErr = err
	}
	if err := f.Close(); writeErr == nil && err != nil {
		writeErr = err
	}
	if writeErr != nil {
		_ = os.Remove(name)
		return "", func() {}, fmt.Errorf("写入 site 外参文件失败: %w", writeErr)
	}
	abs, err := filepath.Abs(name)
	if err != nil {
		abs = name
	}
	return abs, func() { _ = os.Remove(abs) }, nil
}

func flipVerticalBToA(m [16]float32) [16]float32 {
	signs := [4]float32{1, 1, -1, 1}
	var out [16]float32
	for r := 0; r < 4; r++ {
		for c := 0; c < 4; c++ {
			out[r*4+c] = m[r*4+c] * signs[r] * signs[c]
		}
	}
	return out
}

// loadCropBox 取该装机点(bayKey=unit_a_ip)某单元(unit a|b)的持久车位框；无 store / 未设置 / 框退化 /
// 取错均回 ok=false（非致命：测量回退自动地面/设备系 ROI）。
func (r *Runner) loadCropBox(ctx context.Context, unitAIP, unit string) (CropBox, bool) {
	if r.CropBoxes == nil || unitAIP == "" {
		return CropBox{}, false
	}
	box, ok, err := r.CropBoxes.GetCropBox(ctx, unitAIP, unit)
	if err != nil {
		r.Log.Warn("取车位框失败", "err", err, "bay", unitAIP, "unit", unit)
		return CropBox{}, false
	}
	if !ok || !box.Valid() {
		return CropBox{}, false
	}
	return box, true
}

// loadGround 取该装机点(bayKey=unit_a_ip)持久地面平面；无 store / 未设置 / 取错均回 ok=false
// （非致命：回退逐扫描 RANSAC）。
func (r *Runner) loadGround(ctx context.Context, bayKey string) (GroundPlane, bool) {
	if r.Grounds == nil || bayKey == "" {
		return GroundPlane{}, false
	}
	g, ok, err := r.Grounds.GetGround(ctx, bayKey)
	if err != nil {
		r.Log.Warn("取持久地面失败", "err", err, "bay", bayKey)
		return GroundPlane{}, false
	}
	return g, ok
}

// groundDrift 两个地面平面的差：法向夹角(度) + d 差(mm)。诊断"工位被挪动/设备松动"。
func groundDrift(a, b GroundPlane) (deg, mm float32) {
	dot := float64(a.NX*b.NX + a.NY*b.NY + a.NZ*b.NZ)
	if dot > 1 {
		dot = 1
	}
	if dot < -1 {
		dot = -1
	}
	deg = float32(math.Acos(math.Abs(dot)) * 180 / math.Pi)
	mm = float32(math.Abs(float64(a.D - b.D)))
	return
}

// regionFilterWithBToA 围栏过滤器换用给定 B→A（精修后），保证 B 的围栏裁剪与融合摆放同一变换。
// 未启用围栏/原本无 B→A（unit_b 不做世界系映射）时原样返回。
func regionFilterWithBToA(f PointRegionFilter, m [16]float32) PointRegionFilter {
	if !f.Enabled || len(f.BToA) != 16 {
		return f
	}
	nb := make([]float32, 16)
	copy(nb, m[:])
	f.BToA = nb
	return f
}

// loadBackgroundUnitClouds 读取并校验不可变 A/B 单元背景；任何对象/点数/checksum 异常都使本次测量失效。
func (r *Runner) loadBackgroundUnitClouds(
	ctx context.Context,
	rev *repo.LaserBackgroundRevision,
) ([]float32, []float32, string) {
	if r.Reader == nil {
		return nil, nil, "cloud_reader_unavailable"
	}
	if rev == nil || rev.UnitAObjectKey == nil || rev.UnitBObjectKey == nil ||
		rev.UnitAChecksum == nil || rev.UnitBChecksum == nil {
		return nil, nil, "revision_metadata_incomplete"
	}
	load := func(unit, key string, expectedPoints int64, checksum string) ([]float32, string) {
		rc, _, err := r.Reader.GetObject(ctx, key)
		if err != nil {
			r.Log.Warn("读取单元背景对象失败", "revision", rev.ID, "unit", unit, "key", key, "err", err)
			return nil, "raw_object_unavailable"
		}
		defer rc.Close()
		raw, err := io.ReadAll(rc)
		if err != nil {
			r.Log.Warn("读取单元背景内容失败", "revision", rev.ID, "unit", unit, "err", err)
			return nil, "raw_object_unreadable"
		}
		xyz, err := DecodePCDBinary(raw)
		if err != nil || len(xyz) < 3 {
			r.Log.Warn("解析单元背景失败", "revision", rev.ID, "unit", unit, "err", err, "bytes", len(raw))
			return nil, "raw_object_invalid"
		}
		if int64(len(xyz)/3) != expectedPoints {
			return nil, "background_point_count_mismatch"
		}
		if cloudFloatSHA256(xyz) != checksum {
			return nil, "background_checksum_mismatch"
		}
		return xyz, "ready"
	}
	a, reason := load("a", *rev.UnitAObjectKey, rev.UnitAPoints, *rev.UnitAChecksum)
	if reason != "ready" {
		return nil, nil, reason
	}
	b, reason := load("b", *rev.UnitBObjectKey, rev.UnitBPoints, *rev.UnitBChecksum)
	if reason != "ready" {
		return nil, nil, reason
	}
	return a, b, "ready"
}

// loadLegacyFusedBackground 读取修改前网页端使用的区域裁剪融合背景。
// 只接受独立 compatibility binding 验证过的 schema，并逐点校验融合点数与 XYZ checksum；
// 绑定字段表示“与当前配置已验证”，不冒充采集时 site/region 元数据。
func (r *Runner) loadLegacyFusedBackground(
	ctx context.Context,
	rev *repo.LaserBackgroundRevision,
) ([]float32, string) {
	if r.Reader == nil {
		return nil, "cloud_reader_unavailable"
	}
	if rev == nil || rev.CoordinateSchema != repo.LaserBackgroundSchemaLegacyVerifiedFused ||
		rev.LegacyFusedObjectKey == nil || strings.TrimSpace(*rev.LegacyFusedObjectKey) == "" {
		return nil, "legacy_fused_object_missing"
	}
	rc, _, err := r.Reader.GetObject(ctx, *rev.LegacyFusedObjectKey)
	if err != nil {
		r.Log.Warn("读取历史融合背景对象失败", "revision", rev.ID, "key", *rev.LegacyFusedObjectKey, "err", err)
		return nil, "legacy_fused_object_unavailable"
	}
	defer rc.Close()
	raw, err := io.ReadAll(rc)
	if err != nil {
		r.Log.Warn("读取历史融合背景内容失败", "revision", rev.ID, "err", err)
		return nil, "legacy_fused_object_unreadable"
	}
	xyz, err := DecodePCDBinary(raw)
	if err != nil || len(xyz) < 3 {
		r.Log.Warn("解析历史融合背景失败", "revision", rev.ID, "err", err, "bytes", len(raw))
		return nil, "legacy_fused_object_invalid"
	}
	if int64(len(xyz)/3) != rev.LegacyFusedPoints {
		return nil, "legacy_fused_point_count_mismatch"
	}
	if rev.LegacyFusedChecksum == nil || cloudFloatSHA256(xyz) != *rev.LegacyFusedChecksum {
		return nil, "legacy_fused_checksum_mismatch"
	}
	return xyz, "ready"
}

func mustJSON(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		return json.RawMessage(`{}`)
	}
	return b
}
