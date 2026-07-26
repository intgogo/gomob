package laser

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/rbac"
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
	FindLatestMeasurement(ctx context.Context, unitAIP, unitBIP string, ownerUserID *int64) (*repo.LaserScanJob, error)
	Cancel(ctx context.Context, id int64) (*repo.LaserScanJob, error)
	JobStore
}

// SiteCalibrationStore 是双单元工位外参的服务端真理源。
type SiteCalibrationStore interface {
	Get(ctx context.Context, unitAIP, unitBIP string) (*repo.LaserSiteCalibration, error)
	Upsert(ctx context.Context, cal repo.LaserSiteCalibration) error
}

// RegionCalibrationStore 是双单元工位区域墙的服务端真理源。
type RegionCalibrationStore interface {
	Get(ctx context.Context, unitAIP, unitBIP string) (*repo.LaserRegionCalibration, error)
	Upsert(ctx context.Context, cal repo.LaserRegionCalibration) error
	Delete(ctx context.Context, unitAIP, unitBIP string) error
}

// BackgroundRevisionStore 是不可变空工位背景版本存储。
type BackgroundRevisionStore interface {
	GetActive(ctx context.Context, unitAIP, unitBIP string) (*repo.LaserBackgroundRevision, error)
	Activate(ctx context.Context, rev repo.LaserBackgroundRevision) (*repo.LaserBackgroundRevision, error)
}

// InspectionStore 校验扫描与平台查验单的归属、工位和状态。
type InspectionStore interface {
	FindByID(ctx context.Context, id int64) (*repo.Inspection, error)
}

// Config = laserworker 配置。
type Config struct {
	StationID              int64         // 本 laserworker 物理工位；inspection 绑定时必须配置
	DefaultUnitAIP         string        // 默认 192.168.9.101
	DefaultUnitBIP         string        // 默认 192.168.9.102
	DefaultAlign           string        // 默认 site；未显式携带外参时从服务端工位配置解析
	DefaultKeep            float32       // 默认 1.0
	ProbeTimeout           time.Duration // 探活超时，默认 3s
	UnverifiedSiteRevision string        // 临时联调豁免：仅与 canonical site SHA256 完全相等时生效

	// 起扫前给两单元各自下发扫描起止角。默认 false：沿用设备持久化值，让控制面板设置真正生效。
	// 只有运维显式设 GOMOB_LASER_SET_SCAN_ANGLES=true 时才覆盖。
	SetScanAngles bool    // 默认 false（main.go 经 env 注入）
	ScanAStart    float64 // unit A 起始角，默认 0
	ScanAStop     float64 // unit A 停止角，默认 90
	ScanBStart    float64 // unit B 起始角，默认 -170
	ScanBStop     float64 // unit B 停止角，默认 -10

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

	reader      CloudReader             // PCD 下载（可空 → 下载端点 501）
	cropBoxes   CropBoxStore            // 持久车位框存储（可空 → crop-box 端点 501）
	siteCalib   SiteCalibrationStore    // 双单元工位外参（可空 → site 起扫拒绝）
	regionCalib RegionCalibrationStore  // 双单元工位区域墙（可空 → 起扫拒绝）
	backgrounds BackgroundRevisionStore // 不可变空工位背景（可空 → 起扫拒绝）
	inspections InspectionStore         // 平台查验单权限/工位绑定（带 inspection_id 时必需）
	audit       audit.Recorder          // 标定级操作审计（可空 → 只 log 不落审计表）

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

// SetSiteCalibrationStore 注入服务端工位外参存储。
func (h *Handler) SetSiteCalibrationStore(s SiteCalibrationStore) { h.siteCalib = s }

// SetRegionCalibrationStore 注入服务端工位区域墙存储。
func (h *Handler) SetRegionCalibrationStore(s RegionCalibrationStore) { h.regionCalib = s }

// SetBackgroundRevisionStore 注入不可变背景版本存储。
func (h *Handler) SetBackgroundRevisionStore(s BackgroundRevisionStore) { h.backgrounds = s }

// SetInspectionStore 注入平台查验单存储。
func (h *Handler) SetInspectionStore(s InspectionStore) { h.inspections = s }

// SetAuditRecorder 注入审计记录器（标定级操作审计）。可空。
func (h *Handler) SetAuditRecorder(rec audit.Recorder) { h.audit = rec }

// isAdmin 判定调用方是否 admin（标定级/破坏性操作要求）。
func isAdmin(r *http.Request) bool {
	return r.Header.Get("X-Gomob-Roles") == rbac.RoleAdmin
}

// recordAudit 记一条审计（recorder 为空则只 debug log，不致命）。
func (h *Handler) recordAudit(r *http.Request, action, target string, after map[string]any) {
	if h.audit == nil {
		h.log.Info("标定级操作（未配置审计表，仅日志）", "action", action, "target", target, "user", callerUserID(r))
		return
	}
	afterRaw, _ := audit.Encode(after)
	ac, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	go func() {
		defer cancel()
		_ = h.audit.Record(ac, audit.Entry{
			UserID:   callerUserID(r),
			Action:   action,
			Target:   target,
			AfterRaw: afterRaw,
		})
	}()
}

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
		sessions: &sessionRegistry{active: map[int64]*activeSession{}, framingKeys: map[string]struct{}{}},
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
	mux.HandleFunc("DELETE /v1/scans/laser/crop-box", h.DeleteCropBox)
	mux.HandleFunc("POST /v1/scans/laser/{id}/crop-preview", h.CropPreview)

	// 空工位背景（路 B 背景相减）：查本工位是否已采集背景。采集用 POST /v1/scans/laser?mark_as_background=...
	// （即普通扫描 + 标记），重采直接覆盖同 key，故无需 DELETE。
	mux.HandleFunc("GET /v1/scans/laser/background", h.GetBackground)

	// 设备控制面板（原厂功能键）。用 literal 子资源 + ?unit=a|b 查询参，避开与 {id}/cloud/{name}
	// 通配的路由歧义（literal 段比 {id} 更具体，不 panic）。
	mux.HandleFunc("GET /v1/scans/laser/device-status", h.DeviceStatus)                    // 状态信息
	mux.HandleFunc("GET /v1/scans/laser/device-info", h.DeviceInfo)                        // 设备信息+当前设置/标定
	mux.HandleFunc("POST /v1/scans/laser/device-command", h.DeviceCommand)                 // 零位校准/守望/停止/清错/软复位
	mux.HandleFunc("POST /v1/scans/laser/device-scan-settings", h.DeviceScanSettings)      // 扫描设置
	mux.HandleFunc("POST /v1/scans/laser/site-calib", h.SiteCalib)                         // 一键自动标定 A↔B(ArUco 标记场)
	mux.HandleFunc("POST /v1/scans/laser/site-framing", h.SiteFraming)                     // 实时取景标定（边扫边推 RGB 帧+检测）
	mux.HandleFunc("DELETE /v1/scans/laser/site-framing", h.StopSiteFraming)               // 取消指定 session_key 的取景标定
	mux.HandleFunc("POST /v1/scans/laser/site-framing/manual", h.SiteFramingManual)        // 实时取景手动 RGB 点对（欠约束先拒绝）
	mux.HandleFunc("GET /v1/scans/laser/site-calibration", h.GetSiteCalibration)           // 服务端权威工位外参
	mux.HandleFunc("PUT /v1/scans/laser/site-calibration", h.PutSiteCalibration)           // 幂等确认服务端已解算外参
	mux.HandleFunc("GET /v1/scans/laser/region-calibration", h.GetRegionCalibration)       // 服务端权威区域墙
	mux.HandleFunc("PUT /v1/scans/laser/region-calibration", h.PutRegionCalibration)       // 保存/覆盖区域墙
	mux.HandleFunc("DELETE /v1/scans/laser/region-calibration", h.DeleteRegionCalibration) // 删除区域墙
	mux.HandleFunc("POST /v1/scans/laser/device-calib", h.DeviceCalib)                     // 标定参数（破坏性）
}

// resolveUnit 从 ?ip= 或 ?unit=a|b（兼容 101|102）解析出设备客户端 + IP。
// Web 工位管理台可管理多个相机，故允许显式 ip；unit 查询保持 App 兼容。
func (h *Handler) resolveUnit(r *http.Request) (DeviceAPI, string, bool) {
	ip, ok := h.resolveUnitIP(r)
	if !ok {
		return nil, "", false
	}
	return h.newDev(ip), ip, true
}

func (h *Handler) resolveUnitIP(r *http.Request) (string, bool) {
	if rawIP := r.URL.Query().Get("ip"); rawIP != "" {
		addr, err := netip.ParseAddr(rawIP)
		if err != nil || !addr.Is4() {
			return "", false
		}
		return addr.String(), true
	}
	var ip string
	switch r.URL.Query().Get("unit") {
	case "a", "A", "101":
		ip = h.cfg.DefaultUnitAIP
	case "b", "B", "102":
		ip = h.cfg.DefaultUnitBIP
	default:
		return "", false
	}
	return ip, true
}

// resolveManagedMutationUnit 只允许写操作命中本进程配置的受管 A/B 设备。
// 读端继续使用 resolveUnit，便于管理台查看其他设备，但不得借 ?ip= 把服务变成内网写代理。
func (h *Handler) resolveManagedMutationUnit(r *http.Request) (DeviceAPI, string, int, string) {
	ip, ok := h.resolveUnitIP(r)
	if !ok {
		return nil, "", http.StatusBadRequest, "unit 须为 a|b，ip 须为合法 IPv4"
	}
	if ip != h.cfg.DefaultUnitAIP && ip != h.cfg.DefaultUnitBIP {
		return nil, ip, http.StatusForbidden, "设备写操作仅允许配置中的受管 A/B 单元"
	}
	return h.newDev(ip), ip, 0, ""
}

