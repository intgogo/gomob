// device 服务 HTTP handler — 详见 doc.go。
//
// 路径：
//
//	# App
//	POST   /v1/devices                                       绑定（同 user 同 serial 幂等）
//	GET    /v1/devices                                       自己的设备列表
//	GET    /v1/devices/{id}                                  详情（仅自己的）
//	PATCH  /v1/devices/{id}                                  改 nickname / firmware / sdk / note
//	POST   /v1/devices/{id}/touch                            扫描启动心跳 → last_seen_at
//	POST   /v1/devices/{id}/retire                           退役（同 serial 之后能转给其他用户）
//	POST   /v1/devices/{id}/calibrations                     上传新标定（version 自增 / sha256 幂等）
//	GET    /v1/devices/{id}/calibrations                     列所有版本（version DESC）
//	GET    /v1/devices/{id}/calibrations/latest              最新版本
//	GET    /v1/devices/{id}/calibrations/{version}           指定版本
//
//	# admin（管理员能跨用户查任何设备 / 标定 — 用于排障）
//	GET    /admin/v1/devices                                 全表分页（最近 200）
//	GET    /admin/v1/devices/{id}                            详情（任意 user）
//	GET    /admin/v1/devices/{id}/calibrations               历史
package device

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"io.gomob/server/pkg/audit"
	"io.gomob/server/pkg/httpx"
	"io.gomob/server/pkg/logger"
	"io.gomob/server/pkg/repo"
)

type Handler struct {
	pool   *pgxpool.Pool
	devRepo *repo.DeviceRepo
	calRepo *repo.DeviceCalibrationRepo
	audit  audit.Recorder
	log    *slog.Logger
}

func NewHandler(pool *pgxpool.Pool, auditRec audit.Recorder) *Handler {
	return &Handler{
		pool:    pool,
		devRepo: repo.NewDeviceRepo(pool),
		calRepo: repo.NewDeviceCalibrationRepo(pool),
		audit:   auditRec,
		log:     logger.New("device.handler"),
	}
}

func (h *Handler) Mount(mux *http.ServeMux) {
	// App
	mux.HandleFunc("POST /v1/devices", h.Bind)
	mux.HandleFunc("GET /v1/devices", h.ListMine)
	mux.HandleFunc("GET /v1/devices/{id}", h.Get)
	mux.HandleFunc("PATCH /v1/devices/{id}", h.Patch)
	mux.HandleFunc("POST /v1/devices/{id}/touch", h.Touch)
	mux.HandleFunc("POST /v1/devices/{id}/retire", h.Retire)
	mux.HandleFunc("POST /v1/devices/{id}/calibrations", h.UploadCalibration)
	mux.HandleFunc("GET /v1/devices/{id}/calibrations", h.ListCalibrations)
	mux.HandleFunc("GET /v1/devices/{id}/calibrations/latest", h.LatestCalibration)
	mux.HandleFunc("GET /v1/devices/{id}/calibrations/{version}", h.CalibrationByVersion)

	// admin
	mux.HandleFunc("GET /admin/v1/devices", h.AdminList)
	mux.HandleFunc("GET /admin/v1/devices/{id}", h.AdminGet)
	mux.HandleFunc("GET /admin/v1/devices/{id}/calibrations", h.AdminListCalibrations)
}

// ============================================================================
// 工具
// ============================================================================

func callerUserID(r *http.Request) int64 {
	v := r.Header.Get("X-Gomob-User-Id")
	if v == "" {
		return 0
	}
	id, _ := strconv.ParseInt(v, 10, 64)
	return id
}

func callerRoles(r *http.Request) []string {
	raw := r.Header.Get("X-Gomob-Roles")
	if raw == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		out = append(out, strings.TrimSpace(p))
	}
	return out
}

func isAdmin(r *http.Request) bool {
	for _, role := range callerRoles(r) {
		if role == "admin" {
			return true
		}
	}
	return false
}

func parseInt64Path(r *http.Request, key string) (int64, bool) {
	v := r.PathValue(key)
	if v == "" {
		return 0, false
	}
	id, err := strconv.ParseInt(v, 10, 64)
	if err != nil || id <= 0 {
		return 0, false
	}
	return id, true
}

