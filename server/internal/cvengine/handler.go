// cv-engine 服务 HTTP handler。
//
// Phase 1（已完成）：
//
//	GET  /healthz                        总活探测
//	GET  /readyz                         OpenCV / cgo 链已就绪
//	GET  /cv/v1/version                  返回真实 gocv + opencv 版本
//	POST /cv/v1/echo_dim                 IMDecode 真解码返尺寸
//
// Phase 2 已迁入：
//
//	POST /cv/ocr/v1/vin_character_compare           单字符×字符的字形相似度（IoU / Chamfer 双方法）
//	POST /cv/ocr/v1/vin_character_compare_with_ref  单字符 vs 该车型 active 批次厂家库的所有样本，返最优匹配
//	POST /cv/ocr/v1/vin_detect_yolo                 yolo 实例分割检测整张图里的 VIN 区域（VMASK 模型）
//	POST /cv/ocr/v1/vin_pipeline                    一站式整图 → VMASK → 字符 mask → vin-ref 厂家库 → verdict
//	POST /cv/v1/shape_compare                       3D 外廓元数据级比对（Scan vs shape-ref active）
//
// Phase 2.3 已迁入：vin_detect_yolo（gocv.RunMask，真 yolo 推理）。
// Phase 2.x 已迁入：vin_pipeline（整图一次喂入返 verdict）。
//
// 鉴权：业务端点（/cv/ocr/v1/*）默认走 gateway → cvengine 的 X-Gomob-User-Id 头注入路径；
// 直连测试时通过 GOMOB_CVENGINE_REQUIRE_AUTH=true 强制；harness 默认 false。
package cvengine

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	"image/color"
	"io"
	"log/slog"
	"math"
	"net/http"
	"os"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"io.gomob/server/internal/cvengine/core"
	"io.gomob/server/internal/cvengine/gocv"
	"io.gomob/server/internal/cvengine/judge"
	"io.gomob/server/internal/cvengine/proc"
	"io.gomob/server/internal/cvengine/restore"
	"io.gomob/server/internal/cvengine/shapecmp"
	"io.gomob/server/internal/cvengine/shaperefclient"
	"io.gomob/server/internal/cvengine/vinrefclient"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
)

type Handler struct {
	startedAt              time.Time
	log                    *slog.Logger
	requireAuth            bool
	vinRef                 *vinrefclient.Client
	shapeRef               *shaperefclient.Client
	models                 *core.Registry
	vinRecognize           http.Handler
	vinCalibrations        restore.VinCalibrationResolver
	vinCalibrationRequired bool
	vinModelsRequired      bool

	// VIN 还原用 yolo-obb 模型：懒加载一次复用（onnxruntime session 建一次即可）。
	obbOnce sync.Once
	obbErr  error
	// VIN 还原用逐字符 YOLO：为 17 字符格架提供中心、基线和节距。
	charOnce sync.Once
	charErr  error

	// 推理并发闸：限制同时在跑的 RunMask / RunCom 推理数，防过载 OOM。
	// 容量取自 GOMOB_CVENGINE_INFER_CONCURRENCY（默认 4）；获取不到额度时等待 inferTimeout。
	inferSem     chan struct{}
	inferTimeout time.Duration
}

// HandlerOptions 注入不依赖本地 cgo 模型的外部算法处理器。
type HandlerOptions struct {
	VINRecognizeHandler    http.Handler
	VINCalibrationResolver restore.VinCalibrationResolver
}

func NewHandler() *Handler {
	return NewHandlerWithOptions(HandlerOptions{})
}

// NewHandlerWithOptions 创建 cv-engine HTTP handler。
func NewHandlerWithOptions(options HandlerOptions) *Handler {
	conc := parseIntOr(os.Getenv("GOMOB_CVENGINE_INFER_CONCURRENCY"), 4)
	if conc < 1 {
		conc = 1
	}
	timeoutSec := parseIntOr(os.Getenv("GOMOB_CVENGINE_INFER_TIMEOUT_SEC"), 30)
	if timeoutSec < 1 {
		timeoutSec = 1
	}
	calibrationResolver := options.VINCalibrationResolver
	if calibrationResolver == nil {
		calibrationResolver = restore.NewFactoryVinCalibrationResolverFromEnv()
	}
	return &Handler{
		startedAt:       time.Now(),
		log:             logger.New("cvengine.handler"),
		requireAuth:     os.Getenv("GOMOB_CVENGINE_REQUIRE_AUTH") == "true",
		vinRef:          vinrefclient.NewClient(os.Getenv("GOMOB_VINREF_TARGET")),
		shapeRef:        shaperefclient.NewClient(os.Getenv("GOMOB_SHAPEREF_TARGET")),
		models:          core.New(),
		vinRecognize:    options.VINRecognizeHandler,
		vinCalibrations: calibrationResolver,
		vinCalibrationRequired: strings.EqualFold(
			strings.TrimSpace(os.Getenv("GOMOB_VIN_FACTORY_CALIBRATION_REQUIRED")),
			"true",
		),
		vinModelsRequired: strings.EqualFold(
			strings.TrimSpace(os.Getenv("GOMOB_VIN_RESTORE_MODELS_REQUIRED")),
			"true",
		),
		inferSem:     make(chan struct{}, conc),
		inferTimeout: time.Duration(timeoutSec) * time.Second,
	}
}

// parseIntOr 解析 int 环境变量；空 / 非法用默认值。
func parseIntOr(s string, def int) int {
	if s == "" {
		return def
	}
	v, err := strconv.Atoi(s)
	if err != nil {
		return def
	}
	return v
}

func (h *Handler) acquireInferPermit(ctx context.Context) (func(), error) {
	admitCtx, cancel := context.WithTimeout(ctx, h.inferTimeout)
	select {
	case h.inferSem <- struct{}{}:
		cancel()
		return func() { <-h.inferSem }, nil
	case <-admitCtx.Done():
		err := admitCtx.Err()
		cancel()
		return nil, err
	}
}

// runMaskGuarded 给 RunMask 套上并发闸 + 准入超时 + panic→error 防护。
//
//  1. 信号量限并发：满额时阻塞到拿到额度，或 ctx（含 GOMOB_CVENGINE_INFER_TIMEOUT_SEC 准入截止）取消 →
//     返 ctx.Err()，调用方转 503，避免请求堆积撑爆内存。
//  2. recover：onnxruntime / cgo 推理 panic 兜成 error，避免单请求打挂整个进程。
//
// 为何不做"推理跑到一半超时就抢占返回"：底层 gocv RunMask 是经 net.inChan 串行化的同步 cgo 调用，
// 且直接读传入的 img(gocv.Mat) —— 若把它丢进后台 goroutine 然后超时提前返回，调用方会随即 Release img，
// 而后台 cgo 仍在用该 Mat → Mat UAF。真正的"mid-flight 抢占"需 gocv 层加 done channel 支持中断 cgo
// 并接管 Mat 生命周期，属结构性改动（见 core.ReleaseAll 的 G14-thread TODO）。当前在准入处限并发 + 超时，
// 推理本身同步执行，既挡住过载又不引入 Mat UAF。
func (h *Handler) runMaskGuarded(ctx context.Context, tag string, img gocv.Mat,
	conf, maskTh, nmsTh, rudeScale float32) (
	contours [][]image.Point, rrects []gocv.RotatedRect, classes []string, scores []float32, err error) {

	release, err := h.acquireInferPermit(ctx)
	if err != nil {
		return nil, nil, nil, nil, err
	}
	defer release()

	// 2. recover 兜 cgo panic（命名返回值在 defer 里改写 err）
	defer func() {
		if rec := recover(); rec != nil {
			contours, rrects, classes, scores = nil, nil, nil, nil
			err = fmt.Errorf("RunMask panic: %v", rec)
		}
	}()
	return h.models.RunMask(tag, img, conf, maskTh, nmsTh, rudeScale)
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
	mux.Handle("POST /cv/ocr/v1/vin_detect_yolo",
		h.required(http.HandlerFunc(h.VinDetectYolo)))
	mux.Handle("POST /cv/ocr/v1/vin_pipeline",
		h.required(http.HandlerFunc(h.VinPipeline)))
	mux.Handle("POST /cv/ocr/v1/vin_restore",
		h.required(http.HandlerFunc(h.VinRestore)))
	mux.Handle("GET /cv/ocr/v1/vin_preview_calibration",
		h.required(http.HandlerFunc(h.VinPreviewCalibration)))
	vinRecognize := h.vinRecognize
	if vinRecognize == nil {
		vinRecognize = http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
			httpx.WriteError(w, httpx.NewError(50603, http.StatusServiceUnavailable,
				"外部 VIN 识别服务未配置"))
		})
	}
	mux.Handle("POST /cv/ocr/v1/vin_recognize", h.required(vinRecognize))
	mux.Handle("POST /cv/v1/shape_compare",
		h.required(http.HandlerFunc(h.ShapeCompare)))
}