func (h *Handler) managedStationUnitIPs(rawA, rawB string) (string, string, int, string) {
	ipA, ipB, ok := h.stationUnitIPs(rawA, rawB)
	if !ok {
		return "", "", http.StatusBadRequest, "unit_a_ip/unit_b_ip 必须是不同的合法 IPv4 地址"
	}
	if ipA != h.cfg.DefaultUnitAIP || ipB != h.cfg.DefaultUnitBIP {
		return ipA, ipB, http.StatusForbidden, "工位设备写操作仅允许配置中的受管 A/B 单元"
	}
	return ipA, ipB, 0, ""
}

func (h *Handler) expectedSweepDeg(ctx context.Context, ip, tag string) (float32, error) {
	_, span, err := h.acquisitionProfile(ctx, ip, tag, h.cfg.DefaultKeep)
	return span, err
}

// acquisitionProfile 固化起扫实际使用的设备身份、标定与扫描设置，供背景 revision 做严格兼容校验。
func (h *Handler) acquisitionProfile(ctx context.Context, ip, tag string, keepRatio float32) (UnitAcquisitionProfile, float32, error) {
	info, err := h.newDev(ip).GetInfo(ctx)
	if err != nil {
		return UnitAcquisitionProfile{}, 0, errors.New("读取 unit" + tag + "(" + ip + ") 设备信息失败: " + err.Error())
	}
	var start, stop float64
	if h.cfg.SetScanAngles {
		if tag == "A" {
			start, stop = h.cfg.ScanAStart, h.cfg.ScanAStop
		} else {
			start, stop = h.cfg.ScanBStart, h.cfg.ScanBStop
		}
	} else {
		start, stop = info.Control.ScanStartAngle, info.Control.ScanStopAngle
	}
	if err := validateScanAngles(start, stop); err != nil {
		return UnitAcquisitionProfile{}, 0, errors.New("unit" + tag + "(" + ip + ") 扫描设置无效 " +
			strconv.FormatFloat(start, 'f', 1, 64) + "°→" +
			strconv.FormatFloat(stop, 'f', 1, 64) + "°: " + err.Error())
	}
	return newUnitAcquisitionProfile(ip, *info, start, stop, keepRatio, h.runner.FlipVertical), float32(linearScanSpanDeg(start, stop)), nil
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
	MarkAsBackground bool               `json:"mark_as_background"` // true=保存区域裁剪后的 A/B 单元空工位背景，不测量
}

type startResp struct {
	ScanID             int64   `json:"scan_id"`
	SessionKey         string  `json:"session_key"`
	Status             string  `json:"status"`
	EffectiveKeepRatio float32 `json:"effective_keep_ratio"`
}

type siteCalibrationPutReq struct {
	UnitAIP       string          `json:"unit_a_ip"`
	UnitBIP       string          `json:"unit_b_ip"`
	SiteJSON      json.RawMessage `json:"site_json"`
	Source        string          `json:"source"`
	MeanErrorMM   *float64        `json:"mean_error_mm"`
	MaxErrorMM    *float64        `json:"max_error_mm"`
	RMSErrorMM    *float64        `json:"rms_error_mm"`
	CommonMarkers *int            `json:"common_markers"`
}

