// cv-engine 服务 HTTP handler。
//
// Phase 1（已完成）：
//
//	GET  /healthz                        总活探测
//	GET  /readyz                         OpenCV / cgo 链已就绪
//	GET  /cv/v1/version                  返回真实 gocv + opencv 版本
//	POST /cv/v1/echo_dim                 IMDecode 真解码返尺寸
//
// Phase 2 已迁入（本 commit）：
//
//	POST /cv/ocr/v1/vin_character_compare           单字符×字符的字形相似度（IoU / Chamfer 双方法）
//	POST /cv/ocr/v1/vin_character_compare_with_ref  单字符 vs 该车型 active 批次厂家库的所有样本，返最优匹配
//
// Phase 2.x 后续：vin_detect / vin_compare（含模型加载、ProcVINDet）/ vin_more_compare 等。
//
// 鉴权：业务端点（/cv/ocr/v1/*）默认走 gateway → cvengine 的 X-Gomob-User-Id 头注入路径；
// 直连测试时通过 GOMOB_CVENGINE_REQUIRE_AUTH=true 强制；harness 默认 false。
package cvengine

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"os"
	"runtime"
	"strconv"
	"strings"
	"time"

	"io.gomob/server/internal/cvengine/core"
	"io.gomob/server/internal/cvengine/gocv"
	"io.gomob/server/internal/cvengine/judge"
	"io.gomob/server/internal/cvengine/proc"
	"io.gomob/server/internal/cvengine/vinrefclient"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
)

type Handler struct {
	startedAt   time.Time
	log         *slog.Logger
	requireAuth bool
	vinRef      *vinrefclient.Client
	models      *core.Registry
}

func NewHandler() *Handler {
	return &Handler{
		startedAt:   time.Now(),
		log:         logger.New("cvengine.handler"),
		requireAuth: os.Getenv("GOMOB_CVENGINE_REQUIRE_AUTH") == "true",
		vinRef:      vinrefclient.NewClient(os.Getenv("GOMOB_VINREF_TARGET")),
		models:      core.New(),
	}
}

// Models 暴露 registry 给 main.go 做启动期 LoadFromEnv 调用。
func (h *Handler) Models() *core.Registry { return h.models }

func (h *Handler) Mount(mux *http.ServeMux) {
	mux.HandleFunc("GET /healthz", h.Healthz)
	mux.HandleFunc("GET /readyz", h.Readyz)
	mux.HandleFunc("GET /cv/v1/version", h.Version)
	mux.HandleFunc("GET /cv/v1/models", h.ListModels)
	mux.HandleFunc("POST /cv/v1/echo_dim", h.EchoDim)
	// 业务端点（gosmart 时代路径完全保留）
	mux.Handle("POST /cv/ocr/v1/vin_character_compare",
		h.required(http.HandlerFunc(h.VinCharacterCompare)))
	mux.Handle("POST /cv/ocr/v1/vin_character_compare_with_ref",
		h.required(http.HandlerFunc(h.VinCharacterCompareWithRef)))
}

// ListModels 暴露当前注册的模型 + 加载状态。
//
// 即使 GOMOB_CVENGINE_MODELS 没设也返 200 + items=[]，便于 harness 验空场景。
func (h *Handler) ListModels(w http.ResponseWriter, _ *http.Request) {
	items := h.models.List()
	httpx.OK(w, map[string]any{
		"items":        items,
		"total":        len(items),
		"loaded_count": h.models.LoadedCount(),
	})
}