// VinPreviewCalibration 返回手机实时预览所需的服务端权威原厂投影参数。
// 查询必须给出完整 rig/profile，禁止按单颗相机或默认分辨率猜测外参。
func (h *Handler) VinPreviewCalibration(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()
	depthSerial := strings.TrimSpace(query.Get("depth_serial"))
	colorSerial := strings.TrimSpace(query.Get("color_serial"))
	depthWidth, depthWidthErr := strconv.Atoi(query.Get("depth_width"))
	depthHeight, depthHeightErr := strconv.Atoi(query.Get("depth_height"))
	colorWidth, colorWidthErr := strconv.Atoi(query.Get("color_width"))
	colorHeight, colorHeightErr := strconv.Atoi(query.Get("color_height"))
	if depthSerial == "" || colorSerial == "" ||
		depthWidthErr != nil || depthHeightErr != nil || colorWidthErr != nil || colorHeightErr != nil ||
		depthWidth <= 0 || depthHeight <= 0 || colorWidth <= 0 || colorHeight <= 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest,
			"depth/color serial 与 width/height 必须完整且合法"))
		return
	}

	calibration, err := h.vinCalibrations.ResolveVinCalibration(restore.VinCalibrationKey{
		DepthDeviceSerial: depthSerial,
		ColorDeviceSerial: colorSerial,
		DepthWidth:        depthWidth,
		DepthHeight:       depthHeight,
		ColorWidth:        colorWidth,
		ColorHeight:       colorHeight,
	})
	if err != nil {
		if errors.Is(err, restore.ErrVinCalibrationAssetInvalid) {
			httpx.WriteError(w, httpx.NewError(50302, http.StatusServiceUnavailable,
				"VIN 已发布原厂标定资产未就绪: "+err.Error()))
			return
		}
		httpx.WriteError(w, httpx.NewError(40301, http.StatusNotFound,
			"当前 VIN rig/profile 尚未发布预览标定"))
		return
	}

	projection, err := calibration.PreviewProjection()
	if err != nil {
		httpx.WriteError(w, httpx.NewError(50001, http.StatusInternalServerError,
			"VIN 预览标定快照生成失败: "+err.Error()))
		return
	}
	httpx.OK(w, projection)
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
	SampleID       string  `json:"sample_id"`
	BatchID        string  `json:"batch_id"`
	AlphaObjectKey string  `json:"alpha_object_key"`
	FontID         string  `json:"font_id"`
	PositionHint   *int16  `json:"position_hint,omitempty"`
	Value          float64 `json:"value"`      // 算法原始值（IOU 0..1 / Chamfer 0+）
	Similarity     float64 `json:"similarity"` // 归一化 0..1，越大越相似
}

type vinCharRefResp struct {
	VehicleModelID string            `json:"vehicle_model_id"`
	BatchID        string            `json:"batch_id"`
	Character      string            `json:"character"`
	Method         int               `json:"method"`
	Best           *vinCharRefMatch  `json:"best,omitempty"`
	Matches        []vinCharRefMatch `json:"matches"`
	SampleCount    int               `json:"sample_count"`
	LogID          string            `json:"log_id"`
	BelowThreshold bool              `json:"below_threshold,omitempty"`
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
//  1. 调 vin-ref ListActiveSamples → 拿 N 条候选 sample（含签名 alpha_url）
//  2. 对每条 sample：FetchAlpha 拉字节 → ProcVinCharacterCompare 比对
//  3. 排序选最佳；按 method 决定"越大越好"还是"越小越好"
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

// ============================================================================
// vin_detect_yolo —— yolo 实例分割检测整张图的 VIN 区域（M-S10 Phase 2.3）
// ============================================================================

type yoloRRect struct {
	CenterX float32 `json:"cx"`
	CenterY float32 `json:"cy"`
	Width   float32 `json:"w"`
	Height  float32 `json:"h"`
	Angle   float32 `json:"angle"` // 度数
}

type yoloDetection struct {
	Class   string    `json:"class"`
	Score   float32   `json:"score"`
	RRect   yoloRRect `json:"rrect"`
	Contour [][2]int  `json:"contour,omitempty"` // 多边形轮廓（来自 mask）
}

type yoloDetectResp struct {
	Tag           string          `json:"tag"` // 用的模型 tag（默认 VMASK）
	ImageRows     int             `json:"image_rows"`
	ImageCols     int             `json:"image_cols"`
	ConfThreshold float32         `json:"conf_threshold"`
	MaskThreshold float32         `json:"mask_threshold"`
	NMSThreshold  float32         `json:"nms_threshold"`
	Detections    []yoloDetection `json:"detections"`
	Count         int             `json:"count"`
	LogID         string          `json:"log_id"`
}

// VinDetectYolo —— 把整张 VIN 拍照图喂 yolo 实例分割模型（VMASK），返检测到的 VIN 区域多边形 + 旋转矩形。
//
// 入参（multipart / form / base64 / 原始 body）：
//
//	image_binary       图片字节（必填）
//	tag                可选，默认 "VMASK"
//	conf               可选 0..1，默认 0.5（box confidence）
//	mask_thresh        可选 0..1，默认 0.5（mask 二值化）
//	nms_thresh         可选 0..1，默认 0.5
//	rude_scale         可选；rrect 缩放（gosmart 用 0.0 不缩放）
//
// 失败：
//
//	10001 参数 / 解码错
//	40701 模型 tag 未注册（启动期未配 VMASK 或注册失败）
//
// 出参：每个检测包括 class / score / rrect (cx,cy,w,h,angle) / contour（mask 多边形点）。
//
// 注意：在合成图（非真实 VIN 拍照）上模型大概率返 detections=[]，那是 yolo 真实输出 —— 不是 stub。
func (h *Handler) VinDetectYolo(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(32 << 20); err != nil && err != http.ErrNotMultipart {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "multipart 解析失败: "+err.Error()))
		return
	}
	buf, err := readImagePart(r, "image_binary")
	if err != nil || len(buf) == 0 {
		// 兼容裸 body 上传
		if buf2, err2 := io.ReadAll(io.LimitReader(r.Body, 64<<20)); err2 == nil && len(buf2) > 0 {
			buf = buf2
		} else {
			httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "image_binary 缺失"))
			return
		}
	}

	tag := strings.TrimSpace(r.FormValue("tag"))
	if tag == "" {
		tag = "VMASK"
	}
	conf := parseFloatOr(r.FormValue("conf"), 0.5)
	maskTh := parseFloatOr(r.FormValue("mask_thresh"), 0.5)
	nmsTh := parseFloatOr(r.FormValue("nms_thresh"), 0.5)
	rudeScale := parseFloatOr(r.FormValue("rude_scale"), 0.0)

	logID := r.FormValue("log_id")
	if logID == "" {
		logID = "yolo_" + strconv.FormatInt(time.Now().UnixNano(), 36)
	}

	// 解码图（IMReadColor）。Mat 持 C 堆内存、无 finalizer，必须显式 Release（否则长跑 OOM）。
	mat, err := gocv.IMDecode(buf, gocv.IMReadColor)
	defer func() { _ = mat.Release() }()
	if err != nil || mat.Empty() {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "OpenCV 解码失败"))
		return
	}
	// gocv 的 ORTSession_RunMask 期望 RGB 输入；BGR→RGB 标准转换。同样需显式 Release。
	rgb := gocv.NewMat()
	defer func() { _ = rgb.Release() }()
	gocv.CvtColor(mat, &rgb, gocv.ColorBGRToRGB)

	contours, rrects, classes, scores, err := h.runMaskGuarded(r.Context(), tag, rgb, float32(conf), float32(maskTh), float32(nmsTh), float32(rudeScale))
	if err != nil {
		switch {
		case err == core.ErrNotFound:
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"模型 tag="+tag+" 未注册（启动期未配 GOMOB_CVENGINE_MODELS 含 "+tag+":mask=...）"))
		case err == core.ErrWrongKind:
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"模型 tag="+tag+" 未按 mask kind 注册"))
		case err == context.DeadlineExceeded || err == context.Canceled:
			h.log.Warn("RunMask 超时/取消（过载或客户端断开）", "err", err, "tag", tag)
			httpx.WriteError(w, httpx.NewError(50301, http.StatusServiceUnavailable, "推理超时或服务过载: "+err.Error()))
		default:
			h.log.Error("RunMask 失败", "err", err, "tag", tag)
			httpx.WriteError(w, httpx.NewError(50001, http.StatusInternalServerError, "RunMask: "+err.Error()))
		}
		return
	}

	resp := yoloDetectResp{
		Tag:           tag,
		ImageRows:     mat.Rows(),
		ImageCols:     mat.Cols(),
		ConfThreshold: float32(conf),
		MaskThreshold: float32(maskTh),
		NMSThreshold:  float32(nmsTh),
		Detections:    make([]yoloDetection, 0, len(rrects)),
		LogID:         logID,
	}
	for i, rr := range rrects {
		var cls string
		if i < len(classes) {
			cls = classes[i]
		}
		var sc float32
		if i < len(scores) {
			sc = scores[i]
		}
		det := yoloDetection{
			Class: cls,
			Score: sc,
			RRect: yoloRRect{
				CenterX: float32(rr.Center.X),
				CenterY: float32(rr.Center.Y),
				Width:   float32(rr.Width),
				Height:  float32(rr.Height),
				Angle:   float32(rr.Angle),
			},
		}
		if i < len(contours) {
			pts := contours[i]
			det.Contour = make([][2]int, 0, len(pts))
			for _, p := range pts {
				det.Contour = append(det.Contour, [2]int{p.X, p.Y})
			}
		}
		resp.Detections = append(resp.Detections, det)
	}
	resp.Count = len(resp.Detections)

	httpx.OK(w, resp)
}