type regionCalibrationPutReq struct {
	UnitAIP      string       `json:"unit_a_ip"`
	UnitBIP      string       `json:"unit_b_ip"`
	Enabled      bool         `json:"enabled"`
	Points       [][3]float32 `json:"points"`
	Source       string       `json:"source"`
	SourceScanID *int64       `json:"source_scan_id"`
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
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil && !errors.Is(err, io.EOF) {
			writeErr(w, http.StatusBadRequest, "请求 JSON 无效")
			return
		}
	}
	if req.InspectionID != nil {
		if status, message := h.validateInspectionForScan(r, *req.InspectionID, uid); status != 0 {
			writeErr(w, status, message)
			return
		}
	}
	// MarkAsBackground 会把本次区域裁剪后的 A/B 单元云保存为「空工位背景」基准（标定级操作，
	// 影响之后所有背景相减抠车）。限 admin，且审计；普通 inspector 不得覆盖基准。
	if req.MarkAsBackground && !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "采集/覆盖空工位背景基准需 admin 角色")
		return
	}
	ipA, ipB, status, message := h.managedStationUnitIPs(req.UnitAIP, req.UnitBIP)
	if status != 0 {
		writeErr(w, status, message)
		return
	}
	requestSiteJSON := strings.TrimSpace(req.SiteJSON)
	align := strings.TrimSpace(req.Align)
	siteSnapshot := SiteCalibrationSnapshot{}
	var err error
	if align == "" {
		align = h.cfg.DefaultAlign
	}
	// 兼容旧客户端：携带 site_json 即表达 site 扫描，但几何只能用于一致性校验，不能覆盖服务端配置。
	if requestSiteJSON != "" {
		align = "site"
	}
	if align != "site" && align != "raw" {
		writeErr(w, http.StatusBadRequest, "激光多镜头扫描只支持外参融合或未标定原始采集")
		return
	}
	if align == "raw" && !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "未标定原始采集只允许 admin 用于工位诊断")
		return
	}
	if req.MarkAsBackground && align != "site" {
		writeErr(w, http.StatusConflict, "空工位背景必须绑定正式工位外参，不能以未标定 raw 模式采集")
		return
	}
	siteJSON := ""
	if align == "site" {
		if h.siteCalib == nil {
			writeErr(w, http.StatusServiceUnavailable, "工位外参存储未配置")
			return
		}
		cal, err := h.siteCalib.Get(r.Context(), ipA, ipB)
		if errors.Is(err, repo.ErrNotFound) {
			writeErr(w, http.StatusConflict, "当前双单元工位尚未保存外参，请先在 3D 工位管理台完成标定")
			return
		}
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "读取工位外参失败: "+err.Error())
			return
		}
		siteJSON = string(cal.SiteJSON)
		if err := validateSiteExtrinsicJSON(siteJSON); err != nil {
			writeErr(w, http.StatusInternalServerError, "服务端工位外参损坏: "+err.Error())
			return
		}
		siteRevision, err := canonicalSiteSHA256(siteJSON)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "服务端工位外参损坏: "+err.Error())
			return
		}
		quality, qualityErr := evaluateSiteCalibrationQuality(
			cal.RMSErrorMM,
			cal.CommonMarkers,
			siteRevision,
			h.cfg.UnverifiedSiteRevision,
		)
		if qualityErr != nil {
			writeErr(w, http.StatusConflict, "工位外参质量未达生产要求: "+qualityErr.Error()+"；请重新执行 ArUco 工位标定")
			return
		}
		if !quality.ScanEligible {
			writeErr(w, http.StatusConflict, "工位外参质量未达生产要求: 缺少 rms_error_mm/common_markers 质量证据；请重新执行 ArUco 工位标定")
			return
		}
		if requestSiteJSON != "" {
			if err := validateSiteExtrinsicJSON(requestSiteJSON); err != nil {
				writeErr(w, http.StatusBadRequest, err.Error())
				return
			}
			if !sameSiteJSON(requestSiteJSON, siteJSON) {
				writeErr(w, http.StatusConflict, "客户端工位外参与服务端权威版本不一致，请刷新工位配置后重试")
				return
			}
		}
		updatedAt := cal.UpdatedAt
		qualityVerified := quality.Verified
		siteSnapshot = SiteCalibrationSnapshot{
			Source:          cal.Source,
			UpdatedAt:       &updatedAt,
			UpdatedBy:       cal.UpdatedBy,
			SourceScanID:    cal.SourceScanID,
			RMSErrorMM:      cal.RMSErrorMM,
			CommonMarkers:   cal.CommonMarkers,
			QualityVerified: &qualityVerified,
			QualityOverride: quality.OverrideReason,
			MatrixSHA256:    siteRevision,
		}
	}
	keep, err := h.resolveStationKeepRatio(req.KeepRatio)
	if err != nil {
		var conflict *stationConfigConflictError
		if errors.As(err, &conflict) {
			writeErr(w, http.StatusConflict, conflict.Error())
		} else {
			writeErr(w, http.StatusBadRequest, err.Error())
		}
		return
	}
	regionFilter, regionSnapshot, err := h.resolveRegionCalibration(r.Context(), ipA, ipB, align, siteJSON, req.RegionFilter)
	if err != nil {
		var clientErr *stationConfigConflictError
		if errors.As(err, &clientErr) {
			writeErr(w, http.StatusConflict, clientErr.Error())
		} else if errors.Is(err, errStationConfigStoreUnavailable) {
			writeErr(w, http.StatusServiceUnavailable, err.Error())
		} else {
			writeErr(w, http.StatusInternalServerError, err.Error())
		}
		return
	}
	if align == "site" && (!regionSnapshot.Set || !regionSnapshot.Enabled) {
		writeErr(w, http.StatusConflict, "当前双单元工位尚未保存并启用扫描区域，请先在 3D 工位管理台完成区域标定")
		return
	}

	// 单活约束（C-ABI 一次一会话）。
	if !h.sessions.tryReserve(reservationFormalScan) {
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
	for _, unit := range []struct {
		tag string
		ip  string
	}{{"A", ipA}, {"B", ipB}} {
		pr := h.probe.Probe(ctx, unit.ip)
		if !pr.Reachable || !pr.Online {
			release()
			writeErr(w, http.StatusBadGateway, "unit"+unit.tag+"("+unit.ip+") 不可达或子系统离线: "+pr.Err)
			return
		}
		if pr.State != StateReady {
			release()
			writeErr(w, http.StatusConflict, "unit"+unit.tag+"("+unit.ip+") 当前状态为 "+pr.State+"，仅 READY 允许启动正式扫描")
			return
		}
	}
	profileA, expectedA, err := h.acquisitionProfile(ctx, ipA, "A", keep)
	if err != nil {
		release()
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	profileB, expectedB, err := h.acquisitionProfile(ctx, ipB, "B", keep)
	if err != nil {
		release()
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	if h.backgrounds == nil {
		release()
		writeErr(w, http.StatusServiceUnavailable, "空工位背景版本存储未配置")
		return
	}
	var backgroundRevision *repo.LaserBackgroundRevision
	if !req.MarkAsBackground {
		backgroundRevision, err = h.backgrounds.GetActive(context.WithoutCancel(ctx), ipA, ipB)
		if err != nil && !errors.Is(err, repo.ErrNotFound) {
			release()
			writeErr(w, http.StatusInternalServerError, "读取空工位背景版本失败: "+err.Error())
			return
		}
		if errors.Is(err, repo.ErrNotFound) {
			backgroundRevision = nil
		}
		if align == "site" {
			compatible, reason := backgroundRevisionCompatibility(
				backgroundRevision,
				siteSnapshot.MatrixSHA256,
				regionSnapshot.PointsSHA256,
				profileA,
				profileB,
			)
			if !compatible {
				release()
				writeErr(w, http.StatusConflict,
					"background_incompatible: 空工位背景不可用于当前工位采集（"+reason+"），请保持工位为空并重新采集")
				return
			}
		}
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
	if siteSnapshot.qualityOverrideEnabled() {
		h.log.Warn("工位外参质量证据缺失，本次扫描使用临时联调豁免",
			"scan_id", job.ID, "unit_a_ip", ipA, "unit_b_ip", ipB,
			"site_revision", siteSnapshot.MatrixSHA256, "override", siteSnapshot.QualityOverride, "user", uid)
		h.recordAudit(r, "laser.site_quality_override_scan", "bay:"+ipA+"|"+ipB, map[string]any{
			"scan_id": job.ID, "session_key": sessionKey,
			"unit_a_ip": ipA, "unit_b_ip": ipB,
			"site_revision":    siteSnapshot.MatrixSHA256,
			"quality_override": siteSnapshot.QualityOverride,
		})
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
		JobID:              job.ID,
		SessionKey:         sessionKey,
		InspectionID:       req.InspectionID,
		OwnerUserID:        &owner,
		UnitAIP:            ipA,
		UnitBIP:            ipB,
		Align:              align,
		SiteJSON:           siteJSON,
		SiteCalibration:    siteSnapshot,
		RegionCalibration:  regionSnapshot,
		UnitAProfile:       profileA,
		UnitBProfile:       profileB,
		KeepRatio:          keep,
		VehicleTypeID:      vehicleTypeID,
		ExpectedSweepADeg:  expectedA,
		ExpectedSweepBDeg:  expectedB,
		RegionFilter:       regionFilter,
		MarkAsBackground:   req.MarkAsBackground,
		BackgroundRevision: backgroundRevision,
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

	if req.MarkAsBackground {
		h.recordAudit(r, "laser.mark_background", "bay:"+ipA, map[string]any{
			"scan_id":     job.ID,
			"session_key": sessionKey,
			"unit_a_ip":   ipA,
			"unit_b_ip":   ipB,
		})
	}

	writeJSON(w, http.StatusCreated, startResp{
		ScanID: job.ID, SessionKey: sessionKey, Status: repo.LaserScanStatusCapturing,
		EffectiveKeepRatio: keep,
	})
}

func (h *Handler) validateInspectionForScan(r *http.Request, inspectionID, callerID int64) (int, string) {
	if inspectionID <= 0 {
		return http.StatusBadRequest, "inspection_id 必须为正整数"
	}
	if h.inspections == nil {
		return http.StatusServiceUnavailable, "查验单存储未配置，不能绑定激光扫描"
	}
	if h.cfg.StationID <= 0 {
		return http.StatusServiceUnavailable, "laserworker 未配置物理 station_id，不能绑定查验单"
	}
	inspection, err := h.inspections.FindByID(r.Context(), inspectionID)
	if errors.Is(err, repo.ErrNotFound) {
		return http.StatusNotFound, "查验单不存在"
	}
	if err != nil {
		return http.StatusInternalServerError, "读取查验单失败: " + err.Error()
	}
	if inspection == nil {
		return http.StatusInternalServerError, "读取查验单失败"
	}
	if !isAdmin(r) && inspection.InspectorID != callerID {
		return http.StatusForbidden, "无权把扫描绑定到他人的查验单"
	}
	if inspection.StationID != h.cfg.StationID {
		return http.StatusConflict, "查验单不属于本激光工位"
	}
	switch inspection.Status {
	case "created", "scanning":
		return 0, ""
	default:
		return http.StatusConflict, "查验单当前状态不允许追加激光扫描: " + inspection.Status
	}
}

var errStationConfigStoreUnavailable = errors.New("工位配置存储未配置")

type stationConfigConflictError struct{ message string }

func (e *stationConfigConflictError) Error() string { return e.message }

// resolveStationKeepRatio 把软件降采样率收口为服务端工位采集配置。
// 旧客户端字段只允许与服务端值一致；客户端不得再用本地状态改变背景兼容域。
func (h *Handler) resolveStationKeepRatio(requested *float32) (float32, error) {
	keep := h.cfg.DefaultKeep
	if requested == nil {
		return keep, nil
	}
	value := float64(*requested)
	if math.IsNaN(value) || math.IsInf(value, 0) || *requested <= 0 || *requested > 1 {
		return 0, errors.New("keep_ratio 须在 (0,1]")
	}
	if math.Abs(float64(*requested-keep)) > 1e-6 {
		return 0, &stationConfigConflictError{
			message: "客户端 keep_ratio 与服务端工位采集配置不一致，请刷新配置后重试",
		}
	}
	return keep, nil
}

// resolveRegionCalibration 只从服务端读取正式区域墙；旧客户端字段只允许做 canonical 一致性校验。
func (h *Handler) resolveRegionCalibration(
	ctx context.Context,
	unitAIP, unitBIP, align, siteJSON string,
	request *PointRegionFilter,
) (PointRegionFilter, RegionCalibrationSnapshot, error) {
	if h.regionCalib == nil {
		return PointRegionFilter{}, RegionCalibrationSnapshot{}, fmt.Errorf("%w：区域墙", errStationConfigStoreUnavailable)
	}

	definition := PointRegionFilter{}
	snapshot := RegionCalibrationSnapshot{}
	cal, err := h.regionCalib.Get(ctx, unitAIP, unitBIP)
	switch {
	case errors.Is(err, repo.ErrNotFound):
		// 未配置也是明确的服务端状态，客户端不能临时注入几何。
	case err != nil:
		return PointRegionFilter{}, RegionCalibrationSnapshot{}, fmt.Errorf("读取工位区域墙失败: %w", err)
	default:
		var points [][3]float32
		if err := json.Unmarshal(cal.Points, &points); err != nil {
			return PointRegionFilter{}, RegionCalibrationSnapshot{}, fmt.Errorf("服务端工位区域墙损坏: %w", err)
		}
		definition, err = normalizedRegionDefinition(PointRegionFilter{Enabled: cal.Enabled, Points: points})
		if err != nil {
			return PointRegionFilter{}, RegionCalibrationSnapshot{}, fmt.Errorf("服务端工位区域墙损坏: %w", err)
		}
		updatedAt := cal.UpdatedAt
		snapshot = RegionCalibrationSnapshot{
			Set:          true,
			Enabled:      definition.Enabled,
			Source:       cal.Source,
			SourceScanID: cal.SourceScanID,
			UpdatedBy:    cal.UpdatedBy,
			UpdatedAt:    &updatedAt,
		}
	}

	hash, err := regionDefinitionSHA256(definition)
	if err != nil {
		return PointRegionFilter{}, RegionCalibrationSnapshot{}, fmt.Errorf("计算工位区域墙版本失败: %w", err)
	}
	snapshot.PointsSHA256 = hash

	if request != nil {
		requested, normalizeErr := normalizedRegionDefinition(PointRegionFilter{
			Enabled: request.Enabled,
			Points:  request.Points,
		})
		if normalizeErr != nil {
			return PointRegionFilter{}, RegionCalibrationSnapshot{}, &stationConfigConflictError{
				message: "客户端区域墙无效，请刷新工位配置后重试: " + normalizeErr.Error(),
			}
		}
		if !sameRegionDefinition(requested, definition) {
			return PointRegionFilter{}, RegionCalibrationSnapshot{}, &stationConfigConflictError{
				message: "客户端区域墙与服务端权威版本不一致，请刷新工位配置后重试",
			}
		}
	}

	if !definition.Enabled {
		return PointRegionFilter{}, snapshot, nil
	}
	filter := definition
	if align == "site" {
		matrix, err := nativeSiteDisplayMatrix(siteJSON)
		if err != nil {
			return PointRegionFilter{}, RegionCalibrationSnapshot{}, fmt.Errorf("工位外参无法用于区域墙: %w", err)
		}
		filter.BToA = matrixSlice(matrix)
	}
	filter, err = filter.Normalized()
	if err != nil {
		return PointRegionFilter{}, RegionCalibrationSnapshot{}, fmt.Errorf("服务端工位区域墙损坏: %w", err)
	}
	return filter, snapshot, nil
}

// GetSiteCalibration 返回指定双单元的服务端权威工位外参。
func (h *Handler) GetSiteCalibration(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if h.siteCalib == nil {
		writeErr(w, http.StatusServiceUnavailable, "工位外参存储未配置")
		return
	}
	ipA, ipB, ok := h.stationUnitIPs(r.URL.Query().Get("unit_a_ip"), r.URL.Query().Get("unit_b_ip"))
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip/unit_b_ip 必须是合法 IPv4 地址")
		return
	}
	cal, err := h.siteCalib.Get(r.Context(), ipA, ipB)
	if errors.Is(err, repo.ErrNotFound) {
		writeJSON(w, http.StatusOK, map[string]any{
			"set": false, "unit_a_ip": ipA, "unit_b_ip": ipB,
		})
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读取工位外参失败: "+err.Error())
		return
	}
	revision, err := canonicalSiteSHA256(string(cal.SiteJSON))
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "服务端工位外参损坏: "+err.Error())
		return
	}
	quality, qualityErr := evaluateSiteCalibrationQuality(
		cal.RMSErrorMM,
		cal.CommonMarkers,
		revision,
		h.cfg.UnverifiedSiteRevision,
	)
	qualityReason := quality.Reason
	if qualityErr != nil {
		qualityReason = qualityErr.Error()
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"set":                          true,
		"unit_a_ip":                    cal.UnitAIP,
		"unit_b_ip":                    cal.UnitBIP,
		"site_json":                    cal.SiteJSON,
		"source":                       cal.Source,
		"mean_error_mm":                cal.MeanErrorMM,
		"max_error_mm":                 cal.MaxErrorMM,
		"rms_error_mm":                 cal.RMSErrorMM,
		"common_markers":               cal.CommonMarkers,
		"source_scan_id":               cal.SourceScanID,
		"updated_by":                   cal.UpdatedBy,
		"updated_at":                   cal.UpdatedAt,
		"revision":                     revision,
		"site_quality_state":           quality.State,
		"site_quality_verified":        quality.Verified,
		"site_quality_override":        quality.overrideEnabled(),
		"site_quality_override_reason": quality.OverrideReason,
		"site_quality_reason":          qualityReason,
		"scan_eligible":                quality.ScanEligible,
		"production_eligible":          quality.productionEligible(),
	})
}

