package vinalgo

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"

	"io.gomob/server/pkg/httpx"
)

const (
	maxImageBytes         = 32 << 20
	maxMultipartBodyBytes = maxImageBytes + (1 << 20)
)

// HTTPHandler 把 Gomob multipart 请求转换为外部 VIN OCR 调用。
type HTTPHandler struct {
	recognizer Recognizer
	log        *slog.Logger
}

// NewHTTPHandler 创建 VIN OCR HTTP 处理器。
func NewHTTPHandler(recognizer Recognizer, log *slog.Logger) *HTTPHandler {
	if log == nil {
		log = slog.Default()
	}
	return &HTTPHandler{recognizer: recognizer, log: log}
}

func (h *HTTPHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if h.recognizer == nil {
		httpx.WriteError(w, httpx.NewError(50603, http.StatusServiceUnavailable, "外部 VIN 识别服务未配置"))
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxMultipartBodyBytes)
	if err := r.ParseMultipartForm(maxImageBytes); err != nil {
		var maxErr *http.MaxBytesError
		if errors.As(err, &maxErr) {
			httpx.WriteError(w, httpx.NewError(10002, http.StatusRequestEntityTooLarge, "VIN 图片过大"))
			return
		}
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "VIN 图片 multipart 解析失败"))
		return
	}
	if r.MultipartForm != nil {
		defer r.MultipartForm.RemoveAll()
	}

	file, header, err := r.FormFile("image_binary")
	if err != nil {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "image_binary 缺失"))
		return
	}
	defer file.Close()
	if header.Size > maxImageBytes {
		httpx.WriteError(w, httpx.NewError(10002, http.StatusRequestEntityTooLarge, "VIN 图片过大"))
		return
	}
	image, err := io.ReadAll(io.LimitReader(file, maxImageBytes+1))
	if err != nil {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "VIN 图片读取失败"))
		return
	}
	if len(image) == 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "VIN 图片为空"))
		return
	}
	if len(image) > maxImageBytes {
		httpx.WriteError(w, httpx.NewError(10002, http.StatusRequestEntityTooLarge, "VIN 图片过大"))
		return
	}

	result, err := h.recognizer.Recognize(r.Context(), image)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) {
			h.log.Warn("外部 VIN OCR 超时", "err", err)
			httpx.WriteError(w, httpx.NewError(50602, http.StatusGatewayTimeout, "外部 VIN 识别服务超时"))
			return
		}
		h.log.Warn("外部 VIN OCR 失败", "err", err)
		httpx.WriteError(w, httpx.NewError(50601, http.StatusBadGateway, "外部 VIN 识别服务不可用"))
		return
	}

	// 日志只留算法调用度量，禁止记录完整 VIN、图片、签名和 more。
	h.log.Info(
		"外部 VIN OCR 完成",
		"provider", result.Provider,
		"character_count", result.CharacterCount,
		"log_id", result.LogID,
		"infer_ms", result.InferMS,
	)
	httpx.OK(w, result)
}
