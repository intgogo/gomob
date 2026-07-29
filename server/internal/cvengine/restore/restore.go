package restore

import (
	"context"
	"errors"
	"time"

	"io.gomob/server/internal/cvengine/gocv"
)

// StageTimings 记录一次成功还原各阶段的壁钟耗时，仅用于性能观测，不进入 API 契约。
// RegionMS 与 CharDetectMS 是远程调用（含网络往返），其余为本地计算。
type StageTimings struct {
	DecodeMS      float64
	RegionMS      float64
	FrameMS       float64
	ProbeRenderMS float64
	ProbeEncodeMS float64
	CharDetectMS  float64
	AnchorMS      float64
	FinalRenderMS float64
	PNGEncodeMS   float64
	RulerMS       float64
	TotalMS       float64
}

// Result —— 一次还原的全部产物。
//
// PNG 是干净的规范图：OCR 输入、一致性与逐字节等价验收都以它为准，任何显示层装饰都不得写进去。
// RulerPNG 是同一张图叠四周毫米刻度尺的展示/存档副本，与 PNG 像素尺寸相同。
type Result struct {
	PNG      []byte
	RulerPNG []byte
	Meta     Meta
}

// Meta —— 还原结果元信息（随 PNG 一起返回，便于上游判定/记录）。
type Meta struct {
	OK                   bool              `json:"ok"`
	TiltDeg              float64           `json:"tilt_deg"`
	WidthMM              float64           `json:"width_mm"`
	HeightMM             float64           `json:"height_mm"`
	ThetaDeg             float64           `json:"theta_deg"`
	InlierRate           float64           `json:"inlier_rate"`
	RMS                  float64           `json:"rms"`
	MedZ                 float64           `json:"med_z"`
	OutW                 int               `json:"out_w"`
	OutH                 int               `json:"out_h"`
	NumDet               int               `json:"num_det"`
	AnchorCount          int               `json:"anchor_count"`
	AnchorCandidateCount int               `json:"anchor_candidate_count"`
	AnchorPitchPx        float64           `json:"anchor_pitch_px"`
	AnchorRMSPx          float64           `json:"anchor_rms_px"`
	AnchorMeanScore      float64           `json:"anchor_mean_score"`
	AnchorHeightPx       float64           `json:"anchor_height_px"`
	AnchorRotationDeg    float64           `json:"anchor_rotation_deg"`
	AnchorScale          float64           `json:"anchor_scale"`
	CalibrationSHA256    string            `json:"calibration_sha256"`
	CalibrationVersion   uint32            `json:"calibration_version"`
	Metrics              *CharacterMetrics `json:"character_metrics,omitempty"`
	Timings              StageTimings      `json:"-"`
}

// ErrTiltTooLarge 承印面倾角超过 MaxTiltDeg（原厂硬门，码 34）。
var ErrTiltTooLarge = errors.New("tilt 超过门限（承印面过斜）")

// ErrVinNotDetected 表示当前彩色帧没有可靠 VIN 区域，属于可重拍的采集判废。
var ErrVinNotDetected = errors.New("未检测到 VIN 区域")

