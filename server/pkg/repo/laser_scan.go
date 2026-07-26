package repo

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// laser_scan_jobs 状态机（请求驱动，非轮询队列；见 migration 0018 注释）。
const (
	LaserScanStatusCapturing = "capturing"
	LaserScanStatusFusing    = "fusing"
	LaserScanStatusDone      = "done"
	LaserScanStatusFailed    = "failed"
	LaserScanStatusCancelled = "cancelled"
)

// LaserScanJob 一次双单元激光车辆外廓扫描会话：两单元(.101/.102)采集 + ICP/site 融合，
// 产三朵 PCD（fused + unitA + unitB）+ 可选 calib，落 MinIO。
type LaserScanJob struct {
	ID                int64
	SessionKey        string
	InspectionID      *int64
	OwnerUserID       *int64 // 扫描发起者；scan.fusion_done(kind:laser) 实时推送路由键（可空）
	UnitAIP           string
	UnitBIP           string
	Align             string  // 请求的配准策略：icp|none|site
	AlignMethod       *string // 实际采用（icp 未收敛降级 none）
	KeepRatio         float32
	Status            string
	PtsA              *int
	PtsB              *int
	Fused             *int
	AfterCrop         *int
	FusedObjectKey    *string
	UnitAObjectKey    *string
	UnitBObjectKey    *string
	MeasuredObjectKey *string
	CalibObjectKey    *string
	BToA              json.RawMessage // 4x4 行优先 mm（可空）
	Stats             json.RawMessage
	ErrorMessage      *string
	CreatedAt         sql.NullTime
	UpdatedAt         sql.NullTime
}

// LaserScanCompletion 融合完成时回填的派生字段。
type LaserScanCompletion struct {
	AlignMethod       string
	PtsA              int
	PtsB              int
	Fused             int
	AfterCrop         int
	FusedObjectKey    string
	UnitAObjectKey    string
	UnitBObjectKey    string
	MeasuredObjectKey string
	CalibObjectKey    string // 可空 ""
	BToA              json.RawMessage
	Stats             json.RawMessage
}

type LaserScanRepo struct {
	pool *pgxpool.Pool
}

func NewLaserScanRepo(pool *pgxpool.Pool) *LaserScanRepo {
	return &LaserScanRepo{pool: pool}
}

// laserScanAllowedFrom 返回目标状态允许的唯一来源集合。
// 仓库写路径把该集合直接放进 UPDATE 的 WHERE 条件，依靠 PostgreSQL 行锁重检实现原子 CAS。
func laserScanAllowedFrom(to string) []string {
	switch to {
	case LaserScanStatusFusing:
		return []string{LaserScanStatusCapturing}
	case LaserScanStatusDone:
		return []string{LaserScanStatusFusing}
	case LaserScanStatusFailed, LaserScanStatusCancelled:
		return []string{LaserScanStatusCapturing, LaserScanStatusFusing}
	default:
		return nil
	}
}

func isLaserScanTransitionAllowed(from, to string) bool {
	for _, allowed := range laserScanAllowedFrom(to) {
		if from == allowed {
			return true
		}
	}
	return false
}

// laserScanCols 的列顺序必须与 scanLaserJob() 里 row.Scan(...) 接收参数顺序逐一对应。
// 任何重排/增删列都必须两处同步改，否则 Scan 会错位读字段。
const laserScanCols = `id, session_key, inspection_id, owner_user_id, unit_a_ip, unit_b_ip,
	align, align_method, keep_ratio, status, pts_a, pts_b, fused, after_crop,
	fused_object_key, unit_a_object_key, unit_b_object_key, measured_object_key, calib_object_key,
	b_to_a, stats, error_message, created_at, updated_at`

// Create 幂等建会话（capturing）：同 session_key 已存在则原样返回。
func (r *LaserScanRepo) Create(ctx context.Context, sessionKey, unitAIP, unitBIP, align string,
	keepRatio float32, inspectionID, ownerUserID *int64) (*LaserScanJob, error) {
	if align == "" {
		align = "site"
	}
	if keepRatio <= 0 || keepRatio > 1 {
		keepRatio = 1.0
	}
	job := &LaserScanJob{}
	err := scanLaserJob(r.pool.QueryRow(ctx, `
		INSERT INTO laser_scan_jobs(session_key, inspection_id, owner_user_id, unit_a_ip, unit_b_ip, align, keep_ratio, status)
		VALUES($1, $2, $3, $4, $5, $6, $7, 'capturing')
		ON CONFLICT (session_key) DO NOTHING
		RETURNING `+laserScanCols, sessionKey, inspectionID, ownerUserID, unitAIP, unitBIP, align, keepRatio), job)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return r.FindBySessionKey(ctx, sessionKey)
		}
		return nil, err
	}
	return job, nil
}

