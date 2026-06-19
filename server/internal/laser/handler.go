package laser

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"math"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"io.gomob/server/pkg/repo"
)

// handler.go = laserworker 的 REST 面（经 gateway 反代 /v1/scans/laser）。请求驱动：
//
//	POST /v1/scans/laser            起一次扫描（建 job→后台 runner.Run→201 capturing）
//	POST /v1/scans/laser/{id}/stop  协作取消（repo.Cancel + CancelScan + 设备 SCAN_STOP）
//	GET  /v1/scans/laser/{id}       查状态（断线重连兜底，含三 PCD object key）
//
// 单活约束：底层 C-ABI 一次一会话（全局 g_cancel），故同时仅允许一个进行中扫描，忙则 409。
// 依赖全注入（repo / prober / gate 工厂 / launch），故 httptest 不需真设备/DB/cgo/NATS。

// LaserRepo = handler 需要的 repo 子集（*repo.LaserScanRepo 满足）。内嵌 JobStore 供 runner 复用同一实例。
type LaserRepo interface {
	Create(ctx context.Context, sessionKey, unitAIP, unitBIP, align string, keepRatio float32,
		inspectionID, ownerUserID *int64) (*repo.LaserScanJob, error)
	FindByID(ctx context.Context, id int64) (*repo.LaserScanJob, error)
	FindLatestDone(ctx context.Context, unitAIP, unitBIP string) (*repo.LaserScanJob, error)
	Cancel(ctx context.Context, id int64) (*repo.LaserScanJob, error)
	JobStore
}

// Config = laserworker 配置。
type Config struct {
	DefaultUnitAIP string        // 默认 192.168.9.101
	DefaultUnitBIP string        // 默认 192.168.9.102
	DefaultAlign   string        // 默认 site，起扫必须显式携带外参
	DefaultKeep    float32       // 默认 1.0
	ProbeTimeout   time.Duration // 探活超时，默认 3s

	// 起扫前给两单元各自下发扫描起止角。默认 false：沿用设备持久化值，让控制面板设置真正生效。
	// 只有运维显式设 GOMOB_LASER_SET_SCAN_ANGLES=true 时才覆盖。
	SetScanAngles bool    // 默认 false（main.go 经 env 注入）
	ScanAStart    float64 // unit A 起始角，默认 0
	ScanAStop     float64 // unit A 停止角，默认 90
	ScanBStart    float64 // unit B 起始角，默认 -170
	ScanBStop     float64 // unit B 停止角，默认 -10

	DefaultRegionFilter PointRegionFilter // 默认工位区域墙过滤；空值=不过滤。
}

func (c Config) withDefaults() Config {
	if c.DefaultUnitAIP == "" {
		c.DefaultUnitAIP = "192.168.9.101"
	}
	if c.DefaultUnitBIP == "" {
		c.DefaultUnitBIP = "192.168.9.102"
	}
	if c.DefaultAlign == "" {
		c.DefaultAlign = "site"
	}
	if c.DefaultKeep <= 0 || c.DefaultKeep > 1 {
		c.DefaultKeep = 1.0
	}
	if c.ProbeTimeout <= 0 {
		c.ProbeTimeout = 3 * time.Second
	}
	// 角度缺省给一段线性机械角内的稳定扫程；仅 SetScanAngles=true 时使用，默认不覆盖设备持久化值。
	if c.ScanAStart == 0 && c.ScanAStop == 0 && c.ScanBStart == 0 && c.ScanBStop == 0 {
		c.ScanAStart, c.ScanAStop = 0, 90
		c.ScanBStart, c.ScanBStop = -170, -10
	}
	return c
}

// Handler 持有依赖；字段导出/可替换以便测试注入。
type Handler struct {
	cfg      Config
	repo     LaserRepo
	runner   *Runner
	pub      Publisher
	log      *slog.Logger
	sessions *sessionRegistry

	reader    CloudReader  // PCD 下载（可空 → 下载端点 501）
	cropBoxes CropBoxStore // 持久车位框存储（可空 → crop-box 端点 501）

	// 可注入点（默认指向真实现）。
	probe   Prober
	newGate func(ipA, ipB string) DeviceGate
	launch  func(func())              // 后台执行扫描；默认 go f()，测试可改同步
	newDev  func(ip string) DeviceAPI // 单元设备客户端工厂（设备控制面板用）
}

// DeviceAPI = handler 设备控制所需的单元客户端能力（*DeviceClient 满足；测试可 mock）。
type DeviceAPI interface {
	GetStatus(ctx context.Context) (*DeviceStatus, error)
	GetInfo(ctx context.Context) (*DeviceInfo, error)
	ControlScan(ctx context.Context, cmd ScanCmd) error
	UpdateControl(ctx context.Context, s ControlSettings) error
	UpdateCalib(ctx context.Context, p CalibParams) error
}

// SetCloudReader 注入 PCD 下载读取器（laserworker 用同一 MinIOCloudStore 实例）。
func (h *Handler) SetCloudReader(r CloudReader) { h.reader = r }

// SetCropBoxStore 注入持久车位框存储（与 runner.CropBoxes 同实例）。
func (h *Handler) SetCropBoxStore(s CropBoxStore) { h.cropBoxes = s }

