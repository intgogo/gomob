package cvengine

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"

	"io.gomob/server/internal/cvengine/restore"
)

const restoreFactorySHA256ForHTTPTest = "1a87dc030c50d532503218fbb026a453b2c0fa9b17df5316da60782d8d7bf5d2"

// 端侧 meta.json 的深度块（反投影所需内参 + 尺寸）。
type vinCapMeta struct {
	Depth struct {
		W  int     `json:"w"`
		H  int     `json:"h"`
		Fx float64 `json:"fx"`
		Fy float64 `json:"fy"`
		Cx float64 `json:"cx"`
		Cy float64 `json:"cy"`
	} `json:"depth"`
}

// TestVinRestoreHTTPContract —— 服务端 VinRestore HTTP 缝隙端到端契约自验（脱离真机）。
//
// 用当前 rig 真机采集按**端侧 CVEngineApi.vinRestore 完全一致的 multipart 字段名**
// 构请求，直调 h.VinRestore（绕过路由 auth），断言：每张响应 envelope code==0（线格式/解析对）、
// 至少一张 ok=true 返回合法 PNG（还原路通）。证明 Kotlin 客户端的 multipart 与 Go handler 逐字段对齐。
//
// 缺模型 / 缺数据 / onnxruntime 运行库不可用 → t.Skip（不让无设备环境的 CI 因运行期依赖红）。
func TestVinRestoreHTTPContract(t *testing.T) {
	model := os.Getenv("VIN_OBB_MODEL")
	if model == "" {
		model = "/root/lilw/gomob/.dev/vin_models/yolo-obb.onnx"
	}
	if _, err := os.Stat(model); err != nil {
		t.Skipf("yolo-obb 模型缺失，跳过：%v", err)
	}
	os.Setenv("VIN_OBB_MODEL", model) // ensureVinObbModel 读它
	charModel := os.Getenv("VIN_CHAR_MODEL")
	if charModel == "" {
		charModel = "/root/lilw/gomob/.dev/vin_models/vins0.onnx"
	}
	if _, err := os.Stat(charModel); err != nil {
		t.Skipf("VIN 逐字符模型缺失，跳过：%v", err)
	}
	os.Setenv("VIN_CHAR_MODEL", charModel)

	caps, _ := filepath.Glob("/root/lilw/gomob/.dev/vin_factory_bf301208/vin_captures/cap_*")
	if len(caps) == 0 {
		t.Skip("无当前 rig 原始采集，跳过")
	}

	h := NewHandler()
	h.log = slog.New(slog.NewTextHandler(io.Discard, nil))
	defer h.models.ReleaseAll()
	if err := h.ensureVinObbModel(); err != nil {
		t.Skipf("yolo-obb 模型/onnxruntime 运行库不可用，跳过：%v", err)
	}
	if err := h.ensureVinCharModel(); err != nil {
		t.Skipf("VIN 逐字符模型/onnxruntime 运行库不可用，跳过：%v", err)
	}

	okCount, pngOK := 0, false
	for _, capDir := range caps {
		name := filepath.Base(capDir)
		body, ct, err := buildVinMultipart(capDir)
		if err != nil {
			t.Logf("跳过 %s（采集不完整）：%v", name, err)
			continue
		}
		req := httptest.NewRequest(http.MethodPost, "/cv/ocr/v1/vin_restore", body)
		req.Header.Set("Content-Type", ct)
		rec := httptest.NewRecorder()
		h.VinRestore(rec, req)

		if rec.Code != http.StatusOK {
			t.Fatalf("%s HTTP %d（期望 200）：%s", name, rec.Code, rec.Body.String())
		}
		var env struct {
			Code int `json:"code"`
			Data struct {
				OK                   bool    `json:"ok"`
				PNGB64               string  `json:"result_png_base64"`
				Width                int     `json:"width"`
				Height               int     `json:"height"`
				Tilt                 float64 `json:"tilt_deg"`
				NumDet               int     `json:"num_det"`
				AnchorCount          int     `json:"anchor_count"`
				AnchorCandidateCount int     `json:"anchor_candidate_count"`
				AnchorPitch          float64 `json:"anchor_pitch_px"`
				AnchorRotation       float64 `json:"anchor_rotation_deg"`
				AnchorScale          float64 `json:"anchor_scale"`
				CalibrationSHA256    string  `json:"calibration_sha256"`
				CalibrationVersion   uint32  `json:"calibration_version"`
				SyncUS               int64   `json:"sync_delta_us"`
				Reason               string  `json:"reject_reason"`
			} `json:"data"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &env); err != nil {
			t.Fatalf("%s 响应非合法 envelope JSON：%v body=%s", name, err, rec.Body.String())
		}
		if env.Code != 0 {
			t.Fatalf("%s envelope code=%d（期望 0）", name, env.Code)
		}
		if !env.Data.OK {
			t.Logf("%s ok=false 判废=%s tilt=%.1f ndet=%d（契约仍 200）",
				name, env.Data.Reason, env.Data.Tilt, env.Data.NumDet)
			continue
		}
		okCount++
		png, err := base64.StdEncoding.DecodeString(env.Data.PNGB64)
		if err != nil {
			t.Fatalf("%s result_png_base64 解码失败：%v", name, err)
		}
		if len(png) < 8 || !bytes.HasPrefix(png, []byte("\x89PNG\r\n\x1a\n")) {
			t.Fatalf("%s 返回非 PNG（len=%d，头=%x）", name, len(png), png[:min(8, len(png))])
		}
		if len(png) < 26 || (png[25] != 2 && png[25] != 6) {
			t.Fatalf("%s 返回 PNG 不是真彩色（color_type=%d，期望 2/6）", name, png[25])
		}
		if env.Data.Width != restore.CanonicalOutW || env.Data.Height != restore.CanonicalOutH {
			t.Fatalf("%s 还原图尺寸非法 %dx%d", name, env.Data.Width, env.Data.Height)
		}
		if env.Data.AnchorCount != 17 || env.Data.AnchorCandidateCount < 17 || env.Data.AnchorPitch <= 0 {
			t.Fatalf("%s 字符锚定元数据非法: %+v", name, env.Data)
		}
		if env.Data.CalibrationSHA256 != restoreFactorySHA256ForHTTPTest || env.Data.CalibrationVersion != 3 {
			t.Fatalf("%s 原厂标定审计元数据非法: %+v", name, env.Data)
		}
		pngOK = true
		t.Logf("%s ✓ ok PNG %dB %dx%d tilt=%.1f ndet=%d", name, len(png), env.Data.Width, env.Data.Height, env.Data.Tilt, env.Data.NumDet)
	}

	if !pngOK {
		t.Fatalf("无任一 cap 返回 ok=true 的合法 PNG（okCount=%d/%d）；查平面/OBB/还原", okCount, len(caps))
	}
	t.Logf("契约通过：%d/%d cap 返回合法还原 PNG", okCount, len(caps))
}

func TestVinRestoreSyncGateUsesFiveFpsNearestNeighborBoundary(t *testing.T) {
	tests := []struct {
		name           string
		depthTimestamp string
		deltaUs        int64
		reason         string
	}{
		{name: "100ms边界通过同步门", depthTimestamp: "1100000", deltaUs: 100_000, reason: "calibration_unavailable"},
		{name: "超过边界立即判废", depthTimestamp: "1100001", deltaUs: 100_001, reason: "rgbd_out_of_sync"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			env := runVinRestoreSyncGateRequest(t, tt.depthTimestamp)
			if env.Code != 0 || env.Data.OK || env.Data.DeltaUs != tt.deltaUs || env.Data.Reason != tt.reason {
				t.Fatalf("同步门响应不符合契约: %+v", env)
			}
		})
	}
}

type vinSyncGateEnvelope struct {
	Code int `json:"code"`
	Data struct {
		OK      bool   `json:"ok"`
		DeltaUs int64  `json:"sync_delta_us"`
		Reason  string `json:"reject_reason"`
	} `json:"data"`
}

func runVinRestoreSyncGateRequest(t *testing.T, depthTimestamp string) vinSyncGateEnvelope {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	rgbPart, _ := writer.CreateFormFile("image_binary_rgb1300", "rgb.jpg")
	_, _ = rgbPart.Write([]byte{1})
	depthPart, _ := writer.CreateFormFile("image_binary_depth", "depth.u16")
	_, _ = depthPart.Write([]byte{1, 0})
	_ = writer.WriteField("depth_w", "1")
	_ = writer.WriteField("depth_h", "1")
	_ = writer.WriteField("fx", "1")
	_ = writer.WriteField("fy", "1")
	_ = writer.WriteField("cx", "0")
	_ = writer.WriteField("cy", "0")
	_ = writer.WriteField("device_id", "test-device")
	_ = writer.WriteField("color_device_id", "test-hlsd8")
	_ = writer.WriteField("color_w", "4160")
	_ = writer.WriteField("color_h", "832")
	_ = writer.WriteField("color_timestamp_us", "1000000")
	_ = writer.WriteField("depth_timestamp_us", depthTimestamp)
	_ = writer.Close()

	h := NewHandler()
	h.log = slog.New(slog.NewTextHandler(io.Discard, nil))
	req := httptest.NewRequest(http.MethodPost, "/cv/ocr/v1/vin_restore", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	rec := httptest.NewRecorder()
	h.VinRestore(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("HTTP=%d body=%s", rec.Code, rec.Body.String())
	}
	var env vinSyncGateEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &env); err != nil {
		t.Fatal(err)
	}
	return env
}

func TestVinRestoreRejectsMismatchedRigCalibration(t *testing.T) {
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	rgbPart, _ := writer.CreateFormFile("image_binary_rgb1300", "rgb.jpg")
	_, _ = rgbPart.Write([]byte{1})
	depthPart, _ := writer.CreateFormFile("image_binary_depth", "depth.u16")
	_, _ = depthPart.Write([]byte{1, 0})
	_ = writer.WriteField("depth_w", "1")
	_ = writer.WriteField("depth_h", "1")
	_ = writer.WriteField("fx", "1")
	_ = writer.WriteField("fy", "1")
	_ = writer.WriteField("cx", "0")
	_ = writer.WriteField("cy", "0")
	_ = writer.WriteField("device_id", "BF301208")
	_ = writer.WriteField("color_device_id", "OTHER_HLSD8")
	_ = writer.WriteField("color_w", "4160")
	_ = writer.WriteField("color_h", "832")
	_ = writer.WriteField("color_timestamp_us", "1000000")
	_ = writer.WriteField("depth_timestamp_us", "1010000")
	_ = writer.Close()

	h := NewHandler()
	h.log = slog.New(slog.NewTextHandler(io.Discard, nil))
	req := httptest.NewRequest(http.MethodPost, "/cv/ocr/v1/vin_restore", &body)
	req.Header.Set("Content-Type", writer.FormDataContentType())
	rec := httptest.NewRecorder()
	h.VinRestore(rec, req)

	var env struct {
		Code int `json:"code"`
		Data struct {
			OK     bool   `json:"ok"`
			Reason string `json:"reject_reason"`
		} `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &env); err != nil {
		t.Fatal(err)
	}
	if rec.Code != http.StatusOK || env.Code != 0 || env.Data.OK || env.Data.Reason != "calibration_unavailable" {
		t.Fatalf("错误 rig 不应进入生产推理: HTTP=%d env=%+v", rec.Code, env)
	}
}

// buildVinMultipart 按端侧 CVEngineApi.vinRestore 的字段名构 multipart（字段名是契约，须逐字一致）。
func buildVinMultipart(capDir string) (*bytes.Buffer, string, error) {
	metaBytes, err := os.ReadFile(filepath.Join(capDir, "meta.json"))
	if err != nil {
		return nil, "", err
	}
	var m vinCapMeta
	if err := json.Unmarshal(metaBytes, &m); err != nil {
		return nil, "", err
	}
	rgb, err := os.ReadFile(filepath.Join(capDir, "rgb1300.jpg"))
	if err != nil {
		return nil, "", err
	}
	depth, err := os.ReadFile(filepath.Join(capDir, "depth.yuv"))
	if err != nil {
		return nil, "", err
	}

	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	if fw, e := w.CreateFormFile("image_binary_rgb1300", "rgb1300.jpg"); e == nil {
		fw.Write(rgb)
	}
	if fw, e := w.CreateFormFile("image_binary_depth", "depth.u16"); e == nil {
		fw.Write(depth)
	}
	w.WriteField("depth_w", strconv.Itoa(m.Depth.W))
	w.WriteField("depth_h", strconv.Itoa(m.Depth.H))
	w.WriteField("fx", strconv.FormatFloat(m.Depth.Fx, 'f', -1, 64))
	w.WriteField("fy", strconv.FormatFloat(m.Depth.Fy, 'f', -1, 64))
	w.WriteField("cx", strconv.FormatFloat(m.Depth.Cx, 'f', -1, 64))
	w.WriteField("cy", strconv.FormatFloat(m.Depth.Cy, 'f', -1, 64))
	w.WriteField("device_id", "BF301208")
	w.WriteField("color_device_id", "202303111518")
	w.WriteField("color_w", "4160")
	w.WriteField("color_h", "832")
	w.WriteField("color_timestamp_us", "1000000")
	w.WriteField("depth_timestamp_us", "1010000")
	w.Close()
	return &buf, w.FormDataContentType(), nil
}