// PutSiteCalibration 只幂等确认服务端已解算外参，不允许浏览器创建或覆盖。
func (h *Handler) PutSiteCalibration(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "保存工位外参需 admin 角色")
		return
	}
	if h.siteCalib == nil {
		writeErr(w, http.StatusServiceUnavailable, "工位外参存储未配置")
		return
	}
	var req siteCalibrationPutReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "请求 JSON 无效")
		return
	}
	ipA, ipB, ok := h.stationUnitIPs(req.UnitAIP, req.UnitBIP)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip/unit_b_ip 必须是合法 IPv4 地址")
		return
	}
	siteJSON := strings.TrimSpace(string(req.SiteJSON))
	if err := validateSiteExtrinsicJSON(siteJSON); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	source := strings.TrimSpace(req.Source)
	if source == "" {
		source = "unknown"
	}
	if len(source) > 64 {
		writeErr(w, http.StatusBadRequest, "source 过长")
		return
	}
	for name, value := range map[string]*float64{
		"mean_error_mm": req.MeanErrorMM,
		"max_error_mm":  req.MaxErrorMM,
		"rms_error_mm":  req.RMSErrorMM,
	} {
		if value != nil && (math.IsNaN(*value) || math.IsInf(*value, 0) || *value < 0) {
			writeErr(w, http.StatusBadRequest, name+" 必须是非负有限数")
			return
		}
	}
	if req.CommonMarkers != nil && *req.CommonMarkers < 0 {
		writeErr(w, http.StatusBadRequest, "common_markers 必须是非负整数")
		return
	}
	if err := validateProductionSiteQuality(req.RMSErrorMM, req.CommonMarkers); err != nil {
		writeErr(w, http.StatusBadRequest, "工位外参质量未达生产要求: "+err.Error())
		return
	}
	previous, err := h.siteCalib.Get(r.Context(), ipA, ipB)
	if errors.Is(err, repo.ErrNotFound) {
		writeErr(w, http.StatusConflict, "正式工位外参必须由服务端 site-calib/site-framing 解算并入库")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读取工位外参失败: "+err.Error())
		return
	}
	if !sameSiteCalibrationConfirmation(previous, req, source) {
		writeErr(w, http.StatusConflict, "浏览器外参与服务端已解算版本不一致，请刷新后重试")
		return
	}
	revision, _ := canonicalSiteSHA256(string(previous.SiteJSON))
	h.recordAudit(r, "laser.site_calibration.confirm", "bay:"+ipA+"|"+ipB, map[string]any{
		"unit_a_ip": ipA, "unit_b_ip": ipB, "revision": revision,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "unit_a_ip": ipA, "unit_b_ip": ipB,
		"site_json": previous.SiteJSON, "source": previous.Source,
		"mean_error_mm": previous.MeanErrorMM, "max_error_mm": previous.MaxErrorMM,
		"rms_error_mm": previous.RMSErrorMM, "common_markers": previous.CommonMarkers,
		"updated_by": previous.UpdatedBy, "updated_at": previous.UpdatedAt, "revision": revision,
	})
}

// GetRegionCalibration 返回指定工位的服务端权威区域墙。
func (h *Handler) GetRegionCalibration(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if h.regionCalib == nil {
		writeErr(w, http.StatusServiceUnavailable, "工位区域墙存储未配置")
		return
	}
	ipA, ipB, ok := h.stationUnitIPs(r.URL.Query().Get("unit_a_ip"), r.URL.Query().Get("unit_b_ip"))
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip/unit_b_ip 必须是不同的合法 IPv4 地址")
		return
	}
	cal, err := h.regionCalib.Get(r.Context(), ipA, ipB)
	if errors.Is(err, repo.ErrNotFound) {
		writeJSON(w, http.StatusOK, map[string]any{
			"set": false, "unit_a_ip": ipA, "unit_b_ip": ipB,
		})
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读取工位区域墙失败: "+err.Error())
		return
	}
	var points [][3]float32
	if err := json.Unmarshal(cal.Points, &points); err != nil {
		writeErr(w, http.StatusInternalServerError, "服务端工位区域墙损坏: "+err.Error())
		return
	}
	definition, err := normalizedRegionDefinition(PointRegionFilter{Enabled: cal.Enabled, Points: points})
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "服务端工位区域墙损坏: "+err.Error())
		return
	}
	revision, _ := regionDefinitionSHA256(definition)
	writeJSON(w, http.StatusOK, map[string]any{
		"set": true, "unit_a_ip": cal.UnitAIP, "unit_b_ip": cal.UnitBIP,
		"enabled": cal.Enabled, "points": definition.Points, "source": cal.Source,
		"source_scan_id": cal.SourceScanID, "updated_by": cal.UpdatedBy, "updated_at": cal.UpdatedAt,
		"revision": revision,
	})
}

// PutRegionCalibration 保存或覆盖区域墙；标定级操作只允许 admin。
func (h *Handler) PutRegionCalibration(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "保存工位区域墙需 admin 角色")
		return
	}
	if h.regionCalib == nil {
		writeErr(w, http.StatusServiceUnavailable, "工位区域墙存储未配置")
		return
	}
	var req regionCalibrationPutReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "请求 JSON 无效")
		return
	}
	ipA, ipB, ok := h.stationUnitIPs(req.UnitAIP, req.UnitBIP)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip/unit_b_ip 必须是不同的合法 IPv4 地址")
		return
	}
	definition, err := normalizedRegionDefinition(PointRegionFilter{Enabled: req.Enabled, Points: req.Points})
	if err != nil || len(definition.Points) < 3 {
		if err == nil {
			err = errors.New("区域标定至少需要 3 个非重复点")
		}
		writeErr(w, http.StatusBadRequest, "区域墙无效: "+err.Error())
		return
	}
	source := strings.TrimSpace(req.Source)
	if source == "" {
		source = "unknown"
	}
	if len(source) > 64 {
		writeErr(w, http.StatusBadRequest, "source 过长")
		return
	}
	pointsJSON, err := json.Marshal(definition.Points)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "编码区域墙失败")
		return
	}
	updatedBy := uid
	cal := repo.LaserRegionCalibration{
		UnitAIP: ipA, UnitBIP: ipB, Enabled: definition.Enabled,
		Points: pointsJSON, Source: source, SourceScanID: req.SourceScanID, UpdatedBy: &updatedBy,
	}
	if err := h.regionCalib.Upsert(r.Context(), cal); err != nil {
		writeErr(w, http.StatusInternalServerError, "保存工位区域墙失败: "+err.Error())
		return
	}
	revision, _ := regionDefinitionSHA256(definition)
	h.recordAudit(r, "laser.region_calibration.update", "bay:"+ipA+"|"+ipB, map[string]any{
		"unit_a_ip": ipA, "unit_b_ip": ipB, "enabled": definition.Enabled,
		"points": definition.Points, "source": source, "revision": revision,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "set": true, "unit_a_ip": ipA, "unit_b_ip": ipB,
		"enabled": definition.Enabled, "points": definition.Points, "source": source, "revision": revision,
	})
}