// NewHandler 建生产 handler。pub 可空（不发 NATS）。
func NewHandler(cfg Config, lr LaserRepo, runner *Runner, pub Publisher, log *slog.Logger) *Handler {
	cfg = cfg.withDefaults()
	if log == nil {
		log = slog.Default()
	}
	return &Handler{
		cfg:      cfg,
		repo:     lr,
		runner:   runner,
		pub:      pub,
		log:      log,
		sessions: &sessionRegistry{active: map[int64]*activeSession{}},
		probe:    NewDeviceProber(cfg.ProbeTimeout),
		newGate: func(a, b string) DeviceGate {
			return NewDevctlGate(a, b, cfg.ScanAStart, cfg.ScanAStop, cfg.ScanBStart, cfg.ScanBStop, cfg.SetScanAngles, log)
		},
		launch: func(f func()) { go f() },
		newDev: func(ip string) DeviceAPI { return NewDeviceClient(ip, cfg.ProbeTimeout) },
	}
}

// Mount 注册路由。
func (h *Handler) Mount(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/scans/laser", h.StartScan)
	mux.HandleFunc("GET /v1/scans/laser/active", h.ActiveScan)
	mux.HandleFunc("GET /v1/scans/laser/latest", h.LatestScan)
	mux.HandleFunc("GET /v1/scans/laser/active/cloud/{name}", h.DownloadActiveCloud)
	mux.HandleFunc("POST /v1/scans/laser/{id}/stop", h.StopScan)
	mux.HandleFunc("GET /v1/scans/laser/{id}", h.GetScan)
	// PCD 下载（融合 414万点不走 ws，经此流式取；name 白名单从 job 取 object key，零路径穿越）。
	mux.HandleFunc("GET /v1/scans/laser/{id}/cloud/{name}", h.DownloadCloud)

	// 持久车位框（M9.11）。crop-box 是 literal 段，比 {id} 更具体不歧义；crop-preview 是 {id} 子资源。
	mux.HandleFunc("GET /v1/scans/laser/crop-box", h.GetCropBox)
	mux.HandleFunc("PUT /v1/scans/laser/crop-box", h.PutCropBox)
	mux.HandleFunc("POST /v1/scans/laser/{id}/crop-preview", h.CropPreview)

	// 空工位背景（路 B 背景相减）：查本工位是否已采集背景。采集用 POST /v1/scans/laser?mark_as_background=...
	// （即普通扫描 + 标记），重采直接覆盖同 key，故无需 DELETE。
	mux.HandleFunc("GET /v1/scans/laser/background", h.GetBackground)

	// 设备控制面板（原厂功能键）。用 literal 子资源 + ?unit=a|b 查询参，避开与 {id}/cloud/{name}
	// 通配的路由歧义（literal 段比 {id} 更具体，不 panic）。
	mux.HandleFunc("GET /v1/scans/laser/device-status", h.DeviceStatus)               // 状态信息
	mux.HandleFunc("GET /v1/scans/laser/device-info", h.DeviceInfo)                   // 设备信息+当前设置/标定
	mux.HandleFunc("POST /v1/scans/laser/device-command", h.DeviceCommand)            // 零位校准/守望/停止/清错/软复位
	mux.HandleFunc("POST /v1/scans/laser/device-scan-settings", h.DeviceScanSettings) // 扫描设置
	mux.HandleFunc("POST /v1/scans/laser/site-calib", h.SiteCalib)                    // 一键自动标定 A↔B(ArUco 标记场)
	mux.HandleFunc("POST /v1/scans/laser/site-framing", h.SiteFraming)                // 实时取景标定（边扫边推 RGB 帧+检测）
	mux.HandleFunc("POST /v1/scans/laser/device-calib", h.DeviceCalib)                // 标定参数（破坏性）
}

// resolveUnit 从 ?ip= 或 ?unit=a|b（兼容 101|102）解析出设备客户端 + IP。
// Web 工位管理台可管理多个相机，故允许显式 ip；unit 查询保持 App 兼容。
func (h *Handler) resolveUnit(r *http.Request) (DeviceAPI, string, bool) {
	if rawIP := r.URL.Query().Get("ip"); rawIP != "" {
		addr, err := netip.ParseAddr(rawIP)
		if err != nil || !addr.Is4() {
			return nil, "", false
		}
		ip := addr.String()
		return h.newDev(ip), ip, true
	}
	var ip string
	switch r.URL.Query().Get("unit") {
	case "a", "A", "101":
		ip = h.cfg.DefaultUnitAIP
	case "b", "B", "102":
		ip = h.cfg.DefaultUnitBIP
	default:
		return nil, "", false
	}
	return h.newDev(ip), ip, true
}

func (h *Handler) expectedSweepDeg(ctx context.Context, ip, tag string) (float32, error) {
	var start, stop float64
	if h.cfg.SetScanAngles {
		if tag == "A" {
			start, stop = h.cfg.ScanAStart, h.cfg.ScanAStop
		} else {
			start, stop = h.cfg.ScanBStart, h.cfg.ScanBStop
		}
	} else {
		info, err := h.newDev(ip).GetInfo(ctx)
		if err != nil {
			return 0, errors.New("读取 unit" + tag + "(" + ip + ") 扫描设置失败: " + err.Error())
		}
		start, stop = info.Control.ScanStartAngle, info.Control.ScanStopAngle
	}
	if err := validateScanAngles(start, stop); err != nil {
		return 0, errors.New("unit" + tag + "(" + ip + ") 扫描设置无效 " +
			strconv.FormatFloat(start, 'f', 1, 64) + "°→" +
			strconv.FormatFloat(stop, 'f', 1, 64) + "°: " + err.Error())
	}
	return float32(linearScanSpanDeg(start, stop)), nil
}

