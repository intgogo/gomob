package restore

import (
	"errors"
	"time"

	"io.gomob/server/internal/cvengine/gocv"
)

// StageTimings 记录一次成功还原各阶段的壁钟耗时，仅用于性能观测，不进入 API 契约。
type StageTimings struct {
	DecodeMS      float64
	OBBMS         float64
	FrameMS       float64
	ProbeRenderMS float64
	AnchorMS      float64
	FinalRenderMS float64
	PNGEncodeMS   float64
	TotalMS       float64
}

// Meta —— 还原结果元信息（随 PNG 一起返回，便于上游判定/记录）。
type Meta struct {
	OK                   bool         `json:"ok"`
	TiltDeg              float64      `json:"tilt_deg"`
	WidthMM              float64      `json:"width_mm"`
	HeightMM             float64      `json:"height_mm"`
	ThetaDeg             float64      `json:"theta_deg"`
	InlierRate           float64      `json:"inlier_rate"`
	RMS                  float64      `json:"rms"`
	MedZ                 float64      `json:"med_z"`
	OutW                 int          `json:"out_w"`
	OutH                 int          `json:"out_h"`
	NumDet               int          `json:"num_det"`
	AnchorCount          int          `json:"anchor_count"`
	AnchorCandidateCount int          `json:"anchor_candidate_count"`
	AnchorPitchPx        float64      `json:"anchor_pitch_px"`
	AnchorRMSPx          float64      `json:"anchor_rms_px"`
	AnchorMeanScore      float64      `json:"anchor_mean_score"`
	AnchorHeightPx       float64      `json:"anchor_height_px"`
	AnchorRotationDeg    float64      `json:"anchor_rotation_deg"`
	AnchorScale          float64      `json:"anchor_scale"`
	CalibrationSHA256    string       `json:"calibration_sha256"`
	CalibrationVersion   uint32       `json:"calibration_version"`
	Timings              StageTimings `json:"-"`
}

// ErrTiltTooLarge 承印面倾角超过 MaxTiltDeg（原厂硬门，码 34）。
var ErrTiltTooLarge = errors.New("tilt 超过门限（承印面过斜）")

// ErrVinNotDetected 表示当前彩色帧没有可靠 VIN 区域，属于可重拍的采集判废。
var ErrVinNotDetected = errors.New("未检测到 VIN 区域")

// Restore —— VIN 数码拓印还原主入口（端口自 restore_obb.py 主链）。
//
// 入参：
//
//	runner   KindCom 模型 runner（core.Registry 实现 RunCom）
//	tag      yolo-obb 模型 tag
//	rgb      彩色 JPEG 字节（rgb1300）
//	calibration 与完整 rig/profile 绑定的原厂不可变标定
//	depth    RS-D550 mode25 原始 1/8px 视差（小端 u16，dw×dh）
//	dw,dh    深度宽高
//
// 出参：原厂式彩色正射 PNG 字节 + Meta + err；黑白签名图只作内部质量/对齐信号。
// tilt>70 时返回 ErrTiltTooLarge（meta.OK=false 且带 tilt 值），非系统级错误。
type restoreRunner interface {
	comRunner
	yoloRunner
}