// loadOwnedDevice 取设备并验所有权（admin 跳过验权）。
func (h *Handler) loadOwnedDevice(r *http.Request, id, callerID int64) (*repo.Device, error) {
	d, err := h.devRepo.FindByID(r.Context(), id)
	if err != nil {
		return nil, err
	}
	if !isAdmin(r) && d.UserID != callerID {
		return nil, repo.ErrNotFound // 屏蔽存在性
	}
	return d, nil
}

// requireUser 公共校验：必须有 X-Gomob-User-Id 头（gateway 已注入）。
func requireUser(r *http.Request) (int64, bool) {
	uid := callerUserID(r)
	if uid <= 0 {
		return 0, false
	}
	return uid, true
}

// auditCtx 独立超时 ctx，避免请求 cancel 时审计写丢。
func auditCtx() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), 3*time.Second)
}

// ============================================================================
// DTO
// ============================================================================

type deviceDTO struct {
	ID              string  `json:"id"`
	UserID          string  `json:"user_id"`
	SerialNumber    string  `json:"serial_number"`
	Manufacturer    string  `json:"manufacturer"`
	Model           string  `json:"model"`
	FirmwareVersion string  `json:"firmware_version"`
	SDKVersion      *string `json:"sdk_version,omitempty"`
	Nickname        *string `json:"nickname,omitempty"`
	Status          string  `json:"status"`
	LastSeenAt      *string `json:"last_seen_at,omitempty"`
	CalibrationSeq  int64   `json:"calibration_seq"`
	Note            *string `json:"note,omitempty"`
	CreatedAt       string  `json:"created_at"`
	UpdatedAt       string  `json:"updated_at"`
	RetiredAt       *string `json:"retired_at,omitempty"`
}

func toDeviceDTO(d *repo.Device) deviceDTO {
	out := deviceDTO{
		ID:              strconv.FormatInt(d.ID, 10),
		UserID:          strconv.FormatInt(d.UserID, 10),
		SerialNumber:    d.SerialNumber,
		Manufacturer:    d.Manufacturer,
		Model:           d.Model,
		FirmwareVersion: d.FirmwareVersion,
		SDKVersion:      d.SDKVersion,
		Nickname:        d.Nickname,
		Status:          d.Status,
		CalibrationSeq:  d.CalibrationSeq,
		Note:            d.Note,
		CreatedAt:       d.CreatedAt.UTC().Format(time.RFC3339Nano),
		UpdatedAt:       d.UpdatedAt.UTC().Format(time.RFC3339Nano),
	}
	if d.LastSeenAt != nil {
		v := d.LastSeenAt.UTC().Format(time.RFC3339Nano)
		out.LastSeenAt = &v
	}
	if d.RetiredAt != nil {
		v := d.RetiredAt.UTC().Format(time.RFC3339Nano)
		out.RetiredAt = &v
	}
	return out
}

type calibrationDTO struct {
	ID                string          `json:"id"`
	DeviceID          string          `json:"device_id"`
	Version           int64           `json:"version"`
	Params            json.RawMessage `json:"params,omitempty"`
	SHA256            string          `json:"sha256"`
	ReprojectionError *float32        `json:"reprojection_error,omitempty"`
	CalibratedAt      string          `json:"calibrated_at"`
	UploadedAt        string          `json:"uploaded_at"`
	Note              *string         `json:"note,omitempty"`
}

func toCalibrationDTO(c *repo.DeviceCalibration, includeParams bool) calibrationDTO {
	out := calibrationDTO{
		ID:                strconv.FormatInt(c.ID, 10),
		DeviceID:          strconv.FormatInt(c.DeviceID, 10),
		Version:           c.Version,
		SHA256:            c.SHA256,
		ReprojectionError: c.ReprojectionError,
		CalibratedAt:      c.CalibratedAt.UTC().Format(time.RFC3339Nano),
		UploadedAt:        c.UploadedAt.UTC().Format(time.RFC3339Nano),
		Note:              c.Note,
	}
	if includeParams {
		out.Params = c.Params
	}
	return out
}

// ============================================================================
// 设备绑定 / 列表 / 详情 / patch
// ============================================================================