// Restore —— VIN 数码拓印还原主入口。
//
// 入参：
//
//	provider 外部视觉服务（VMASK 区域 + VINS 字符），模型只在 gosmart 一处维护
//	rgb      彩色 JPEG 字节（rgb1300）；原样转发给 VMASK，不再本地解码送模型
//	calibration 与完整 rig/profile 绑定的原厂不可变标定
//	depth    RS-D550 mode25 原始 1/8px 视差（小端 u16，dw×dh）
//	dw,dh    深度宽高
//
// 出参：Result（干净规范图 PNG + 叠刻度尺展示图 + Meta）+ err；黑白签名图只作内部质量/对齐信号。
// tilt>70 时返回 ErrTiltTooLarge（meta.OK=false 且带 tilt 值），非系统级错误。
func Restore(
	ctx context.Context,
	provider VisionProvider,
	calibration *VinCalibration,
	rgb []byte, depth []byte,
	dw, dh int,
) (Result, error) {
	totalStarted := time.Now()
	timings := StageTimings{}
	if calibration == nil {
		return Result{}, ErrVinCalibrationUnavailable
	}
	if calibration.key.DepthWidth != dw || calibration.key.DepthHeight != dh {
		return Result{}, errors.New("深度尺寸与原厂标定 profile 不一致")
	}
	if len(rgb) == 0 {
		return Result{}, errors.New("rgb 为空")
	}
	if len(depth) < dw*dh*2 {
		return Result{}, errors.New("depth 字节数与 dw*dh*2 不符")
	}

	// 彩色 HLSD8 原始帧直接用于检测；几何投影由原厂 CCameraModel 私有模型完成。
	stageStarted := time.Now()
	colorBGR, err := gocv.IMDecode(rgb, gocv.IMReadColor)
	timings.DecodeMS = elapsedMS(stageStarted)
	if err != nil || colorBGR.Empty() {
		return Result{}, errors.New("彩色解码失败")
	}
	defer func() { _ = colorBGR.Release() }()

	// VMASK 直接吃原始 JPEG，省掉一次重编码；本地解码出的 Mat 只用于后续像素采样。
	stageStarted = time.Now()
	dets, err := provider.LocateVinRegions(ctx, rgb)
	timings.RegionMS = elapsedMS(stageStarted)
	if err != nil {
		return Result{}, err
	}

	stageStarted = time.Now()
	f, tilt, err := buildFrame(depth, dw, dh, calibration, colorBGR, dets)
	timings.FrameMS = elapsedMS(stageStarted)
	if err != nil {
		return Result{Meta: Meta{TiltDeg: tilt, NumDet: len(dets)}}, err
	}
	if f == nil {
		// tilt 门：废弃
		return Result{Meta: Meta{OK: false, TiltDeg: tilt, NumDet: len(dets)}}, ErrTiltTooLarge
	}

	stageStarted = time.Now()
	probe, probeAxes, probeMMPerPixel, err := renderCanonicalProbe(f)
	timings.ProbeRenderMS = elapsedMS(stageStarted)
	if err != nil {
		return Result{Meta: Meta{TiltDeg: tilt, NumDet: len(dets)}}, err
	}
	stageStarted = time.Now()
	probeW, probeH := probe.Cols(), probe.Rows()
	probePNG, encErr := gocv.IMEncode(gocv.PNGFileExt, probe)
	_ = probe.Release()
	if encErr != nil {
		return Result{Meta: Meta{TiltDeg: tilt, NumDet: len(dets)}}, encErr
	}
	timings.ProbeEncodeMS = elapsedMS(stageStarted)

	stageStarted = time.Now()
	charBoxes, err := provider.DetectCharacters(ctx, probePNG)
	timings.CharDetectMS = elapsedMS(stageStarted)
	var anchor textAnchor
	if err == nil {
		stageStarted = time.Now()
		anchor, err = buildTextAnchor(charBoxes, probeW, probeH)
		timings.AnchorMS = elapsedMS(stageStarted)
	}
	if err != nil {
		anchorScale := 0.0
		if anchor.PitchPx > 0 {
			anchorScale = canonicalProbeTargetPitchPx / anchor.PitchPx
		}
		return Result{Meta: Meta{
			TiltDeg: tilt, NumDet: len(dets),
			AnchorCount: anchor.Count, AnchorCandidateCount: anchor.CandidateCount,
			AnchorPitchPx: anchor.PitchPx, AnchorRMSPx: anchor.RMSPx,
			AnchorMeanScore: anchor.MeanScore, AnchorHeightPx: anchor.MedianHeightPx,
			AnchorRotationDeg: -anchor.AngleDeg(),
			AnchorScale:       anchorScale,
		}}, err
	}
	stageStarted = time.Now()
	rect, outW, outH, grid, err := renderTextCanonical(f, probeAxes, probeMMPerPixel, anchor)
	timings.FinalRenderMS = elapsedMS(stageStarted)
	if err != nil {
		return Result{Meta: Meta{TiltDeg: tilt, NumDet: len(dets)}}, err
	}
	defer func() { _ = rect.Release() }()

	metrics, err := buildCharacterMetrics(anchor, grid)
	if err != nil {
		return Result{Meta: Meta{TiltDeg: tilt, NumDet: len(dets)}}, err
	}

	meta := Meta{
		TiltDeg:              f.tilt,
		WidthMM:              f.width,
		HeightMM:             f.height,
		ThetaDeg:             f.theta,
		InlierRate:           f.plane.InlierRate,
		RMS:                  f.plane.RMS,
		MedZ:                 f.plane.MedZ,
		OutW:                 outW,
		OutH:                 outH,
		NumDet:               len(dets),
		AnchorCount:          anchor.Count,
		AnchorCandidateCount: anchor.CandidateCount,
		AnchorPitchPx:        anchor.PitchPx,
		AnchorRMSPx:          anchor.RMSPx,
		AnchorMeanScore:      anchor.MeanScore,
		AnchorHeightPx:       anchor.MedianHeightPx,
		AnchorRotationDeg:    -anchor.AngleDeg(),
		AnchorScale:          canonicalProbeTargetPitchPx / anchor.PitchPx,
		CalibrationSHA256:    calibration.sourceSHA256,
		CalibrationVersion:   calibration.fileVersion,
		Metrics:              &metrics,
		Timings:              timings,
	}

	// 送 OCR 与验收的是这张**干净**规范图，四周刻度尺只画在下面的展示副本上：把装饰烧进主图
	// 等于改了 OCR 输入，也会让一致性/逐字节等价基线一起失效。
	// 用户可见/OCR 输出采用原厂 4425×600 画布：第 9 字符中心落画布中心、17 字符中心基线水平，
	// 字符尺度严格来自同一承印平面上的真实物理节距 × 25px/mm。正确原厂标定下，同一 VIN 多次拍摄
	// 会自然得到同一字号；禁止再用单张 OBB 宽度把内容拉到人为固定节距。
	// 最终网格直接回到平面并采样原始彩色图，避免先生成图再二次缩放造成清晰度损失。
	// 真实 WidthMM/HeightMM 仍在 Meta 中，原始 RGBD 也已持久化，可随时重建诊断图。
	// 画布严格 25px/mm 后，刻度尺已能画在展示副本上（见 ruler.go）：旧注释那句「规范图不画 mm 刻度」
	// 针对的是 M4.5 之前按 OBB 宽度归一的版本，那时尺度随取景变，画刻度等于把显示尺度冒充物理尺度。
	// 旧的「去阴影二值化墨水占比」质量闸已删除：真机 21 组实测它把锐利、无高光、对比良好的真实采集
	// （板面有污渍/阴影、或网格越过板缘的暗带）当成「墨水」误判废 43%，而鋭度/饱和/对比都不能判别坏采集
	// —— 即无有效信号的调参式兜底，违背项目魂（不靠假门掩盖、show 真实让用户复看）。坏采集靠 OBB 检测 +
	// tilt 门兜，外加用户复看重拍。TODO(终态): 若要真实质量闸，先用 harness 标注好/坏样本，用经验证的真实
	// 指标（聚焦方差/越界占比/平面 rms）设计，不再拍脑袋设阈。
	stageStarted = time.Now()
	png, err := gocv.IMEncode(gocv.PNGFileExt, rect)
	timings.PNGEncodeMS = elapsedMS(stageStarted)
	if err != nil {
		return Result{}, err
	}

	stageStarted = time.Now()
	ruled := drawCanonicalRuler(rect, metrics)
	rulerPNG, err := gocv.IMEncode(gocv.PNGFileExt, ruled)
	_ = ruled.Release()
	timings.RulerMS = elapsedMS(stageStarted)
	if err != nil {
		return Result{}, err
	}

	timings.TotalMS = elapsedMS(totalStarted)
	meta.Timings = timings
	meta.OK = true
	return Result{PNG: png, RulerPNG: rulerPNG, Meta: meta}, nil
}

func elapsedMS(started time.Time) float64 {
	return float64(time.Since(started).Microseconds()) / 1000.0
}