// ============================================================================
// vin_pipeline —— 整图 → VMASK → 字符 mask → vin-ref → verdict（M-S10 Phase 2.x）
// ============================================================================

// vinPipelineCharResult 一个字符位的端到端打分结果。
type vinPipelineCharResult struct {
	Index          int              `json:"index"`           // 0-based 排序后位置（按 cx 从左到右）
	Character      string           `json:"character"`       // VMASK 输出的 class（即识别字符）
	DetectionScore float32          `json:"detection_score"` // yolo box conf
	BBox           yoloRRect        `json:"bbox"`
	Best           *vinCharRefMatch `json:"best,omitempty"`
	Similarity     float64          `json:"similarity"`
	Status         string           `json:"status"` // scored / no_ref / compare_failed / encode_failed / invalid_class
	Note           string           `json:"note,omitempty"`
}

// vinPipelineResp 一站式 VIN 比对响应。
type vinPipelineResp struct {
	VehicleModelID string                  `json:"vehicle_model_id"`
	BatchID        string                  `json:"batch_id,omitempty"`
	Tag            string                  `json:"tag"`
	Method         int                     `json:"method"`
	ImageRows      int                     `json:"image_rows"`
	ImageCols      int                     `json:"image_cols"`
	Detections     int                     `json:"detections"`
	Scored         int                     `json:"scored"`
	AvgSimilarity  float64                 `json:"avg_similarity"`
	MinSimilarity  float64                 `json:"min_similarity"`
	Verdict        string                  `json:"verdict"` // pass / warning / fail
	PassThreshold  float64                 `json:"pass_threshold"`
	WarnThreshold  float64                 `json:"warn_threshold"`
	Reasons        []string                `json:"reasons,omitempty"`
	Characters     []vinPipelineCharResult `json:"characters"`
	LogID          string                  `json:"log_id"`
}