// --- 请求/响应体 ---

type startReq struct {
	InspectionID     *int64             `json:"inspection_id"`
	UnitAIP          string             `json:"unit_a_ip"`
	UnitBIP          string             `json:"unit_b_ip"`
	Align            string             `json:"align"`
	SiteJSON         string             `json:"site_json"`
	KeepRatio        *float32           `json:"keep_ratio"`
	VehicleTypeID    *int               `json:"vehicle_type_id"` // 逆向 JCHY 车型编号（docs/16 §4.1）；缺省=未选
	RegionFilter     *PointRegionFilter `json:"region_filter"`
	MarkAsBackground bool               `json:"mark_as_background"` // true=把本次融合云存为本工位空工位背景，不测量
}

type startResp struct {
	ScanID     int64  `json:"scan_id"`
	SessionKey string `json:"session_key"`
	Status     string `json:"status"`
}

// StartScan POST /v1/scans/laser。
func (h *Handler) StartScan(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	var req startReq
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&req) // 空体 → 全默认
	}
	ipA := orStr(req.UnitAIP, h.cfg.DefaultUnitAIP)
	ipB := orStr(req.UnitBIP, h.cfg.DefaultUnitBIP)
	siteJSON := strings.TrimSpace(req.SiteJSON)
	align := strings.TrimSpace(req.Align)
	if align == "" {
		align = h.cfg.DefaultAlign
	}
	if align != "site" && align != "raw" {
		writeErr(w, http.StatusBadRequest, "激光多镜头扫描只支持外参融合或未标定原始采集")
		return
	}
	if siteJSON == "" {
		align = "raw"
	} else {
		align = "site"
		if err := validateSiteExtrinsicJSON(siteJSON); err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
	}
	keep := h.cfg.DefaultKeep
	if req.KeepRatio != nil {
		keep = *req.KeepRatio
	}
	if keep <= 0 || keep > 1 {
		writeErr(w, http.StatusBadRequest, "keep_ratio 须在 (0,1]")
		return
	}
	regionFilter := h.cfg.DefaultRegionFilter
	if req.RegionFilter != nil {
		regionFilter = *req.RegionFilter
	}
	var err error
	regionFilter, err = regionFilter.Normalized()
	if err != nil {
		writeErr(w, http.StatusBadRequest, "region_filter 无效: "+err.Error())
		return
	}

	// 单活约束（C-ABI 一次一会话）。
	if !h.sessions.tryReserve() {
		writeErr(w, http.StatusConflict, "已有进行中的激光扫描，请先停止")
		return
	}
	// 失败路径需释放预留。
	released := false
	release := func() {
		if !released {
			released = true
			h.sessions.release()
		}
	}

	ctx := r.Context()
	// 探活两单元（不可达/离线则不起扫描）。
	if pr := h.probe.Probe(ctx, ipA); !pr.Reachable || !pr.Online {
		release()
		writeErr(w, http.StatusBadGateway, "unitA("+ipA+") 不可达或子系统离线: "+pr.Err)
		return
	}
	if pr := h.probe.Probe(ctx, ipB); !pr.Reachable || !pr.Online {
		release()
		writeErr(w, http.StatusBadGateway, "unitB("+ipB+") 不可达或子系统离线: "+pr.Err)
		return
	}
	expectedA, err := h.expectedSweepDeg(ctx, ipA, "A")
	if err != nil {
		release()
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	expectedB, err := h.expectedSweepDeg(ctx, ipB, "B")
	if err != nil {
		release()
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}

	sessionKey, err := newSessionKey()
	if err != nil {
		release()
		writeErr(w, http.StatusInternalServerError, "生成 session 失败")
		return
	}
	owner := uid
	jobAlign := align
	if jobAlign == "raw" {
		jobAlign = "site"
	}
	job, err := h.repo.Create(context.WithoutCancel(ctx), sessionKey, ipA, ipB, jobAlign, keep, req.InspectionID, &owner)
	if err != nil {
		release()
		writeErr(w, http.StatusInternalServerError, "建扫描任务失败: "+err.Error())
		return
	}

	// 配置 runner 的设备门控（live SCAN_START/STOP）。
	h.runner.Gate = h.newGate(ipA, ipB)
	active := &activeSession{
		jobID:        job.ID,
		sessionKey:   sessionKey,
		owner:        owner,
		unitAIP:      ipA,
		unitBIP:      ipB,
		alignMethod:  align,
		state:        repo.LaserScanStatusCapturing,
		regionFilter: regionFilter,
		cache:        newLivePointCache(),
		cancel:       CancelScan,
	}
	sink := liveSessionSink{active: active, primary: NewNATSSink(h.pub, sessionKey, &owner, h.log)}
	vehicleTypeID := -1 // 未选
	if req.VehicleTypeID != nil {
		vehicleTypeID = *req.VehicleTypeID
	}
	spec := RunSpec{
		JobID:             job.ID,
		SessionKey:        sessionKey,
		InspectionID:      req.InspectionID,
		OwnerUserID:       &owner,
		UnitAIP:           ipA,
		UnitBIP:           ipB,
		Align:             align,
		SiteJSON:          siteJSON,
		KeepRatio:         keep,
		VehicleTypeID:     vehicleTypeID,
		ExpectedSweepADeg: expectedA,
		ExpectedSweepBDeg: expectedB,
		RegionFilter:      regionFilter,
		MarkAsBackground:  req.MarkAsBackground,
	}
	// 注册活动会话（cancel = CancelScan 协作取消 cgo 采集；设备 SCAN_STOP 由 runner 的 defer Gate.Stop 兜底）。
	h.sessions.set(job.ID, active)

	h.launch(func() {
		defer release()
		defer h.sessions.clear(job.ID)
		runCtx := context.Background()
		if _, err := h.runner.Run(runCtx, spec, sink); err != nil {
			h.log.Info("扫描结束（非成功）", "job", job.ID, "err", err)
		}
	})

	writeJSON(w, http.StatusCreated, startResp{ScanID: job.ID, SessionKey: sessionKey, Status: repo.LaserScanStatusCapturing})
}

