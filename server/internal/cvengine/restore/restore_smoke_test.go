package restore

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

// 端侧 meta.json 的最小结构（只取深度内参 + 宽高）。
type capMeta struct {
	DepthDeviceSerial string `json:"depthDeviceSerial"`
	ColorDeviceSerial string `json:"colorDeviceSerial"`
	Color             struct {
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

// TestRestoreSmoke —— 运行级自验：加载真模型 + 真机 cap，跑 Restore 出 PNG，落 .dev/vin_restore_go/。
//
// 缺模型 / 缺数据 / onnxruntime 跑不起来 → t.Skip（不让 CI 因运行期依赖红）。
func TestRestoreSmoke(t *testing.T) {
	replayDir := getenvOr("VIN_VISION_REPLAY_DIR", "/root/lilw/gomob/.dev/vin_vision_records")
	capDir := getenvOr("VIN_CAP_DIR", "/root/lilw/gomob/.dev/vin_factory_bf301208/vin_captures/cap_001_1784015012764")
	outDir := getenvOr("VIN_RESTORE_OUT", "/root/lilw/gomob/.dev/vin_restore_go")

	if _, err := os.Stat(capDir); err != nil {
		t.Skipf("cap 不存在，跳过：%v", err)
	}

	// 视觉观测来自离线录制（cmd/vinvisionrecord）；没录过就跳过，绝不联网自动补，
	// 否则 smoke 会在无人察觉时变成依赖现场服务的用例。
	provider, err := NewReplayVisionProvider(replayDir)
	if err != nil {
		t.Skipf("视觉观测录制不存在，跳过：%v", err)
	}

	metaBytes, err := os.ReadFile(filepath.Join(capDir, "meta.json"))
	if err != nil {
		t.Fatalf("读 meta.json：%v", err)
	}
	var m capMeta
	if err := json.Unmarshal(metaBytes, &m); err != nil {
		t.Fatalf("解析 meta.json：%v", err)
	}
	rgb, err := os.ReadFile(filepath.Join(capDir, "rgb1300.jpg"))
	if err != nil {
		t.Fatalf("读 rgb1300.jpg：%v", err)
	}
	depth, err := os.ReadFile(filepath.Join(capDir, "depth.yuv"))
	if err != nil {
		t.Fatalf("读 depth.yuv：%v", err)
	}
	colorWidth, colorHeight := m.Color.EncodedW, m.Color.EncodedH
	if colorWidth == 0 || colorHeight == 0 {
		colorWidth, colorHeight = m.Color.W, m.Color.H
	}
	calibration, err := NewFactoryVinCalibrationResolverFromEnv().ResolveVinCalibration(VinCalibrationKey{
		DepthDeviceSerial: m.DepthDeviceSerial,
		ColorDeviceSerial: m.ColorDeviceSerial,
		DepthWidth:        m.Depth.W,
		DepthHeight:       m.Depth.H,
		ColorWidth:        colorWidth,
		ColorHeight:       colorHeight,
	})
	if err != nil {
		t.Fatalf("加载原厂标定：%v", err)
	}

	restored, err := Restore(context.Background(), provider, calibration, rgb, depth,
		m.Depth.W, m.Depth.H)
	png, meta := restored.PNG, restored.Meta
	if err == ErrTiltTooLarge {
		t.Logf("tilt 门废弃：tilt=%.1f ndet=%d", meta.TiltDeg, meta.NumDet)
		return
	}
	if err != nil {
		t.Fatalf("Restore 失败：%v（tilt=%.1f ndet=%d）", err, meta.TiltDeg, meta.NumDet)
	}

	if err := os.MkdirAll(outDir, 0o755); err != nil {
		t.Fatalf("建输出目录：%v", err)
	}
	outPath := filepath.Join(outDir, filepath.Base(capDir)+"_rectified.png")
	if err := os.WriteFile(outPath, png, 0o644); err != nil {
		t.Fatalf("写 PNG：%v", err)
	}
	rulerPath := filepath.Join(outDir, filepath.Base(capDir)+"_ruler.png")
	if err := os.WriteFile(rulerPath, restored.RulerPNG, 0o644); err != nil {
		t.Fatalf("写刻度尺 PNG：%v", err)
	}
	t.Logf("OK → %s  size=%d bytes  %dx%d tilt=%.1f w=%.0fmm h=%.0fmm theta=%.1f inlier=%.2f rms=%.1f medz=%.0f ndet=%d",
		outPath, len(png), meta.OutW, meta.OutH, meta.TiltDeg, meta.WidthMM, meta.HeightMM,
		meta.ThetaDeg, meta.InlierRate, meta.RMS, meta.MedZ, meta.NumDet)
	if meta.Metrics != nil {
		t.Logf("字符度量 → 总宽 %.2fmm 字宽 %.2fmm 字高 %.2fmm 节距 %.2fmm 空隙 %.2fmm（刻度尺图 %s）",
			meta.Metrics.TotalWidthMM, meta.Metrics.CharWidthMM, meta.Metrics.CharHeightMM,
			meta.Metrics.PitchMM, meta.Metrics.GapMM, rulerPath)
	}
	// 刻度尺是新增在成功路径上的固定开销，单列出来才能判断它值不值这些毫秒。
	t.Logf("耗时 → total=%.1fms png=%.1fms ruler=%.1fms(%.1f%%) region=%.1fms char=%.1fms 刻度尺图 %d 字节",
		meta.Timings.TotalMS, meta.Timings.PNGEncodeMS, meta.Timings.RulerMS,
		100*meta.Timings.RulerMS/meta.Timings.TotalMS,
		meta.Timings.RegionMS, meta.Timings.CharDetectMS, len(restored.RulerPNG))
}

func getenvOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