// VinPipeline —— 把整张 VIN 拍照图喂进 VMASK yolo seg → 拿 N 个字符检测
// → 每个检测从 contour 抠出 alpha mask → 与 vin-ref 厂家库逐字符对照 → 聚合 verdict。
//
// 这是 gosmart 时代 RequestVinCompare + ProcVINDet 的端口对端；调用方（worker / 客户端）
// 不再需要先 detect 再逐字符 compare_with_ref，一次 HTTP 调用拿端到端结果。
//
// 入参（multipart / form / base64 / 原始 body）：
//
//	image_binary       整张 VIN 拍照图（必填）
//	vehicle_model_id   必填（int64 字符串）
//	tag                可选，默认 "VMASK"
//	method             0=IoU（默认）/ 1=Chamfer
//	conf / mask_thresh / nms_thresh   yolo 阈值，默认 0.5
//	rude_scale         rrect 缩放系数，默认 0
//	pass_threshold     pass 阈值（avg+min），默认 0.85
//	warn_threshold     warning 阈值（avg），默认 0.60
//	log_id             可选
//
// 出参 verdict：
//
//	scored=0                                 → fail（reasons 包含 no_chars_detected）
//	scored>0 且 avg>=pass 且 min>=warn      → pass
//	scored>0 且 avg>=warn                   → warning
//	其它                                     → fail
func (h *Handler) VinPipeline(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(64 << 20); err != nil && err != http.ErrNotMultipart {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "multipart 解析失败: "+err.Error()))
		return
	}
	buf, err := readImagePart(r, "image_binary")
	if err != nil || len(buf) == 0 {
		// 兼容裸 body
		if buf2, err2 := io.ReadAll(io.LimitReader(r.Body, 64<<20)); err2 == nil && len(buf2) > 0 {
			buf = buf2
		} else {
			httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "image_binary 缺失"))
			return
		}
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

	tag := strings.TrimSpace(r.FormValue("tag"))
	if tag == "" {
		tag = "VMASK"
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
	conf := parseFloatOr(r.FormValue("conf"), 0.5)
	maskTh := parseFloatOr(r.FormValue("mask_thresh"), 0.5)
	nmsTh := parseFloatOr(r.FormValue("nms_thresh"), 0.5)
	rudeScale := parseFloatOr(r.FormValue("rude_scale"), 0.0)
	passTh := parseFloatOr(r.FormValue("pass_threshold"), 0.85)
	warnTh := parseFloatOr(r.FormValue("warn_threshold"), 0.60)
	if warnTh > passTh {
		warnTh = passTh
	}

	logID := r.FormValue("log_id")
	if logID == "" {
		logID = "vin_pipe_" + strconv.FormatInt(time.Now().UnixNano(), 36)
	}

	// 1. 解码原图。Mat 持 C 堆内存、无 finalizer，必须显式 Release（否则 VIN 主链长跑 OOM）。
	mat, err := gocv.IMDecode(buf, gocv.IMReadColor)
	defer func() { _ = mat.Release() }()
	if err != nil || mat.Empty() {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "OpenCV 解码失败"))
		return
	}
	rows, cols := mat.Rows(), mat.Cols()

	// 2. BGR → RGB → RunMask。rgb 同样需显式 Release。
	rgb := gocv.NewMat()
	defer func() { _ = rgb.Release() }()
	gocv.CvtColor(mat, &rgb, gocv.ColorBGRToRGB)
	contours, rrects, classes, scores, runErr := h.runMaskGuarded(
		r.Context(), tag, rgb, float32(conf), float32(maskTh), float32(nmsTh), float32(rudeScale))
	if runErr != nil {
		switch {
		case runErr == core.ErrNotFound:
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"模型 tag="+tag+" 未注册"))
		case runErr == core.ErrWrongKind:
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"模型 tag="+tag+" 不是 mask kind"))
		case runErr == context.DeadlineExceeded || runErr == context.Canceled:
			h.log.Warn("RunMask 超时/取消（过载或客户端断开）", "err", runErr, "tag", tag, "log_id", logID)
			httpx.WriteError(w, httpx.NewError(50301, http.StatusServiceUnavailable, "推理超时或服务过载: "+runErr.Error()))
		default:
			h.log.Error("RunMask 失败", "err", runErr, "tag", tag, "log_id", logID)
			httpx.WriteError(w, httpx.NewError(50001, http.StatusInternalServerError, "RunMask: "+runErr.Error()))
		}
		return
	}

	// 3. 按 cx 从左到右排序
	type det struct {
		idx     int
		cx      int
		rr      gocv.RotatedRect
		contour []image.Point
		class   string
		score   float32
	}
	dets := make([]det, 0, len(rrects))
	for i := range rrects {
		var cnt []image.Point
		if i < len(contours) {
			cnt = contours[i]
		}
		var cls string
		if i < len(classes) {
			cls = strings.ToUpper(strings.TrimSpace(classes[i]))
		}
		var sc float32
		if i < len(scores) {
			sc = scores[i]
		}
		dets = append(dets, det{
			idx:     i,
			cx:      rrects[i].Center.X,
			rr:      rrects[i],
			contour: cnt,
			class:   cls,
			score:   sc,
		})
	}
	sort.Slice(dets, func(i, j int) bool { return dets[i].cx < dets[j].cx })

	// 4. 按 detection 抽 alpha mask → 调 vin-ref + ProcVinCharacterCompare
	results := make([]vinPipelineCharResult, 0, len(dets))
	var batchID string
	scoredCount := 0
	sum := 0.0
	minSim := 1.0
	reasons := []string{}

	for pos, d := range dets {
		res := vinPipelineCharResult{
			Index:          pos,
			Character:      d.class,
			DetectionScore: d.score,
			BBox: yoloRRect{
				CenterX: float32(d.rr.Center.X),
				CenterY: float32(d.rr.Center.Y),
				Width:   float32(d.rr.Width),
				Height:  float32(d.rr.Height),
				Angle:   float32(d.rr.Angle),
			},
		}
		if d.class == "" || len(d.class) != 1 || !isVinChar(d.class[0]) {
			res.Status = "invalid_class"
			res.Note = "class 不是合法 VIN 字符"
			results = append(results, res)
			continue
		}
		alphaBytes, encErr := contourToAlphaPNG(d.contour, rows, cols)
		if encErr != nil || len(alphaBytes) == 0 {
			res.Status = "encode_failed"
			res.Note = "contour 抠 alpha 失败"
			if encErr != nil {
				res.Note = encErr.Error()
			}
			results = append(results, res)
			continue
		}
		samples, bid, listErr := h.vinRef.ListActiveSamples(r.Context(), vmid, d.class, 0, 0)
		if listErr != nil {
			if listErr == vinrefclient.ErrNotFound {
				res.Status = "no_ref"
				res.Note = "该车型无 active 批次"
				results = append(results, res)
				if !contains(reasons, "no_active_batch") {
					reasons = append(reasons, "no_active_batch")
				}
				continue
			}
			h.log.Warn("vinref ListActiveSamples 失败", "err", listErr, "char", d.class, "log_id", logID)
			res.Status = "no_ref"
			res.Note = "vin-ref 不可用: " + listErr.Error()
			results = append(results, res)
			continue
		}
		if batchID == "" {
			batchID = bid
		}
		if len(samples) == 0 {
			res.Status = "no_ref"
			res.Note = "active 批次中无字符 " + d.class + " 样本"
			results = append(results, res)
			continue
		}

		bestIdx := -1
		var bestVal float64
		var bestSim float64
		var bestSample vinrefclient.Sample
		for _, s := range samples {
			alphaRef, ferr := h.vinRef.FetchAlpha(r.Context(), s)
			if ferr != nil || len(alphaRef) == 0 {
				continue
			}
			val, cerr := proc.ProcVinCharacterCompare(alphaBytes, alphaRef, logID+"_"+s.ID, method)
			if cerr != nil {
				continue
			}
			sim := normalizeSim(val, method)
			if bestIdx < 0 || sim > bestSim {
				bestIdx, bestVal, bestSim, bestSample = 0, val, sim, s
				_ = bestIdx
			}
		}
		if bestSim == 0 && bestIdx < 0 {
			res.Status = "compare_failed"
			res.Note = "全部 ref 样本下载或比对失败"
			results = append(results, res)
			continue
		}
		res.Best = &vinCharRefMatch{
			SampleID:       bestSample.ID,
			BatchID:        bestSample.BatchID,
			AlphaObjectKey: bestSample.AlphaObjectKey,
			FontID:         bestSample.FontID,
			PositionHint:   bestSample.PositionHint,
			Value:          bestVal,
			Similarity:     bestSim,
		}
		res.Similarity = bestSim
		res.Status = "scored"
		results = append(results, res)
		scoredCount++
		sum += bestSim
		if bestSim < minSim {
			minSim = bestSim
		}
	}

	avg := 0.0
	if scoredCount > 0 {
		avg = sum / float64(scoredCount)
	}
	if scoredCount == 0 {
		minSim = 0
	}
	verdict := "fail"
	switch {
	case scoredCount == 0:
		if !contains(reasons, "no_chars_detected") {
			reasons = append(reasons, "no_chars_detected")
		}
	case avg >= passTh && minSim >= warnTh:
		verdict = "pass"
	case avg >= warnTh:
		verdict = "warning"
		reasons = append(reasons, "below_pass_threshold")
	default:
		reasons = append(reasons, "below_warn_threshold")
	}
	if scoredCount > 0 && scoredCount < len(dets) {
		reasons = append(reasons, "partial_scoring")
	}

	httpx.OK(w, vinPipelineResp{
		VehicleModelID: vmidStr,
		BatchID:        batchID,
		Tag:            tag,
		Method:         method,
		ImageRows:      rows,
		ImageCols:      cols,
		Detections:     len(dets),
		Scored:         scoredCount,
		AvgSimilarity:  avg,
		MinSimilarity:  minSim,
		Verdict:        verdict,
		PassThreshold:  passTh,
		WarnThreshold:  warnTh,
		Reasons:        reasons,
		Characters:     results,
		LogID:          logID,
	})
}

// ============================================================================
// vin_restore —— VIN 数码拓印还原（深度去透视 + OBB 正射 → 原厂式彩色正射 PNG）
// ============================================================================

// dev 旁路默认 yolo-obb 路径（env VIN_OBB_MODEL 覆盖）。无 model-registry/MinIO 的纯本地开发/harness 用。
const defaultVinObbModelPath = "/root/lilw/gomob/.dev/vin_models/yolo-obb.onnx"
const defaultVinCharModelPath = "/root/lilw/gomob/.dev/vin_models/vins0.onnx"

// vinObbTag —— yolo-obb 模型在 model-registry / cv-engine 里的 tag（与 model name 同一字符串）。
// 生产部署：把它加进 GOMOB_CVENGINE_MODEL_NAMES，启动期 loader 从 registry→MinIO 拉（metadata.kind="com"）。
const vinObbTag = "VINOBB"
const vinCharTag = "VINCHAR"

