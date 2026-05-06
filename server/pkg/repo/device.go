// device 仓储 — Berxel 相机绑定 + 双摄标定参数版本化云同步（M-S3）。
//
// 数据模型：
//
//	users ─┬─< devices ─< device_calibrations
//	       │
//	       └ 一用户多设备；同 serial 全系统同一时刻至多 1 active（partial unique）；
//	         calibration 不可变历史，version 自 devices.calibration_seq FOR UPDATE 取 +1。
//
// 设计参见 docs/architecture/server/00-server-overview.md §6.x。
package repo

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ============================================================================
// Device
// ============================================================================

type Device struct {
	ID              int64
	UserID          int64
	SerialNumber    string
	Manufacturer    string
	Model           string
	FirmwareVersion string
	SDKVersion      *string
	Nickname        *string
	Status          string // active / retired
	LastSeenAt      *time.Time
	CalibrationSeq  int64
	Note            *string
	CreatedAt       time.Time
	UpdatedAt       time.Time
	RetiredAt       *time.Time
}

type DeviceRepo struct {
	pool *pgxpool.Pool
}

func NewDeviceRepo(pool *pgxpool.Pool) *DeviceRepo { return &DeviceRepo{pool: pool} }

// Bind 绑定设备：
//   - 同 serial 自己的 active 设备：返已有记录 + isNew=false（幂等；调用方可再 Patch 更新 firmware）
//   - 同 serial 别人的 active 设备：ErrConflict（老主人需先 retire）
//   - 否则新建 active 行
func (r *DeviceRepo) Bind(ctx context.Context, d *Device) (out *Device, isNew bool, err error) {
	d.SerialNumber = strings.TrimSpace(d.SerialNumber)
	d.FirmwareVersion = strings.TrimSpace(d.FirmwareVersion)
	d.Model = strings.TrimSpace(d.Model)
	if d.Manufacturer == "" {
		d.Manufacturer = "berxel"
	}
	if d.SerialNumber == "" || d.FirmwareVersion == "" || d.Model == "" || d.UserID <= 0 {
		return nil, false, ErrFieldRange
	}

	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return nil, false, err
	}
	defer tx.Rollback(ctx)

	// 查同 serial active 是否已存在
	var existingUser int64
	var existingID int64
	err = tx.QueryRow(ctx, `
		SELECT id, user_id FROM devices
		WHERE serial_number=$1 AND status='active' FOR UPDATE
	`, d.SerialNumber).Scan(&existingID, &existingUser)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		// 新建
	case err != nil:
		return nil, false, err
	default:
		if existingUser != d.UserID {
			return nil, false, ErrConflict
		}
		// 同用户同 serial → 幂等：返已有
		var got Device
		if e := tx.QueryRow(ctx, deviceSelectByID, existingID).Scan(deviceScanArgs(&got)...); e != nil {
			return nil, false, e
		}
		if e := tx.Commit(ctx); e != nil {
			return nil, false, e
		}
		return &got, false, nil
	}

	row := tx.QueryRow(ctx, `
		INSERT INTO devices (user_id, serial_number, manufacturer, model,
		                     firmware_version, sdk_version, nickname, note)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
		RETURNING `+deviceCols, d.UserID, d.SerialNumber, d.Manufacturer, d.Model,
		d.FirmwareVersion, d.SDKVersion, d.Nickname, d.Note)
	var got Device
	if err := row.Scan(deviceScanArgs(&got)...); err != nil {
		if pgErr, ok := isPgError(err, "23505"); ok && pgErr.ConstraintName == "uq_devices_serial_active" {
			return nil, false, ErrConflict
		}
		if _, ok := isPgError(err, "23514"); ok {
			return nil, false, ErrFieldRange
		}
		return nil, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, false, err
	}
	return &got, true, nil
}

// FindByID 取设备（不验所有权 — 调用方 handler 自己判 user 一致）
func (r *DeviceRepo) FindByID(ctx context.Context, id int64) (*Device, error) {
	var d Device
	err := r.pool.QueryRow(ctx, deviceSelectByID, id).Scan(deviceScanArgs(&d)...)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &d, nil
}