// required gateway 注入 X-Gomob-User-Id 头时放行；空且 requireAuth 时返 40102。
// 在 dev / harness 直连场景下默认放行（GOMOB_CVENGINE_REQUIRE_AUTH 缺省）。
func (h *Handler) required(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if h.requireAuth && r.Header.Get("X-Gomob-User-Id") == "" {
			httpx.WriteError(w, httpx.ErrTokenInvalid)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// ============================================================================
// 业务端点
// ============================================================================

type vinCharCmpResp struct {
	Method     int     `json:"method"`     // 0=IOU / 1=Chamfer
	Value      float64 `json:"value"`      // method=IOU: 0..1 越大越相似 / method=Chamfer: 0+ 越小越相似
	Similarity float64 `json:"similarity"` // 归一化 0..1（统一语义；越大越相似）
	LogID      string  `json:"log_id"`
}

// VinCharacterCompare —— 单字符×字符的字形相似度计算。
//
// 入参（multipart / form / 原始 body）：
//
//	image_binary1 / image_binary2  二个字符图（任一为空 → 10001）
//	method                         "0"=IOU（默认）/ "1"=Chamfer
//	log_id                         可选；不给生成时间戳
//
// 出参：
//
//	value      原始算法输出（IOU 0..1 / Chamfer 0+）
//	similarity 统一归一化到 0..1（IOU 直接用；Chamfer = max(0, 1 - value/8.0)，
//	           8.0 这个 cap 与 gosmart 同样按经验取，超过即视为完全不相似）
//
// 用例（gomob：扫描端拍到的字符 mask vs 厂家库 alpha）：
//
//	curl -F image_binary1=@scan_A.png -F image_binary2=@factory_A.webp \
//	     -F method=0  http://cvengine:18810/cv/ocr/v1/vin_character_compare
func (h *Handler) VinCharacterCompare(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(32 << 20); err != nil && err != http.ErrNotMultipart {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "multipart 解析失败: "+err.Error()))
		return
	}
	buf1, err := readImagePart(r, "image_binary1")
	if err != nil || len(buf1) == 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "image_binary1 缺失"))
		return
	}
	buf2, err := readImagePart(r, "image_binary2")
	if err != nil || len(buf2) == 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "image_binary2 缺失"))
		return
	}

	methodStr := r.FormValue("method")
	method := judge.FONT_DIST_IOU
	if methodStr != "" {
		v, perr := strconv.Atoi(methodStr)
		if perr != nil || (v != judge.FONT_DIST_IOU && v != judge.FONT_DIST_CHAMFER) {
			httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest,
				"method 必须是 0(IOU) 或 1(Chamfer)"))
			return
		}
		method = v
	}

	logID := r.FormValue("log_id")
	if logID == "" {
		logID = "char_cmp_" + strconv.FormatInt(time.Now().UnixNano(), 36)
	}

	val, err := proc.ProcVinCharacterCompare(buf1, buf2, logID, method)
	if err != nil {
		h.log.Warn("ProcVinCharacterCompare 失败", "err", err, "log_id", logID)
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, err.Error()))
		return
	}

	resp := vinCharCmpResp{
		Method: method,
		Value:  val,
		LogID:  logID,
	}
	switch method {
	case judge.FONT_DIST_IOU:
		// IOU 已经是 0..1
		resp.Similarity = val
	case judge.FONT_DIST_CHAMFER:
		// Chamfer 是距离，越小越相似；按经验 cap 8.0 归一化（与 gosmart 实践对齐）
		const chamferCap = 8.0
		s := 1.0 - val/chamferCap
		if s < 0 {
			s = 0
		}
		if s > 1 {
			s = 1
		}
		resp.Similarity = s
	}

	httpx.OK(w, resp)
}

// readImagePart 从 multipart files / form base64 / 原始 body 任选一拿原始字节。
func readImagePart(r *http.Request, name string) ([]byte, error) {
	if r.MultipartForm != nil {
		if files := r.MultipartForm.File[name]; len(files) > 0 {
			f, err := files[0].Open()
			if err != nil {
				return nil, err
			}
			defer f.Close()
			return io.ReadAll(f)
		}
	}
	if v := r.PostFormValue(name); v != "" {
		// 容忍 data:image/...;base64, 前缀
		if idx := indexOf(v, ","); idx > 0 && idx < 64 && hasPrefix(v, "data:") {
			v = v[idx+1:]
		}
		return base64.StdEncoding.DecodeString(v)
	}
	return nil, http.ErrMissingFile
}

func indexOf(s, sub string) int {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return i
		}
	}
	return -1
}

func hasPrefix(s, p string) bool { return len(s) >= len(p) && s[:len(p)] == p }