// ensureVinObbModel 确保 yolo-obb（KindCom）已注册，懒执行一次。
//
// 优先复用启动期 loader 从 model-registry / GOMOB_CVENGINE_MODELS dev 旁路注册的 VINOBB（与 VMASK 同机制）；
// 仅当未注册（纯本地开发，无 registry/MinIO）时才从 VIN_OBB_MODEL / 默认 .dev 路径懒加载兜底。
func (h *Handler) ensureVinObbModel() error {
	h.obbOnce.Do(func() {
		if err := h.models.CheckKind(vinObbTag, core.KindCom); err == nil {
			return // 已由 loader 注册，直接复用
		} else if !errors.Is(err, core.ErrNotFound) {
			h.obbErr = fmt.Errorf("%s 模型类型错误: %w", vinObbTag, err)
			return
		}
		path := os.Getenv("VIN_OBB_MODEL")
		if path == "" {
			path = defaultVinObbModelPath
		}
		// std=1/255 mean=0 → ÷255 归一，与端侧 yolo-obb 预处理一致。
		h.obbErr = h.models.RegisterComONNX(vinObbTag, path, 1.0/255.0, gocv.Scalar{})
		if h.obbErr != nil {
			h.log.Error("yolo-obb 模型加载失败（dev 旁路）", "err", h.obbErr, "path", path)
		}
	})
	return h.obbErr
}

// ensureVinCharModel 确保逐字符 YOLO 用正确的多输出检测解码加载，不能误注册成 mask。
func (h *Handler) ensureVinCharModel() error {
	h.charOnce.Do(func() {
		if err := h.models.CheckKind(vinCharTag, core.KindYolo); err == nil {
			return
		} else if !errors.Is(err, core.ErrNotFound) {
			h.charErr = fmt.Errorf("%s 模型类型错误: %w", vinCharTag, err)
			return
		}
		path := os.Getenv("VIN_CHAR_MODEL")
		if path == "" {
			path = defaultVinCharModelPath
		}
		h.charErr = h.models.RegisterYoloONNX(
			vinCharTag,
			path,
			core.DefaultYoloOptions(restore.VinCharacterClasses()...),
		)
		if h.charErr != nil {
			h.log.Error("VIN 逐字符模型加载失败（dev 旁路）", "err", h.charErr, "path", path)
		}
	})
	return h.charErr
}

type vinRestoreResp struct {
	OK                   bool    `json:"ok"`
	ResultPNGB64         string  `json:"result_png_base64,omitempty"`
	Width                int     `json:"width"`
	Height               int     `json:"height"`
	TiltDeg              float64 `json:"tilt_deg"`
	WidthMM              float64 `json:"width_mm"`
	HeightMM             float64 `json:"height_mm"`
	ThetaDeg             float64 `json:"theta_deg"`
	InlierRate           float64 `json:"inlier_rate"`
	RMS                  float64 `json:"rms"`
	MedZ                 float64 `json:"med_z"`
	NumDet               int     `json:"num_det"`
	AnchorCount          int     `json:"anchor_count"`
	AnchorCandidateCount int     `json:"anchor_candidate_count"`
	AnchorPitch          float64 `json:"anchor_pitch_px"`
	AnchorRMS            float64 `json:"anchor_rms_px"`
	AnchorScore          float64 `json:"anchor_mean_score"`
	AnchorHeight         float64 `json:"anchor_height_px"`
	AnchorRotation       float64 `json:"anchor_rotation_deg"`
	AnchorScale          float64 `json:"anchor_scale"`
	CalibrationSHA256    string  `json:"calibration_sha256,omitempty"`
	CalibrationVersion   uint32  `json:"calibration_version,omitempty"`
	SyncDeltaUs          int64   `json:"sync_delta_us"`
	RejectReason         string  `json:"reject_reason,omitempty"` // ok=false：tilt_too_large / vin_not_detected / rgbd_out_of_sync / text_anchor_unreliable / calibration_unavailable
	DeviceID             string  `json:"device_id,omitempty"`
	ColorDeviceID        string  `json:"color_device_id,omitempty"`
	LogID                string  `json:"log_id"`
}