// ListByUser 列某用户全部设备（active + retired），按 created_at desc。
func (r *DeviceRepo) ListByUser(ctx context.Context, userID int64) ([]Device, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT `+deviceCols+` FROM devices
		WHERE user_id=$1 ORDER BY status='active' DESC, created_at DESC
	`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		var d Device
		if err := rows.Scan(deviceScanArgs(&d)...); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// DevicePatch 改 nickname / firmware / sdk / note；只允许 active 设备改。
type DevicePatch struct {
	Nickname        *string
	FirmwareVersion *string
	SDKVersion      *string
	Note            *string
}

func (r *DeviceRepo) Patch(ctx context.Context, id int64, p DevicePatch) (*Device, error) {
	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)

	var status string
	if err := tx.QueryRow(ctx, `SELECT status FROM devices WHERE id=$1 FOR UPDATE`, id).Scan(&status); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	if status != "active" {
		return nil, ErrStateConflict
	}

	if _, err := tx.Exec(ctx, `
		UPDATE devices SET
			nickname         = COALESCE($1, nickname),
			firmware_version = COALESCE($2, firmware_version),
			sdk_version      = COALESCE($3, sdk_version),
			note             = COALESCE($4, note)
		WHERE id=$5
	`, p.Nickname, p.FirmwareVersion, p.SDKVersion, p.Note, id); err != nil {
		return nil, err
	}

	var d Device
	if err := tx.QueryRow(ctx, deviceSelectByID, id).Scan(deviceScanArgs(&d)...); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return &d, nil
}

// TouchLastSeen 端侧扫描启动时打活心跳；仅 active 设备生效。
func (r *DeviceRepo) TouchLastSeen(ctx context.Context, id int64) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE devices SET last_seen_at = now()
		WHERE id=$1 AND status='active'
	`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// Retire 解绑 / 退役：同 serial 之后可被新用户重新绑定。
func (r *DeviceRepo) Retire(ctx context.Context, id int64) error {
	tag, err := r.pool.Exec(ctx, `
		UPDATE devices SET status='retired', retired_at=now()
		WHERE id=$1 AND status='active'
	`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		// 不存在或已 retired
		var status string
		err := r.pool.QueryRow(ctx, `SELECT status FROM devices WHERE id=$1`, id).Scan(&status)
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

const deviceCols = `id, user_id, serial_number, manufacturer, model,
	firmware_version, sdk_version, nickname, status, last_seen_at,
	calibration_seq, note, created_at, updated_at, retired_at`

const deviceSelectByID = `SELECT ` + deviceCols + ` FROM devices WHERE id=$1`

func deviceScanArgs(d *Device) []any {
	return []any{
		&d.ID, &d.UserID, &d.SerialNumber, &d.Manufacturer, &d.Model,
		&d.FirmwareVersion, &d.SDKVersion, &d.Nickname, &d.Status, &d.LastSeenAt,
		&d.CalibrationSeq, &d.Note, &d.CreatedAt, &d.UpdatedAt, &d.RetiredAt,
	}
}

// ============================================================================
// DeviceCalibration
// ============================================================================

type DeviceCalibration struct {
	ID                int64
	DeviceID          int64
	Version           int64
	Params            json.RawMessage
	SHA256            string
	ReprojectionError *float32
	CalibratedAt      time.Time
	UploadedAt        time.Time
	Note              *string
}

type DeviceCalibrationRepo struct {
	pool *pgxpool.Pool
}

func NewDeviceCalibrationRepo(pool *pgxpool.Pool) *DeviceCalibrationRepo {
	return &DeviceCalibrationRepo{pool: pool}
}

// Insert 写一份新标定（事务内 FOR UPDATE 拿 calibration_seq +1）。
//
// 幂等：同 device_id 同 sha256 已存在 → 返已有 + isNew=false，不 bump version。
func (r *DeviceCalibrationRepo) Insert(ctx context.Context, c *DeviceCalibration) (out *DeviceCalibration, isNew bool, err error) {
	if c.DeviceID <= 0 || len(c.Params) == 0 || c.SHA256 == "" || c.CalibratedAt.IsZero() {
		return nil, false, ErrFieldRange
	}

	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, false, err
	}
	defer tx.Rollback(ctx)

	// 锁 devices 行 — 拿 calibration_seq
	var seq int64
	var status string
	if err := tx.QueryRow(ctx, `
		SELECT calibration_seq, status FROM devices WHERE id=$1 FOR UPDATE
	`, c.DeviceID).Scan(&seq, &status); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, false, ErrNotFound
		}
		return nil, false, err
	}
	if status != "active" {
		return nil, false, ErrStateConflict
	}

	// 幂等：同 sha256 已存在 → 返已有
	var existing DeviceCalibration
	err = tx.QueryRow(ctx, `
		SELECT `+calCols+` FROM device_calibrations
		WHERE device_id=$1 AND sha256=$2
	`, c.DeviceID, c.SHA256).Scan(calScanArgs(&existing)...)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		// 落新行
	case err != nil:
		return nil, false, err
	default:
		if e := tx.Commit(ctx); e != nil {
			return nil, false, e
		}
		return &existing, false, nil
	}

	newVer := seq + 1
	row := tx.QueryRow(ctx, `
		INSERT INTO device_calibrations
		    (device_id, version, params, sha256, reprojection_error, calibrated_at, note)
		VALUES ($1,$2,$3,$4,$5,$6,$7)
		RETURNING `+calCols,
		c.DeviceID, newVer, c.Params, c.SHA256, c.ReprojectionError, c.CalibratedAt, c.Note)
	var got DeviceCalibration
	if err := row.Scan(calScanArgs(&got)...); err != nil {
		if _, ok := isPgError(err, "23505"); ok {
			// 极端 race（同 sha256 在 phantom 间）— 已 FOR UPDATE 应不可达；保险 ErrConflict
			return nil, false, ErrConflict
		}
		if _, ok := isPgError(err, "23514"); ok {
			return nil, false, ErrFieldRange
		}
		return nil, false, err
	}

	if _, err := tx.Exec(ctx, `UPDATE devices SET calibration_seq=$1 WHERE id=$2`, newVer, c.DeviceID); err != nil {
		return nil, false, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, false, err
	}
	return &got, true, nil
}