// MarkFusing capturing→fusing：采集完两单元、进配准融合阶段，回填原始点数。
func (r *LaserScanRepo) MarkFusing(ctx context.Context, id int64, ptsA, ptsB int) (*LaserScanJob, error) {
	allowedFrom := laserScanAllowedFrom(LaserScanStatusFusing)
	job := &LaserScanJob{}
	err := scanLaserJob(r.pool.QueryRow(ctx, `
		UPDATE laser_scan_jobs
		SET status='fusing', pts_a=$2, pts_b=$3, updated_at=now()
		WHERE id=$1 AND status=ANY($4::text[])
		RETURNING `+laserScanCols, id, ptsA, ptsB, allowedFrom), job)
	return oneLaser(job, err)
}

// Complete fusing→done：回填三朵 PCD object key + 融合统计 + 实际配准法。
func (r *LaserScanRepo) Complete(ctx context.Context, id int64, c LaserScanCompletion) (*LaserScanJob, error) {
	return completeLaserScan(ctx, r.pool, id, c)
}

type laserScanQueryRower interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

// completeLaserScan 允许普通完成路径和“完成任务 + 激活背景”的事务路径复用同一条 CAS。
func completeLaserScan(ctx context.Context, q laserScanQueryRower, id int64, c LaserScanCompletion) (*LaserScanJob, error) {
	allowedFrom := laserScanAllowedFrom(LaserScanStatusDone)
	stats := c.Stats
	if len(stats) == 0 {
		stats = json.RawMessage(`{}`)
	}
	var calib *string
	if c.CalibObjectKey != "" {
		calib = &c.CalibObjectKey
	}
	var measured *string
	if c.MeasuredObjectKey != "" {
		measured = &c.MeasuredObjectKey
	}
	var bToA []byte
	if len(c.BToA) > 0 {
		bToA = c.BToA
	}
	job := &LaserScanJob{}
	err := scanLaserJob(q.QueryRow(ctx, `
		UPDATE laser_scan_jobs
		SET status='done', align_method=$2, pts_a=$3, pts_b=$4, fused=$5, after_crop=$6,
		    fused_object_key=$7, unit_a_object_key=$8, unit_b_object_key=$9, measured_object_key=$10,
		    calib_object_key=$11, b_to_a=$12, stats=$13, error_message=NULL, updated_at=now()
		WHERE id=$1 AND status=ANY($14::text[])
		RETURNING `+laserScanCols, id, c.AlignMethod, c.PtsA, c.PtsB, c.Fused, c.AfterCrop,
		c.FusedObjectKey, c.UnitAObjectKey, c.UnitBObjectKey, measured, calib, bToA, stats, allowedFrom), job)
	return oneLaser(job, err)
}

// Fail capturing/fusing→failed：终态不可被迟到错误覆盖。
func (r *LaserScanRepo) Fail(ctx context.Context, id int64, message string) (*LaserScanJob, error) {
	allowedFrom := laserScanAllowedFrom(LaserScanStatusFailed)
	job := &LaserScanJob{}
	err := scanLaserJob(r.pool.QueryRow(ctx, `
		UPDATE laser_scan_jobs
		SET status='failed', error_message=$2, updated_at=now()
		WHERE id=$1 AND status=ANY($3::text[])
		RETURNING `+laserScanCols, id, message, allowedFrom), job)
	return oneLaser(job, err)
}

// Cancel 置 cancelled（用户 stop / 协作取消）。仅进行中(capturing/fusing)可取消。
func (r *LaserScanRepo) Cancel(ctx context.Context, id int64) (*LaserScanJob, error) {
	allowedFrom := laserScanAllowedFrom(LaserScanStatusCancelled)
	job := &LaserScanJob{}
	err := scanLaserJob(r.pool.QueryRow(ctx, `
		UPDATE laser_scan_jobs
		SET status='cancelled', updated_at=now()
		WHERE id=$1 AND status=ANY($2::text[])
		RETURNING `+laserScanCols, id, allowedFrom), job)
	return oneLaser(job, err)
}

func (r *LaserScanRepo) FindByID(ctx context.Context, id int64) (*LaserScanJob, error) {
	job := &LaserScanJob{}
	err := scanLaserJob(r.pool.QueryRow(ctx,
		`SELECT `+laserScanCols+` FROM laser_scan_jobs WHERE id=$1`, id), job)
	return oneLaser(job, err)
}

const findLatestLaserMeasurementsSQL = `SELECT ` + laserScanCols + ` FROM laser_scan_jobs
	WHERE unit_a_ip=$1 AND unit_b_ip=$2 AND status=$3
	  AND measured_object_key IS NOT NULL
	  AND length(btrim(measured_object_key)) > 0
	  AND stats #>> '{measure,valid}' = 'true'
	  AND length(coalesce(stats #>> '{measured_artifact,xyz_sha256}', '')) = 64
	  AND length(coalesce(stats #>> '{measured_artifact,final_b_to_a_sha256}', '')) = 64
	  AND stats #>> '{measured_artifact,coordinate_schema}' = 'unit_a_world_mm_v1'
	  AND length(coalesce(stats #>> '{measured_artifact,site_revision}', '')) > 0
	  AND length(coalesce(stats #>> '{measured_artifact,region_revision}', '')) > 0
	  AND CASE
	        WHEN coalesce(stats #>> '{measured_artifact,source_points}', '') ~ '^[0-9]+$'
	        THEN (stats #>> '{measured_artifact,source_points}')::int
	        ELSE 0
	      END > 0
	  AND ($4::bigint IS NULL OR owner_user_id=$4)
	ORDER BY id DESC LIMIT $5`

