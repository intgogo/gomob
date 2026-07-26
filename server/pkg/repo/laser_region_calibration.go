package repo

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// LaserRegionCalibration 是按双单元 IP 绑定的区域墙真理源。
// Points 只含 unit A / 融合显示系 mm 边界点，B→A 外参由 site 标定单独提供。
type LaserRegionCalibration struct {
	UnitAIP      string
	UnitBIP      string
	Enabled      bool
	Points       json.RawMessage
	Source       string
	SourceScanID *int64
	UpdatedBy    *int64
	UpdatedAt    time.Time
}

type LaserRegionCalibrationRepo struct {
	pool *pgxpool.Pool
}

func NewLaserRegionCalibrationRepo(pool *pgxpool.Pool) *LaserRegionCalibrationRepo {
	return &LaserRegionCalibrationRepo{pool: pool}
}

// Get 返回指定双单元的区域墙；未配置返回 ErrNotFound。
func (r *LaserRegionCalibrationRepo) Get(ctx context.Context, unitAIP, unitBIP string) (*LaserRegionCalibration, error) {
	cal := &LaserRegionCalibration{}
	err := r.pool.QueryRow(ctx, `
		SELECT unit_a_ip, unit_b_ip, enabled, points, source, source_scan_id, updated_by, updated_at
		FROM laser_region_calibration
		WHERE unit_a_ip=$1 AND unit_b_ip=$2`, unitAIP, unitBIP).Scan(
		&cal.UnitAIP,
		&cal.UnitBIP,
		&cal.Enabled,
		&cal.Points,
		&cal.Source,
		newNullInt64Target(&cal.SourceScanID),
		newNullInt64Target(&cal.UpdatedBy),
		&cal.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return cal, nil
}

// Upsert 保存或覆盖区域墙。调用方负责校验 points 的几何语义，数据库继续守住 JSON/最少点数。
func (r *LaserRegionCalibrationRepo) Upsert(ctx context.Context, cal LaserRegionCalibration) error {
	source := cal.Source
	if source == "" {
		source = "unknown"
	}
	_, err := r.pool.Exec(ctx, `
		INSERT INTO laser_region_calibration (
			unit_a_ip, unit_b_ip, enabled, points, source, source_scan_id, updated_by, updated_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,now())
		ON CONFLICT (unit_a_ip, unit_b_ip) DO UPDATE SET
			enabled=EXCLUDED.enabled,
			points=EXCLUDED.points,
			source=EXCLUDED.source,
			source_scan_id=EXCLUDED.source_scan_id,
			updated_by=EXCLUDED.updated_by,
			updated_at=now()`,
		cal.UnitAIP,
		cal.UnitBIP,
		cal.Enabled,
		cal.Points,
		source,
		cal.SourceScanID,
		cal.UpdatedBy,
	)
	return err
}

// Delete 删除指定工位区域墙；不存在返回 ErrNotFound。
func (r *LaserRegionCalibrationRepo) Delete(ctx context.Context, unitAIP, unitBIP string) error {
	tag, err := r.pool.Exec(ctx, `
		DELETE FROM laser_region_calibration WHERE unit_a_ip=$1 AND unit_b_ip=$2`, unitAIP, unitBIP)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// nullInt64Target 把 pgx 的可空 BIGINT 扫进 *int64 字段。
func newNullInt64Target(dst **int64) *nullableInt64Scanner {
	return &nullableInt64Scanner{dst: dst}
}

type nullableInt64Scanner struct {
	dst **int64
}

func (s *nullableInt64Scanner) Scan(src any) error {
	var value sql.NullInt64
	if err := value.Scan(src); err != nil {
		return err
	}
	if !value.Valid {
		*s.dst = nil
		return nil
	}
	v := value.Int64
	*s.dst = &v
	return nil
}