// FindLatest 拿最新版本（按 version DESC）；没标定过返 ErrNotFound。
func (r *DeviceCalibrationRepo) FindLatest(ctx context.Context, deviceID int64) (*DeviceCalibration, error) {
	var c DeviceCalibration
	err := r.pool.QueryRow(ctx, `
		SELECT `+calCols+` FROM device_calibrations
		WHERE device_id=$1 ORDER BY version DESC LIMIT 1
	`, deviceID).Scan(calScanArgs(&c)...)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &c, nil
}

// FindByVersion 取指定 version。
func (r *DeviceCalibrationRepo) FindByVersion(ctx context.Context, deviceID, version int64) (*DeviceCalibration, error) {
	var c DeviceCalibration
	err := r.pool.QueryRow(ctx, `
		SELECT `+calCols+` FROM device_calibrations WHERE device_id=$1 AND version=$2
	`, deviceID, version).Scan(calScanArgs(&c)...)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return &c, nil
}

// ListByDevice 列设备所有标定历史，version DESC。
func (r *DeviceCalibrationRepo) ListByDevice(ctx context.Context, deviceID int64) ([]DeviceCalibration, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT `+calCols+` FROM device_calibrations
		WHERE device_id=$1 ORDER BY version DESC
	`, deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DeviceCalibration
	for rows.Next() {
		var c DeviceCalibration
		if err := rows.Scan(calScanArgs(&c)...); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

const calCols = `id, device_id, version, params, sha256, reprojection_error,
	calibrated_at, uploaded_at, note`

func calScanArgs(c *DeviceCalibration) []any {
	return []any{
		&c.ID, &c.DeviceID, &c.Version, &c.Params, &c.SHA256, &c.ReprojectionError,
		&c.CalibratedAt, &c.UploadedAt, &c.Note,
	}
}