// StopScan POST /v1/scans/laser/{id}/stop。
func (h *Handler) StopScan(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "无效 scan id")
		return
	}
	job, err := h.repo.FindByID(r.Context(), id)
	if err != nil {
		writeErr(w, http.StatusNotFound, "扫描不存在")
		return
	}
	if !ownsOrAdmin(r, job, uid) {
		writeErr(w, http.StatusForbidden, "无权操作该扫描")
		return
	}
	// 先协作取消 cgo 采集（若是当前活动会话）。
	if as := h.sessions.get(id); as != nil && as.cancel != nil {
		as.cancel()
	}
	// 置 cancelled（仅进行中可转）。已 done/failed 则保持原状（FindByID 已是终态）。
	updated, cerr := h.repo.Cancel(context.WithoutCancel(r.Context()), id)
	if cerr != nil && !errors.Is(cerr, repo.ErrNotFound) {
		writeErr(w, http.StatusInternalServerError, "取消失败: "+cerr.Error())
		return
	}
	status := job.Status
	if updated != nil {
		status = updated.Status
	}
	writeJSON(w, http.StatusOK, map[string]any{"scan_id": id, "status": status})
}

// GetScan GET /v1/scans/laser/{id}。
func (h *Handler) GetScan(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "无效 scan id")
		return
	}
	job, err := h.repo.FindByID(r.Context(), id)
	if err != nil {
		writeErr(w, http.StatusNotFound, "扫描不存在")
		return
	}
	if !ownsOrAdmin(r, job, uid) {
		writeErr(w, http.StatusForbidden, "无权查看该扫描")
		return
	}
	writeJSON(w, http.StatusOK, jobView(job))
}

// ActiveScan GET /v1/scans/laser/active?unit_a_ip=...&unit_b_ip=...。
// 返回当前工位正在跑的扫描；网页刷新后据此恢复 scan_id/session_key 并下载实时快照。
func (h *Handler) ActiveScan(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	ipA, ipB, ok := activeStationIPs(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip/unit_b_ip 必须是 IPv4")
		return
	}
	as := h.sessions.find(ipA, ipB)
	if as == nil {
		writeJSON(w, http.StatusOK, map[string]any{"active": false})
		return
	}
	job, err := h.repo.FindByID(r.Context(), as.jobID)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{"active": false})
		return
	}
	if !ownsOrAdmin(r, job, uid) {
		writeErr(w, http.StatusForbidden, "无权查看该扫描")
		return
	}
	state, framesA, framesB := as.liveStatus()
	counts := as.cache.counts()
	resp := jobView(job)
	resp["active"] = true
	resp["unit_a_ip"] = as.unitAIP
	resp["unit_b_ip"] = as.unitBIP
	resp["live_state"] = state
	resp["frames_a"] = framesA
	resp["frames_b"] = framesB
	resp["live_points_a"] = counts[0]
	resp["live_points_b"] = counts[1]
	resp["align_method"] = as.alignMethod
	resp["fusion_available"] = as.alignMethod == "site"
	resp["region_filter"] = as.regionFilter
	writeJSON(w, http.StatusOK, resp)
}

// LatestScan GET /v1/scans/laser/latest?unit_a_ip=...&unit_b_ip=...。
// 返回该工位最近一次已完成扫描（done），供网页刷新后默认展示上次结果；无则 {found:false}。
// 不依赖客户端本地记忆，刷新即可还原（含新代码上线前的历史扫描）。
func (h *Handler) LatestScan(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	ipA, ipB, ok := activeStationIPs(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip/unit_b_ip 必须是 IPv4")
		return
	}
	job, err := h.repo.FindLatestDone(r.Context(), ipA, ipB)
	if err != nil || job == nil {
		writeJSON(w, http.StatusOK, map[string]any{"found": false})
		return
	}
	if !ownsOrAdmin(r, job, uid) {
		writeJSON(w, http.StatusOK, map[string]any{"found": false})
		return
	}
	resp := jobView(job)
	resp["found"] = true
	writeJSON(w, http.StatusOK, resp)
}