// FindLatestMeasurements 返回按 id 倒序的车辆测量候选。完整 measured manifest、B→A 与对象内容
// 由 handler 逐项验证；最新候选损坏时可继续恢复更早的健康任务。
func (r *LaserScanRepo) FindLatestMeasurements(
	ctx context.Context,
	unitAIP, unitBIP string,
	ownerUserID *int64,
	limit int,
) ([]*LaserScanJob, error) {
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	rows, err := r.pool.Query(
		ctx,
		findLatestLaserMeasurementsSQL,
		unitAIP,
		unitBIP,
		LaserScanStatusDone,
		ownerUserID,
		limit,
	)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	jobs := make([]*LaserScanJob, 0, limit)
	for rows.Next() {
		job := &LaserScanJob{}
		if err := scanLaserJob(rows, job); err != nil {
			return nil, err
		}
		jobs = append(jobs, job)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return jobs, nil
}

// FindLatestMeasurement 返回指定工位最近一次有效车辆测量。
// ownerUserID 非空时只查该用户；管理员传 nil 可跨用户查看。背景采集、旧版无 measured 云、
// 测量无效任务都不属于“最近车辆结果”，避免刷新后恢复出整房间或失败尝试。
func (r *LaserScanRepo) FindLatestMeasurement(
	ctx context.Context,
	unitAIP, unitBIP string,
	ownerUserID *int64,
) (*LaserScanJob, error) {
	jobs, err := r.FindLatestMeasurements(ctx, unitAIP, unitBIP, ownerUserID, 1)
	if err != nil {
		return nil, err
	}
	if len(jobs) == 0 {
		return nil, ErrNotFound
	}
	return jobs[0], nil
}

func (r *LaserScanRepo) FindBySessionKey(ctx context.Context, sessionKey string) (*LaserScanJob, error) {
	job := &LaserScanJob{}
	err := scanLaserJob(r.pool.QueryRow(ctx,
		`SELECT `+laserScanCols+` FROM laser_scan_jobs WHERE session_key=$1`, sessionKey), job)
	return oneLaser(job, err)
}

func oneLaser(job *LaserScanJob, err error) (*LaserScanJob, error) {
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return job, nil
}

type laserScanScanner interface {
	Scan(dest ...any) error
}

func scanLaserJob(row laserScanScanner, job *LaserScanJob) error {
	var inspectionID, ownerUserID sql.NullInt64
	var alignMethod, fusedKey, unitAKey, unitBKey, measuredKey, calibKey, errMsg sql.NullString
	var ptsA, ptsB, fused, afterCrop sql.NullInt32
	var bToA, stats []byte
	err := row.Scan(
		&job.ID, &job.SessionKey, &inspectionID, &ownerUserID, &job.UnitAIP, &job.UnitBIP,
		&job.Align, &alignMethod, &job.KeepRatio, &job.Status, &ptsA, &ptsB, &fused, &afterCrop,
		&fusedKey, &unitAKey, &unitBKey, &measuredKey, &calibKey,
		&bToA, &stats, &errMsg, &job.CreatedAt, &job.UpdatedAt,
	)
	if err != nil {
		return err
	}
	if inspectionID.Valid {
		job.InspectionID = &inspectionID.Int64
	}
	if ownerUserID.Valid {
		job.OwnerUserID = &ownerUserID.Int64
	}
	if alignMethod.Valid {
		job.AlignMethod = &alignMethod.String
	}
	job.PtsA = nullInt32Ptr(ptsA)
	job.PtsB = nullInt32Ptr(ptsB)
	job.Fused = nullInt32Ptr(fused)
	job.AfterCrop = nullInt32Ptr(afterCrop)
	if fusedKey.Valid {
		job.FusedObjectKey = &fusedKey.String
	}
	if unitAKey.Valid {
		job.UnitAObjectKey = &unitAKey.String
	}
	if unitBKey.Valid {
		job.UnitBObjectKey = &unitBKey.String
	}
	if measuredKey.Valid {
		job.MeasuredObjectKey = &measuredKey.String
	}
	if calibKey.Valid {
		job.CalibObjectKey = &calibKey.String
	}
	if len(bToA) > 0 {
		job.BToA = append(json.RawMessage(nil), bToA...)
	}
	if len(stats) == 0 {
		job.Stats = json.RawMessage(`{}`)
	} else {
		job.Stats = append(json.RawMessage(nil), stats...)
	}
	if errMsg.Valid {
		job.ErrorMessage = &errMsg.String
	}
	return nil
}

func nullInt32Ptr(n sql.NullInt32) *int {
	if !n.Valid {
		return nil
	}
	v := int(n.Int32)
	return &v
}
