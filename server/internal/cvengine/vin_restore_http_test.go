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

	"io.gomob/server/internal/cvengine/core"
)

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
// 用真机已存采集（.dev/vin_captures/cap_*）按**端侧 CVEngineApi.vinRestore 完全一致的 multipart 字段名**
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

	caps, _ := filepath.Glob("/root/lilw/gomob/.dev/vin_captures/cap_*")
	if len(caps) == 0 {
		t.Skip("无采集数据 .dev/vin_captures/cap_*，跳过")
	}

	h := &Handler{log: slog.New(slog.NewTextHandler(io.Discard, nil)), models: core.New()}
	if err := h.ensureVinObbModel(); err != nil {
		t.Skipf("yolo-obb 模型/onnxruntime 运行库不可用，跳过：%v", err)
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
				OK     bool    `json:"ok"`
				PNGB64 string  `json:"result_png_base64"`
				Width  int     `json:"width"`
				Height int     `json:"height"`
				Tilt   float64 `json:"tilt_deg"`
				NumDet int     `json:"num_det"`
				Ink    float64 `json:"ink_ratio"`
				Reason string  `json:"reject_reason"`
			} `json:"data"`
		}
		if err := json.Unmarshal(rec.Body.Bytes(), &env); err != nil {
			t.Fatalf("%s 响应非合法 envelope JSON：%v body=%s", name, err, rec.Body.String())
		}
		if env.Code != 0 {
			t.Fatalf("%s envelope code=%d（期望 0）", name, env.Code)
		}
		if !env.Data.OK {
			t.Logf("%s ok=false 判废=%s tilt=%.1f 墨水=%.0f%% ndet=%d（契约仍 200）",
				name, env.Data.Reason, env.Data.Tilt, env.Data.Ink*100, env.Data.NumDet)
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
		if env.Data.Width <= 0 || env.Data.Height <= 0 {
			t.Fatalf("%s 还原图尺寸非法 %dx%d", name, env.Data.Width, env.Data.Height)
		}
		pngOK = true
		t.Logf("%s ✓ ok PNG %dB %dx%d tilt=%.1f 墨水=%.0f%% ndet=%d", name, len(png), env.Data.Width, env.Data.Height, env.Data.Tilt, env.Data.Ink*100, env.Data.NumDet)
	}

	if !pngOK {
		t.Fatalf("无任一 cap 返回 ok=true 的合法 PNG（okCount=%d/%d）；查平面/OBB/还原", okCount, len(caps))
	}
	t.Logf("契约通过：%d/%d cap 返回合法还原 PNG", okCount, len(caps))
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
	w.WriteField("device_id", "test-http-contract")
	w.Close()
	return &buf, w.FormDataContentType(), nil
}