// VinRestore —— 收彩色 rgb1300 + depth.yuv + 深度内参，出彩色正射 PNG。
//
// 入参（multipart / form / base64）：
//
//	image_binary_rgb1300   彩色 JPEG（必填）
//	image_binary_depth     RS-D550 mode25 原始 1/8px 视差（小端 u16，dw×dh）（必填）
//	depth_w / depth_h      深度宽高（必填，照端侧 meta.json depth.w / depth.h）
//	fx / fy / cx / cy      客户端诊断元数据（必填但不参与还原；服务端只用原厂 bin）
//	device_id / color_device_id  深度相机 / HLSD8 物理序列号
//	color_w / color_h      HLSD8 实际编码档位
//	log_id                 可选日志 ID
//	color_timestamp_us / depth_timestamp_us  native 收帧 host 单调时钟（必填）
//
// 出参 JSON：result_png_base64 / width / height / tilt_deg / ok / log_id ...
//
// 失败：
//
//	10001  参数缺失 / 解析错
//	40701  VIN OBB / 逐字符模型未就绪（路径不存在 / onnxruntime 加载失败）
//	42201  tilt>70（承印面过斜，原厂硬门）→ ok=false
//	50001  还原内部错（深度点不足 / 无 OBB / 平面奇异等）
func (h *Handler) VinRestore(w http.ResponseWriter, r *http.Request) {
	if err := r.ParseMultipartForm(64 << 20); err != nil && err != http.ErrNotMultipart {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "multipart 解析失败: "+err.Error()))
		return
	}

	rgb, err := readImagePart(r, "image_binary_rgb1300")
	if err != nil || len(rgb) == 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "image_binary_rgb1300 缺失"))
		return
	}
	depth, err := readImagePart(r, "image_binary_depth")
	if err != nil || len(depth) == 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "image_binary_depth 缺失"))
		return
	}

	dw, derr := strconv.Atoi(r.FormValue("depth_w"))
	dh, herr := strconv.Atoi(r.FormValue("depth_h"))
	if derr != nil || herr != nil || dw <= 0 || dh <= 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "depth_w / depth_h 非法"))
		return
	}
	fx, fxe := strconv.ParseFloat(r.FormValue("fx"), 64)
	fy, fye := strconv.ParseFloat(r.FormValue("fy"), 64)
	cx, cxe := strconv.ParseFloat(r.FormValue("cx"), 64)
	cy, cye := strconv.ParseFloat(r.FormValue("cy"), 64)
	if fxe != nil || fye != nil || cxe != nil || cye != nil || fx <= 0 || fy <= 0 ||
		math.IsNaN(fx) || math.IsInf(fx, 0) || math.IsNaN(fy) || math.IsInf(fy, 0) ||
		math.IsNaN(cx) || math.IsInf(cx, 0) || math.IsNaN(cy) || math.IsInf(cy, 0) {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "fx/fy/cx/cy 非法（fx,fy 必须>0）"))
		return
	}

	deviceID := r.FormValue("device_id")
	colorDeviceID := r.FormValue("color_device_id")
	colorW, colorWErr := strconv.Atoi(r.FormValue("color_w"))
	colorH, colorHErr := strconv.Atoi(r.FormValue("color_h"))
	if colorWErr != nil || colorHErr != nil || colorW <= 0 || colorH <= 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "color_w / color_h 非法"))
		return
	}
	colorTimestampUs, colorTSErr := strconv.ParseInt(r.FormValue("color_timestamp_us"), 10, 64)
	depthTimestampUs, depthTSErr := strconv.ParseInt(r.FormValue("depth_timestamp_us"), 10, 64)
	if colorTSErr != nil || depthTSErr != nil || colorTimestampUs <= 0 || depthTimestampUs <= 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest,
			"color_timestamp_us / depth_timestamp_us 非法（必须为 native host 单调时钟）"))
		return
	}
	syncDeltaUs := colorTimestampUs - depthTimestampUs
	if syncDeltaUs < 0 {
		syncDeltaUs = -syncDeltaUs
	}
	logID := r.FormValue("log_id")
	if logID == "" {
		logID = "vin_restore_" + strconv.FormatInt(time.Now().UnixNano(), 36)
	}
	if syncDeltaUs > vinRestoreMaxSyncDeltaUs {
		httpx.OK(w, vinRestoreResp{
			OK:            false,
			SyncDeltaUs:   syncDeltaUs,
			RejectReason:  "rgbd_out_of_sync",
			DeviceID:      deviceID,
			ColorDeviceID: colorDeviceID,
			LogID:         logID,
		})
		return
	}
	calibrationKey := restore.VinCalibrationKey{
		DepthDeviceSerial: deviceID,
		ColorDeviceSerial: colorDeviceID,
		DepthWidth:        dw,
		DepthHeight:       dh,
		ColorWidth:        colorW,
		ColorHeight:       colorH,
	}
	calibration, calibrationErr := h.vinCalibrations.ResolveVinCalibration(calibrationKey)
	if calibrationErr != nil {
		h.log.Warn("VIN 原厂标定不可用", "err", calibrationErr, "depth_serial", deviceID,
			"color_serial", colorDeviceID, "depth_profile", fmt.Sprintf("%dx%d", dw, dh),
			"color_profile", fmt.Sprintf("%dx%d", colorW, colorH))
		if errors.Is(calibrationErr, restore.ErrVinCalibrationAssetInvalid) {
			httpx.WriteError(w, httpx.NewError(50302, http.StatusServiceUnavailable,
				"VIN 已发布原厂标定资产未就绪: "+calibrationErr.Error()))
			return
		}
		httpx.OK(w, vinRestoreResp{
			OK:            false,
			SyncDeltaUs:   syncDeltaUs,
			RejectReason:  "calibration_unavailable",
			DeviceID:      deviceID,
			ColorDeviceID: colorDeviceID,
			LogID:         logID,
		})
		return
	}
	calibrationSHA256, calibrationVersion := calibration.AuditIdentity()

	// 懒加载模型
	if err := h.ensureVinObbModel(); err != nil {
		httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
			"yolo-obb 模型未就绪: "+err.Error()))
		return
	}
	if err := h.ensureVinCharModel(); err != nil {
		httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
			"VIN 逐字符模型未就绪: "+err.Error()))
		return
	}
	releaseInfer, err := h.acquireInferPermit(r.Context())
	if err != nil {
		httpx.WriteError(w, httpx.NewError(50301, http.StatusServiceUnavailable,
			"推理超时或服务过载: "+err.Error()))
		return
	}
	defer releaseInfer()

	png, meta, rerr := restore.Restore(h.models, vinObbTag, vinCharTag, calibration, rgb, depth, dw, dh)
	if rerr != nil {
		// tilt 过斜 → 判废（HTTP 200 + ok=false + 原因），端侧提示重拍，不算系统错。
		if rerr == restore.ErrTiltTooLarge {
			httpx.OK(w, vinRestoreResp{
				OK:                 false,
				TiltDeg:            meta.TiltDeg,
				NumDet:             meta.NumDet,
				InlierRate:         meta.InlierRate,
				CalibrationSHA256:  calibrationSHA256,
				CalibrationVersion: calibrationVersion,
				SyncDeltaUs:        syncDeltaUs,
				RejectReason:       "tilt_too_large",
				DeviceID:           deviceID,
				ColorDeviceID:      colorDeviceID,
				LogID:              logID,
			})
			return
		}
		if errors.Is(rerr, restore.ErrVinNotDetected) {
			httpx.OK(w, vinRestoreResp{
				OK:                 false,
				NumDet:             meta.NumDet,
				CalibrationSHA256:  calibrationSHA256,
				CalibrationVersion: calibrationVersion,
				SyncDeltaUs:        syncDeltaUs,
				RejectReason:       "vin_not_detected",
				DeviceID:           deviceID,
				ColorDeviceID:      colorDeviceID,
				LogID:              logID,
			})
			return
		}
		if errors.Is(rerr, restore.ErrTextAnchorUnreliable) {
			httpx.OK(w, vinRestoreResp{
				OK:                   false,
				TiltDeg:              meta.TiltDeg,
				NumDet:               meta.NumDet,
				AnchorCount:          meta.AnchorCount,
				AnchorCandidateCount: meta.AnchorCandidateCount,
				AnchorPitch:          meta.AnchorPitchPx,
				AnchorRMS:            meta.AnchorRMSPx,
				AnchorScore:          meta.AnchorMeanScore,
				AnchorHeight:         meta.AnchorHeightPx,
				AnchorRotation:       meta.AnchorRotationDeg,
				AnchorScale:          meta.AnchorScale,
				CalibrationSHA256:    calibrationSHA256,
				CalibrationVersion:   calibrationVersion,
				SyncDeltaUs:          syncDeltaUs,
				RejectReason:         "text_anchor_unreliable",
				DeviceID:             deviceID,
				ColorDeviceID:        colorDeviceID,
				LogID:                logID,
			})
			return
		}
		h.log.Warn("VinRestore 还原失败", "err", rerr, "log_id", logID, "tilt", meta.TiltDeg, "ndet", meta.NumDet)
		httpx.WriteError(w, httpx.NewError(50001, http.StatusInternalServerError, "还原失败: "+rerr.Error()))
		return
	}

	h.log.Info(
		"VIN 还原完成",
		"log_id", logID,
		"total_ms", meta.Timings.TotalMS,
		"decode_ms", meta.Timings.DecodeMS,
		"obb_ms", meta.Timings.OBBMS,
		"frame_ms", meta.Timings.FrameMS,
		"probe_render_ms", meta.Timings.ProbeRenderMS,
		"anchor_ms", meta.Timings.AnchorMS,
		"final_render_ms", meta.Timings.FinalRenderMS,
		"png_encode_ms", meta.Timings.PNGEncodeMS,
		"png_bytes", len(png),
	)
	httpx.OK(w, vinRestoreResp{
		OK:                   true,
		ResultPNGB64:         base64.StdEncoding.EncodeToString(png),
		Width:                meta.OutW,
		Height:               meta.OutH,
		TiltDeg:              meta.TiltDeg,
		WidthMM:              meta.WidthMM,
		HeightMM:             meta.HeightMM,
		ThetaDeg:             meta.ThetaDeg,
		InlierRate:           meta.InlierRate,
		RMS:                  meta.RMS,
		MedZ:                 meta.MedZ,
		NumDet:               meta.NumDet,
		AnchorCount:          meta.AnchorCount,
		AnchorCandidateCount: meta.AnchorCandidateCount,
		AnchorPitch:          meta.AnchorPitchPx,
		AnchorRMS:            meta.AnchorRMSPx,
		AnchorScore:          meta.AnchorMeanScore,
		AnchorHeight:         meta.AnchorHeightPx,
		AnchorRotation:       meta.AnchorRotationDeg,
		AnchorScale:          meta.AnchorScale,
		CalibrationSHA256:    meta.CalibrationSHA256,
		CalibrationVersion:   meta.CalibrationVersion,
		SyncDeltaUs:          syncDeltaUs,
		DeviceID:             deviceID,
		ColorDeviceID:        colorDeviceID,
		LogID:                logID,
	})
}

// HLSD8 与 RS-D550 均为无硬触发 5fps 流；native 回调最近邻在任意启动相位下的理论上界为半帧周期。
// 该值只表示回调完成时间差，不等同传感器曝光级同步；高精度标定数据仍需 PTS/SCR 或同步光学事件校正。
const vinRestoreMaxSyncDeltaUs = 100_000

