package cvengine

import (
	"bytes"
	"context"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"io.gomob/server/internal/cvengine/restore"
)

func TestReadyzRejectsMissingRequiredVINCalibration(t *testing.T) {
	t.Setenv("GOMOB_VIN_FACTORY_CALIBRATION_REQUIRED", "true")
	h := NewHandlerWithOptions(HandlerOptions{
		VINCalibrationResolver: restore.NewFactoryVinCalibrationResolver(t.TempDir()),
	})

	recorder := httptest.NewRecorder()
	h.Readyz(recorder, httptest.NewRequest(http.MethodGet, "/readyz", nil))

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("readyz=%d，期望 503，body=%s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), "VIN 原厂标定未就绪") {
		t.Fatalf("readyz 未暴露标定缺失原因: %s", recorder.Body.String())
	}
}

func TestReadyzAcceptsMountedFactoryVINCalibration(t *testing.T) {
	t.Setenv("GOMOB_VIN_FACTORY_CALIBRATION_REQUIRED", "true")
	dir := t.TempDir()
	fixture, err := os.ReadFile(filepath.Join("..", "..", "..", "tests", "vincreator-apk", "VIN_BF301208.bin"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "VIN_BF301208.bin"), fixture, 0o600); err != nil {
		t.Fatal(err)
	}
	h := NewHandlerWithOptions(HandlerOptions{
		VINCalibrationResolver: restore.NewFactoryVinCalibrationResolver(dir),
	})

	recorder := httptest.NewRecorder()
	h.Readyz(recorder, httptest.NewRequest(http.MethodGet, "/readyz", nil))

	if recorder.Code != http.StatusOK {
		t.Fatalf("readyz=%d，期望 200，body=%s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), `"vin_factory_calibration_ready":true`) {
		t.Fatalf("readyz 未报告标定就绪: %s", recorder.Body.String())
	}
}

// 模型下沉到外部算法服务后，本地唯一能验的依赖是视觉 provider 是否注入；
// 没注入却声明 required 时必须 503，不能让还原链在缺依赖时静默半可用。
func TestReadyzRejectsMissingRequiredVINRestoreModels(t *testing.T) {
	t.Setenv("GOMOB_VIN_RESTORE_MODELS_REQUIRED", "true")
	h := NewHandlerWithOptions(HandlerOptions{
		VINCalibrationResolver: restore.NewFactoryVinCalibrationResolver(t.TempDir()),
	})

	recorder := httptest.NewRecorder()
	h.Readyz(recorder, httptest.NewRequest(http.MethodGet, "/readyz", nil))

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("readyz=%d，期望 503，body=%s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), "VIN 还原视觉服务未配置") {
		t.Fatalf("readyz 未暴露视觉服务缺失原因: %s", recorder.Body.String())
	}
}

// provider 已注入时 readyz 必须放行，否则生产会被自己的门卡死。
func TestReadyzAcceptsInjectedVisionProvider(t *testing.T) {
	t.Setenv("GOMOB_VIN_RESTORE_MODELS_REQUIRED", "true")
	h := NewHandlerWithOptions(HandlerOptions{
		VINCalibrationResolver: restore.NewFactoryVinCalibrationResolver(t.TempDir()),
		VINVisionProvider:      stubVisionProvider{},
	})

	recorder := httptest.NewRecorder()
	h.Readyz(recorder, httptest.NewRequest(http.MethodGet, "/readyz", nil))

	if recorder.Code != http.StatusOK {
		t.Fatalf("readyz=%d，期望 200，body=%s", recorder.Code, recorder.Body.String())
	}
}

type stubVisionProvider struct{}

func (stubVisionProvider) LocateVinRegions(context.Context, []byte) ([]restore.Detection, error) {
	return nil, nil
}

func (stubVisionProvider) DetectCharacters(context.Context, []byte) ([]restore.CharacterBox, error) {
	return nil, nil
}

func TestValidateRequiredDependenciesRejectsMissingPublishedCalibration(t *testing.T) {
	t.Setenv("GOMOB_VIN_FACTORY_CALIBRATION_REQUIRED", "true")
	h := NewHandlerWithOptions(HandlerOptions{
		VINCalibrationResolver: restore.NewFactoryVinCalibrationResolver(t.TempDir()),
	})

	err := h.ValidateRequiredDependencies()
	if err == nil || !strings.Contains(err.Error(), "VIN 原厂标定未就绪") {
		t.Fatalf("启动硬门未拒绝缺失原厂标定: %v", err)
	}
}

func TestVinRestoreTreatsMissingPublishedCalibrationAsServiceFailure(t *testing.T) {
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	rgb, err := writer.CreateFormFile("image_binary_rgb1300", "rgb.jpg")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = rgb.Write([]byte{1})
	depth, err := writer.CreateFormFile("image_binary_depth", "depth.u16")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = depth.Write(make([]byte, 640*128*2))
	for key, value := range map[string]string{
		"depth_w": "640", "depth_h": "128",
		"fx": "614.60498", "fy": "614.60498", "cx": "324", "cy": "65.4325",
		"device_id": "BF301208", "color_device_id": "202303111518",
		"color_w": "4160", "color_h": "832",
		"color_timestamp_us": "1000000", "depth_timestamp_us": "1010000",
	} {
		if err := writer.WriteField(key, value); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	h := NewHandlerWithOptions(HandlerOptions{
		VINCalibrationResolver: restore.NewFactoryVinCalibrationResolver(t.TempDir()),
	})
	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodPost, "/cv/ocr/v1/vin_restore", &body)
	request.Header.Set("Content-Type", writer.FormDataContentType())
	h.VinRestore(recorder, request)

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("缺失已发布标定 HTTP=%d，期望 503，body=%s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), "VIN 已发布原厂标定资产未就绪") {
		t.Fatalf("未返回基础设施故障语义: %s", recorder.Body.String())
	}
}
