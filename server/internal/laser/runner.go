package laser

import (
	"context"
	"encoding/json"
	"fmt"
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

const realtimePreviewFlushInterval = 120 * time.Millisecond
const realtimePreviewBatchMaxPoints = 24_000

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

	// 轴距/前后悬（M9.4，几何贴地接触带检测；docs/16 §3⑥ caluteDeepWheel 等价）。
	NumAxles         int       `json:"num_axles,omitempty"`
	WheelbasesMM     []float32 `json:"wheelbases_mm,omitempty"`
	TotalWheelbaseMM float32   `json:"total_wheelbase_mm,omitempty"`
	FrontOverhangMM  float32   `json:"front_overhang_mm,omitempty"`
	RearOverhangMM   float32   `json:"rear_overhang_mm,omitempty"`
	AxleValid        bool      `json:"axle_valid"`

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
	Jobs      JobStore
	Clouds    CloudStore
	Publisher Publisher    // 可空（不发 NATS）
	Gate      DeviceGate   // 可空（replay/test）
	CropBoxes CropBoxStore // 可空（无则回退自动地面测量）：持久车位框，按 bayKey=unit_a_ip 取
	Live      ScanFunc
	Replay    ScanFunc
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
	JobID         int64
	SessionKey    string
	InspectionID  *int64
	OwnerUserID   *int64
	UnitAIP       string
	UnitBIP       string
	Align         string
	SiteJSON      string
	KeepRatio     float32
	VehicleTypeID int // 逆向 JCHY 车型编号（docs/16 §4.1）；-1=未选。驱动 carType 偏移 + 按型合规 + 记录

	Replay bool // host 测试：用录制 .bin 取代实时
	BinA   string
	BinB   string

	ExpectedSweepADeg float32 // 当前 unitA 配置的线性目标扫掠角，仅用于诊断日志
	ExpectedSweepBDeg float32 // 当前 unitB 配置的线性目标扫掠角，仅用于诊断日志

	RegionFilter PointRegionFilter // 可选：工位区域墙过滤，只保留墙内点云。
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
	cloudA = filterXYZForUnit(cloudA, 0, spec.RegionFilter)
	cloudB = filterXYZForUnit(cloudB, 1, spec.RegionFilter)
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
		colorXYZ, colorRGB := filterXYZRGBForUnit(colorBXYZ, rgbB, 1, spec.RegionFilter)
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
	var compl Compliance
	measMode := "unfused"
	carOffset := CarTypeOffset(spec.VehicleTypeID)
	if rawOnly {
		r.Log.Info("工位未标定，已跳过融合和外廓测量", "job", spec.JobID, "pts_a", gotA, "pts_b", gotB)
	} else {
		// 地面检测：融合后 RANSAC 拟合地面平面。一份数据两用：① 端侧视角预设的"上"方向基准；
		// ② 测量参考系（设备坐标原点随底座而变，硬编码 ROI 不通用 → 用真实地面做参考系）。
		// 非致命——检不到只记 valid=false，端侧回退 +Z，测量回退原厂设备系 ROI 路径。
		ground = DetectGround(cloudFus, DefaultGroundParams())
		if !ground.Valid {
			r.Log.Warn("地面检测失败(点云稀疏?)", "job", spec.JobID, "inlier_ratio", ground.InlierRatio)
		}

		// M9.11/M9.6/M9.10 测量：融合后对 fused 云算车长/宽/高 + GB7258 合规。优先级：
		//   ① 持久车位框(CropBox)——用户一次圈定、世界系定向、按深度隔离背景，不依赖自动地面（首选）；
		//   ② 地面相对——自动地面有效时坐标系无关路径（高度 band + 地面投影 OBB）；
		//   ③ 原厂设备系 ROI——回退基线（JCHY 真值 ≤2.5%）。
		// 非致命——测量无效不让 job 失败，只记 measure_valid=false（docs/16 §3⑥/§8）。
		mp := DefaultMeasureParams()
		measMode = "device_roi"
		measCloud := cloudFus
		boxA, okA := r.loadCropBox(ctx, spec.UnitAIP, "a")
		boxB, okB := r.loadCropBox(ctx, spec.UnitAIP, "b")
		switch {
		case okA && okB:
			// 按镜头双框：各单元云按各自框去背景 → unitB 经 B→A 并入世界系 → 对隔离并集测量。
			// 每台相机看到的背景不同，分别在各自点云空间裁剪，比单一世界框隔离更干净。
			measCloud = append(CropToBox(cloudA, boxA), transformPoints(CropToBox(cloudB, boxB), res.BToA)...)
			if ground.Valid {
				// 并集已隔离到车体；用地面正交基测量（坐标系无关，高度从地面量起）。
				mp = GroundMeasureParams([3]float32{ground.NX, ground.NY, ground.NZ}, ground.D, 30, 5000)
			} else {
				mp = CropBoxMeasureParams(boxA) // 无地面：退回 A 框系定向测量。
			}
			measMode = "crop_box_dual"
		case okA:
			// 仅 A 框（含历史单框迁移数据）：A 框 == 世界系，裁融合云测量（权威路径不变）。
			mp = CropBoxMeasureParams(boxA)
			measMode = "crop_box"
		case okB && ground.Valid:
			// 仅 B 框 + 有地面：B 云按 B 框去背景 → 经 B→A 入世界系 → 地面相对测量（A 框缺时用 B 的部分外廓，
			// 不静默丢弃 B 标注）。无地面则无法把 B 框定向到世界系，落到下方 device_roi 回退。
			measCloud = transformPoints(CropToBox(cloudB, boxB), res.BToA)
			mp = GroundMeasureParams([3]float32{ground.NX, ground.NY, ground.NZ}, ground.D, 30, 5000)
			measMode = "crop_box_b"
		case ground.Valid:
			mp = GroundMeasureParams([3]float32{ground.NX, ground.NY, ground.NZ}, ground.D, 30, 5000)
			measMode = "ground"
		}
		// 车型 carType 偏移：选定车型把该型 (x,y,z) 偏移叠到测量区域（设备 ROI/裁剪框路径生效；地面路径暂不接）。
		mp.CarOffset = carOffset
		// MeasureFull 一遍出外廓 LWH + 轴距/前后悬（同一车体点、同一 OBB 帧；docs/16 §3⑥）。
		dims, axle = MeasureFull(measCloud, mp, DefaultAxleParams())
		// 合规按车型套限值（当前逐型限值未录入，LimitsForVehicleType 回退通用值，见其 TODO）。
		compl = CheckCompliance(dims, LimitsForVehicleType(spec.VehicleTypeID))
		if !dims.Valid {
			r.Log.Warn("测量无效(点云退化?)", "job", spec.JobID, "mode", measMode, "fused", res.Fused, "body", dims.BodyPts)
		}
		if axle.Valid {
			r.Log.Info("轴距测量", "job", spec.JobID, "axles", axle.NumAxles,
				"wheelbases", axle.WheelbasesMM, "front_over", axle.FrontOverhangMM, "rear_over", axle.RearOverhangMM)
		}
	}

	bToA, _ := json.Marshal(res.BToA)
	comp := repo.LaserScanCompletion{
		AlignMethod:    res.Align,
		PtsA:           gotA,
		PtsB:           gotB,
		Fused:          gotFus,
		AfterCrop:      gotFus,
		FusedObjectKey: fusedKey,
		UnitAObjectKey: aKey,
		UnitBObjectKey: bKey,
		BToA:           bToA,
		Stats: mustJSON(map[string]any{
			"align_method":    res.Align,
			"pts_a":           gotA,
			"pts_b":           gotB,
			"fused":           gotFus,
			"raw_pts_a":       res.PtsA,
			"raw_pts_b":       res.PtsB,
			"raw_fused":       res.Fused,
			"region_filter":   spec.RegionFilter,
			"keep_ratio":      spec.KeepRatio,
			"measure":         dims,
			"axle":            axle,
			"measure_mode":    measMode,
			"vehicle_type_id": spec.VehicleTypeID,
			"car_offset":      carOffset,
			"compliance":      compl,
			"ground":          ground,
		}),
	}
	job, err := r.Jobs.Complete(ctx, spec.JobID, comp)
	if err != nil {
		return nil, fmt.Errorf("repo.Complete 失败: %w", err)
	}

	publishRes := res
	publishRes.PtsA = gotA
	publishRes.PtsB = gotB
	publishRes.Fused = gotFus
	publishRes.AfterCrop = gotFus
	r.publishDone(ctx, spec, job, publishRes, fusedKey, aKey, bKey, dims, axle, compl, ground)
	return job, nil
}