// DownloadCloud GET /v1/scans/laser/{id}/cloud/{name}（name ∈ fused|unit_a|unit_b）。
// 流式回传 PCD。object key 从 job 白名单字段取（非客户端传入），杜绝路径穿越/越权取任意对象。
func (h *Handler) DownloadCloud(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if h.reader == nil {
		writeErr(w, http.StatusNotImplemented, "未配置点云存储读取器")
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "无效 scan id")
		return
	}
	job, err := h.repo.FindByID(r.Context(), id)
	if err != nil {
		writeErr(w, http.StatusNotFound, "扫描不存在")
		return
	}
	if !ownsOrAdmin(r, job, uid) {
		writeErr(w, http.StatusForbidden, "无权下载该扫描")
		return
	}
	var key *string
	switch r.PathValue("name") {
	case "fused":
		key = job.FusedObjectKey
	case "unit_a":
		key = job.UnitAObjectKey
	case "unit_b":
		key = job.UnitBObjectKey
	default:
		writeErr(w, http.StatusBadRequest, "name 须为 fused|unit_a|unit_b")
		return
	}
	if key == nil || *key == "" {
		if r.PathValue("name") == "fused" && jAlignMethod(job) == "raw" {
			writeErr(w, http.StatusNotFound, "未标定无法融合")
			return
		}
		writeErr(w, http.StatusNotFound, "该点云尚未就绪（扫描未完成？）")
		return
	}
	rc, size, gerr := h.reader.GetObject(r.Context(), *key)
	if gerr != nil {
		writeErr(w, http.StatusBadGateway, "取点云失败: "+gerr.Error())
		return
	}
	defer rc.Close()
	w.Header().Set("Content-Type", "application/octet-stream")
	if size > 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	}
	w.Header().Set("Content-Disposition", `attachment; filename="`+r.PathValue("name")+`.pcd"`)
	_, _ = io.Copy(w, rc)
}

// DownloadActiveCloud GET /v1/scans/laser/active/cloud/{unit_a|unit_b}?unit_a_ip=...&unit_b_ip=...。
// 返回当前活动扫描的分镜实时缓存快照；未完成前也能被网页刷新后直接加载。
func (h *Handler) DownloadActiveCloud(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	ipA, ipB, ok := activeStationIPs(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip/unit_b_ip 必须是 IPv4")
		return
	}
	as := h.sessions.find(ipA, ipB)
	if as == nil {
		writeErr(w, http.StatusNotFound, "当前工位没有正在扫描的点云")
		return
	}
	job, err := h.repo.FindByID(r.Context(), as.jobID)
	if err != nil {
		writeErr(w, http.StatusNotFound, "扫描不存在")
		return
	}
	if !ownsOrAdmin(r, job, uid) {
		writeErr(w, http.StatusForbidden, "无权下载该扫描")
		return
	}
	var unit int
	switch r.PathValue("name") {
	case "unit_a":
		unit = 0
	case "unit_b":
		unit = 1
	default:
		writeErr(w, http.StatusBadRequest, "name 须为 unit_a|unit_b")
		return
	}
	pcd, err := EncodePCDBinary(as.cache.snapshot(unit))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "编码实时点云失败: "+err.Error())
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.Itoa(len(pcd)))
	w.Header().Set("Content-Disposition", `attachment; filename="live_`+r.PathValue("name")+`.pcd"`)
	_, _ = w.Write(pcd)
}

// --- 设备控制面板端点（直接打单元 :4000，不经扫描任务）---

// DeviceStatus GET /v1/scans/laser/device-status?unit=a|b。实时状态（状态机/角度/温度/错误位/在线）。
func (h *Handler) DeviceStatus(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	dev, ip, ok := h.resolveUnit(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit 须为 a|b")
		return
	}
	st, err := dev.GetStatus(r.Context())
	if err != nil {
		writeErr(w, http.StatusBadGateway, "查状态失败("+ip+"): "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ip":             ip,
		"online":         st.Online(),
		"state":          st.State,
		"scan_msg":       st.ScanMsg,
		"uptime":         st.Uptime,
		"encoder_online": st.EncoderOnline,
		"lidar_online":   st.LidarOnline,
		"camera_online":  st.CameraOnline,
		"control_online": st.ControlOnline,
		"latest_angle":   st.LatestAngle,
		"zero_degs":      st.ZeroDegs,
		"angle_degs":     st.AngleDegs,
		"error_code":     st.ErrorCode,
		"tempre":         st.Tempre,
	})
}

// DeviceInfo GET /v1/scans/laser/device-info?unit=a|b。型号/SN/固件/规格 + 当前扫描设置 + 当前标定。
func (h *Handler) DeviceInfo(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	dev, ip, ok := h.resolveUnit(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit 须为 a|b")
		return
	}
	info, err := dev.GetInfo(r.Context())
	if err != nil {
		writeErr(w, http.StatusBadGateway, "查设备信息失败("+ip+"): "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, info)
}

// DeviceCommand POST /v1/scans/laser/device-command?unit=a|b  body {"cmd":"ALIGN_ZERO"}。
// 直接设备控制（零位校准/守望/停止/清错/软复位）。SCAN_START 不在此（走扫描任务流 POST /v1/scans/laser）。
func (h *Handler) DeviceCommand(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	dev, ip, ok := h.resolveUnit(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit 须为 a|b")
		return
	}
	var body struct {
		Cmd string `json:"cmd"`
	}
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&body)
	}
	cmd := ScanCmd(body.Cmd)
	switch cmd {
	case ScanStop, ScanWatch, AlignZero, ClearError, SoftReboot:
		// 允许
	default:
		writeErr(w, http.StatusBadRequest, "cmd 须为 SCAN_STOP|SCAN_WATCH|ALIGN_ZERO|CLEAR_ERROR|SOFT_REBOOT")
		return
	}
	if err := dev.ControlScan(r.Context(), cmd); err != nil {
		writeErr(w, http.StatusBadGateway, "命令失败("+ip+"): "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "unit_ip": ip, "cmd": string(cmd)})
}

