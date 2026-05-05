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
//
// Phase 2.3 已迁入：vin_detect_yolo（gocv.RunMask，真 yolo 推理）。
// Phase 2.x 已迁入：vin_pipeline（整图一次喂入返 verdict）。
//
// 鉴权：业务端点（/cv/ocr/v1/*）默认走 gateway → cvengine 的 X-Gomob-User-Id 头注入路径；
// 直连测试时通过 GOMOB_CVENGINE_REQUIRE_AUTH=true 强制；harness 默认 false。
package cvengine

import (
	"encoding/base64"
	"encoding/json"
	"image"
	"image/color"
	"io"
	"log/slog"
	"net/http"
	"os"
	"runtime"
	"sort"
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
	mux.Handle("POST /cv/ocr/v1/vin_detect_yolo",
		h.required(http.HandlerFunc(h.VinDetectYolo)))
	mux.Handle("POST /cv/ocr/v1/vin_pipeline",
		h.required(http.HandlerFunc(h.VinPipeline)))
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
	Class    string      `json:"class"`
	Score    float32     `json:"score"`
	RRect    yoloRRect   `json:"rrect"`
	Contour  [][2]int    `json:"contour,omitempty"` // 多边形轮廓（来自 mask）
}

type yoloDetectResp struct {
	Tag           string          `json:"tag"`           // 用的模型 tag（默认 VMASK）
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

	// 解码图（IMReadColor）
	mat, err := gocv.IMDecode(buf, gocv.IMReadColor)
	if err != nil || mat.Empty() {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "OpenCV 解码失败"))
		return
	}
	// gocv 的 ORTSession_RunMask 期望 RGB 输入；BGR→RGB 标准转换。
	rgb := gocv.NewMat()
	gocv.CvtColor(mat, &rgb, gocv.ColorBGRToRGB)

	contours, rrects, classes, scores, err := h.models.RunMask(tag, rgb, float32(conf), float32(maskTh), float32(nmsTh), float32(rudeScale))
	if err != nil {
		switch err {
		case core.ErrNotFound:
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"模型 tag="+tag+" 未注册（启动期未配 GOMOB_CVENGINE_MODELS 含 "+tag+":mask=...）"))
		case core.ErrWrongKind:
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"模型 tag="+tag+" 未按 mask kind 注册"))
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

	// 1. 解码原图
	mat, err := gocv.IMDecode(buf, gocv.IMReadColor)
	if err != nil || mat.Empty() {
		httpx.WriteError(w, httpx.NewError(10001, http.StatusBadRequest, "OpenCV 解码失败"))
		return
	}
	rows, cols := mat.Rows(), mat.Cols()

	// 2. BGR → RGB → RunMask
	rgb := gocv.NewMat()
	gocv.CvtColor(mat, &rgb, gocv.ColorBGRToRGB)
	contours, rrects, classes, scores, runErr := h.models.RunMask(
		tag, rgb, float32(conf), float32(maskTh), float32(nmsTh), float32(rudeScale))
	if runErr != nil {
		switch runErr {
		case core.ErrNotFound:
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"模型 tag="+tag+" 未注册"))
		case core.ErrWrongKind:
			httpx.WriteError(w, httpx.NewError(40701, http.StatusNotFound,
				"模型 tag="+tag+" 不是 mask kind"))
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

func (e *pipelineErr) Error() string         { return e.msg }
func newPipelineErr(s string) *pipelineErr   { return &pipelineErr{msg: s} }

func contains(ss []string, x string) bool {
	for _, s := range ss {
		if s == x {
			return true
		}
	}
	return false
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