func Restore(
	runner restoreRunner, obbTag, charTag string,
	calibration *VinCalibration,
	rgb []byte, depth []byte,
	dw, dh int,
) ([]byte, Meta, error) {
	totalStarted := time.Now()
	timings := StageTimings{}
	if calibration == nil {
		return nil, Meta{}, ErrVinCalibrationUnavailable
	}
	if calibration.key.DepthWidth != dw || calibration.key.DepthHeight != dh {
		return nil, Meta{}, errors.New("深度尺寸与原厂标定 profile 不一致")
	}
	if len(rgb) == 0 {
		return nil, Meta{}, errors.New("rgb 为空")
	}
	if len(depth) < dw*dh*2 {
		return nil, Meta{}, errors.New("depth 字节数与 dw*dh*2 不符")
	}

	// 彩色 HLSD8 原始帧直接用于检测；几何投影由原厂 CCameraModel 私有模型完成。
	stageStarted := time.Now()
	colorBGR, err := gocv.IMDecode(rgb, gocv.IMReadColor)
	timings.DecodeMS = elapsedMS(stageStarted)
	if err != nil || colorBGR.Empty() {
		return nil, Meta{}, errors.New("彩色解码失败")
	}
	defer func() { _ = colorBGR.Release() }()

	stageStarted = time.Now()
	dets, err := Detect(runner, obbTag, colorBGR)
	timings.OBBMS = elapsedMS(stageStarted)
	if err != nil {
		return nil, Meta{}, err
	}

	stageStarted = time.Now()
	f, tilt, err := buildFrame(depth, dw, dh, calibration, colorBGR, dets)
	timings.FrameMS = elapsedMS(stageStarted)
	if err != nil {
		return nil, Meta{TiltDeg: tilt, NumDet: len(dets)}, err
	}
	if f == nil {
		// tilt 门：废弃
		return nil, Meta{OK: false, TiltDeg: tilt, NumDet: len(dets)}, ErrTiltTooLarge
	}

	stageStarted = time.Now()
	probe, probeAxes, probeMMPerPixel, err := renderCanonicalProbe(f)
	timings.ProbeRenderMS = elapsedMS(stageStarted)
	if err != nil {
		return nil, Meta{TiltDeg: tilt, NumDet: len(dets)}, err
	}
	stageStarted = time.Now()
	anchor, err := detectTextAnchor(runner, charTag, probe)
	timings.AnchorMS = elapsedMS(stageStarted)
	_ = probe.Release()
	if err != nil {
		anchorScale := 0.0
		if anchor.PitchPx > 0 {
			anchorScale = canonicalProbeTargetPitchPx / anchor.PitchPx
		}
		return nil, Meta{
			TiltDeg: tilt, NumDet: len(dets),
			AnchorCount: anchor.Count, AnchorCandidateCount: anchor.CandidateCount,
			AnchorPitchPx: anchor.PitchPx, AnchorRMSPx: anchor.RMSPx,
			AnchorMeanScore: anchor.MeanScore, AnchorHeightPx: anchor.MedianHeightPx,
			AnchorRotationDeg: -anchor.AngleDeg(),
			AnchorScale:       anchorScale,
		}, err
	}
	stageStarted = time.Now()
	rect, outW, outH, err := renderTextCanonical(f, probeAxes, probeMMPerPixel, anchor)
	timings.FinalRenderMS = elapsedMS(stageStarted)
	if err != nil {
		return nil, Meta{TiltDeg: tilt, NumDet: len(dets)}, err
	}
	defer func() { _ = rect.Release() }()

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
		Timings:              timings,
	}

	// 用户可见/OCR 输出采用原厂 4425×600 画布：第 9 字符中心落画布中心、17 字符中心基线水平，
	// 字符尺度严格来自同一承印平面上的真实物理节距 × 25px/mm。正确原厂标定下，同一 VIN 多次拍摄
	// 会自然得到同一字号；禁止再用单张 OBB 宽度把内容拉到人为固定节距。
	// 最终网格直接回到平面并采样原始彩色图，避免先生成图再二次缩放造成清晰度损失。
	// 真实 WidthMM/HeightMM 仍在 Meta 中，原始 RGBD 也已持久化，可随时重建严格 metric 诊断图；规范图不画 mm
	// 刻度，避免把等比归一后的显示尺度误充成物理刻度。
	// 旧的「去阴影二值化墨水占比」质量闸已删除：真机 21 组实测它把锐利、无高光、对比良好的真实采集
	// （板面有污渍/阴影、或网格越过板缘的暗带）当成「墨水」误判废 43%，而鋭度/饱和/对比都不能判别坏采集
	// —— 即无有效信号的调参式兜底，违背项目魂（不靠假门掩盖、show 真实让用户复看）。坏采集靠 OBB 检测 +
	// tilt 门兜，外加用户复看重拍。TODO(终态): 若要真实质量闸，先用 harness 标注好/坏样本，用经验证的真实
	// 指标（聚焦方差/越界占比/平面 rms）设计，不再拍脑袋设阈。
	stageStarted = time.Now()
	png, err := gocv.IMEncode(gocv.PNGFileExt, rect)
	timings.PNGEncodeMS = elapsedMS(stageStarted)
	if err != nil {
		return nil, Meta{}, err
	}
	timings.TotalMS = elapsedMS(totalStarted)
	meta.Timings = timings
	meta.OK = true
	return png, meta, nil
}

func elapsedMS(started time.Time) float64 {
	return float64(time.Since(started).Microseconds()) / 1000.0
}