// DeviceScanSettings POST /v1/scans/laser/device-scan-settings?unit=a|b  body ControlSettings。
func (h *Handler) DeviceScanSettings(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	dev, ip, ok := h.resolveUnit(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit 须为 a|b")
		return
	}
	var s ControlSettings
	if err := json.NewDecoder(r.Body).Decode(&s); err != nil {
		writeErr(w, http.StatusBadRequest, "解析扫描设置失败: "+err.Error())
		return
	}
	if s.ScanAngle != nil {
		stop, err := scanStopFromAngle(s.ScanStartAngle, *s.ScanAngle)
		if err != nil {
			writeErr(w, http.StatusBadRequest, err.Error())
			return
		}
		s.ScanStopAngle = stop
	}
	if err := validateScanAngles(s.ScanStartAngle, s.ScanStopAngle); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	if s.ScanAngle == nil {
		scanAngle := s.ScanStopAngle - s.ScanStartAngle
		s.ScanAngle = &scanAngle
	}
	if err := dev.UpdateControl(r.Context(), s); err != nil {
		writeErr(w, http.StatusBadGateway, "下发扫描设置失败("+ip+"): "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "unit_ip": ip})
}

func validateScanAngles(start, stop float64) error {
	if math.IsNaN(start) || math.IsNaN(stop) || math.IsInf(start, 0) || math.IsInf(stop, 0) {
		return errors.New("扫描角无效：起止角必须是有限数字")
	}
	if stop <= start {
		return errors.New("扫描角无效：当前固件只支持沿设备正向扫描；结束位置必须大于初始位置，负扫描角会被设备跨 +180° 扫成超大角度")
	}
	if !scanAxisAngleInRange(start) || !scanAxisAngleInRange(stop) {
		return errors.New("扫描角无效：起止角需在设备机械范围 -180°～180° 内，并避开 ±180° 边界")
	}
	span := linearScanSpanDeg(start, stop)
	if span < minSweepDeg {
		return errors.New("扫描角无效：有效扫程需 ≥10°")
	}
	if span >= 179.5 {
		return errors.New("扫描角无效：单段扫描角度必须小于 180°；请拆多段或调整初始位置")
	}
	return nil
}

func scanStopFromAngle(start, scanAngle float64) (float64, error) {
	if math.IsNaN(start) || math.IsNaN(scanAngle) || math.IsInf(start, 0) || math.IsInf(scanAngle, 0) {
		return 0, errors.New("扫描角无效：初始位置和扫描角度必须是有限数字")
	}
	if scanAngle <= 0 {
		return 0, errors.New("扫描角无效：当前固件只支持沿设备正向扫描；负扫描角会被设备跨 +180° 扫成超大角度，请调换初始位置后使用正扫描角")
	}
	stop := start + scanAngle
	if err := validateScanAngles(start, stop); err != nil {
		return 0, err
	}
	return stop, nil
}

func linearScanSpanDeg(start, stop float64) float64 { return stop - start }

func scanAxisAngleInRange(a float64) bool { return a > -180.0 && a < 180.0 }

// DeviceCalib POST /v1/scans/laser/device-calib?unit=a|b  body CalibParams（破坏性：覆写设备存储标定）。
func (h *Handler) DeviceCalib(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	dev, ip, ok := h.resolveUnit(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit 须为 a|b")
		return
	}
	var p CalibParams
	if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
		writeErr(w, http.StatusBadRequest, "解析标定参数失败: "+err.Error())
		return
	}
	if err := dev.UpdateCalib(r.Context(), p); err != nil {
		writeErr(w, http.StatusBadGateway, "下发标定失败("+ip+"): "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "unit_ip": ip})
}

// --- 持久车位框端点（M9.11；服务端持久化，非设备写，无需设备审批）---

// bayKey 当前装机点标识 = 默认 unit_a_ip（固定 master 单元标识车位）。
func (h *Handler) bayKey() string { return h.cfg.DefaultUnitAIP }

// cropUnit 从 ?unit=a|b 解析车位框单元，缺省 a（向后兼容单框语义；a 框在世界系、b 框在 unitB 设备系）。
func cropUnit(r *http.Request) string {
	switch r.URL.Query().Get("unit") {
	case "b", "B", "102":
		return "b"
	default:
		return "a"
	}
}

// GetCropBox GET /v1/scans/laser/crop-box。返回当前车位框（未设置 → set=false）。
func (h *Handler) GetCropBox(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if h.cropBoxes == nil {
		writeErr(w, http.StatusNotImplemented, "未配置车位框存储")
		return
	}
	unit := cropUnit(r)
	box, ok, err := h.cropBoxes.GetCropBox(r.Context(), h.bayKey(), unit)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "取车位框失败: "+err.Error())
		return
	}
	resp := map[string]any{"bay_key": h.bayKey(), "unit": unit, "set": ok}
	if ok {
		resp["box"] = box
	}
	writeJSON(w, http.StatusOK, resp)
}

