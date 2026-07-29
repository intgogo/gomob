package vinalgo

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"testing"
)

type fakeRecognizer struct {
	result Result
	err    error
	image  []byte
}

func (f *fakeRecognizer) Recognize(_ context.Context, image []byte) (Result, error) {
	f.image = append([]byte(nil), image...)
	return f.result, f.err
}

func TestHTTPHandlerSuccessEnvelope(t *testing.T) {
	fake := &fakeRecognizer{result: Result{
		Provider:        "gosmart",
		VIN:             "ABC",
		Confidence:      0.9,
		CharacterScores: []float64{0.8, 1.0, 0.9},
		CharacterCount:  3,
		LogID:           "log-1",
		InferMS:         12,
		CharacterCrops: []CharacterCrop{
			{
				Position:  1,
				Character: "A",
				Image: CropImage{
					MIMEType:   "image/webp",
					DataBase64: testCharacterCropWebPBase64,
					Width:      vinCreatorCharacterCropWidth,
					Height:     vinCreatorCharacterCropHeight,
				},
			},
		},
	}}
	handler := NewHTTPHandler(fake, discardLogger())
	body, contentType := testMultipart(t, "image_binary", []byte("png"))
	req := httptest.NewRequest(http.MethodPost, "/cv/ocr/v1/vin_recognize", body)
	req.Header.Set("Content-Type", contentType)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("HTTP %d：%s", rec.Code, rec.Body.String())
	}
	if string(fake.image) != "png" {
		t.Fatalf("传给识别器的图片=%q", fake.image)
	}
	var envelope struct {
		Code int    `json:"code"`
		Data Result `json:"data"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &envelope); err != nil {
		t.Fatal(err)
	}
	if envelope.Code != 0 || envelope.Data.VIN != "ABC" ||
		len(envelope.Data.CharacterCrops) != 1 ||
		envelope.Data.CharacterCrops[0].Image.DataBase64 != testCharacterCropWebPBase64 {
		t.Fatalf("响应错误：%+v", envelope)
	}
}

func TestHTTPHandlerRejectsMissingOrEmptyImage(t *testing.T) {
	tests := []struct {
		name       string
		field      string
		image      []byte
		expectCode int
	}{
		{name: "字段缺失", field: "other", image: []byte("png"), expectCode: http.StatusBadRequest},
		{name: "图片为空", field: "image_binary", image: nil, expectCode: http.StatusBadRequest},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			body, contentType := testMultipart(t, tt.field, tt.image)
			req := httptest.NewRequest(http.MethodPost, "/cv/ocr/v1/vin_recognize", body)
			req.Header.Set("Content-Type", contentType)
			rec := httptest.NewRecorder()
			NewHTTPHandler(&fakeRecognizer{}, discardLogger()).ServeHTTP(rec, req)
			if rec.Code != tt.expectCode {
				t.Fatalf("HTTP %d：%s", rec.Code, rec.Body.String())
			}
		})
	}
}

func TestHTTPHandlerMapsExternalErrors(t *testing.T) {
	tests := []struct {
		name       string
		err        error
		httpStatus int
		code       int
	}{
		{name: "超时", err: context.DeadlineExceeded, httpStatus: http.StatusGatewayTimeout, code: 50602},
		{name: "包装超时", err: fmt.Errorf("调用失败: %w", context.DeadlineExceeded), httpStatus: http.StatusGatewayTimeout, code: 50602},
		{name: "上游失败", err: errors.New("upstream failed"), httpStatus: http.StatusBadGateway, code: 50601},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			body, contentType := testMultipart(t, "image_binary", []byte("png"))
			req := httptest.NewRequest(http.MethodPost, "/cv/ocr/v1/vin_recognize", body)
			req.Header.Set("Content-Type", contentType)
			rec := httptest.NewRecorder()
			NewHTTPHandler(&fakeRecognizer{err: tt.err}, discardLogger()).ServeHTTP(rec, req)
			if rec.Code != tt.httpStatus {
				t.Fatalf("HTTP %d：%s", rec.Code, rec.Body.String())
			}
			var envelope struct {
				Code int `json:"code"`
			}
			if err := json.Unmarshal(rec.Body.Bytes(), &envelope); err != nil {
				t.Fatal(err)
			}
			if envelope.Code != tt.code {
				t.Fatalf("code=%d，期望 %d", envelope.Code, tt.code)
			}
		})
	}
}

func TestHTTPHandlerWithoutRecognizerReturnsServiceUnavailable(t *testing.T) {
	body, contentType := testMultipart(t, "image_binary", []byte("png"))
	req := httptest.NewRequest(http.MethodPost, "/cv/ocr/v1/vin_recognize", body)
	req.Header.Set("Content-Type", contentType)
	rec := httptest.NewRecorder()

	NewHTTPHandler(nil, discardLogger()).ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("HTTP %d：%s", rec.Code, rec.Body.String())
	}
}

func testMultipart(t *testing.T, field string, image []byte) (*bytes.Buffer, string) {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile(field, "vin.png")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write(image); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return &body, writer.FormDataContentType()
}

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}