// DeleteRegionCalibration 删除正式区域墙；重复删除保持幂等。
func (h *Handler) DeleteRegionCalibration(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "删除工位区域墙需 admin 角色")
		return
	}
	if h.regionCalib == nil {
		writeErr(w, http.StatusServiceUnavailable, "工位区域墙存储未配置")
		return
	}
	ipA, ipB, ok := h.stationUnitIPs(r.URL.Query().Get("unit_a_ip"), r.URL.Query().Get("unit_b_ip"))
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip/unit_b_ip 必须是不同的合法 IPv4 地址")
		return
	}
	if err := h.regionCalib.Delete(r.Context(), ipA, ipB); err != nil && !errors.Is(err, repo.ErrNotFound) {
		writeErr(w, http.StatusInternalServerError, "删除工位区域墙失败: "+err.Error())
		return
	}
	h.recordAudit(r, "laser.region_calibration.delete", "bay:"+ipA+"|"+ipB, map[string]any{
		"unit_a_ip": ipA, "unit_b_ip": ipB, "deleted": true,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "set": false, "unit_a_ip": ipA, "unit_b_ip": ipB,
	})
}

func (h *Handler) stationUnitIPs(rawA, rawB string) (string, string, bool) {
	ipA := orStr(strings.TrimSpace(rawA), h.cfg.DefaultUnitAIP)
	ipB := orStr(strings.TrimSpace(rawB), h.cfg.DefaultUnitBIP)
	a, errA := netip.ParseAddr(ipA)
	b, errB := netip.ParseAddr(ipB)
	if errA != nil || errB != nil || !a.Is4() || !b.Is4() || a == b {
		return "", "", false
	}
	return a.String(), b.String(), true
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
	if errors.Is(err, repo.ErrNotFound) {
		writeErr(w, http.StatusNotFound, "扫描不存在")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读取扫描状态失败: "+err.Error())
		return
	}
	if !ownsOrAdmin(r, job, uid) {
		writeErr(w, http.StatusForbidden, "无权操作该扫描")
		return
	}
	// 先以数据库 CAS 抢占终态，再协作取消采集。若先停 cgo、后写 cancelled，融合线程可能在
	// 两者之间先 Complete 成 done，形成“用户已点停止但仍发布完成”的短竞态。
	updated, cerr := h.repo.Cancel(context.WithoutCancel(r.Context()), id)
	if cerr != nil && !errors.Is(cerr, repo.ErrNotFound) {
		writeErr(w, http.StatusInternalServerError, "取消失败: "+cerr.Error())
		return
	}
	status := job.Status
	if updated != nil {
		status = updated.Status
	} else if errors.Is(cerr, repo.ErrNotFound) {
		// CAS 未命中通常表示任务已被另一协程推进到终态；重读后返回权威状态，避免把旧的
		// capturing/fusing 快照回给客户端。
		if current, findErr := h.repo.FindByID(r.Context(), id); findErr == nil {
			status = current.Status
		} else {
			writeErr(w, http.StatusInternalServerError, "读取取消后的扫描状态失败: "+findErr.Error())
			return
		}
	}
	if status == repo.LaserScanStatusCancelled {
		if as := h.sessions.get(id); as != nil && as.cancel != nil {
			as.cancel()
		}
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
	if errors.Is(err, repo.ErrNotFound) {
		writeErr(w, http.StatusNotFound, "扫描不存在")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读取扫描状态失败: "+err.Error())
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
	ipA, ipB, ok := h.activeStationIPs(r)
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
	if errors.Is(err, repo.ErrNotFound) {
		writeJSON(w, http.StatusOK, map[string]any{"active": false})
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读取活动扫描状态失败: "+err.Error())
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
	ipA, ipB, ok := h.activeStationIPs(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip/unit_b_ip 必须是 IPv4")
		return
	}
	var ownerUserID *int64
	if !isAdmin(r) {
		ownerUserID = &uid
	}
	candidates, err := h.latestMeasurementCandidates(r.Context(), ipA, ipB, ownerUserID)
	if errors.Is(err, repo.ErrNotFound) {
		writeJSON(w, http.StatusOK, map[string]any{"found": false})
		return
	}
	if err != nil {
		h.log.Error("查询最近激光扫描失败", "unit_a_ip", ipA, "unit_b_ip", ipB, "err", err)
		writeErr(w, http.StatusInternalServerError, "查询最近扫描失败")
		return
	}
	for _, job := range candidates {
		if job == nil || !ownsOrAdmin(r, job, uid) {
			continue
		}
		artifact, ok := measuredArtifactFromJob(job)
		if !ok {
			h.log.Warn("跳过 measured manifest 损坏的历史扫描", "scan_id", job.ID)
			continue
		}
		if !h.measuredCloudObjectHealthy(r.Context(), job, artifact) {
			h.log.Warn("跳过 measured PCD 损坏或缺失的历史扫描", "scan_id", job.ID)
			continue
		}
		resp := jobView(job)
		resp["found"] = true
		writeJSON(w, http.StatusOK, resp)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"found": false})
}

const latestMeasurementCandidateLimit = 20

type latestMeasurementLister interface {
	FindLatestMeasurements(
		ctx context.Context,
		unitAIP, unitBIP string,
		ownerUserID *int64,
		limit int,
	) ([]*repo.LaserScanJob, error)
}

func (h *Handler) latestMeasurementCandidates(
	ctx context.Context,
	unitAIP, unitBIP string,
	ownerUserID *int64,
) ([]*repo.LaserScanJob, error) {
	if lister, ok := h.repo.(latestMeasurementLister); ok {
		jobs, err := lister.FindLatestMeasurements(
			ctx,
			unitAIP,
			unitBIP,
			ownerUserID,
			latestMeasurementCandidateLimit,
		)
		if err != nil {
			return nil, err
		}
		if len(jobs) == 0 {
			return nil, repo.ErrNotFound
		}
		return jobs, nil
	}
	job, err := h.repo.FindLatestMeasurement(ctx, unitAIP, unitBIP, ownerUserID)
	if err != nil {
		return nil, err
	}
	if job == nil {
		return nil, errors.New("最近扫描仓储返回 nil,nil")
	}
	return []*repo.LaserScanJob{job}, nil
}

func (h *Handler) measuredCloudObjectHealthy(
	ctx context.Context,
	job *repo.LaserScanJob,
	artifact *MeasuredCloudArtifact,
) bool {
	if h.reader == nil {
		return true
	}
	if job == nil || job.MeasuredObjectKey == nil || artifact == nil {
		return false
	}
	rc, size, err := h.reader.GetObject(ctx, *job.MeasuredObjectKey)
	if err != nil {
		return false
	}
	defer rc.Close()
	sample, err := PreparePCDBinarySample(rc, size, 1)
	if err != nil || sample.SourcePoints() != artifact.SourcePoints ||
		sample.CoordinateSchema() != artifact.CoordinateSchema ||
		sample.XYZSHA256() != artifact.XYZSHA256 ||
		sample.FinalBToASHA256() != artifact.FinalBToASHA256 ||
		!sample.CanonicalXYZRecords() {
		return false
	}
	_, err = sample.ReadSampleVerified(artifact.XYZSHA256)
	return err == nil
}

// DownloadCloud GET /v1/scans/laser/{id}/cloud/{name}（name ∈ fused|unit_a|unit_b|measured）。
// 默认流式回传权威完整 PCD；max_points>0 时从同一 PCD 派生有界渲染样本，测量/导出数据不变。
// object key 从 job 白名单字段取（非客户端传入），杜绝路径穿越/越权取任意对象。
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
	if errors.Is(err, repo.ErrNotFound) {
		writeErr(w, http.StatusNotFound, "扫描不存在")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读取扫描状态失败: "+err.Error())
		return
	}
	if !ownsOrAdmin(r, job, uid) {
		writeErr(w, http.StatusForbidden, "无权下载该扫描")
		return
	}
	name := r.PathValue("name")
	var key *string
	var measuredArtifact *MeasuredCloudArtifact
	switch name {
	case "fused":
		key = job.FusedObjectKey
	case "unit_a":
		key = job.UnitAObjectKey
	case "unit_b":
		key = job.UnitBObjectKey
	case "measured":
		key = job.MeasuredObjectKey
	default:
		writeErr(w, http.StatusBadRequest, "name 须为 fused|unit_a|unit_b|measured")
		return
	}
	if key == nil || *key == "" {
		if name == "fused" && jAlignMethod(job) == "raw" {
			writeErr(w, http.StatusNotFound, "未标定无法融合")
			return
		}
		writeErr(w, http.StatusNotFound, "该点云尚未就绪（扫描未完成？）")
		return
	}
	if name == "measured" {
		var ok bool
		measuredArtifact, ok = measuredArtifactFromJob(job)
		if !ok {
			writeErr(w, http.StatusConflict, "车辆测量点云缺少内容身份清单，必须重新扫描")
			return
		}
	}
	rc, size, gerr := h.reader.GetObject(r.Context(), *key)
	if gerr != nil {
		writeErr(w, http.StatusBadGateway, "取点云失败: "+gerr.Error())
		return
	}
	defer rc.Close()
	maxPoints, err := parseCloudMaxPoints(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	if measuredArtifact != nil {
		limit := maxPoints
		if limit <= 0 || limit > measuredArtifact.SourcePoints {
			limit = measuredArtifact.SourcePoints
		}
		sample, sampleErr := PreparePCDBinarySample(rc, size, limit)
		if sampleErr != nil {
			writeErr(w, http.StatusBadGateway, "解析车辆测量点云失败: "+sampleErr.Error())
			return
		}
		if sample.SourcePoints() != measuredArtifact.SourcePoints ||
			sample.CoordinateSchema() != measuredArtifact.CoordinateSchema ||
			sample.XYZSHA256() != measuredArtifact.XYZSHA256 ||
			sample.FinalBToASHA256() != measuredArtifact.FinalBToASHA256 ||
			!sample.CanonicalXYZRecords() {
			writeErr(w, http.StatusBadGateway, "车辆测量点云与任务内容身份不一致")
			return
		}
		payload, verifyErr := sample.ReadSampleVerified(measuredArtifact.XYZSHA256)
		if verifyErr != nil {
			writeErr(w, http.StatusBadGateway, "校验车辆测量点云失败: "+verifyErr.Error())
			return
		}
		w.Header().Set("Content-Type", "application/octet-stream")
		w.Header().Set("Content-Disposition", `attachment; filename="`+name+`.pcd"`)
		w.Header().Set("Content-Length", strconv.Itoa(len(payload)))
		w.Header().Set("X-Gomob-Source-Points", strconv.Itoa(sample.SourcePoints()))
		w.Header().Set("X-Gomob-Render-Points", strconv.Itoa(sample.SamplePoints()))
		w.Header().Set("X-Gomob-Coordinate-Schema", measuredArtifact.CoordinateSchema)
		w.Header().Set("X-Gomob-XYZ-SHA256", measuredArtifact.XYZSHA256)
		w.Header().Set("X-Gomob-Final-B-To-A-SHA256", measuredArtifact.FinalBToASHA256)
		_, _ = w.Write(payload)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", `attachment; filename="`+name+`.pcd"`)
	if maxPoints > 0 {
		sample, sampleErr := PreparePCDBinarySample(rc, size, maxPoints)
		if sampleErr != nil {
			writeErr(w, http.StatusBadGateway, "解析点云失败: "+sampleErr.Error())
			return
		}
		w.Header().Set("Content-Length", strconv.FormatInt(sample.ContentLength(), 10))
		w.Header().Set("X-Gomob-Source-Points", strconv.Itoa(sample.SourcePoints()))
		w.Header().Set("X-Gomob-Render-Points", strconv.Itoa(sample.SamplePoints()))
		if err := sample.WriteSampleTo(w); err != nil {
			// 响应已开始后不能再改状态码；记录在服务端日志，由客户端的长度校验拒绝残缺 PCD。
			slog.Warn("laser cloud sample write failed", "scan_id", id, "name", name, "error", err)
		}
		return
	}
	if size > 0 {
		w.Header().Set("Content-Length", strconv.FormatInt(size, 10))
	}
	_, _ = io.Copy(w, rc)
}

func parseCloudMaxPoints(r *http.Request) (int, error) {
	raw := strings.TrimSpace(r.URL.Query().Get("max_points"))
	if raw == "" {
		return 0, nil
	}
	maxPoints, err := strconv.Atoi(raw)
	if err != nil || maxPoints <= 0 || maxPoints > 1_000_000 {
		return 0, errors.New("max_points 须为 1..1000000")
	}
	return maxPoints, nil
}

// DownloadActiveCloud GET /v1/scans/laser/active/cloud/{unit_a|unit_b}?unit_a_ip=...&unit_b_ip=...。
// 返回当前活动扫描的分镜实时缓存快照；未完成前也能被网页刷新后直接加载。
func (h *Handler) DownloadActiveCloud(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	ipA, ipB, ok := h.activeStationIPs(r)
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
	if errors.Is(err, repo.ErrNotFound) {
		writeErr(w, http.StatusNotFound, "扫描不存在")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读取活动扫描状态失败: "+err.Error())
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
	maxPoints, err := parseCloudMaxPoints(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	snapshot := as.cache.snapshot(unit, maxPoints)
	renderPoints := len(snapshot.XYZ) / 3
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Length", strconv.FormatInt(
		pcdBinaryContentLength(renderPoints, snapshot.SourcePoints, nil), 10,
	))
	w.Header().Set("Content-Disposition", `attachment; filename="live_`+r.PathValue("name")+`.pcd"`)
	w.Header().Set("X-Gomob-Source-Points", strconv.Itoa(snapshot.SourcePoints))
	w.Header().Set("X-Gomob-Render-Points", strconv.Itoa(renderPoints))
	if err := writePCDBinary(w, snapshot.XYZ, snapshot.SourcePoints, nil); err != nil {
		slog.Warn("laser active cloud write failed", "scan_id", as.jobID, "name", r.PathValue("name"), "error", err)
	}
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
	if !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "设备控制命令需 admin 角色")
		return
	}
	dev, ip, status, message := h.resolveManagedMutationUnit(r)
	if status != 0 {
		writeErr(w, status, message)
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
	if cmd == ScanStop {
		acquired, allowed := h.sessions.tryReserveBareStop()
		if !allowed {
			writeErr(w, http.StatusConflict, "正式扫描活动中不得直接 SCAN_STOP，请使用 /v1/scans/laser/{id}/stop")
			return
		}
		if acquired {
			defer h.sessions.release()
		}
	} else {
		if !h.sessions.tryReserve(reservationDeviceMutation) {
			writeErr(w, http.StatusConflict, "有扫描/标定/设备操作进行中，禁止下发设备命令")
			return
		}
		defer h.sessions.release()
	}
	if err := dev.ControlScan(r.Context(), cmd); err != nil {
		writeErr(w, http.StatusBadGateway, "命令失败("+ip+"): "+err.Error())
		return
	}
	h.recordAudit(r, "laser.device_command", "unit:"+ip, map[string]any{
		"unit_ip": ip, "cmd": string(cmd),
	})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "unit_ip": ip, "cmd": string(cmd)})
}

