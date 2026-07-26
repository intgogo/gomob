package restore

import (
	"bufio"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"io.gomob/server/internal/cvengine/core"
	"io.gomob/server/internal/cvengine/gocv"
)

type consistencyCaptureMeta struct {
	DepthDeviceSerial string `json:"depthDeviceSerial"`
	ColorDeviceSerial string `json:"colorDeviceSerial"`
	Sync              struct {
		ColorTimestampUs int64 `json:"colorTimestampUs"`
		DepthTimestampUs int64 `json:"depthTimestampUs"`
		DeltaUs          int64 `json:"deltaUs"`
	} `json:"sync"`
	Color struct {
		W        int `json:"w"`
		H        int `json:"h"`
		EncodedW int `json:"encodedW"`
		EncodedH int `json:"encodedH"`
	} `json:"color"`
	Depth struct {
		W  int     `json:"w"`
		H  int     `json:"h"`
		Fx float64 `json:"fx"`
		Fy float64 `json:"fy"`
		Cx float64 `json:"cx"`
		Cy float64 `json:"cy"`
	} `json:"depth"`
}

type consistencyResult struct {
	Capture           string             `json:"capture"`
	PNG               string             `json:"png,omitempty"`
	OK                bool               `json:"ok"`
	RejectReason      string             `json:"reject_reason,omitempty"`
	Error             string             `json:"error,omitempty"`
	Meta              Meta               `json:"meta"`
	OutputAnchor      *consistencyAnchor `json:"output_anchor,omitempty"`
	OutputAnchorError string             `json:"output_anchor_error,omitempty"`
	SyncDeltaUs       *int64             `json:"sync_delta_us,omitempty"`
	RGBSHA256         string             `json:"rgb_sha256,omitempty"`
	DepthSHA256       string             `json:"depth_sha256,omitempty"`
}

type consistencyAnchor struct {
	Count          int     `json:"count"`
	CandidateCount int     `json:"candidate_count"`
	Text           string  `json:"text"`
	CenterX        float64 `json:"center_x"`
	CenterY        float64 `json:"center_y"`
	PitchPx        float64 `json:"pitch_px"`
	RMSPx          float64 `json:"rms_px"`
	MeanScore      float64 `json:"mean_score"`
	HeightPx       float64 `json:"height_px"`
	AngleDeg       float64 `json:"angle_deg"`
}

type canonicalProbeLayout struct {
	Scale                  float64
	TranslateX, TranslateY float64
}

func makeCanonicalProbeLayout() canonicalProbeLayout {
	// 固定 0.36 只由评估坐标契约决定：原厂 25px/mm 输出映到 9px/mm 探针域。
	// 它不读取单张图像内容，也不按每张字符检测结果重新缩放。
	const scale = 0.36
	return canonicalProbeLayout{
		Scale:      scale,
		TranslateX: (float64(CanonicalProbeW) - float64(CanonicalOutW)*scale) * 0.5,
		TranslateY: (float64(CanonicalProbeH) - float64(CanonicalOutH)*scale) * 0.5,
	}
}

func consistencyAnchorFromText(anchor textAnchor) *consistencyAnchor {
	return &consistencyAnchor{
		Count:          anchor.Count,
		CandidateCount: anchor.CandidateCount,
		Text:           anchor.Text,
		CenterX:        anchor.CenterX,
		CenterY:        anchor.CenterY,
		PitchPx:        anchor.PitchPx,
		RMSPx:          anchor.RMSPx,
		MeanScore:      anchor.MeanScore,
		HeightPx:       anchor.MedianHeightPx,
		AngleDeg:       anchor.AngleDeg(),
	}
}

// detectCanonicalOutputAnchor 把 4425×600 原厂用户画布按一套固定、全样本相同的相似变换
// 放回 VINCHAR 已验证的 1200×260 探针坐标，再把检测结果映回原厂画布。这里没有逐图配准。
func detectCanonicalOutputAnchor(
	runner yoloRunner,
	tag string,
	output gocv.Mat,
) (textAnchor, error) {
	layout := makeCanonicalProbeLayout()
	matrix, err := gocv.NewMatFromBytes(
		2,
		3,
		gocv.MatTypeCV64F,
		f64bytes([]float64{
			layout.Scale, 0, layout.TranslateX,
			0, layout.Scale, layout.TranslateY,
		}),
	)
	if err != nil {
		return textAnchor{}, fmt.Errorf("创建固定探针相似变换: %w", err)
	}
	defer func() { _ = matrix.Release() }()
	canvas := gocv.NewMat()
	defer func() { _ = canvas.Release() }()
	gocv.WarpAffineWithParams(
		output,
		&canvas,
		matrix,
		image.Pt(CanonicalProbeW, CanonicalProbeH),
		gocv.InterpolationLinear,
		gocv.BorderConstant,
		color.RGBA{R: 128, G: 128, B: 128},
	)

	anchor, detectErr := detectTextAnchor(runner, tag, canvas)
	// 即使可靠性门失败，只要检测器返回了候选几何，也必须先映回 4425×600 坐标再记录诊断。
	// 否则最差样本会把 1200×260 探针坐标混进原厂画布统计，制造巨大的假偏差。
	if anchor.Count > 0 || anchor.CandidateCount > 0 {
		anchor.CenterX = (anchor.CenterX - layout.TranslateX) / layout.Scale
		anchor.CenterY = (anchor.CenterY - layout.TranslateY) / layout.Scale
		anchor.PitchPx /= layout.Scale
		anchor.RMSPx /= layout.Scale
		anchor.MedianHeightPx /= layout.Scale
	}
	return anchor, detectErr
}