// 让 Linter 知道 encoding/json / os 是有用导入：
// json: 后续业务端点会用 json.Decoder
// os: requireAuth 由 os.Getenv 决定
var _ = json.Marshal
var _ = os.Getenv

// ============================================================================
// vin_character_compare_with_ref —— 单字符 vs vin-ref 厂家库（M-S10 Phase 2.2）
// ============================================================================

type vinCharRefMatch struct {
	SampleID         string  `json:"sample_id"`
	BatchID          string  `json:"batch_id"`
	AlphaObjectKey   string  `json:"alpha_object_key"`
	FontID           string  `json:"font_id"`
	PositionHint     *int16  `json:"position_hint,omitempty"`
	Value            float64 `json:"value"`      // 算法原始值（IOU 0..1 / Chamfer 0+）
	Similarity       float64 `json:"similarity"` // 归一化 0..1，越大越相似
}

type vinCharRefResp struct {
	VehicleModelID  string            `json:"vehicle_model_id"`
	BatchID         string            `json:"batch_id"`
	Character       string            `json:"character"`
	Method          int               `json:"method"`
	Best            *vinCharRefMatch  `json:"best,omitempty"`
	Matches         []vinCharRefMatch `json:"matches"`
	SampleCount     int               `json:"sample_count"`
	LogID           string            `json:"log_id"`
	BelowThreshold  bool              `json:"below_threshold,omitempty"`
}