// PutCropBox PUT /v1/scans/laser/crop-box  body=CropBox。保存/覆盖当前车位框。
func (h *Handler) PutCropBox(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if h.cropBoxes == nil {
		writeErr(w, http.StatusNotImplemented, "未配置车位框存储")
		return
	}
	var box CropBox
	if err := json.NewDecoder(r.Body).Decode(&box); err != nil {
		writeErr(w, http.StatusBadRequest, "解析车位框失败: "+err.Error())
		return
	}
	if !box.Valid() {
		writeErr(w, http.StatusBadRequest, "车位框退化（半尺须为正、Up 非零）")
		return
	}
	unit := cropUnit(r)
	if err := h.cropBoxes.SaveCropBox(r.Context(), h.bayKey(), unit, box); err != nil {
		writeErr(w, http.StatusInternalServerError, "保存车位框失败: "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "bay_key": h.bayKey(), "unit": unit})
}

// GetBackground GET /v1/scans/laser/background。返回本工位是否已采集空工位背景（背景相减抠车的前提）。
// 仅做对象存在性探测（GetObject 的 Stat=HEAD，不下载 24MB 点云）。
func (h *Handler) GetBackground(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	resp := map[string]any{"bay_key": h.bayKey(), "set": false}
	if h.reader != nil {
		if rc, size, err := h.reader.GetObject(r.Context(), backgroundObjectKey(h.bayKey())); err == nil {
			_ = rc.Close()
			resp["set"] = true
			resp["bytes"] = size
		}
	}
	writeJSON(w, http.StatusOK, resp)
}

// CropPreview POST /v1/scans/laser/{id}/crop-preview  body=CropBox。
// 用候选框裁某次扫描的融合云并测量，回 {in_points,total_points,measurement}，供拖框实时预览（不落库）。
func (h *Handler) CropPreview(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if h.reader == nil {
		writeErr(w, http.StatusNotImplemented, "未配置点云存储读取器")
		return
	}
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "无效 scan id")
		return
	}
	job, err := h.repo.FindByID(r.Context(), id)
	if err != nil {
		writeErr(w, http.StatusNotFound, "扫描不存在")
		return
	}
	if !ownsOrAdmin(r, job, uid) {
		writeErr(w, http.StatusForbidden, "无权预览该扫描")
		return
	}
	// 预览云：?unit 缺省→融合（向后兼容/权威测量视图）；a→unitA 云；b→unitB 云。
	// 按镜头标注时各自对自己镜头的点云空间裁剪预览（A 框在世界系、B 框在 unitB 设备系）。
	var objKey *string
	switch r.URL.Query().Get("unit") {
	case "", "fused":
		objKey = job.FusedObjectKey
	case "a", "A", "101":
		objKey = job.UnitAObjectKey
	case "b", "B", "102":
		objKey = job.UnitBObjectKey
	default:
		writeErr(w, http.StatusBadRequest, "unit 须为 a|b")
		return
	}
	if objKey == nil || *objKey == "" {
		writeErr(w, http.StatusNotFound, "目标点云尚未就绪")
		return
	}
	var box CropBox
	if err := json.NewDecoder(r.Body).Decode(&box); err != nil {
		writeErr(w, http.StatusBadRequest, "解析车位框失败: "+err.Error())
		return
	}
	if !box.Valid() {
		writeErr(w, http.StatusBadRequest, "车位框退化（半尺须为正、Up 非零）")
		return
	}
	rc, _, gerr := h.reader.GetObject(r.Context(), *objKey)
	if gerr != nil {
		writeErr(w, http.StatusBadGateway, "取融合云失败: "+gerr.Error())
		return
	}
	defer rc.Close()
	raw, rerr := io.ReadAll(rc)
	if rerr != nil {
		writeErr(w, http.StatusBadGateway, "读融合云失败: "+rerr.Error())
		return
	}
	xyz, derr := DecodePCDBinary(raw)
	if derr != nil {
		writeErr(w, http.StatusInternalServerError, "解码融合云失败: "+derr.Error())
		return
	}
	dims := Measure(xyz, CropBoxMeasureParams(box))
	writeJSON(w, http.StatusOK, map[string]any{
		"total_points": len(xyz) / 3,
		"in_points":    len(CropToBox(xyz, box)) / 3,
		"measurement":  dims,
	})
}

// jobView 转端侧可见视图（object key 供 presign 下载；不外泄内部字段）。
func jobView(j *repo.LaserScanJob) map[string]any {
	v := map[string]any{
		"scan_id":     j.ID,
		"session_key": j.SessionKey,
		"status":      j.Status,
		"align":       j.Align,
	}
	if j.AlignMethod != nil {
		v["align_method"] = *j.AlignMethod
	}
	if j.Fused != nil {
		v["points"] = *j.Fused
	}
	if j.PtsA != nil {
		v["pts_a"] = *j.PtsA
	}
	if j.PtsB != nil {
		v["pts_b"] = *j.PtsB
	}
	if j.FusedObjectKey != nil {
		v["result_object_key"] = *j.FusedObjectKey
	}
	if j.UnitAObjectKey != nil {
		v["unit_a_object_key"] = *j.UnitAObjectKey
	}
	if j.UnitBObjectKey != nil {
		v["unit_b_object_key"] = *j.UnitBObjectKey
	}
	if j.ErrorMessage != nil {
		v["error"] = *j.ErrorMessage
	}
	if jAlignMethod(j) == "raw" {
		v["fusion_available"] = false
	} else if jAlignMethod(j) == "site" {
		v["fusion_available"] = true
	}
	flattenMeasureFromStats(j.Stats, v)
	return v
}

