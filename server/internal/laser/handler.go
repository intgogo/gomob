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

	reader CloudReader // PCD 下载（可空 → 下载端点 501）

	// 可注入点（默认指向真实现）。
	probe   Prober
	newGate func(ipA, ipB string) DeviceGate
	launch  func(func()) // 后台执行扫描；默认 go f()，测试可改同步
}

// SetCloudReader 注入 PCD 下载读取器（laserworker 用同一 MinIOCloudStore 实例）。
func (h *Handler) SetCloudReader(r CloudReader) { h.reader = r }

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
		newGate:  func(a, b string) DeviceGate { return NewDevctlGate(a, b, log) },
		launch:   func(f func()) { go f() },
	}
}

// Mount 注册路由。
func (h *Handler) Mount(mux *http.ServeMux) {
	mux.HandleFunc("POST /v1/scans/laser", h.StartScan)
	mux.HandleFunc("POST /v1/scans/laser/{id}/stop", h.StopScan)
	mux.HandleFunc("GET /v1/scans/laser/{id}", h.GetScan)
	// PCD 下载（融合 414万点不走 ws，经此流式取；name 白名单从 job 取 object key，零路径穿越）。
	mux.HandleFunc("GET /v1/scans/laser/{id}/cloud/{name}", h.DownloadCloud)
}

// --- 请求/响应体 ---

type startReq struct {
	InspectionID *int64   `json:"inspection_id"`
	UnitAIP      string   `json:"unit_a_ip"`
	UnitBIP      string   `json:"unit_b_ip"`
	Align        string   `json:"align"`
	KeepRatio    *float32 `json:"keep_ratio"`
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
	spec := RunSpec{
		JobID:        job.ID,
		SessionKey:   sessionKey,
		InspectionID: req.InspectionID,
		OwnerUserID:  &owner,
		UnitAIP:      ipA,
		UnitBIP:      ipB,
		Align:        align,
		KeepRatio:    keep,
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