// DeviceScanSettings POST /v1/scans/laser/device-scan-settings?unit=a|b  body ControlSettings。
func (h *Handler) DeviceScanSettings(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "修改设备扫描设置需 admin 角色")
		return
	}
	dev, ip, status, message := h.resolveManagedMutationUnit(r)
	if status != 0 {
		writeErr(w, status, message)
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
	if !h.sessions.tryReserve(reservationDeviceMutation) {
		writeErr(w, http.StatusConflict, "有扫描/标定/设备操作进行中，禁止修改扫描设置")
		return
	}
	defer h.sessions.release()
	if err := dev.UpdateControl(r.Context(), s); err != nil {
		writeErr(w, http.StatusBadGateway, "下发扫描设置失败("+ip+"): "+err.Error())
		return
	}
	h.recordAudit(r, "laser.device_scan_settings", "unit:"+ip, map[string]any{
		"unit_ip": ip, "settings": s,
	})
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
	// 破坏性：覆写设备存储标定，错值会废掉整台设备的测量。限 admin + 审计。
	if !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "覆写设备标定参数需 admin 角色")
		return
	}
	dev, ip, status, message := h.resolveManagedMutationUnit(r)
	if status != 0 {
		writeErr(w, status, message)
		return
	}
	var p CalibParams
	if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
		writeErr(w, http.StatusBadRequest, "解析标定参数失败: "+err.Error())
		return
	}
	if !h.sessions.tryReserve(reservationDeviceMutation) {
		writeErr(w, http.StatusConflict, "有扫描/标定/设备操作进行中，禁止覆写设备标定")
		return
	}
	defer h.sessions.release()
	if err := dev.UpdateCalib(r.Context(), p); err != nil {
		writeErr(w, http.StatusBadGateway, "下发标定失败("+ip+"): "+err.Error())
		return
	}
	h.recordAudit(r, "laser.device_calib", "unit:"+ip, map[string]any{"unit_ip": ip, "calib": p})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "unit_ip": ip})
}

// --- 持久车位框端点（M9.11；服务端持久化，非设备写，无需设备审批）---

// bayKeyFromReq 按请求里的工位标识取装机点键（与 runner 用 unit_a_ip 作 bayKey 对齐）。
// 多工位共用一个 laserworker 时，crop-box/background 必须按各自工位取键，否则张冠李戴。
// ?unit_a_ip= 显式给出则用之（须合法 IPv4）；缺省回退默认工位（向后兼容单工位部署）。
// 第二返回值 false 表示传了 unit_a_ip 但非法 IPv4，调用方应拒绝。
func (h *Handler) bayKeyFromReq(r *http.Request) (string, bool) {
	raw := r.URL.Query().Get("unit_a_ip")
	if raw == "" {
		return h.cfg.DefaultUnitAIP, true
	}
	ip, ok := normalizeOptionalIPv4(raw)
	if !ok || ip == "" {
		return "", false
	}
	return ip, true
}

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
	bayKey, ok := h.bayKeyFromReq(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip 必须是 IPv4")
		return
	}
	unit := cropUnit(r)
	box, has, err := h.cropBoxes.GetCropBox(r.Context(), bayKey, unit)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "取车位框失败: "+err.Error())
		return
	}
	resp := map[string]any{"bay_key": bayKey, "unit": unit, "set": has}
	if has {
		resp["box"] = box
	}
	writeJSON(w, http.StatusOK, resp)
}

// PutCropBox PUT /v1/scans/laser/crop-box  body=CropBox。保存/覆盖当前车位框。
func (h *Handler) PutCropBox(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "保存工位测量框需 admin 角色")
		return
	}
	if h.cropBoxes == nil {
		writeErr(w, http.StatusNotImplemented, "未配置车位框存储")
		return
	}
	bayKey, ok := h.bayKeyFromReq(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip 必须是 IPv4")
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
	if err := h.cropBoxes.SaveCropBox(r.Context(), bayKey, unit, box); err != nil {
		writeErr(w, http.StatusInternalServerError, "保存车位框失败: "+err.Error())
		return
	}
	h.recordAudit(r, "laser.crop_box.update", "bay:"+bayKey+"|unit:"+unit, map[string]any{
		"bay_key": bayKey, "unit": unit, "box": box,
	})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "bay_key": bayKey, "unit": unit})
}

