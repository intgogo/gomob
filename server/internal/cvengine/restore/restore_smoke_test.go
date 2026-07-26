package restore

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"io.gomob/server/internal/cvengine/core"
	"io.gomob/server/internal/cvengine/gocv"
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
	modelPath := getenvOr("VIN_OBB_MODEL", "/root/lilw/gomob/.dev/vin_models/yolo-obb.onnx")
	charModelPath := getenvOr("VIN_CHAR_MODEL", "/root/lilw/gomob/.dev/vin_models/vins0.onnx")
	capDir := getenvOr("VIN_CAP_DIR", "/root/lilw/gomob/.dev/vin_factory_bf301208/vin_captures/cap_001_1784015012764")
	outDir := getenvOr("VIN_RESTORE_OUT", "/root/lilw/gomob/.dev/vin_restore_go")

	if _, err := os.Stat(modelPath); err != nil {
		t.Skipf("模型不存在，跳过：%v", err)
	}
	if _, err := os.Stat(charModelPath); err != nil {
		t.Skipf("逐字符模型不存在，跳过：%v", err)
	}
	if _, err := os.Stat(capDir); err != nil {
		t.Skipf("cap 不存在，跳过：%v", err)
	}

	reg := core.New()
	defer reg.ReleaseAll()
	if err := reg.RegisterComONNX("VINOBB", modelPath, 1.0/255.0, gocv.Scalar{}); err != nil {
		t.Skipf("模型加载失败（可能缺 onnxruntime 运行库），跳过：%v", err)
	}
	if err := reg.RegisterYoloONNX(
		"VINCHAR", charModelPath, core.DefaultYoloOptions(VinCharacterClasses()...),
	); err != nil {
		t.Skipf("逐字符模型加载失败，跳过：%v", err)
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

	png, meta, err := Restore(reg, "VINOBB", "VINCHAR", calibration, rgb, depth,
		m.Depth.W, m.Depth.H)
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
	t.Logf("OK → %s  size=%d bytes  %dx%d tilt=%.1f w=%.0fmm h=%.0fmm theta=%.1f inlier=%.2f rms=%.1f medz=%.0f ndet=%d",
		outPath, len(png), meta.OutW, meta.OutH, meta.TiltDeg, meta.WidthMM, meta.HeightMM,
		meta.ThetaDeg, meta.InlierRate, meta.RMS, meta.MedZ, meta.NumDet)
}

func getenvOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