// contourToAlphaPNG 把 yolo 检测的 contour（原图坐标）抠成本字符的 alpha PNG 字节。
//
// 流程：
//  1. 从 contour 算 tight bbox（带 4px 边距，clip 到 [0,W)/[0,H)）
//  2. 新 CV8UC1 黑色 Mat (h,w)
//  3. 把 contour 平移到本地坐标后 FillPoly 白色
//  4. IMEncode .png → []byte
//
// alpha 语义：白(255) = 字符前景，黑(0) = 背景。下游 ProcVinCharacterCompare 走
// IMReadGrayScale 解码再二值化（judge.CalculateVinFontDifference 内部 Threshold）。
func contourToAlphaPNG(contour []image.Point, rows, cols int) ([]byte, error) {
	if len(contour) < 3 {
		return nil, errFewPoints
	}
	const pad = 4
	minX, minY := contour[0].X, contour[0].Y
	maxX, maxY := contour[0].X, contour[0].Y
	for _, p := range contour[1:] {
		if p.X < minX {
			minX = p.X
		}
		if p.Y < minY {
			minY = p.Y
		}
		if p.X > maxX {
			maxX = p.X
		}
		if p.Y > maxY {
			maxY = p.Y
		}
	}
	minX -= pad
	minY -= pad
	maxX += pad
	maxY += pad
	if minX < 0 {
		minX = 0
	}
	if minY < 0 {
		minY = 0
	}
	if maxX >= cols {
		maxX = cols - 1
	}
	if maxY >= rows {
		maxY = rows - 1
	}
	w := maxX - minX + 1
	h := maxY - minY + 1
	if w <= 0 || h <= 0 {
		return nil, errBadBBox
	}
	black := gocv.Scalar{Val1: 0, Val2: 0, Val3: 0, Val4: 0}
	canvas := gocv.NewMatWithSizeFromScalar(black, h, w, gocv.MatTypeCV8UC1)
	defer func() { _ = canvas.Release() }()

	local := make([]image.Point, len(contour))
	for i, p := range contour {
		local[i] = image.Point{X: p.X - minX, Y: p.Y - minY}
	}
	gocv.FillPoly(&canvas, [][]image.Point{local}, color.RGBA{R: 255, G: 255, B: 255, A: 255})

	out, err := gocv.IMEncode(gocv.PNGFileExt, canvas)
	if err != nil {
		return nil, err
	}
	return out, nil
}

var errFewPoints = newPipelineErr("contour 点数不足")
var errBadBBox = newPipelineErr("contour bbox 非法")

type pipelineErr struct{ msg string }

func (e *pipelineErr) Error() string       { return e.msg }
func newPipelineErr(s string) *pipelineErr { return &pipelineErr{msg: s} }

func contains(ss []string, x string) bool {
	for _, s := range ss {
		if s == x {
			return true
		}
	}
	return false
}

// ============================================================================
// shape_compare —— 3D 外廓元数据级质量比对（M-S9.x cv-engine 接入）
// ============================================================================

// shapeBBoxIn 入参 bbox（指针成员便于"未提供"语义）。
type shapeBBoxIn struct {
	MinX *float32 `json:"min_x"`
	MinY *float32 `json:"min_y"`
	MinZ *float32 `json:"min_z"`
	MaxX *float32 `json:"max_x"`
	MaxY *float32 `json:"max_y"`
	MaxZ *float32 `json:"max_z"`
}

// shapeMetaIn JSON 入参里的 scan / ref 元数据形态。
type shapeMetaIn struct {
	TriangleCount int64        `json:"triangle_count"`
	PointCount    int64        `json:"point_count"`
	BBox          *shapeBBoxIn `json:"bbox"`
	Coverage      *float32     `json:"coverage,omitempty"`
	QCScore       *float32     `json:"qc_score,omitempty"`
}

type shapeCompareReq struct {
	VehicleModelID string      `json:"vehicle_model_id"` // int64 字符串
	Scan           shapeMetaIn `json:"scan"`
	PassThreshold  *float64    `json:"pass_threshold,omitempty"` // 默认 0.85
	WarnThreshold  *float64    `json:"warn_threshold,omitempty"` // 默认 0.60
	LogID          string      `json:"log_id,omitempty"`
}

type shapeMetricsResp struct {
	TriRatio        float64 `json:"tri_ratio"`
	PointRatio      float64 `json:"point_ratio"`
	BBoxVolumeRatio float64 `json:"bbox_volume_ratio"`
	BBoxIoU         float64 `json:"bbox_iou"`
	CoverageDiff    float64 `json:"coverage_diff"`
	QCScoreDiff     float64 `json:"qc_score_diff"`

	TriMissing      bool `json:"tri_missing,omitempty"`
	PointMissing    bool `json:"point_missing,omitempty"`
	BBoxMissing     bool `json:"bbox_missing,omitempty"`
	CoverageMissing bool `json:"coverage_missing,omitempty"`
	QCMissing       bool `json:"qc_missing,omitempty"`
}

type shapeRefSummary struct {
	ID            string       `json:"id"`
	VersionLabel  string       `json:"version_label"`
	Format        string       `json:"format"`
	TriangleCount *int64       `json:"triangle_count,omitempty"`
	PointCount    *int64       `json:"point_count,omitempty"`
	BBox          *shapeBBoxIn `json:"bbox,omitempty"`
	Coverage      *float32     `json:"coverage,omitempty"`
	QCScore       *float32     `json:"qc_score,omitempty"`
}

type shapeCompareResp struct {
	VehicleModelID string           `json:"vehicle_model_id"`
	Ref            shapeRefSummary  `json:"ref"`
	Scan           shapeMetaIn      `json:"scan"`
	Metrics        shapeMetricsResp `json:"metrics"`
	Score          float64          `json:"metadata_quality_score"`
	Verdict        string           `json:"verdict"`
	PassThreshold  float64          `json:"pass_threshold"`
	WarnThreshold  float64          `json:"warn_threshold"`
	Reasons        []string         `json:"reasons,omitempty"`
	LogID          string           `json:"log_id"`
}