type bindReq struct {
	SerialNumber    string  `json:"serial_number"`
	Manufacturer    string  `json:"manufacturer"`
	Model           string  `json:"model"`
	FirmwareVersion string  `json:"firmware_version"`
	SDKVersion      *string `json:"sdk_version"`
	Nickname        *string `json:"nickname"`
	Note            *string `json:"note"`
}

func (h *Handler) Bind(w http.ResponseWriter, r *http.Request) {
	uid, ok := requireUser(r)
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	var req bindReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	d := &repo.Device{
		UserID:          uid,
		SerialNumber:    strings.TrimSpace(req.SerialNumber),
		Manufacturer:    strings.TrimSpace(req.Manufacturer),
		Model:           strings.TrimSpace(req.Model),
		FirmwareVersion: strings.TrimSpace(req.FirmwareVersion),
		SDKVersion:      req.SDKVersion,
		Nickname:        req.Nickname,
		Note:            req.Note,
	}
	out, isNew, err := h.devRepo.Bind(r.Context(), d)
	switch {
	case errors.Is(err, repo.ErrConflict):
		httpx.WriteError(w, httpx.NewError(40203, http.StatusConflict, "该设备序列号已被其他账号绑定"))
		return
	case errors.Is(err, repo.ErrFieldRange):
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	case err != nil:
		h.log.Error("bind device", "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if isNew {
		ac, cancel := auditCtx()
		defer cancel()
		afterJSON, _ := audit.Encode(toDeviceDTO(out))
		_ = h.audit.Record(ac, audit.Entry{
			UserID:   uid,
			Action:   "device.bind",
			Target:   "device:" + strconv.FormatInt(out.ID, 10),
			AfterRaw: afterJSON,
		})
	}
	httpx.WriteJSON(w, http.StatusOK, httpx.Envelope{Data: map[string]any{
		"device":  toDeviceDTO(out),
		"is_new":  isNew,
	}})
}

func (h *Handler) ListMine(w http.ResponseWriter, r *http.Request) {
	uid, ok := requireUser(r)
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	list, err := h.devRepo.ListByUser(r.Context(), uid)
	if err != nil {
		h.log.Error("list mine", "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	out := make([]deviceDTO, 0, len(list))
	for i := range list {
		out = append(out, toDeviceDTO(&list[i]))
	}
	httpx.OK(w, map[string]any{"items": out, "total": len(out)})
}

func (h *Handler) Get(w http.ResponseWriter, r *http.Request) {
	uid, ok := requireUser(r)
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	id, ok := parseInt64Path(r, "id")
	if !ok {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	d, err := h.loadOwnedDevice(r, id, uid)
	switch {
	case errors.Is(err, repo.ErrNotFound):
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	case err != nil:
		h.log.Error("get device", "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	httpx.OK(w, map[string]any{"device": toDeviceDTO(d)})
}

type patchReq struct {
	Nickname        *string `json:"nickname"`
	FirmwareVersion *string `json:"firmware_version"`
	SDKVersion      *string `json:"sdk_version"`
	Note            *string `json:"note"`
}

func (h *Handler) Patch(w http.ResponseWriter, r *http.Request) {
	uid, ok := requireUser(r)
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	id, ok := parseInt64Path(r, "id")
	if !ok {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, err := h.loadOwnedDevice(r, id, uid)
	switch {
	case errors.Is(err, repo.ErrNotFound):
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	case err != nil:
		h.log.Error("load device", "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	var req patchReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	out, err := h.devRepo.Patch(r.Context(), id, repo.DevicePatch{
		Nickname:        req.Nickname,
		FirmwareVersion: req.FirmwareVersion,
		SDKVersion:      req.SDKVersion,
		Note:            req.Note,
	})
	switch {
	case errors.Is(err, repo.ErrNotFound):
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	case errors.Is(err, repo.ErrStateConflict):
		httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "设备已退役，不能再修改"))
		return
	case err != nil:
		h.log.Error("patch device", "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	ac, cancel := auditCtx()
	defer cancel()
	beforeJSON, _ := audit.Encode(toDeviceDTO(before))
	afterJSON, _ := audit.Encode(toDeviceDTO(out))
	_ = h.audit.Record(ac, audit.Entry{
		UserID:    uid,
		Action:    "device.patch",
		Target:    "device:" + strconv.FormatInt(out.ID, 10),
		BeforeRaw: beforeJSON,
		AfterRaw:  afterJSON,
	})
	httpx.OK(w, map[string]any{"device": toDeviceDTO(out)})
}

func (h *Handler) Touch(w http.ResponseWriter, r *http.Request) {
	uid, ok := requireUser(r)
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	id, ok := parseInt64Path(r, "id")
	if !ok {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if _, err := h.loadOwnedDevice(r, id, uid); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	if err := h.devRepo.TouchLastSeen(r.Context(), id); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			// 已 retired — 用 4040x 不开新错误码
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "设备已退役"))
		default:
			h.log.Error("touch", "err", err)
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	httpx.OK(w, map[string]any{"ok": true})
}

func (h *Handler) Retire(w http.ResponseWriter, r *http.Request) {
	uid, ok := requireUser(r)
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	id, ok := parseInt64Path(r, "id")
	if !ok {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	before, err := h.loadOwnedDevice(r, id, uid)
	switch {
	case errors.Is(err, repo.ErrNotFound):
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	case err != nil:
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	if err := h.devRepo.Retire(r.Context(), id); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		case errors.Is(err, repo.ErrStateConflict):
			httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "设备已退役"))
		default:
			h.log.Error("retire", "err", err)
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	ac, cancel := auditCtx()
	defer cancel()
	beforeJSON, _ := audit.Encode(toDeviceDTO(before))
	_ = h.audit.Record(ac, audit.Entry{
		UserID:    uid,
		Action:    "device.retire",
		Target:    "device:" + strconv.FormatInt(id, 10),
		BeforeRaw: beforeJSON,
	})
	httpx.OK(w, map[string]any{"ok": true})
}

// ============================================================================
// 标定参数
// ============================================================================

type uploadCalReq struct {
	Params            json.RawMessage `json:"params"`
	SHA256            string          `json:"sha256"`
	ReprojectionError *float32        `json:"reprojection_error"`
	CalibratedAt      string          `json:"calibrated_at"` // RFC3339
	Note              *string         `json:"note"`
}

func (h *Handler) UploadCalibration(w http.ResponseWriter, r *http.Request) {
	uid, ok := requireUser(r)
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	id, ok := parseInt64Path(r, "id")
	if !ok {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if _, err := h.loadOwnedDevice(r, id, uid); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}

	var req uploadCalReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if len(req.Params) == 0 || req.SHA256 == "" || req.CalibratedAt == "" {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	calT, err := time.Parse(time.RFC3339, req.CalibratedAt)
	if err != nil {
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	}

	c := &repo.DeviceCalibration{
		DeviceID:          id,
		Params:            req.Params,
		SHA256:            req.SHA256,
		ReprojectionError: req.ReprojectionError,
		CalibratedAt:      calT,
		Note:              req.Note,
	}
	out, isNew, err := h.calRepo.Insert(r.Context(), c)
	switch {
	case errors.Is(err, repo.ErrNotFound):
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	case errors.Is(err, repo.ErrStateConflict):
		httpx.WriteError(w, httpx.NewError(40401, http.StatusConflict, "设备已退役，不能再上传标定"))
		return
	case errors.Is(err, repo.ErrFieldRange):
		httpx.WriteError(w, httpx.ErrFieldRange)
		return
	case err != nil:
		h.log.Error("upload calibration", "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	if isNew {
		ac, cancel := auditCtx()
		defer cancel()
		// 不记 params 全文（可能很大）— 只记 version + sha256
		afterJSON, _ := audit.Encode(map[string]any{
			"version": out.Version,
			"sha256":  out.SHA256,
		})
		_ = h.audit.Record(ac, audit.Entry{
			UserID:   uid,
			Action:   "device.calibration_upload",
			Target:   "device:" + strconv.FormatInt(id, 10),
			AfterRaw: afterJSON,
		})
	}
	httpx.OK(w, map[string]any{
		"calibration": toCalibrationDTO(out, true),
		"is_new":      isNew,
	})
}

func (h *Handler) ListCalibrations(w http.ResponseWriter, r *http.Request) {
	uid, ok := requireUser(r)
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	id, ok := parseInt64Path(r, "id")
	if !ok {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if _, err := h.loadOwnedDevice(r, id, uid); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	list, err := h.calRepo.ListByDevice(r.Context(), id)
	if err != nil {
		h.log.Error("list calibrations", "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	includeParams := r.URL.Query().Get("include_params") == "true"
	out := make([]calibrationDTO, 0, len(list))
	for i := range list {
		out = append(out, toCalibrationDTO(&list[i], includeParams))
	}
	httpx.OK(w, map[string]any{"items": out, "total": len(out)})
}

func (h *Handler) LatestCalibration(w http.ResponseWriter, r *http.Request) {
	uid, ok := requireUser(r)
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	id, ok := parseInt64Path(r, "id")
	if !ok {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if _, err := h.loadOwnedDevice(r, id, uid); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	c, err := h.calRepo.FindLatest(r.Context(), id)
	switch {
	case errors.Is(err, repo.ErrNotFound):
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	case err != nil:
		h.log.Error("latest calibration", "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	includeParams := r.URL.Query().Get("include_params") != "false" // 默认带 params
	httpx.OK(w, map[string]any{"calibration": toCalibrationDTO(c, includeParams)})
}

func (h *Handler) CalibrationByVersion(w http.ResponseWriter, r *http.Request) {
	uid, ok := requireUser(r)
	if !ok {
		httpx.WriteError(w, httpx.ErrTokenInvalid)
		return
	}
	id, ok := parseInt64Path(r, "id")
	if !ok {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	verStr := r.PathValue("version")
	ver, err := strconv.ParseInt(verStr, 10, 64)
	if err != nil || ver <= 0 {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if _, err := h.loadOwnedDevice(r, id, uid); err != nil {
		switch {
		case errors.Is(err, repo.ErrNotFound):
			httpx.WriteError(w, httpx.ErrNotFound)
		default:
			httpx.WriteError(w, httpx.ErrInternal)
		}
		return
	}
	c, err := h.calRepo.FindByVersion(r.Context(), id, ver)
	switch {
	case errors.Is(err, repo.ErrNotFound):
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	case err != nil:
		h.log.Error("calibration by version", "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	httpx.OK(w, map[string]any{"calibration": toCalibrationDTO(c, true)})
}

// ============================================================================
// admin
// ============================================================================

// adminDeviceCols 与 pkg/repo/device.go 的 deviceCols 列序一致（该常量在 repo 包未导出，
// 此处显式镜像；列序变更需两处同步）。
const adminDeviceCols = `id, user_id, serial_number, manufacturer, model,
	firmware_version, sdk_version, nickname, status, last_seen_at,
	calibration_seq, note, created_at, updated_at, retired_at`

const adminListPageSize = 100

func (h *Handler) AdminList(w http.ResponseWriter, r *http.Request) {
	if !isAdmin(r) {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}

	limit := adminListPageSize
	if v := strings.TrimSpace(r.URL.Query().Get("limit")); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 && n <= adminListPageSize {
			limit = n
		}
	}

	// 游标分页：keyset on (created_at, id) 与 ORDER BY created_at DESC, id DESC 对齐，
	// 比 OFFSET 稳定（插入/删除不漏不重）。cursor = base64("<rfc3339nano>|<id>")。
	cursorTS, cursorID, hasCursor, err := decodeAdminCursor(r.URL.Query().Get("cursor"))
	if err != nil {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}

	// 单次查询取全部列，消除 N+1（原来每行再 FindByID 一次）。多取 1 行用于判断是否有下一页。
	var (
		rows pgx.Rows
		qErr error
	)
	if hasCursor {
		rows, qErr = h.pool.Query(r.Context(), `
			SELECT `+adminDeviceCols+` FROM devices
			WHERE (created_at, id) < ($1, $2)
			ORDER BY created_at DESC, id DESC
			LIMIT $3
		`, cursorTS, cursorID, limit+1)
	} else {
		rows, qErr = h.pool.Query(r.Context(), `
			SELECT `+adminDeviceCols+` FROM devices
			ORDER BY created_at DESC, id DESC
			LIMIT $1
		`, limit+1)
	}
	if qErr != nil {
		h.log.Error("admin list query", "err", qErr)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	defer rows.Close()

	out := make([]deviceDTO, 0, limit)
	var lastTS time.Time
	var lastID int64
	for rows.Next() {
		var d repo.Device
		// 扫描顺序严格对应 adminDeviceCols。
		if err := rows.Scan(
			&d.ID, &d.UserID, &d.SerialNumber, &d.Manufacturer, &d.Model,
			&d.FirmwareVersion, &d.SDKVersion, &d.Nickname, &d.Status, &d.LastSeenAt,
			&d.CalibrationSeq, &d.Note, &d.CreatedAt, &d.UpdatedAt, &d.RetiredAt,
		); err != nil {
			// 不静默丢行：扫描失败说明数据/列序异常，直接报错而非跳过。
			h.log.Error("admin list scan", "err", err)
			httpx.WriteError(w, httpx.ErrInternal)
			return
		}
		out = append(out, toDeviceDTO(&d))
		lastTS, lastID = d.CreatedAt, d.ID
	}
	if err := rows.Err(); err != nil {
		h.log.Error("admin list rows", "err", err)
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}

	var nextCursor string
	if len(out) > limit {
		// 多取的那 1 行只用来判定有无下一页；下一页游标取本页最后一行。
		out = out[:limit]
		last := out[limit-1]
		lastID, _ = strconv.ParseInt(last.ID, 10, 64)
		if t, perr := time.Parse(time.RFC3339Nano, last.CreatedAt); perr == nil {
			lastTS = t
		}
		nextCursor = encodeAdminCursor(lastTS, lastID)
	}

	resp := map[string]any{"items": out, "total": len(out)}
	if nextCursor != "" {
		resp["next_cursor"] = nextCursor
	}
	httpx.OK(w, resp)
}

// encodeAdminCursor 把 (created_at, id) 编为 URL-safe base64 游标。
func encodeAdminCursor(ts time.Time, id int64) string {
	raw := ts.UTC().Format(time.RFC3339Nano) + "|" + strconv.FormatInt(id, 10)
	return base64.RawURLEncoding.EncodeToString([]byte(raw))
}

// decodeAdminCursor 解析游标；空串 → hasCursor=false；格式错 → error。
func decodeAdminCursor(s string) (ts time.Time, id int64, hasCursor bool, err error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return time.Time{}, 0, false, nil
	}
	raw, derr := base64.RawURLEncoding.DecodeString(s)
	if derr != nil {
		return time.Time{}, 0, false, derr
	}
	parts := strings.SplitN(string(raw), "|", 2)
	if len(parts) != 2 {
		return time.Time{}, 0, false, errors.New("游标格式错误")
	}
	ts, err = time.Parse(time.RFC3339Nano, parts[0])
	if err != nil {
		return time.Time{}, 0, false, err
	}
	id, err = strconv.ParseInt(parts[1], 10, 64)
	if err != nil {
		return time.Time{}, 0, false, err
	}
	return ts, id, true, nil
}

func (h *Handler) AdminGet(w http.ResponseWriter, r *http.Request) {
	if !isAdmin(r) {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	id, ok := parseInt64Path(r, "id")
	if !ok {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	d, err := h.devRepo.FindByID(r.Context(), id)
	switch {
	case errors.Is(err, repo.ErrNotFound):
		httpx.WriteError(w, httpx.ErrNotFound)
		return
	case err != nil:
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	httpx.OK(w, map[string]any{"device": toDeviceDTO(d)})
}

func (h *Handler) AdminListCalibrations(w http.ResponseWriter, r *http.Request) {
	if !isAdmin(r) {
		httpx.WriteError(w, httpx.ErrPermDenied)
		return
	}
	id, ok := parseInt64Path(r, "id")
	if !ok {
		httpx.WriteError(w, httpx.ErrBadParam)
		return
	}
	if _, err := h.devRepo.FindByID(r.Context(), id); err != nil {
		if errors.Is(err, repo.ErrNotFound) {
			httpx.WriteError(w, httpx.ErrNotFound)
			return
		}
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	list, err := h.calRepo.ListByDevice(r.Context(), id)
	if err != nil {
		httpx.WriteError(w, httpx.ErrInternal)
		return
	}
	includeParams := r.URL.Query().Get("include_params") == "true"
	out := make([]calibrationDTO, 0, len(list))
	for i := range list {
		out = append(out, toCalibrationDTO(&list[i], includeParams))
	}
	httpx.OK(w, map[string]any{"items": out, "total": len(out)})
}