// TestRestoreConsistencyBatch 用同一 VIN 的多次实拍批量跑生产 Restore。
// 默认跳过，仅由 tests/harness/vin_restore_consistency/run.sh 显式启用。
func TestRestoreConsistencyBatch(t *testing.T) {
	if os.Getenv("VIN_CONSISTENCY") != "1" {
		t.Skip("仅由 VIN 一致性 harness 启用")
	}
	modelPath := os.Getenv("VIN_OBB_MODEL")
	charModelPath := os.Getenv("VIN_CHAR_MODEL")
	capGlob := os.Getenv("VIN_CAP_GLOB")
	capList := os.Getenv("VIN_CAP_LIST")
	outDir := os.Getenv("VIN_RESTORE_OUT")
	if modelPath == "" || charModelPath == "" || (capGlob == "" && capList == "") || outDir == "" {
		t.Fatal("VIN_OBB_MODEL、VIN_CHAR_MODEL、VIN_RESTORE_OUT 以及 VIN_CAP_LIST/VIN_CAP_GLOB 之一必须设置")
	}
	captureSet := map[string]struct{}{}
	if capList != "" {
		file, err := os.Open(capList)
		if err != nil {
			t.Fatalf("读采集清单 %s: %v", capList, err)
		}
		scanner := bufio.NewScanner(file)
		for scanner.Scan() {
			capture := strings.TrimSpace(scanner.Text())
			if capture != "" {
				captureSet[capture] = struct{}{}
			}
		}
		if err := scanner.Err(); err != nil {
			_ = file.Close()
			t.Fatalf("扫描采集清单: %v", err)
		}
		_ = file.Close()
	}
	for _, pattern := range strings.Split(capGlob, ";") {
		if strings.TrimSpace(pattern) == "" {
			continue
		}
		matches, err := filepath.Glob(strings.TrimSpace(pattern))
		if err != nil {
			t.Fatalf("采集 glob 无效 %q: %v", pattern, err)
		}
		for _, capture := range matches {
			captureSet[capture] = struct{}{}
		}
	}
	captures := make([]string, 0, len(captureSet))
	for capture := range captureSet {
		info, err := os.Stat(capture)
		if err != nil {
			t.Fatalf("采集不存在 %s: %v", capture, err)
		}
		if !info.IsDir() {
			t.Fatalf("采集路径不是目录: %s", capture)
		}
		captures = append(captures, capture)
	}
	sort.Strings(captures)
	if len(captures) < 2 {
		t.Fatalf("一致性验收至少需要 2 组采集，当前 %d 组: %s", len(captures), capGlob)
	}
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		t.Fatalf("创建输出目录: %v", err)
	}

	reg := core.New()
	defer reg.ReleaseAll()
	if err := reg.RegisterComONNX("VINOBB", modelPath, 1.0/255.0, gocv.Scalar{}); err != nil {
		t.Fatalf("加载 VIN OBB 模型: %v", err)
	}
	if err := reg.RegisterYoloONNX(
		"VINCHAR", charModelPath, core.DefaultYoloOptions(VinCharacterClasses()...),
	); err != nil {
		t.Fatalf("加载 VIN 逐字符模型: %v", err)
	}

	results := make([]consistencyResult, 0, len(captures))
	okCount := 0
	calibrationResolver := NewFactoryVinCalibrationResolverFromEnv()
	for _, capDir := range captures {
		name := filepath.Base(capDir)
		result := consistencyResult{Capture: name}
		metaBytes, readErr := os.ReadFile(filepath.Join(capDir, "meta.json"))
		if readErr != nil {
			result.Error = "读取 meta.json: " + readErr.Error()
			results = append(results, result)
			continue
		}
		var captureMeta consistencyCaptureMeta
		if readErr = json.Unmarshal(metaBytes, &captureMeta); readErr != nil {
			result.Error = "解析 meta.json: " + readErr.Error()
			results = append(results, result)
			continue
		}
		colorWidth, colorHeight := captureMeta.Color.EncodedW, captureMeta.Color.EncodedH
		if colorWidth == 0 || colorHeight == 0 {
			colorWidth, colorHeight = captureMeta.Color.W, captureMeta.Color.H
		}
		calibration, calibrationErr := calibrationResolver.ResolveVinCalibration(VinCalibrationKey{
			DepthDeviceSerial: captureMeta.DepthDeviceSerial,
			ColorDeviceSerial: captureMeta.ColorDeviceSerial,
			DepthWidth:        captureMeta.Depth.W,
			DepthHeight:       captureMeta.Depth.H,
			ColorWidth:        colorWidth,
			ColorHeight:       colorHeight,
		})
		if calibrationErr != nil {
			result.RejectReason = "calibration_unavailable"
			result.Error = calibrationErr.Error()
			results = append(results, result)
			continue
		}
		rgb, rgbErr := os.ReadFile(filepath.Join(capDir, "rgb1300.jpg"))
		depth, depthErr := os.ReadFile(filepath.Join(capDir, "depth.yuv"))
		if rgbErr != nil || depthErr != nil {
			result.Error = "读取 RGB/深度失败"
			results = append(results, result)
			continue
		}
		png, meta, restoreErr := Restore(
			reg,
			"VINOBB",
			"VINCHAR",
			calibration,
			rgb,
			depth,
			captureMeta.Depth.W,
			captureMeta.Depth.H,
		)
		result.Meta = meta
		if captureMeta.Sync.ColorTimestampUs > 0 && captureMeta.Sync.DepthTimestampUs > 0 {
			deltaUs := captureMeta.Sync.ColorTimestampUs - captureMeta.Sync.DepthTimestampUs
			if deltaUs < 0 {
				deltaUs = -deltaUs
			}
			result.SyncDeltaUs = &deltaUs
		}
		rgbSum := sha256.Sum256(rgb)
		depthSum := sha256.Sum256(depth)
		result.RGBSHA256 = fmt.Sprintf("%x", rgbSum)
		result.DepthSHA256 = fmt.Sprintf("%x", depthSum)
		if restoreErr != nil {
			if errors.Is(restoreErr, ErrTiltTooLarge) {
				result.RejectReason = "tilt_too_large"
				result.Error = "倾角超限"
			} else if errors.Is(restoreErr, ErrTextAnchorUnreliable) {
				result.RejectReason = "text_anchor_unreliable"
				result.Error = restoreErr.Error()
			} else {
				result.RejectReason = "restore_error"
				result.Error = restoreErr.Error()
			}
			results = append(results, result)
			continue
		}
		output, decodeErr := gocv.IMDecode(png, gocv.IMReadColor)
		if decodeErr != nil || output.Empty() {
			result.OutputAnchorError = "最终 PNG 解码失败"
		} else {
			outputAnchor, anchorErr := detectCanonicalOutputAnchor(reg, "VINCHAR", output)
			_ = output.Release()
			result.OutputAnchor = consistencyAnchorFromText(outputAnchor)
			if anchorErr != nil {
				result.OutputAnchorError = fmt.Sprintf(
					"%v（count=%d candidates=%d pitch=%.2f rms=%.2f score=%.3f angle=%.2f°）",
					anchorErr,
					outputAnchor.Count,
					outputAnchor.CandidateCount,
					outputAnchor.PitchPx,
					outputAnchor.RMSPx,
					outputAnchor.MeanScore,
					outputAnchor.AngleDeg(),
				)
			}
		}
		outName := name + ".png"
		if writeErr := os.WriteFile(filepath.Join(outDir, outName), png, 0o644); writeErr != nil {
			t.Fatalf("写 %s: %v", outName, writeErr)
		}
		result.OK = true
		result.PNG = outName
		results = append(results, result)
		okCount++
		t.Logf(
			"%s → %dx%d，内容 %.1f×%.1fmm，theta=%.2f° tilt=%.2f° anchor=%d pitch=%.2f rms=%.2f",
			name,
			meta.OutW,
			meta.OutH,
			meta.WidthMM,
			meta.HeightMM,
			meta.ThetaDeg,
			meta.TiltDeg,
			meta.AnchorCount,
			meta.AnchorPitchPx,
			meta.AnchorRMSPx,
		)
	}

	report, err := json.MarshalIndent(results, "", "  ")
	if err != nil {
		t.Fatalf("编码结果: %v", err)
	}
	if err := os.WriteFile(filepath.Join(outDir, "results.json"), report, 0o644); err != nil {
		t.Fatalf("写 results.json: %v", err)
	}
	if okCount < 2 {
		t.Fatalf("成功还原不足 2 张，当前 %d/%d", okCount, len(captures))
	}
}
