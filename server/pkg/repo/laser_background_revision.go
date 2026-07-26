package repo

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	LaserBackgroundSchemaRawUnitFramesV1     = "raw_unit_frames_v1"
	LaserBackgroundSchemaRegionCroppedUnitV1 = "region_cropped_unit_frames_v1"
	LaserBackgroundSchemaLegacyFused         = "legacy_fused"
	LaserBackgroundSchemaLegacyVerifiedFused = "legacy_verified_region_fused_v1"
	LaserBackgroundLegacyPipelineRevision    = "legacy_region_fused_v1"
)

// LaserBackgroundRevision 是不可变空工位背景版本。
// active 是唯一允许切换的状态；对象键、指纹和采集元数据写入后不覆盖。
type LaserBackgroundRevision struct {
	ID                    int64
	UnitAIP               string
	UnitBIP               string
	UnitAObjectKey        *string
	UnitBObjectKey        *string
	LegacyFusedObjectKey  *string
	SourceScanID          *int64
	SiteRevision          *string
	RegionRevision        *string
	LegacyFusedPoints     int64
	LegacyFusedChecksum   *string
	CompatibilitySite     *string
	CompatibilityRegion   *string
	CompatibilityEvidence json.RawMessage
	UnitAPoints           int64
	UnitBPoints           int64
	UnitAChecksum         *string
	UnitBChecksum         *string
	UnitAIdentity         json.RawMessage
	UnitBIdentity         json.RawMessage
	UnitADeviceConfigHash *string
	UnitBDeviceConfigHash *string
	UnitAScanConfigHash   *string
	UnitBScanConfigHash   *string
	CoordinateSchema      string
	CapturedBy            *int64
	CapturedAt            time.Time
	Active                bool
	CreatedAt             time.Time
}

type LaserBackgroundRevisionRepo struct {
	pool *pgxpool.Pool
}

func NewLaserBackgroundRevisionRepo(pool *pgxpool.Pool) *LaserBackgroundRevisionRepo {
	return &LaserBackgroundRevisionRepo{pool: pool}
}

const laserBackgroundRevisionCols = `id, unit_a_ip, unit_b_ip,
	unit_a_object_key, unit_b_object_key, legacy_fused_object_key, source_scan_id,
	site_revision, region_revision,
	legacy_fused_points, legacy_fused_checksum,
	compatibility_site_revision, compatibility_region_revision, compatibility_evidence,
	unit_a_points, unit_b_points, unit_a_checksum, unit_b_checksum,
	unit_a_identity, unit_b_identity,
	unit_a_device_config_hash, unit_b_device_config_hash,
	unit_a_scan_config_hash, unit_b_scan_config_hash,
	coordinate_schema, captured_by, captured_at, active, created_at`

// GetActive 返回工位当前启用的背景 revision；未设置返回 ErrNotFound。
func (r *LaserBackgroundRevisionRepo) GetActive(ctx context.Context, unitAIP, unitBIP string) (*LaserBackgroundRevision, error) {
	rev := &LaserBackgroundRevision{}
	err := scanLaserBackgroundRevision(r.pool.QueryRow(ctx, `
		SELECT `+laserBackgroundRevisionCols+`
		FROM laser_background_revision
		WHERE unit_a_ip=$1 AND unit_b_ip=$2 AND active`, unitAIP, unitBIP), rev)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return rev, nil
}