// DeleteCropBox 删除旧工位测量框；背景 revision 生效后通常不需要该 fallback。
func (h *Handler) DeleteCropBox(w http.ResponseWriter, r *http.Request) {
	uid := callerUserID(r)
	if uid == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if !isAdmin(r) {
		writeErr(w, http.StatusForbidden, "删除工位测量框需 admin 角色")
		return
	}
	if h.cropBoxes == nil {
		writeErr(w, http.StatusNotImplemented, "未配置持久车位框存储")
		return
	}
	bayKey, ok := h.bayKeyFromReq(r)
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip 必须是 IPv4")
		return
	}
	unit := cropUnit(r)
	if err := h.cropBoxes.DeleteCropBox(r.Context(), bayKey, unit); err != nil {
		writeErr(w, http.StatusInternalServerError, "删除车位框失败: "+err.Error())
		return
	}
	h.recordAudit(r, "laser.crop_box.delete", "bay:"+bayKey+"|unit:"+unit, map[string]any{
		"bay_key": bayKey, "unit": unit, "deleted": true,
	})
	writeJSON(w, http.StatusOK, map[string]any{"ok": true, "set": false, "bay_key": bayKey, "unit": unit})
}

// GetBackground GET /v1/scans/laser/background。返回当前 active revision 与设备配置兼容性。
func (h *Handler) GetBackground(w http.ResponseWriter, r *http.Request) {
	if callerUserID(r) == 0 {
		writeErr(w, http.StatusUnauthorized, "需要鉴权")
		return
	}
	if h.backgrounds == nil {
		writeErr(w, http.StatusServiceUnavailable, "空工位背景版本存储未配置")
		return
	}
	ipA, ipB, ok := h.stationUnitIPs(r.URL.Query().Get("unit_a_ip"), r.URL.Query().Get("unit_b_ip"))
	if !ok {
		writeErr(w, http.StatusBadRequest, "unit_a_ip/unit_b_ip 必须是不同的合法 IPv4 地址")
		return
	}
	regionSnapshot := RegionCalibrationSnapshot{}
	if h.regionCalib != nil {
		_, resolved, regionErr := h.resolveRegionCalibration(r.Context(), ipA, ipB, "raw", "", nil)
		if regionErr != nil {
			writeErr(w, http.StatusInternalServerError, "读取工位区域墙失败: "+regionErr.Error())
			return
		}
		regionSnapshot = resolved
	}
	regionConfigured := regionSnapshot.Set && regionSnapshot.Enabled
	revision, err := h.backgrounds.GetActive(r.Context(), ipA, ipB)
	var requestedKeep *float32
	if rawKeep := strings.TrimSpace(r.URL.Query().Get("keep_ratio")); rawKeep != "" {
		parsed, parseErr := strconv.ParseFloat(rawKeep, 32)
		if parseErr != nil {
			writeErr(w, http.StatusBadRequest, "keep_ratio 须在 (0,1]")
			return
		}
		value := float32(parsed)
		requestedKeep = &value
	}
	keep, keepErr := h.resolveStationKeepRatio(requestedKeep)
	if keepErr != nil {
		var conflict *stationConfigConflictError
		if errors.As(keepErr, &conflict) {
			writeErr(w, http.StatusConflict, conflict.Error())
		} else {
			writeErr(w, http.StatusBadRequest, keepErr.Error())
		}
		return
	}
	if errors.Is(err, repo.ErrNotFound) {
		writeJSON(w, http.StatusOK, map[string]any{
			"set": false, "unit_a_ip": ipA, "unit_b_ip": ipB,
			"compatible": false, "background_incompatible": false, "reason": "not_set",
			"region_configured": regionConfigured, "region_missing": !regionConfigured,
			"effective_keep_ratio": keep,
		})
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读取空工位背景版本失败: "+err.Error())
		return
	}
	siteRevision, siteErr := h.currentSiteRevision(r.Context(), ipA, ipB)
	profileA, _, profileErrA := h.acquisitionProfile(r.Context(), ipA, "A", keep)
	profileB, _, profileErrB := h.acquisitionProfile(r.Context(), ipB, "B", keep)
	compatible, reason := false, "site_calibration_unavailable"
	if siteErr == nil {
		reason = "device_profile_unavailable"
		if profileErrA == nil && profileErrB == nil {
			compatible, reason = backgroundRevisionCompatibility(
				revision,
				siteRevision,
				regionSnapshot.PointsSHA256,
				profileA,
				profileB,
			)
		}
	}
	resp := map[string]any{
		"set": true, "unit_a_ip": ipA, "unit_b_ip": ipB,
		"revision": revision.ID, "schema": revision.CoordinateSchema,
		"site_revision": siteRevision, "background_site_revision": revision.SiteRevision,
		"region_revision": regionSnapshot.PointsSHA256, "background_region_revision": revision.RegionRevision,
		"compatibility_site_revision":   revision.CompatibilitySite,
		"compatibility_region_revision": revision.CompatibilityRegion,
		"legacy_fused_points":           revision.LegacyFusedPoints,
		"legacy_fused_checksum":         revision.LegacyFusedChecksum,
		"source_scan_id":                revision.SourceScanID, "captured_at": revision.CapturedAt,
		"compatible": compatible, "background_incompatible": !compatible, "reason": reason,
		"region_configured": regionConfigured, "region_missing": !regionConfigured,
		"effective_keep_ratio": keep,
	}
	if profileErrA == nil && profileErrB == nil {
		resp["unit_a_profile"] = profileA
		resp["unit_b_profile"] = profileB
	}
	if siteErr != nil || profileErrA != nil || profileErrB != nil {
		resp["profile_error"] = errors.Join(siteErr, profileErrA, profileErrB).Error()
	}
	writeJSON(w, http.StatusOK, resp)
}

func (h *Handler) currentSiteRevision(ctx context.Context, unitAIP, unitBIP string) (string, error) {
	if h.siteCalib == nil {
		return "", errors.New("工位外参存储未配置")
	}
	cal, err := h.siteCalib.Get(ctx, unitAIP, unitBIP)
	if err != nil {
		return "", err
	}
	revision, err := canonicalSiteSHA256(string(cal.SiteJSON))
	if err != nil {
		return "", fmt.Errorf("服务端工位外参损坏: %w", err)
	}
	return revision, nil
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
	if errors.Is(err, repo.ErrNotFound) {
		writeErr(w, http.StatusNotFound, "扫描不存在")
		return
	}
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "读取扫描状态失败: "+err.Error())
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
		"scan_id":               j.ID,
		"session_key":           j.SessionKey,
		"status":                j.Status,
		"align":                 j.Align,
		"effective_keep_ratio":  j.KeepRatio,
		"site_quality_verified": false,
		"site_quality_override": false,
		"production_eligible":   false,
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
	if j.MeasuredObjectKey != nil {
		v["measured_object_key"] = *j.MeasuredObjectKey
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
	if artifact, ok := measuredArtifactFromJob(j); ok {
		v["measured_artifact"] = artifact
	} else if valid, _ := v["measure_valid"].(bool); valid {
		invalidateCanonicalMeasurementView(v)
	}
	return v
}