func (r *Runner) publishDone(ctx context.Context, spec RunSpec, job *repo.LaserScanJob,
	res ScanResult, fusedKey, aKey, bKey string, dims Dimensions, axle AxleResult, compl Compliance, ground GroundPlane) {
	if r.Publisher == nil {
		return
	}
	evt := FusionDoneEvent{
		Kind:             "laser",
		JobID:            job.ID,
		SessionKey:       spec.SessionKey,
		InspectionID:     spec.InspectionID,
		OwnerUserID:      spec.OwnerUserID,
		ResultObjectKey:  fusedKey,
		UnitAObjectKey:   aKey,
		UnitBObjectKey:   bKey,
		Points:           res.Fused,
		PtsA:             res.PtsA,
		PtsB:             res.PtsB,
		AlignMethod:      res.Align,
		LengthMM:         dims.LengthMM,
		WidthMM:          dims.WidthMM,
		HeightMM:         dims.HeightMM,
		MeasureValid:     dims.Valid,
		Compliant:        compl.Compliant,
		Violations:       compl.Violations,
		NumAxles:         axle.NumAxles,
		WheelbasesMM:     axle.WheelbasesMM,
		TotalWheelbaseMM: axle.TotalWheelbaseMM,
		FrontOverhangMM:  axle.FrontOverhangMM,
		RearOverhangMM:   axle.RearOverhangMM,
		AxleValid:        axle.Valid,
		GroundNX:         ground.NX,
		GroundNY:         ground.NY,
		GroundNZ:         ground.NZ,
		GroundD:          ground.D,
		GroundValid:      ground.Valid,
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
	var payload struct {
		BToA []float64 `json:"b_to_a"`
	}
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		return fmt.Errorf("site_json 必须是合法 JSON: %w", err)
	}
	if len(payload.BToA) != 16 {
		return fmt.Errorf("site_json.b_to_a 必须包含 16 个数")
	}
	for _, v := range payload.BToA {
		if math.IsNaN(v) || math.IsInf(v, 0) {
			return fmt.Errorf("site_json.b_to_a 不能包含非有限数")
		}
	}
	return nil
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

func mustJSON(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		return json.RawMessage(`{}`)
	}
	return b
}
