package repo

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// LaserSiteCalibration 是按物理双单元 IP 绑定的工位外参真理源。
type LaserSiteCalibration struct {
	UnitAIP       string
	UnitBIP       string
	SiteJSON      json.RawMessage
	Source        string
	MeanErrorMM   *float64
	MaxErrorMM    *float64
	RMSErrorMM    *float64
	CommonMarkers *int
	SourceScanID  *int64
	UpdatedBy     *int64
	UpdatedAt     time.Time
}

type LaserSiteCalibrationRepo struct {
	pool *pgxpool.Pool
}

func NewLaserSiteCalibrationRepo(pool *pgxpool.Pool) *LaserSiteCalibrationRepo {
	return &LaserSiteCalibrationRepo{pool: pool}
}

// Get 返回指定双单元的权威外参；未配置返回 ErrNotFound。
func (r *LaserSiteCalibrationRepo) Get(ctx context.Context, unitAIP, unitBIP string) (*LaserSiteCalibration, error) {
	cal := &LaserSiteCalibration{}
	err := r.pool.QueryRow(ctx, `
		SELECT unit_a_ip, unit_b_ip, site_json, source, mean_error_mm, max_error_mm,
		       rms_error_mm, common_markers,
		       source_scan_id, updated_by, updated_at
		FROM laser_site_calibration
		WHERE unit_a_ip=$1 AND unit_b_ip=$2`, unitAIP, unitBIP).Scan(
		&cal.UnitAIP, &cal.UnitBIP, &cal.SiteJSON, &cal.Source, &cal.MeanErrorMM, &cal.MaxErrorMM,
		&cal.RMSErrorMM, &cal.CommonMarkers,
		&cal.SourceScanID, &cal.UpdatedBy, &cal.UpdatedAt,
	)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return cal, nil
}

// Upsert 写入或覆盖指定双单元的权威外参。
func (r *LaserSiteCalibrationRepo) Upsert(ctx context.Context, cal LaserSiteCalibration) error {
	_, err := r.pool.Exec(ctx, `
		INSERT INTO laser_site_calibration (
			unit_a_ip, unit_b_ip, site_json, source, mean_error_mm, max_error_mm,
			rms_error_mm, common_markers, source_scan_id, updated_by, updated_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,now())
		ON CONFLICT (unit_a_ip, unit_b_ip) DO UPDATE SET
			site_json=EXCLUDED.site_json,
			source=EXCLUDED.source,
			mean_error_mm=EXCLUDED.mean_error_mm,
			max_error_mm=EXCLUDED.max_error_mm,
			rms_error_mm=EXCLUDED.rms_error_mm,
			common_markers=EXCLUDED.common_markers,
			source_scan_id=EXCLUDED.source_scan_id,
			updated_by=EXCLUDED.updated_by,
			updated_at=now()`,
		cal.UnitAIP, cal.UnitBIP, cal.SiteJSON, cal.Source, cal.MeanErrorMM, cal.MaxErrorMM,
		cal.RMSErrorMM, cal.CommonMarkers, cal.SourceScanID, cal.UpdatedBy,
	)
	return err
}