// flattenMeasureFromStats 把 job.Stats 里的 measure/axle/compliance 拍平进端侧视图，
// 对齐 FusionDoneEvent 的扁平字段名 → 刷新看历史扫描时测量面板与实时事件同款渲染。
func flattenMeasureFromStats(stats json.RawMessage, v map[string]any) {
	if len(stats) == 0 {
		return
	}
	var s struct {
		Measure     *Dimensions     `json:"measure"`
		Axle        *AxleResult     `json:"axle"`
		CargoBox    *CargoBox       `json:"cargo_box"`
		Overlay     *VehicleOverlay `json:"overlay"`
		Compliance  *Compliance     `json:"compliance"`
		MeasureMode string          `json:"measure_mode"`
		BgSet       bool            `json:"bg_set"`
		BgCaptured  bool            `json:"bg_captured"`
	}
	if err := json.Unmarshal(stats, &s); err != nil {
		return
	}
	// 抠车隔离方式（背景相减/裁剪框/无隔离），供端侧测量面板按情况给提示。
	if s.MeasureMode != "" {
		v["meas_mode"] = s.MeasureMode
	}
	v["background_set"] = s.BgSet
	if s.BgCaptured {
		v["background_captured"] = true
	}
	if s.Measure != nil && s.Measure.Valid {
		v["measure_valid"] = true
		v["length_mm"] = s.Measure.LengthMM
		v["width_mm"] = s.Measure.WidthMM
		v["height_mm"] = s.Measure.HeightMM
	}
	if s.Compliance != nil {
		v["compliant"] = s.Compliance.Compliant
		if len(s.Compliance.Violations) > 0 {
			v["violations"] = s.Compliance.Violations
		}
	}
	if s.Axle != nil && s.Axle.Valid {
		v["axle_valid"] = true
		v["num_axles"] = s.Axle.NumAxles
		v["wheelbases_mm"] = s.Axle.WheelbasesMM
		v["total_wheelbase_mm"] = s.Axle.TotalWheelbaseMM
		v["front_overhang_mm"] = s.Axle.FrontOverhangMM
		v["rear_overhang_mm"] = s.Axle.RearOverhangMM
	}
	if s.CargoBox != nil && s.CargoBox.Valid && s.CargoBox.HasBox {
		v["has_cargo_box"] = true
		v["box_outer_length_mm"] = s.CargoBox.OuterLengthMM
		v["box_outer_width_mm"] = s.CargoBox.OuterWidthMM
		v["box_depth_mm"] = s.CargoBox.DepthMM
		v["box_inner_width_mm"] = s.CargoBox.InnerWidthMM
	}
	if s.Overlay != nil && s.Overlay.Valid {
		v["overlay"] = s.Overlay // 世界系车体框/货箱框/轴线，网页 3D 叠加（与事件同结构）
	}
}

func jAlignMethod(j *repo.LaserScanJob) string {
	if j == nil || j.AlignMethod == nil {
		return ""
	}
	return *j.AlignMethod
}

// --- 活动会话注册表（单活 + 取消句柄）---

type activeSession struct {
	mu           sync.RWMutex
	jobID        int64
	sessionKey   string
	owner        int64
	unitAIP      string
	unitBIP      string
	alignMethod  string
	state        string
	framesA      int
	framesB      int
	regionFilter PointRegionFilter
	cache        *livePointCache
	cancel       func()
}

type sessionRegistry struct {
	mu       sync.Mutex
	reserved bool // 已预留（探活/建库阶段，尚未 set）
	active   map[int64]*activeSession
}

// tryReserve 预留单活名额；已被占用返回 false。
func (s *sessionRegistry) tryReserve() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.reserved || len(s.active) > 0 {
		return false
	}
	s.reserved = true
	return true
}

func (s *sessionRegistry) release() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.reserved = false
}

func (s *sessionRegistry) set(id int64, as *activeSession) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.active[id] = as
}

func (s *sessionRegistry) get(id int64) *activeSession {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.active[id]
}

func (s *sessionRegistry) clear(id int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.active, id)
}

// --- helpers ---

func callerUserID(r *http.Request) int64 {
	v := r.Header.Get("X-Gomob-User-Id")
	if v == "" {
		return 0
	}
	id, _ := strconv.ParseInt(v, 10, 64)
	return id
}

func ownsOrAdmin(r *http.Request, j *repo.LaserScanJob, uid int64) bool {
	if j.OwnerUserID != nil && *j.OwnerUserID == uid {
		return true
	}
	return r.Header.Get("X-Gomob-Roles") == "admin"
}

func activeStationIPs(r *http.Request) (string, string, bool) {
	ipA, okA := normalizeOptionalIPv4(r.URL.Query().Get("unit_a_ip"))
	ipB, okB := normalizeOptionalIPv4(r.URL.Query().Get("unit_b_ip"))
	return ipA, ipB, okA && okB
}

func normalizeOptionalIPv4(raw string) (string, bool) {
	if raw == "" {
		return "", true
	}
	addr, err := netip.ParseAddr(raw)
	if err != nil || !addr.Is4() {
		return "", false
	}
	return addr.String(), true
}

func newSessionKey() (string, error) {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return "laser-" + hex.EncodeToString(b), nil
}

func orStr(v, def string) string {
	if v == "" {
		return def
	}
	return v
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}