// flattenMeasureFromStats 把 job.Stats 里的 measure/axle/compliance 拍平进端侧视图，
// 对齐 FusionDoneEvent 的扁平字段名 → 刷新看历史扫描时测量面板与实时事件同款渲染。
func flattenMeasureFromStats(stats json.RawMessage, v map[string]any) {
	if len(stats) == 0 {
		return
	}
	var s struct {
		Measure           *Dimensions               `json:"measure"`
		Axle              *AxleResult               `json:"axle"`
		CargoBox          *CargoBox                 `json:"cargo_box"`
		Overlay           *VehicleOverlay           `json:"overlay"`
		Ground            *GroundPlane              `json:"ground"`
		Compliance        *Compliance               `json:"compliance"`
		MeasureMode       string                    `json:"measure_mode"`
		MeasureReason     string                    `json:"measure_reason"`
		BgSet             bool                      `json:"bg_set"`
		BgCaptured        bool                      `json:"bg_captured"`
		BgCompatible      *bool                     `json:"background_compatible"`
		BgReason          string                    `json:"background_reason"`
		BgRevisionID      int64                     `json:"background_revision_id"`
		BgSchema          string                    `json:"background_schema"`
		FgPoints          int                       `json:"fg_points"`
		MeasuredPoints    int                       `json:"measured_points"`
		SiteCalibration   SiteCalibrationSnapshot   `json:"site_calibration"`
		RegionCalibration RegionCalibrationSnapshot `json:"region_calibration"`
	}
	if err := json.Unmarshal(stats, &s); err != nil {
		return
	}
	// 抠车隔离方式（背景相减/裁剪框/无隔离），供端侧测量面板按情况给提示。
	if s.MeasureMode != "" {
		v["meas_mode"] = s.MeasureMode
	}
	if s.MeasureReason != "" {
		v["measure_reason"] = s.MeasureReason
	}
	v["background_set"] = s.BgSet
	if s.BgCompatible != nil {
		v["background_compatible"] = *s.BgCompatible
		v["background_incompatible"] = s.BgSet && !*s.BgCompatible
	}
	if s.BgReason != "" {
		v["background_reason"] = s.BgReason
	}
	if s.BgRevisionID != 0 {
		v["background_revision_id"] = s.BgRevisionID
	}
	if s.BgSchema != "" {
		v["background_schema"] = s.BgSchema
	}
	v["fg_points"] = s.FgPoints
	v["measured_points"] = s.MeasuredPoints
	if s.SiteCalibration.MatrixSHA256 != "" {
		v["site_revision"] = s.SiteCalibration.MatrixSHA256
	}
	v["site_quality_verified"] = s.SiteCalibration.qualityVerified()
	v["site_quality_override"] = s.SiteCalibration.qualityOverrideEnabled()
	v["production_eligible"] = s.SiteCalibration.productionEligible()
	if s.SiteCalibration.QualityOverride != "" {
		v["site_quality_override_reason"] = s.SiteCalibration.QualityOverride
	}
	if s.RegionCalibration.PointsSHA256 != "" {
		v["region_revision"] = s.RegionCalibration.PointsSHA256
	}
	if s.Ground != nil {
		v["ground_nx"] = s.Ground.NX
		v["ground_ny"] = s.Ground.NY
		v["ground_nz"] = s.Ground.NZ
		v["ground_d"] = s.Ground.D
		v["ground_valid"] = s.Ground.Valid
	}
	if s.BgCaptured {
		v["background_captured"] = true
	}
	v["measure_valid"] = s.Measure != nil && s.Measure.Valid
	if s.Measure != nil && s.Measure.Valid {
		v["length_mm"] = s.Measure.LengthMM
		v["width_mm"] = s.Measure.WidthMM
		v["height_mm"] = s.Measure.HeightMM
	}
	if s.Compliance != nil {
		v["compliance_determined"] = s.Compliance.Determined
		if s.Compliance.Reason != "" {
			v["compliance_reason"] = s.Compliance.Reason
		}
		v["compliant"] = s.Compliance.Compliant
		if s.Compliance.Determined {
			if len(s.Compliance.Violations) > 0 {
				v["violations"] = s.Compliance.Violations
			}
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

func measuredArtifactFromJob(job *repo.LaserScanJob) (*MeasuredCloudArtifact, bool) {
	if job == nil || len(job.Stats) == 0 || job.MeasuredObjectKey == nil || strings.TrimSpace(*job.MeasuredObjectKey) == "" {
		return nil, false
	}
	var payload struct {
		MeasuredArtifact  *MeasuredCloudArtifact    `json:"measured_artifact"`
		MeasuredPoints    int                       `json:"measured_points"`
		BgRevisionID      int64                     `json:"background_revision_id"`
		SiteCalibration   SiteCalibrationSnapshot   `json:"site_calibration"`
		RegionCalibration RegionCalibrationSnapshot `json:"region_calibration"`
	}
	if err := json.Unmarshal(job.Stats, &payload); err != nil || payload.MeasuredArtifact == nil ||
		!payload.MeasuredArtifact.validContentIdentity() {
		return nil, false
	}
	artifact := payload.MeasuredArtifact
	if artifact.SourcePoints != payload.MeasuredPoints ||
		artifact.SiteRevision != payload.SiteCalibration.MatrixSHA256 ||
		artifact.RegionRevision != payload.RegionCalibration.PointsSHA256 ||
		artifact.BackgroundRevision != payload.BgRevisionID {
		return nil, false
	}
	var bToA [16]float32
	if len(job.BToA) == 0 || json.Unmarshal(job.BToA, &bToA) != nil ||
		artifact.FinalBToASHA256 != cloudFloatSHA256(bToA[:]) {
		return nil, false
	}
	return payload.MeasuredArtifact, true
}

func invalidateCanonicalMeasurementView(v map[string]any) {
	v["measure_valid"] = false
	v["compliance_determined"] = false
	v["compliance_reason"] = "measured_artifact_mismatch"
	v["compliant"] = false
	v["measure_reason"] = "measured_artifact_mismatch"
	for _, key := range []string{
		"length_mm", "width_mm", "height_mm", "violations",
		"axle_valid", "num_axles", "wheelbases_mm", "total_wheelbase_mm", "front_overhang_mm", "rear_overhang_mm",
		"has_cargo_box", "box_outer_length_mm", "box_outer_width_mm", "box_depth_mm", "box_inner_width_mm", "overlay",
	} {
		delete(v, key)
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
	mu          sync.Mutex
	reservation sessionReservation
	active      map[int64]*activeSession
	framing     *activeFramingSession
	lastFraming *activeFramingSession
	framingKeys map[string]struct{}
}

type activeFramingSession struct {
	key          string
	owner        int64
	unitAIP      string
	unitBIP      string
	cancel       context.CancelFunc
	done         chan struct{}
	pipelineDone chan struct{}

	cancelRequested  bool
	pipelineFinished bool
	commitStarted    bool
	committed        bool
	devicesReady     bool
	cleanupStarted   bool
}

type framingCancelDecision struct {
	session   *activeFramingSession
	matched   bool
	active    bool
	cancelWon bool
}

type sessionReservation string

const (
	reservationFormalScan     sessionReservation = "formal_scan"
	reservationFraming        sessionReservation = "framing"
	reservationDeviceMutation sessionReservation = "device_mutation"
	reservationBareStop       sessionReservation = "bare_stop"
)

// tryReserve 预留单活名额；已被占用返回 false。
func (s *sessionRegistry) tryReserve(kind sessionReservation) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.reservation != "" || len(s.active) > 0 {
		return false
	}
	s.reservation = kind
	return true
}

func (s *sessionRegistry) release() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.reservation = ""
}

func (s *sessionRegistry) registerFraming(key string, owner int64, unitAIP, unitBIP string, cancel context.CancelFunc) (*activeFramingSession, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.reservation != reservationFraming || s.framing != nil {
		return nil, false
	}
	if s.framingKeys == nil {
		s.framingKeys = map[string]struct{}{}
	}
	if _, used := s.framingKeys[key]; used {
		return nil, false
	}
	framing := &activeFramingSession{
		key:          key,
		owner:        owner,
		unitAIP:      unitAIP,
		unitBIP:      unitBIP,
		cancel:       cancel,
		done:         make(chan struct{}),
		pipelineDone: make(chan struct{}),
	}
	s.framing = framing
	s.framingKeys[key] = struct{}{}
	return framing, true
}

// requestFramingCancel 与 beginFramingCommit 原子竞争：停止先赢则禁止提交，提交先赢则停止端明确报告。
func (s *sessionRegistry) requestFramingCancel(key string) framingCancelDecision {
	s.mu.Lock()
	if s.framing == nil {
		last := s.lastFraming
		s.mu.Unlock()
		if last != nil && last.key == key {
			return framingCancelDecision{session: last, matched: true}
		}
		return framingCancelDecision{}
	}
	if s.framing.key != key {
		s.mu.Unlock()
		return framingCancelDecision{active: true}
	}
	framing := s.framing
	decision := framingCancelDecision{session: framing, matched: true, active: true}
	if !framing.commitStarted {
		framing.cancelRequested = true
		decision.cancelWon = true
	}
	s.mu.Unlock()
	if decision.cancelWon {
		framing.cancel()
	}
	return decision
}

// beginFramingCommit 与 requestFramingCancel 共用同一把锁，消除“停止与外参入库”之间的 TOCTOU。
func (s *sessionRegistry) beginFramingCommit(key string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.framing == nil || s.framing.key != key || s.framing.cancelRequested {
		return false
	}
	s.framing.commitStarted = true
	return true
}

func (s *sessionRegistry) markFramingCommitted(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.framing != nil && s.framing.key == key {
		s.framing.committed = true
	}
}

func (s *sessionRegistry) markFramingDevicesReady(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.framing != nil && s.framing.key == key {
		s.framing.devicesReady = true
	}
}

func (s *sessionRegistry) framingCancelRequested(key string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.framing != nil && s.framing.key == key && s.framing.cancelRequested
}

func (s *sessionRegistry) startFramingCleanup(key string) (*activeFramingSession, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.framing == nil || s.framing.key != key || s.framing.cleanupStarted {
		return s.framing, false
	}
	s.framing.cleanupStarted = true
	return s.framing, true
}

func (s *sessionRegistry) markFramingPipelineDone(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.framing == nil || s.framing.key != key || s.framing.pipelineFinished {
		return
	}
	s.framing.pipelineFinished = true
	close(s.framing.pipelineDone)
}

// finishFraming 原子释放取景会话与单活 reservation，并通知停止端点可以安全返回。
func (s *sessionRegistry) finishFraming(key string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.framing == nil || s.framing.key != key {
		return
	}
	if !s.framing.pipelineFinished {
		s.framing.pipelineFinished = true
		close(s.framing.pipelineDone)
	}
	done := s.framing.done
	s.framing.devicesReady = true
	s.lastFraming = s.framing
	s.framing = nil
	if s.reservation == reservationFraming {
		s.reservation = ""
	}
	close(done)
}

// tryReserveBareStop 实现裸 SCAN_STOP 的特殊互斥语义：
// 正式扫描不得绕过 /{id}/stop；取景无 job id，保留管理员紧急停止通道。
func (s *sessionRegistry) tryReserveBareStop() (acquired, allowed bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.active) > 0 || s.reservation == reservationFormalScan {
		return false, false
	}
	switch s.reservation {
	case "":
		s.reservation = reservationBareStop
		return true, true
	case reservationFraming:
		return false, true
	default:
		return false, false
	}
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

func (h *Handler) activeStationIPs(r *http.Request) (string, string, bool) {
	return h.stationUnitIPs(r.URL.Query().Get("unit_a_ip"), r.URL.Query().Get("unit_b_ip"))
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
