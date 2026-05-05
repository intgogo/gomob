// cv-engine 服务 HTTP handler — Phase 1 地基。
//
// Phase 1（M-S10.1）当前只暴露：
//
//	GET  /healthz                        总活探测
//	GET  /readyz                         OpenCV / cgo 链已就绪
//	GET  /cv/v1/version                  返回真实 gocv + opencv 版本（强制 cgo runtime 初始化）
//	POST /cv/v1/echo_dim                 上传图（base64 / multipart / form-data）→ gocv 解码 → 返回真实尺寸（验证 IMRead 链）
//
// Phase 2（M-S10.2 后续 session）：迁 gosmart/apps/api/ivv 的 vin_detect / vin_compare / 等业务路由进来。
//
// 鉴权说明：本 phase 内部端点（如 /cv/v1/version）**暂不**强制 JWT，让 harness 能直探"cgo 是否真链上"；
// Phase 2 加业务端点时再上 JWT + HMAC 双轨鉴权（详见 02-api-contract.md §14.1）。
package cvengine

import (
	"encoding/base64"
	"io"
	"log/slog"
	"net/http"
	"runtime"
	"strconv"
	"time"

	"io.gomob/server/internal/cvengine/gocv"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
)

type Handler struct {
	startedAt time.Time
	log       *slog.Logger
}

func NewHandler() *Handler {
	return &Handler{
		startedAt: time.Now(),
		log:       logger.New("cvengine.handler"),
	}
}

func (h *Handler) Mount(mux *http.ServeMux) {
	mux.HandleFunc("GET /healthz", h.Healthz)
	mux.HandleFunc("GET /readyz", h.Readyz)
	mux.HandleFunc("GET /cv/v1/version", h.Version)
	mux.HandleFunc("POST /cv/v1/echo_dim", h.EchoDim)
}

// Healthz 不调 cgo，纯 Go 探活。
func (h *Handler) Healthz(w http.ResponseWriter, _ *http.Request) {
	httpx.OK(w, map[string]any{
		"ok":          true,
		"uptime_sec":  int(time.Since(h.startedAt).Seconds()),
		"go_version":  runtime.Version(),
	})
}

// Readyz 强制走 cgo —— 调 gocv.OpenCVVersion()，能拿到非空字符串说明 libopencv_world / libccv 真实加载。
//
// 这是 spike 报告之外，第一个在 cv-engine 二进制内可观测的"真实 cv 调用"。
func (h *Handler) Readyz(w http.ResponseWriter, _ *http.Request) {
	ocvVer := gocv.OpenCVVersion()
	gocvVer := gocv.Version()
	if ocvVer == "" {
		http.Error(w, "OpenCV not linked", http.StatusServiceUnavailable)
		return
	}
	httpx.OK(w, map[string]any{
		"ready":          true,
		"opencv_version": ocvVer,
		"gocv_version":   gocvVer,
	})
}

// Version 暴露版本信息（与 readyz 同样强制 cgo）。
func (h *Handler) Version(w http.ResponseWriter, _ *http.Request) {
	httpx.OK(w, map[string]any{
		"opencv_version": gocv.OpenCVVersion(),
		"gocv_version":   gocv.Version(),
		"go_version":     runtime.Version(),
		"phase":          "M-S10.1 foundation",
	})
}

// EchoDim 接收一张图（multipart `image` / form `image_binary` base64 / 原始 body），
// 调 gocv.IMDecode 解码 → 返 (rows, cols, channels, type)。失败 10001。
//
// 这一步会真实穿过：HTTP body → []byte → gocv.NewMatFromBytes 路径之外的 IMDecode（CGO C++ → libopencv_world.cv::imdecode）→ 拿真实尺寸返回。
//
// 不写硬编码 size，不返回 mock —— 失败 = OpenCV 实际解码失败 / 文件损坏。
func (h *Handler) EchoDim(w http.ResponseWriter, r *http.Request) {
	buf, err := readImageBuf(r)
	if err != nil {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "image_binary 缺失或解析失败: "+err.Error()))
		return
	}
	if len(buf) == 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "image_binary 长度为 0"))
		return
	}

	mat, err := gocv.IMDecode(buf, gocv.IMReadColor)
	if err != nil {
		h.log.Warn("IMDecode 失败", "err", err, "len", len(buf))
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "OpenCV 解码失败: "+err.Error()))
		return
	}
	// gocv.Mat 由 Go GC + finalizer 回收（gocv/mat_noprofile.go SetFinalizer），不需要显式 Close。
	if mat.Empty() {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "OpenCV 解码后 Mat 为空"))
		return
	}

	httpx.OK(w, map[string]any{
		"rows":     mat.Rows(),
		"cols":     mat.Cols(),
		"channels": mat.Channels(),
		"mat_type": int(mat.Type()),
		"bytes":    len(buf),
	})
}

// readImageBuf 兼容三种上传形式：
//
//   - multipart/form-data: file part name = "image"
//   - application/x-www-form-urlencoded: image_binary = <base64>
//   - 原始 body: 直接 application/octet-stream / image/*
func readImageBuf(r *http.Request) ([]byte, error) {
	// 优先 multipart
	if err := r.ParseMultipartForm(32 << 20); err == nil && r.MultipartForm != nil {
		if files := r.MultipartForm.File["image"]; len(files) > 0 {
			f, err := files[0].Open()
			if err != nil {
				return nil, err
			}
			defer f.Close()
			return io.ReadAll(f)
		}
	}
	// form 字段 image_binary（base64）
	if v := r.PostFormValue("image_binary"); v != "" {
		return base64.StdEncoding.DecodeString(v)
	}
	// 原始 body
	return io.ReadAll(io.LimitReader(r.Body, 64<<20))
}

// 工具：避免未用变量在 minimal phase 里报警（保留导出供后续 phase 用）
var _ = strconv.Itoa
