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
//	POST /cv/v1/shape_compare                       3D 外廓元数据级比对（Scan vs shape-ref active）
//
// VIN 拓印采集与还原已迁移到独立 vin-rubbing-service；本进程不再暴露任何旧 /cv/ocr/v1/vin_* 生产接口。
//
// 鉴权：业务端点默认走 gateway → cvengine 的 X-Gomob-User-Id 头注入路径；
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
	"time"

	"io.gomob/server/internal/cvengine/core"
	"io.gomob/server/internal/cvengine/gocv"
	"io.gomob/server/internal/cvengine/shapecmp"
	"io.gomob/server/internal/cvengine/shaperefclient"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
)

type Handler struct {
	startedAt    time.Time
	log          *slog.Logger
	requireAuth  bool
	shapeRef     *shaperefclient.Client
	models       *core.Registry
}

func NewHandler() *Handler {
	return &Handler{
		startedAt:    time.Now(),
		log:          logger.New("cvengine.handler"),
		requireAuth:  os.Getenv("GOMOB_CVENGINE_REQUIRE_AUTH") == "true",
		shapeRef:     shaperefclient.NewClient(os.Getenv("GOMOB_SHAPEREF_TARGET")),
		models:       core.New(),
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
	mux.Handle("POST /cv/v1/shape_compare",
		h.required(http.HandlerFunc(h.ShapeCompare)))
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

// 业务端点（VIN 已迁移到 vin-rubbing-service）

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

// Readyz 验证 cvengine 自身的 OpenCV 后端；VIN 还原依赖由独立服务负责。
func (h *Handler) Readyz(w http.ResponseWriter, _ *http.Request) {
	ocvVer := gocv.OpenCVVersion()
	if ocvVer == "" {
		http.Error(w, "OpenCV not linked", http.StatusServiceUnavailable)
		return
	}
	httpx.OK(w, map[string]any{
		"ready":          true,
		"opencv_version": ocvVer,
		"gocv_version":   gocv.Version(),
	})
}

// ValidateRequiredDependencies 在监听前验证 cvengine 自身依赖；VIN 依赖不再由此进程加载。
func (h *Handler) ValidateRequiredDependencies() error {
	if gocv.OpenCVVersion() == "" {
		return fmt.Errorf("OpenCV not linked")
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