// Activate 插入一个新 revision，并在同一事务内把该工位旧 active 指针切换到新行。
// 事务级 advisory lock 防止空工位首次并发激活时绕过行锁撞 partial unique index。
func (r *LaserBackgroundRevisionRepo) Activate(ctx context.Context, rev LaserBackgroundRevision) (*LaserBackgroundRevision, error) {
	if err := validateLaserBackgroundRevision(rev); err != nil {
		return nil, err
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	created, err := activateLaserBackgroundRevision(ctx, tx, rev)
	if err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return created, nil
}

// ActivateAndComplete 在同一 PostgreSQL 事务内激活背景 revision，并把来源任务 fusing→done。
// 任一步失败都会回滚 active 指针；用户 stop 与完成 CAS 并发时，只允许一个终态获胜。
func (r *LaserBackgroundRevisionRepo) ActivateAndComplete(
	ctx context.Context,
	jobID int64,
	completion LaserScanCompletion,
	rev LaserBackgroundRevision,
) (*LaserScanJob, *LaserBackgroundRevision, error) {
	if err := validateLaserBackgroundRevision(rev); err != nil {
		return nil, nil, err
	}
	if rev.SourceScanID == nil || *rev.SourceScanID != jobID {
		return nil, nil, fmt.Errorf("背景 revision 来源任务与完成任务不一致")
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	created, err := activateLaserBackgroundRevision(ctx, tx, rev)
	if err != nil {
		return nil, nil, err
	}
	completion.Stats, err = withBackgroundRevisionID(completion.Stats, created.ID)
	if err != nil {
		return nil, nil, err
	}
	job, err := completeLaserScan(ctx, tx, jobID, completion)
	if err != nil {
		return nil, nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, nil, err
	}
	return job, created, nil
}

func activateLaserBackgroundRevision(
	ctx context.Context,
	tx pgx.Tx,
	rev LaserBackgroundRevision,
) (*LaserBackgroundRevision, error) {
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtext($1), hashtext($2))`, rev.UnitAIP, rev.UnitBIP); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `
		UPDATE laser_background_revision
		SET active=FALSE
		WHERE unit_a_ip=$1 AND unit_b_ip=$2 AND active`, rev.UnitAIP, rev.UnitBIP); err != nil {
		return nil, err
	}

	identityA := rev.UnitAIdentity
	if len(identityA) == 0 {
		identityA = json.RawMessage(`{}`)
	}
	identityB := rev.UnitBIdentity
	if len(identityB) == 0 {
		identityB = json.RawMessage(`{}`)
	}
	compatibilityEvidence := rev.CompatibilityEvidence
	if len(compatibilityEvidence) == 0 {
		compatibilityEvidence = json.RawMessage(`{}`)
	}
	capturedAt := rev.CapturedAt
	if capturedAt.IsZero() {
		capturedAt = time.Now().UTC()
	}

	created := &LaserBackgroundRevision{}
	err := scanLaserBackgroundRevision(tx.QueryRow(ctx, `
		INSERT INTO laser_background_revision (
			unit_a_ip, unit_b_ip,
			unit_a_object_key, unit_b_object_key, legacy_fused_object_key, source_scan_id,
			site_revision, region_revision,
			legacy_fused_points, legacy_fused_checksum,
			compatibility_site_revision, compatibility_region_revision, compatibility_evidence,
			unit_a_points, unit_b_points, unit_a_checksum, unit_b_checksum,
			unit_a_identity, unit_b_identity,
			unit_a_device_config_hash, unit_b_device_config_hash,
			unit_a_scan_config_hash, unit_b_scan_config_hash,
			coordinate_schema, captured_by, captured_at, active
		) VALUES (
			$1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,$24,$25,$26,TRUE
		)
		RETURNING `+laserBackgroundRevisionCols,
		rev.UnitAIP,
		rev.UnitBIP,
		rev.UnitAObjectKey,
		rev.UnitBObjectKey,
		rev.LegacyFusedObjectKey,
		rev.SourceScanID,
		rev.SiteRevision,
		rev.RegionRevision,
		rev.LegacyFusedPoints,
		rev.LegacyFusedChecksum,
		rev.CompatibilitySite,
		rev.CompatibilityRegion,
		compatibilityEvidence,
		rev.UnitAPoints,
		rev.UnitBPoints,
		rev.UnitAChecksum,
		rev.UnitBChecksum,
		identityA,
		identityB,
		rev.UnitADeviceConfigHash,
		rev.UnitBDeviceConfigHash,
		rev.UnitAScanConfigHash,
		rev.UnitBScanConfigHash,
		rev.CoordinateSchema,
		rev.CapturedBy,
		capturedAt,
	), created)
	if err != nil {
		return nil, err
	}
	return created, nil
}

func withBackgroundRevisionID(raw json.RawMessage, revisionID int64) (json.RawMessage, error) {
	stats := map[string]any{}
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &stats); err != nil {
			return nil, fmt.Errorf("扫描统计不是合法 JSON 对象: %w", err)
		}
	}
	stats["background_revision_id"] = revisionID
	return json.Marshal(stats)
}

func validateLaserBackgroundRevision(rev LaserBackgroundRevision) error {
	if strings.TrimSpace(rev.UnitAIP) == "" || strings.TrimSpace(rev.UnitBIP) == "" || rev.UnitAIP == rev.UnitBIP {
		return fmt.Errorf("背景 revision 的双单元 IP 无效")
	}
	switch rev.CoordinateSchema {
	case LaserBackgroundSchemaLegacyFused:
		if blankStringPtr(rev.LegacyFusedObjectKey) {
			return fmt.Errorf("legacy_fused 背景缺少融合对象键")
		}
	case LaserBackgroundSchemaLegacyVerifiedFused:
		if blankStringPtr(rev.LegacyFusedObjectKey) {
			return fmt.Errorf("已验证 legacy 背景缺少融合对象键")
		}
		if rev.LegacyFusedPoints <= 0 || blankStringPtr(rev.LegacyFusedChecksum) {
			return fmt.Errorf("已验证 legacy 背景缺少融合点数或校验和")
		}
		if blankStringPtr(rev.CompatibilitySite) || blankStringPtr(rev.CompatibilityRegion) {
			return fmt.Errorf("已验证 legacy 背景缺少兼容 site/region revision")
		}
		if blankStringPtr(rev.UnitADeviceConfigHash) || blankStringPtr(rev.UnitBDeviceConfigHash) ||
			blankStringPtr(rev.UnitAScanConfigHash) || blankStringPtr(rev.UnitBScanConfigHash) {
			return fmt.Errorf("已验证 legacy 背景缺少设备或扫描配置绑定")
		}
		if !identityMatchesIP(rev.UnitAIdentity, rev.UnitAIP) || !identityMatchesIP(rev.UnitBIdentity, rev.UnitBIP) {
			return fmt.Errorf("已验证 legacy 背景的设备 identity 与工位 IP 不一致")
		}
		if !validLegacyCompatibilityEvidence(rev) {
			return fmt.Errorf("已验证 legacy 背景缺少兼容性证据")
		}
	case LaserBackgroundSchemaRawUnitFramesV1, LaserBackgroundSchemaRegionCroppedUnitV1:
		if blankStringPtr(rev.SiteRevision) {
			return fmt.Errorf("A/B 单元背景缺少工位外参 revision")
		}
		if rev.CoordinateSchema == LaserBackgroundSchemaRegionCroppedUnitV1 && blankStringPtr(rev.RegionRevision) {
			return fmt.Errorf("region_cropped_unit_frames_v1 背景缺少区域 revision")
		}
		if blankStringPtr(rev.UnitAObjectKey) || blankStringPtr(rev.UnitBObjectKey) {
			return fmt.Errorf("A/B 单元背景缺少对象键")
		}
		if rev.UnitAPoints <= 0 || rev.UnitBPoints <= 0 {
			return fmt.Errorf("A/B 单元背景点数必须大于 0")
		}
		if blankStringPtr(rev.UnitAChecksum) || blankStringPtr(rev.UnitBChecksum) {
			return fmt.Errorf("A/B 单元背景缺少校验和")
		}
		if blankStringPtr(rev.UnitADeviceConfigHash) || blankStringPtr(rev.UnitBDeviceConfigHash) ||
			blankStringPtr(rev.UnitAScanConfigHash) || blankStringPtr(rev.UnitBScanConfigHash) {
			return fmt.Errorf("A/B 单元背景缺少设备或扫描配置指纹")
		}
		if !identityMatchesIP(rev.UnitAIdentity, rev.UnitAIP) || !identityMatchesIP(rev.UnitBIdentity, rev.UnitBIP) {
			return fmt.Errorf("A/B 单元背景的设备 identity 与工位 IP 不一致")
		}
	default:
		return fmt.Errorf("不支持的背景坐标 schema %q", rev.CoordinateSchema)
	}
	return nil
}

func validLegacyCompatibilityEvidence(rev LaserBackgroundRevision) bool {
	var evidence struct {
		BindingVersion      int             `json:"binding_version"`
		PipelineRevision    string          `json:"pipeline_revision"`
		SourceRevisionID    int64           `json:"source_revision_id"`
		SourceScanID        int64           `json:"source_scan_id"`
		SourceFusedPoints   int64           `json:"source_fused_points"`
		SourceFusedSHA256   string          `json:"source_fused_xyz_sha256"`
		ReferenceScans      json.RawMessage `json:"reference_scans"`
		AuditReportSHA256   string          `json:"audit_report_sha256"`
		HarnessReportSHA256 string          `json:"harness_report_sha256"`
	}
	if err := json.Unmarshal(rev.CompatibilityEvidence, &evidence); err != nil {
		return false
	}
	var references []json.RawMessage
	if err := json.Unmarshal(evidence.ReferenceScans, &references); err != nil || len(references) == 0 {
		return false
	}
	return evidence.BindingVersion == 1 &&
		evidence.PipelineRevision == LaserBackgroundLegacyPipelineRevision &&
		evidence.SourceRevisionID > 0 && evidence.SourceScanID > 0 &&
		evidence.SourceFusedPoints == rev.LegacyFusedPoints &&
		evidence.SourceFusedSHA256 == stringValue(rev.LegacyFusedChecksum) &&
		strings.TrimSpace(evidence.AuditReportSHA256) != "" &&
		strings.TrimSpace(evidence.HarnessReportSHA256) != ""
}

func stringValue(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

func blankStringPtr(value *string) bool {
	return value == nil || strings.TrimSpace(*value) == ""
}

func identityMatchesIP(raw json.RawMessage, ip string) bool {
	var identity map[string]any
	if err := json.Unmarshal(raw, &identity); err != nil {
		return false
	}
	value, ok := identity["ip"].(string)
	return ok && value == ip
}

type laserBackgroundRevisionScanner interface {
	Scan(dest ...any) error
}

func scanLaserBackgroundRevision(row laserBackgroundRevisionScanner, rev *LaserBackgroundRevision) error {
	var unitAObjectKey, unitBObjectKey, legacyFusedObjectKey sql.NullString
	var unitAChecksum, unitBChecksum sql.NullString
	var siteRevision, regionRevision sql.NullString
	var legacyFusedChecksum, compatibilitySite, compatibilityRegion sql.NullString
	var unitADeviceHash, unitBDeviceHash, unitAScanHash, unitBScanHash sql.NullString
	var sourceScanID, capturedBy sql.NullInt64
	var identityA, identityB, compatibilityEvidence []byte

	err := row.Scan(
		&rev.ID,
		&rev.UnitAIP,
		&rev.UnitBIP,
		&unitAObjectKey,
		&unitBObjectKey,
		&legacyFusedObjectKey,
		&sourceScanID,
		&siteRevision,
		&regionRevision,
		&rev.LegacyFusedPoints,
		&legacyFusedChecksum,
		&compatibilitySite,
		&compatibilityRegion,
		&compatibilityEvidence,
		&rev.UnitAPoints,
		&rev.UnitBPoints,
		&unitAChecksum,
		&unitBChecksum,
		&identityA,
		&identityB,
		&unitADeviceHash,
		&unitBDeviceHash,
		&unitAScanHash,
		&unitBScanHash,
		&rev.CoordinateSchema,
		&capturedBy,
		&rev.CapturedAt,
		&rev.Active,
		&rev.CreatedAt,
	)
	if err != nil {
		return err
	}

	rev.UnitAObjectKey = nullStringPtr(unitAObjectKey)
	rev.UnitBObjectKey = nullStringPtr(unitBObjectKey)
	rev.LegacyFusedObjectKey = nullStringPtr(legacyFusedObjectKey)
	rev.SourceScanID = nullInt64Ptr(sourceScanID)
	rev.SiteRevision = nullStringPtr(siteRevision)
	rev.RegionRevision = nullStringPtr(regionRevision)
	rev.LegacyFusedChecksum = nullStringPtr(legacyFusedChecksum)
	rev.CompatibilitySite = nullStringPtr(compatibilitySite)
	rev.CompatibilityRegion = nullStringPtr(compatibilityRegion)
	rev.CompatibilityEvidence = append(json.RawMessage(nil), compatibilityEvidence...)
	rev.UnitAChecksum = nullStringPtr(unitAChecksum)
	rev.UnitBChecksum = nullStringPtr(unitBChecksum)
	rev.UnitAIdentity = append(json.RawMessage(nil), identityA...)
	rev.UnitBIdentity = append(json.RawMessage(nil), identityB...)
	rev.UnitADeviceConfigHash = nullStringPtr(unitADeviceHash)
	rev.UnitBDeviceConfigHash = nullStringPtr(unitBDeviceHash)
	rev.UnitAScanConfigHash = nullStringPtr(unitAScanHash)
	rev.UnitBScanConfigHash = nullStringPtr(unitBScanHash)
	rev.CapturedBy = nullInt64Ptr(capturedBy)
	return nil
}
