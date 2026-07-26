package api

import (
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"io.gomob/server/internal/feedback"
)

func TestWriteFeedbackErrorHidesInternalDetails(t *testing.T) {
	h := &Handler{log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	recorder := httptest.NewRecorder()

	h.writeFeedbackError(recorder, errors.New("open /secret/app-feedback: not a directory"), 42)

	if recorder.Code != http.StatusInternalServerError {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	body := recorder.Body.String()
	if !strings.Contains(body, "服务端内部错误") {
		t.Fatalf("未返回通用错误: %s", body)
	}
	if strings.Contains(body, "/secret") || strings.Contains(body, "not a directory") {
		t.Fatalf("响应泄露内部错误: %s", body)
	}
}

func TestWriteFeedbackErrorKeepsValidationMessage(t *testing.T) {
	h := &Handler{log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	recorder := httptest.NewRecorder()

	h.writeFeedbackError(recorder, feedback.ValidationError{Message: "请至少标注一个问题区域"}, 42)

	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	if !strings.Contains(recorder.Body.String(), "请至少标注一个问题区域") {
		t.Fatalf("参数错误信息丢失: %s", recorder.Body.String())
	}
}
