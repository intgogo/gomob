package cvengine

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"io.gomob/server/internal/cvengine/restore"
)

func TestVinPreviewCalibrationHTTPContract(t *testing.T) {
	h := NewHandlerWithOptions(HandlerOptions{
		VINCalibrationResolver: restore.NewFactoryVinCalibrationResolver("/root/WindowsR"),
	})
	mux := http.NewServeMux()
	h.Mount(mux)
	req := httptest.NewRequest(http.MethodGet,
		"/cv/ocr/v1/vin_preview_calibration?depth_serial=bf301208&color_serial=202303111518&"+
			"depth_width=640&depth_height=128&color_width=4160&color_height=832", nil)
	recorder := httptest.NewRecorder()
	mux.ServeHTTP(recorder, req)

	if recorder.Code != http.StatusOK {
		t.Fatalf("HTTP=%d body=%s", recorder.Code, recorder.Body.String())
	}
	var envelope struct {
		Code int                           `json:"code"`
		Data restore.VinPreviewCalibration `json:"data"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.Code != 0 || envelope.Data.Key.DepthDeviceSerial != "BF301208" ||
		envelope.Data.Key.ColorDeviceSerial != "202303111518" {
		t.Fatalf("响应未返回 resolver 规范化后的完整键: %+v", envelope)
	}
	if envelope.Data.CalibrationSHA256 != "1a87dc030c50d532503218fbb026a453b2c0fa9b17df5316da60782d8d7bf5d2" ||
		envelope.Data.CalibrationVersion != 3 || len(envelope.Data.Color.Rotation) != 9 {
		t.Fatalf("投影身份或数组错误: %+v", envelope.Data)
	}
}

func TestVinPreviewCalibrationRejectsIncompleteOrUnpublishedKey(t *testing.T) {
	h := NewHandlerWithOptions(HandlerOptions{
		VINCalibrationResolver: restore.NewFactoryVinCalibrationResolver("/root/WindowsR"),
	})
	for _, test := range []struct {
		name   string
		query  string
		status int
		code   int
	}{
		{name: "参数缺失", query: "depth_serial=BF301208", status: http.StatusBadRequest, code: 10001},
		{
			name:   "完整键未发布",
			query:  "depth_serial=BF301208&color_serial=OTHER&depth_width=640&depth_height=128&color_width=4160&color_height=832",
			status: http.StatusNotFound,
			code:   40301,
		},
	} {
		t.Run(test.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/cv/ocr/v1/vin_preview_calibration?"+test.query, nil)
			recorder := httptest.NewRecorder()
			h.VinPreviewCalibration(recorder, req)
			var envelope struct {
				Code int `json:"code"`
			}
			if err := json.Unmarshal(recorder.Body.Bytes(), &envelope); err != nil {
				t.Fatal(err)
			}
			if recorder.Code != test.status || envelope.Code != test.code {
				t.Fatalf("HTTP=%d code=%d body=%s", recorder.Code, envelope.Code, recorder.Body.String())
			}
		})
	}
}

func TestVinPreviewCalibrationTreatsMissingPublishedAssetAsServiceFailure(t *testing.T) {
	h := NewHandlerWithOptions(HandlerOptions{
		VINCalibrationResolver: restore.NewFactoryVinCalibrationResolver(t.TempDir()),
	})
	req := httptest.NewRequest(http.MethodGet,
		"/cv/ocr/v1/vin_preview_calibration?depth_serial=BF301208&color_serial=202303111518&"+
			"depth_width=640&depth_height=128&color_width=4160&color_height=832", nil)
	recorder := httptest.NewRecorder()
	h.VinPreviewCalibration(recorder, req)
	var envelope struct {
		Code int `json:"code"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if recorder.Code != http.StatusServiceUnavailable || envelope.Code != 50302 {
		t.Fatalf("已发布资产缺失未按服务故障处理: HTTP=%d body=%s", recorder.Code, recorder.Body.String())
	}
}
