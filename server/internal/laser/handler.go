package laser

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
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
	Cancel(ctx context.Context, id int64) (*repo.LaserScanJob, error)
	JobStore
}

// Config = laserworker 配置。
type Config struct {
	DefaultUnitAIP string        // 默认 192.168.9.101
	DefaultUnitBIP string        // 默认 192.168.9.102
	DefaultAlign   string        // 默认 icp
	DefaultKeep    float32       // 默认 1.0
	ProbeTimeout   time.Duration // 探活超时，默认 3s

	// 起扫前给两单元各自下发扫描起止角（per-unit；两单元几何/可扫范围不同，不能强加同一对称值
	// —— 实测 -180/180 会让 A 起=止塌成 0° 扫程不转）。默认用 job 9 验证可扫的设备原值
	// A:0→90 / B:-180→20。SetScanAngles=false 时跳过、沿用设备持久化值。
	SetScanAngles bool    // 默认 true（main.go 经 env 注入）
	ScanAStart    float64 // unit A 起始角，默认 0
	ScanAStop     float64 // unit A 停止角，默认 90
	ScanBStart    float64 // unit B 起始角，默认 -180
	ScanBStop     float64 // unit B 停止角，默认 20
}

func (c Config) withDefaults() Config {
	if c.DefaultUnitAIP == "" {
		c.DefaultUnitAIP = "192.168.9.101"
	}
	if c.DefaultUnitBIP == "" {
		c.DefaultUnitBIP = "192.168.9.102"
	}
	if c.DefaultAlign == "" {
		c.DefaultAlign = "icp"
	}
	if c.DefaultKeep <= 0 || c.DefaultKeep > 1 {
		c.DefaultKeep = 1.0
	}
	if c.ProbeTimeout <= 0 {
		c.ProbeTimeout = 3 * time.Second
	}
	// 角度缺省给 job 9 验证可扫的设备原值；SetScanAngles 由 main.go 显式注入（默认 true），测试默认 false。
	if c.ScanAStart == 0 && c.ScanAStop == 0 && c.ScanBStart == 0 && c.ScanBStop == 0 {
		c.ScanAStart, c.ScanAStop = 0, 90
		c.ScanBStart, c.ScanBStop = -180, 20
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
	launch  func(func()) // 后台执行扫描；默认 go f()，测试可改同步
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
		newGate:  func(a, b string) DeviceGate { return NewDevctlGate(a, b, cfg.ScanAStart, cfg.ScanAStop, cfg.ScanBStart, cfg.ScanBStop, cfg.SetScanAngles, log) },
		launch:   func(f func()) { go f() },
		newDev:   func(ip string) DeviceAPI { return NewDeviceClient(ip, cfg.ProbeTimeout) },
	}
}

// Mount 注册路由。
func (h *Handler) Mount(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/scans/laser", h.StartScan)
	mux.HandleFunc("POST /v1/scans/laser/{id}/stop", h.StopScan)
	mux.HandleFunc("GET /v1/scans/laser/{id}", h.GetScan)
	// PCD 下载（融合 414万点不走 ws，经此流式取；name 白名单从 job 取 object key，零路径穿越）。
	mux.HandleFunc("GET /v1/scans/laser/{id}/cloud/{name}", h.DownloadCloud)

	// 持久车位框（M9.11）。crop-box 是 literal 段，比 {id} 更具体不歧义；crop-preview 是 {id} 子资源。
	mux.HandleFunc("GET /v1/scans/laser/crop-box", h.GetCropBox)
	mux.HandleFunc("PUT /v1/scans/laser/crop-box", h.PutCropBox)
	mux.HandleFunc("POST /v1/scans/laser/{id}/crop-preview", h.CropPreview)

	// 设备控制面板（原厂功能键）。用 literal 子资源 + ?unit=a|b 查询参，避开与 {id}/cloud/{name}
	// 通配的路由歧义（literal 段比 {id} 更具体，不 panic）。
	mux.HandleFunc("GET /v1/scans/laser/device-status", h.DeviceStatus)             // 状态信息
	mux.HandleFunc("GET /v1/scans/laser/device-info", h.DeviceInfo)                 // 设备信息+当前设置/标定
	mux.HandleFunc("POST /v1/scans/laser/device-command", h.DeviceCommand)         // 零位校准/守望/停止/清错/软复位
	mux.HandleFunc("POST /v1/scans/laser/device-scan-settings", h.DeviceScanSettings) // 扫描设置
	mux.HandleFunc("POST /v1/scans/laser/device-calib", h.DeviceCalib)             // 标定参数（破坏性）
}

// resolveUnit 从 ?unit=a|b（兼容 101|102）解析出该单元的设备客户端 + IP。
func (h *Handler) resolveUnit(r *http.Request) (DeviceAPI, string, bool) {
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

// --- 请求/响应体 ---

type startReq struct {
	InspectionID  *int64   `json:"inspection_id"`
	UnitAIP       string   `json:"unit_a_ip"`
	UnitBIP       string   `json:"unit_b_ip"`
	Align         string   `json:"align"`
	KeepRatio     *float32 `json:"keep_ratio"`
	VehicleTypeID *int     `json:"vehicle_type_id"` // 逆向 JCHY 车型编号（docs/16 §4.1）；缺省=未选
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
	align := orStr(req.Align, h.cfg.DefaultAlign)
	if align != "icp" && align != "none" && align != "site" {
		writeErr(w, http.StatusBadRequest, "align 须为 icp|none|site")
		return
	}
	keep := h.cfg.DefaultKeep
	if req.KeepRatio != nil {
		keep = *req.KeepRatio
	}
	if keep <= 0 || keep > 1 {
		writeErr(w, http.StatusBadRequest, "keep_ratio 须在 (0,1]")
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

	sessionKey, err := newSessionKey()
	if err != nil {
		release()
		writeErr(w, http.StatusInternalServerError, "生成 session 失败")
		return
	}
	owner := uid
	job, err := h.repo.Create(context.WithoutCancel(ctx), sessionKey, ipA, ipB, align, keep, req.InspectionID, &owner)
	if err != nil {
		release()
		writeErr(w, http.StatusInternalServerError, "建扫描任务失败: "+err.Error())
		return
	}

	// 配置 runner 的设备门控（live SCAN_START/STOP）。
	h.runner.Gate = h.newGate(ipA, ipB)
	sink := NewNATSSink(h.pub, sessionKey, &owner, h.log)
	vehicleTypeID := -1 // 未选
	if req.VehicleTypeID != nil {
		vehicleTypeID = *req.VehicleTypeID
	}
	spec := RunSpec{
		JobID:         job.ID,
		SessionKey:    sessionKey,
		InspectionID:  req.InspectionID,
		OwnerUserID:   &owner,
		UnitAIP:       ipA,
		UnitBIP:       ipB,
		Align:         align,
		KeepRatio:     keep,
		VehicleTypeID: vehicleTypeID,
	}
	// 注册活动会话（cancel = CancelScan 协作取消 cgo 采集；设备 SCAN_STOP 由 runner 的 defer Gate.Stop 兜底）。
	h.sessions.set(job.ID, &activeSession{jobID: job.ID, sessionKey: sessionKey, owner: owner, cancel: CancelScan})

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
	if err := dev.UpdateControl(r.Context(), s); err != nil {
		writeErr(w, http.StatusBadGateway, "下发扫描设置失败("+ip+"): "+err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "unit_ip": ip})
}

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
	case "":
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
	return v
}

// --- 活动会话注册表（单活 + 取消句柄）---

type activeSession struct {
	jobID      int64
	sessionKey string
	owner      int64
	cancel     func()
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