// ShapeCompare —— 端侧扫描的 3D 外廓元数据 vs 该车型 active shape-ref 的元数据级比对。
//
// 入参（application/json）：
//
//	vehicle_model_id     必填（int64 字符串）
//	scan                 必填，含 triangle_count / point_count / bbox / coverage / qc_score
//	pass_threshold       可选，默认 0.85
//	warn_threshold       可选，默认 0.60
//	log_id               可选
//
// 处理：
//
//  1. 拉 shape-ref active 记录（按 vehicle_model_id）
//  2. shapecmp.Compute(scan, ref) 算 ratios + bbox IoU
//  3. shapecmp.Score / Verdict 出综合分 + verdict + reasons
//
// 错误：
//
//	10001  json 解析 / 必填缺失
//	40701  shape-ref 该车型无 active 版本
//	50001  shape-ref 不可达 / 其它内部错
//
// 注：本端点只做"元数据级"比对（基于 triangle_count / point_count / bbox / coverage / qc_score）。
// "几何级"chamfer / Hausdorff 在 mesh 解析层完成后将作为 GeoMetrics 字段叠加进 metrics，
// 当前响应结构的 metrics / score / verdict 形态保持稳定。
func (h *Handler) ShapeCompare(w http.ResponseWriter, r *http.Request) {
	defer r.Body.Close()
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "body 读取: "+err.Error()))
		return
	}
	var req shapeCompareReq
	if err := json.Unmarshal(body, &req); err != nil {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "json 解析: "+err.Error()))
		return
	}
	if req.VehicleModelID == "" {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "vehicle_model_id 必填"))
		return
	}
	vmid, perr := strconv.ParseInt(req.VehicleModelID, 10, 64)
	if perr != nil || vmid <= 0 {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "vehicle_model_id 非法"))
		return
	}
	passTh := 0.85
	warnTh := 0.60
	if req.PassThreshold != nil {
		passTh = *req.PassThreshold
	}
	if req.WarnThreshold != nil {
		warnTh = *req.WarnThreshold
	}
	if warnTh > passTh {
		warnTh = passTh
	}
	logID := req.LogID
	if logID == "" {
		logID = "shape_cmp_" + strconv.FormatInt(time.Now().UnixNano(), 36)
	}

	// 1. 拉 shape-ref active
	ref, err := h.shapeRef.GetActive(r.Context(), vmid)
	if err != nil {
		if err == shaperefclient.ErrNotFound {
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"该车型 vehicle_model_id="+req.VehicleModelID+" 无 active shape"))
			return
		}
		h.log.Error("shape-ref GetActive 失败", "err", err, "vmid", vmid, "log_id", logID)
		httpx.WriteError(w, httpx.NewError(50001, http.StatusBadGateway, "shape-ref 不可用: "+err.Error()))
		return
	}

	// 2. 装 Metadata
	scanMeta := shapecmp.Metadata{
		TriangleCount: req.Scan.TriangleCount,
		PointCount:    req.Scan.PointCount,
		Coverage:      req.Scan.Coverage,
		QCScore:       req.Scan.QCScore,
	}
	if req.Scan.BBox != nil && req.Scan.BBox.MinX != nil && req.Scan.BBox.MaxX != nil &&
		req.Scan.BBox.MinY != nil && req.Scan.BBox.MaxY != nil &&
		req.Scan.BBox.MinZ != nil && req.Scan.BBox.MaxZ != nil {
		scanMeta.BBox = &shapecmp.BBox{
			MinX: *req.Scan.BBox.MinX, MinY: *req.Scan.BBox.MinY, MinZ: *req.Scan.BBox.MinZ,
			MaxX: *req.Scan.BBox.MaxX, MaxY: *req.Scan.BBox.MaxY, MaxZ: *req.Scan.BBox.MaxZ,
		}
	}

	refMeta := shapecmp.Metadata{
		Coverage: ref.Coverage,
		QCScore:  ref.QCScore,
	}
	if ref.TriangleCount != nil {
		refMeta.TriangleCount = *ref.TriangleCount
	}
	if ref.PointCount != nil {
		refMeta.PointCount = *ref.PointCount
	}
	if ref.BBox != nil && ref.BBox.MinX != nil && ref.BBox.MaxX != nil &&
		ref.BBox.MinY != nil && ref.BBox.MaxY != nil &&
		ref.BBox.MinZ != nil && ref.BBox.MaxZ != nil {
		refMeta.BBox = &shapecmp.BBox{
			MinX: *ref.BBox.MinX, MinY: *ref.BBox.MinY, MinZ: *ref.BBox.MinZ,
			MaxX: *ref.BBox.MaxX, MaxY: *ref.BBox.MaxY, MaxZ: *ref.BBox.MaxZ,
		}
	}

	// 3. 比对 + 得分 + verdict
	m := shapecmp.Compute(scanMeta, refMeta)
	score := m.Score()
	verdict, reasons := shapecmp.Verdict(m, score, passTh, warnTh)

	// 4. 拼响应
	resp := shapeCompareResp{
		VehicleModelID: req.VehicleModelID,
		Ref: shapeRefSummary{
			ID:            ref.ID,
			VersionLabel:  ref.VersionLabel,
			Format:        ref.Format,
			TriangleCount: ref.TriangleCount,
			PointCount:    ref.PointCount,
			Coverage:      ref.Coverage,
			QCScore:       ref.QCScore,
		},
		Scan: req.Scan,
		Metrics: shapeMetricsResp{
			TriRatio:        m.TriRatio,
			PointRatio:      m.PointRatio,
			BBoxVolumeRatio: m.BBoxVolumeRatio,
			BBoxIoU:         m.BBoxIoU,
			CoverageDiff:    m.CoverageDiff,
			QCScoreDiff:     m.QCScoreDiff,
			TriMissing:      m.TriMissing,
			PointMissing:    m.PointMissing,
			BBoxMissing:     m.BBoxMissing,
			CoverageMissing: m.CoverageMissing,
			QCMissing:       m.QCMissing,
		},
		Score:         score,
		Verdict:       verdict,
		PassThreshold: passTh,
		WarnThreshold: warnTh,
		Reasons:       reasons,
		LogID:         logID,
	}
	if ref.BBox != nil {
		resp.Ref.BBox = &shapeBBoxIn{
			MinX: ref.BBox.MinX, MinY: ref.BBox.MinY, MinZ: ref.BBox.MinZ,
			MaxX: ref.BBox.MaxX, MaxY: ref.BBox.MaxY, MaxZ: ref.BBox.MaxZ,
		}
	}
	httpx.OK(w, resp)
}

// parseFloatOr 解析 float 参数；失败用默认值。
func parseFloatOr(s string, def float64) float64 {
	if s == "" {
		return def
	}
	v, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return def
	}
	return v
}

// Healthz 不调 cgo，纯 Go 探活。
func (h *Handler) Healthz(w http.ResponseWriter, _ *http.Request) {
	httpx.OK(w, map[string]any{
		"ok":         true,
		"uptime_sec": int(time.Since(h.startedAt).Seconds()),
		"go_version": runtime.Version(),
	})
}

// Readyz 同时验证 cgo/OpenCV、VIN 原厂标定与还原模型。生产缺任一依赖都必须返回 503，
// 不能让编排器把“进程活着但 VIN 拍照必然失败”判成可接流量。
func (h *Handler) Readyz(w http.ResponseWriter, _ *http.Request) {
	ocvVer := gocv.OpenCVVersion()
	gocvVer := gocv.Version()
	if ocvVer == "" {
		http.Error(w, "OpenCV not linked", http.StatusServiceUnavailable)
		return
	}
	calibrationErr := restore.ValidateRequiredFactoryVinCalibrations(h.vinCalibrations)
	calibrationReady := calibrationErr == nil
	if h.vinCalibrationRequired && !calibrationReady {
		http.Error(w, "VIN 原厂标定未就绪: "+calibrationErr.Error(), http.StatusServiceUnavailable)
		return
	}
	modelsErr := h.validateVinRestoreModels()
	modelsReady := modelsErr == nil
	if h.vinModelsRequired && !modelsReady {
		http.Error(w, "VIN 还原模型未就绪: "+modelsErr.Error(), http.StatusServiceUnavailable)
		return
	}
	httpx.OK(w, map[string]any{
		"ready":                            true,
		"opencv_version":                   ocvVer,
		"gocv_version":                     gocvVer,
		"vin_factory_calibration_required": h.vinCalibrationRequired,
		"vin_factory_calibration_ready":    calibrationReady,
		"vin_restore_models_required":      h.vinModelsRequired,
		"vin_restore_models_ready":         modelsReady,
	})
}

func (h *Handler) validateVinRestoreModels() error {
	if err := h.models.CheckKind(vinObbTag, core.KindCom); err != nil {
		return fmt.Errorf("%s: %w", vinObbTag, err)
	}
	if err := h.models.CheckKind(vinCharTag, core.KindYolo); err != nil {
		return fmt.Errorf("%s: %w", vinCharTag, err)
	}
	return nil
}

// ValidateRequiredDependencies 在 HTTP 监听前执行生产依赖硬门；Docker unhealthy 不是流量隔离机制，
// 因此已声明 required 的标定或模型缺失时进程必须直接启动失败。
func (h *Handler) ValidateRequiredDependencies() error {
	if h.vinCalibrationRequired {
		if err := restore.ValidateRequiredFactoryVinCalibrations(h.vinCalibrations); err != nil {
			return fmt.Errorf("VIN 原厂标定未就绪: %w", err)
		}
	}
	if h.vinModelsRequired {
		if err := h.validateVinRestoreModels(); err != nil {
			return fmt.Errorf("VIN 还原模型未就绪: %w", err)
		}
	}
	return nil
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
	// 订正旧错误注释：本仓 gocv shim（mat_noprofile.go / mat_profile.go）未注册 runtime.SetFinalizer，
	// Mat 持有的是 C 堆内存，GC 不会回收 —— 必须显式 Release，否则 VIN 主链长跑泄漏 → OOM。
	defer func() { _ = mat.Release() }()
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