// VinCharacterCompareWithRef —— 把扫描端拍到的字符 mask 与该车型 active 批次的所有
// alpha 样本逐个 IoU/Chamfer 比对，返最优匹配 + 全表。
//
// 入参（multipart / form / base64）：
//
//	image_binary       字符二值/灰度图（必填）
//	vehicle_model_id   必填（int64 字符串）
//	character          必填（VIN 33 字符之一；自动 ToUpper）
//	method             0=IoU（默认）/ 1=Chamfer
//	position_hint      可选 1..17，按 VIN 位置过滤 ref 子集
//	threshold          可选；通过该阈值时 below_threshold=false（IoU 用 sim≥thr / Chamfer 用 val≤thr）
//
// 内部流程：
//
//	1. 调 vin-ref ListActiveSamples → 拿 N 条候选 sample（含签名 alpha_url）
//	2. 对每条 sample：FetchAlpha 拉字节 → ProcVinCharacterCompare 比对
//	3. 排序选最佳；按 method 决定"越大越好"还是"越小越好"
func (h *Handler) VinCharacterCompareWithRef(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(32 << 20); err != nil && err != http.ErrNotMultipart {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "multipart 解析失败: "+err.Error()))
		return
	}

	scan, err := readImagePart(r, "image_binary")
	if err != nil || len(scan) == 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "image_binary 缺失"))
		return
	}

	vmidStr := r.FormValue("vehicle_model_id")
	if vmidStr == "" {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "vehicle_model_id 必填"))
		return
	}
	vmid, perr := strconv.ParseInt(vmidStr, 10, 64)
	if perr != nil || vmid <= 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "vehicle_model_id 非法"))
		return
	}

	character := r.FormValue("character")
	if character == "" {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "character 必填"))
		return
	}
	character = strings.ToUpper(strings.TrimSpace(character))
	if len(character) != 1 || !isVinChar(character[0]) {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest,
			"character 必须 1 个 VIN 合法字符（0-9 + A-Z 去 I/O/Q）"))
		return
	}

	method := judge.FONT_DIST_IOU
	if v := r.FormValue("method"); v != "" {
		m, perr := strconv.Atoi(v)
		if perr != nil || (m != judge.FONT_DIST_IOU && m != judge.FONT_DIST_CHAMFER) {
			httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest,
				"method 必须 0(IOU) 或 1(Chamfer)"))
			return
		}
		method = m
	}
	positionHint := 0
	if v := r.FormValue("position_hint"); v != "" {
		positionHint, _ = strconv.Atoi(v)
	}
	var threshold *float64
	if v := r.FormValue("threshold"); v != "" {
		if t, perr := strconv.ParseFloat(v, 64); perr == nil {
			threshold = &t
		}
	}

	logID := r.FormValue("log_id")
	if logID == "" {
		logID = "char_ref_cmp_" + strconv.FormatInt(time.Now().UnixNano(), 36)
	}

	// 1. 拉 vin-ref 对照集
	samples, batchID, err := h.vinRef.ListActiveSamples(r.Context(), vmid, character, positionHint, 0)
	if err != nil {
		if err == vinrefclient.ErrNotFound {
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"该车型 vehicle_model_id="+vmidStr+" 暂无 published vin-ref 批次"))
			return
		}
		h.log.Error("vinref ListActiveSamples 失败", "err", err, "vmid", vmid, "char", character)
		httpx.WriteError(w, httpx.NewError(50001, http.StatusBadGateway, "vin-ref 不可用: "+err.Error()))
		return
	}
	if len(samples) == 0 {
		httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
			"vin-ref active 批次中无字符 "+character+" 的样本"))
		return
	}

	// 2. 逐个比对
	matches := make([]vinCharRefMatch, 0, len(samples))
	for _, s := range samples {
		alphaBytes, ferr := h.vinRef.FetchAlpha(r.Context(), s)
		if ferr != nil {
			h.log.Warn("vinref FetchAlpha 失败", "err", ferr, "sample_id", s.ID)
			continue
		}
		val, perr := proc.ProcVinCharacterCompare(scan, alphaBytes, logID+"_"+s.ID, method)
		if perr != nil {
			h.log.Warn("ProcVinCharacterCompare 失败", "err", perr, "sample_id", s.ID)
			continue
		}
		matches = append(matches, vinCharRefMatch{
			SampleID:       s.ID,
			BatchID:        s.BatchID,
			AlphaObjectKey: s.AlphaObjectKey,
			FontID:         s.FontID,
			PositionHint:   s.PositionHint,
			Value:          val,
			Similarity:     normalizeSim(val, method),
		})
	}
	if len(matches) == 0 {
		httpx.WriteError(w, httpx.NewError(50001, http.StatusBadGateway,
			"所有 ref 样本下载或比对失败"))
		return
	}

	// 3. 选最佳：IoU 取最大 similarity；Chamfer 取最小 value（=最大 similarity）
	bestIdx := 0
	for i := 1; i < len(matches); i++ {
		if matches[i].Similarity > matches[bestIdx].Similarity {
			bestIdx = i
		}
	}
	best := matches[bestIdx]

	resp := vinCharRefResp{
		VehicleModelID: vmidStr,
		BatchID:        batchID,
		Character:      character,
		Method:         method,
		Best:           &best,
		Matches:        matches,
		SampleCount:    len(samples),
		LogID:          logID,
	}
	if threshold != nil {
		// 阈值语义按 method 区分：IoU 看 similarity≥thr；Chamfer 看 value≤thr
		switch method {
		case judge.FONT_DIST_IOU:
			resp.BelowThreshold = best.Similarity < *threshold
		case judge.FONT_DIST_CHAMFER:
			resp.BelowThreshold = best.Value > *threshold
		}
	}
	httpx.OK(w, resp)
}

// normalizeSim 与 VinCharacterCompare 同样归一化语义：
//   - IoU：原值就是 0..1
//   - Chamfer：max(0, 1 - value/8.0)，cap 8.0 与 gosmart 经验对齐
func normalizeSim(value float64, method int) float64 {
	if method == judge.FONT_DIST_CHAMFER {
		const chamferCap = 8.0
		s := 1.0 - value/chamferCap
		if s < 0 {
			return 0
		}
		if s > 1 {
			return 1
		}
		return s
	}
	return value
}

// isVinChar 校验 VIN 合法字符（0-9 + A-Z 去 I/O/Q）。
func isVinChar(b byte) bool {
	switch {
	case b >= '0' && b <= '9':
		return true
	case b >= 'A' && b <= 'Z':
		return b != 'I' && b != 'O' && b != 'Q'
	}
	return false
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
