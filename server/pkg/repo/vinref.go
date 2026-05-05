// vin-ref（车驾号字形参考库）仓储 — 详见 docs/architecture/server/00-server-overview.md §6.z。
//
// 数据模型：
//
//	vehicle_models ─┬─< vin_glyph_batches ─< vin_glyph_samples
//	                │
//	                └ 一车型多批次（按厂家送货时间区分），同时刻最多 1 个 published
//
// 状态机：draft ──publish──▶ published ──archive──▶ archived
//
//	publish 在事务里把同 vehicle_model_id 的旧 published 自动 archive；
//	partial unique index uq_vin_batches_one_published 兜底。
//
// 字段对齐 gosmart `apps/api/ivv/item.go` VinMore：character / arr_mode / font_id /
// font_family_id / alpha_image_data / origin_image_data。M-S10 cv-engine 迁移时
// `doCompareVin` 改成按 (vehicle_model_id, character) 拉对照集，与本次扫描字符比对。
package repo

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// ============================================================================
// VinGlyphBatch
// ============================================================================

type VinGlyphBatch struct {
	ID             int64
	VehicleModelID int64
	Name           string
	Description    *string
	CapturedAt     *time.Time
	CapturedBy     *string
	SampleCount    int32
	Status         string // draft / published / archived
	Note           *string
	CreatedBy      *int64
	CreatedAt      time.Time
	UpdatedAt      time.Time
	PublishedAt    *time.Time
	ArchivedAt     *time.Time
}

type VinGlyphBatchRepo struct {
	pool *pgxpool.Pool
}

func NewVinGlyphBatchRepo(pool *pgxpool.Pool) *VinGlyphBatchRepo {
	return &VinGlyphBatchRepo{pool: pool}
}

// Create 写一条 draft 批次。同 (vehicle_model_id, name) 重复返 ErrConflict。
func (r *VinGlyphBatchRepo) Create(ctx context.Context, b *VinGlyphBatch) error {
	const q = `
		INSERT INTO vin_glyph_batches(
			vehicle_model_id, name, description, captured_at, captured_by, note, created_by, status)
		VALUES($1, $2, $3, $4, $5, $6, $7, 'draft')
		RETURNING id, sample_count, status, created_at, updated_at`
	err := r.pool.QueryRow(ctx, q,
		b.VehicleModelID, b.Name, b.Description, b.CapturedAt, b.CapturedBy, b.Note, b.CreatedBy,
	).Scan(&b.ID, &b.SampleCount, &b.Status, &b.CreatedAt, &b.UpdatedAt)
	if err != nil {
		if _, ok := isPgError(err, "23505"); ok {
			return ErrConflict
		}
		// 23503 = foreign_key_violation（vehicle_model_id 不存在）
		if _, ok := isPgError(err, "23503"); ok {
			return ErrNotFound
		}
		return err
	}
	return nil
}

func (r *VinGlyphBatchRepo) FindByID(ctx context.Context, id int64) (*VinGlyphBatch, error) {
	return r.scanOne(ctx, `WHERE id=$1`, id)
}

// FindActive 拿 vehicle_model 当前 published 批次（最多 1 个，由 partial unique 保证）。
func (r *VinGlyphBatchRepo) FindActive(ctx context.Context, vehicleModelID int64) (*VinGlyphBatch, error) {
	return r.scanOne(ctx, `WHERE vehicle_model_id=$1 AND status='published'`, vehicleModelID)
}

func (r *VinGlyphBatchRepo) scanOne(ctx context.Context, where string, args ...any) (*VinGlyphBatch, error) {
	q := `SELECT id, vehicle_model_id, name, description, captured_at, captured_by,
	             sample_count, status, note, created_by, created_at, updated_at,
	             published_at, archived_at
	      FROM vin_glyph_batches ` + where + ` LIMIT 1`
	row := r.pool.QueryRow(ctx, q, args...)
	b := &VinGlyphBatch{}
	if err := row.Scan(&b.ID, &b.VehicleModelID, &b.Name, &b.Description, &b.CapturedAt, &b.CapturedBy,
		&b.SampleCount, &b.Status, &b.Note, &b.CreatedBy, &b.CreatedAt, &b.UpdatedAt,
		&b.PublishedAt, &b.ArchivedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return b, nil
}

// VinBatchListFilter 列表过滤。
type VinBatchListFilter struct {
	VehicleModelID int64  // 必填
	Status         string // ""=不限 / draft / published / archived
	Limit          int
	Cursor         int64 // 上页最后一项 ID（id < cursor）
}

// List 列出某车型下的批次（按 id DESC）。
func (r *VinGlyphBatchRepo) List(ctx context.Context, f VinBatchListFilter) ([]VinGlyphBatch, int64, error) {
	limit := f.Limit
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	const q = `
		SELECT id, vehicle_model_id, name, description, captured_at, captured_by,
		       sample_count, status, note, created_by, created_at, updated_at,
		       published_at, archived_at
		FROM vin_glyph_batches
		WHERE vehicle_model_id=$1
		  AND ($2 = '' OR status=$2)
		  AND ($3 = 0 OR id < $3)
		ORDER BY id DESC LIMIT $4`
	rows, err := r.pool.Query(ctx, q, f.VehicleModelID, f.Status, f.Cursor, limit+1)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := make([]VinGlyphBatch, 0, limit+1)
	for rows.Next() {
		var b VinGlyphBatch
		if err := rows.Scan(&b.ID, &b.VehicleModelID, &b.Name, &b.Description, &b.CapturedAt, &b.CapturedBy,
			&b.SampleCount, &b.Status, &b.Note, &b.CreatedBy, &b.CreatedAt, &b.UpdatedAt,
			&b.PublishedAt, &b.ArchivedAt); err != nil {
			return nil, 0, err
		}
		out = append(out, b)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	var next int64
	if len(out) > limit {
		next = out[limit-1].ID
		out = out[:limit]
	}
	return out, next, nil
}

// Patch 改 draft 批次的元数据；非 draft 返 ErrStateConflict（保持已发布数据稳定）。
type VinGlyphBatchPatch struct {
	Name        *string    `json:"name"`
	Description *string    `json:"description"`
	CapturedAt  *time.Time `json:"captured_at"`
	CapturedBy  *string    `json:"captured_by"`
	Note        *string    `json:"note"`
}

func (r *VinGlyphBatchRepo) Patch(ctx context.Context, id int64, p VinGlyphBatchPatch) error {
	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var status string
	if err := tx.QueryRow(ctx, `SELECT status FROM vin_glyph_batches WHERE id=$1`, id).Scan(&status); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if status != "draft" {
		return ErrStateConflict
	}

	const q = `
		UPDATE vin_glyph_batches SET
			name        = COALESCE($2, name),
			description = COALESCE($3, description),
			captured_at = COALESCE($4, captured_at),
			captured_by = COALESCE($5, captured_by),
			note        = COALESCE($6, note)
		WHERE id=$1`
	tag, err := tx.Exec(ctx, q, id, p.Name, p.Description, p.CapturedAt, p.CapturedBy, p.Note)
	if err != nil {
		if _, ok := isPgError(err, "23505"); ok {
			return ErrConflict
		}
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return tx.Commit(ctx)
}

// Publish 把指定 batch 设为 published；同 vehicle_model 的旧 published 自动 archive。
//
// 事务原因：行锁 + 旧 active archive + 新 active publish 必须原子，
// 否则 partial unique index uq_vin_batches_one_published 会拒绝（race），
// 我们想要的是"始终只有一个 active"的语义而不是"重试"。
func (r *VinGlyphBatchRepo) Publish(ctx context.Context, id int64) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var vmID int64
	var status string
	if err := tx.QueryRow(ctx,
		`SELECT vehicle_model_id, status FROM vin_glyph_batches WHERE id=$1 FOR UPDATE`, id,
	).Scan(&vmID, &status); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if status != "draft" && status != "published" {
		return ErrStateConflict
	}
	if status == "published" {
		// 幂等：同一条 batch 已经是 active，无需动
		return tx.Commit(ctx)
	}
	// 1) 旧 published → archived
	if _, err := tx.Exec(ctx,
		`UPDATE vin_glyph_batches SET status='archived', archived_at=now()
		 WHERE vehicle_model_id=$1 AND status='published' AND id<>$2`, vmID, id); err != nil {
		return err
	}
	// 2) 当前 → published
	tag, err := tx.Exec(ctx,
		`UPDATE vin_glyph_batches SET status='published', published_at=now()
		 WHERE id=$1 AND status='draft'`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrStateConflict
	}
	return tx.Commit(ctx)
}

// Archive 把 published / draft 状态的 batch 设 archived。archived 重复调用返 ErrStateConflict。
func (r *VinGlyphBatchRepo) Archive(ctx context.Context, id int64) error {
	tag, err := r.pool.Exec(ctx,
		`UPDATE vin_glyph_batches SET status='archived', archived_at=now()
		 WHERE id=$1 AND status IN ('draft','published')`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		_ = r.pool.QueryRow(ctx,
			`SELECT EXISTS(SELECT 1 FROM vin_glyph_batches WHERE id=$1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

// DeleteDraft 仅允许删 draft 批次（CASCADE 会顺带删 samples）；其它状态返 ErrStateConflict。
func (r *VinGlyphBatchRepo) DeleteDraft(ctx context.Context, id int64) error {
	tag, err := r.pool.Exec(ctx,
		`DELETE FROM vin_glyph_batches WHERE id=$1 AND status='draft'`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		_ = r.pool.QueryRow(ctx,
			`SELECT EXISTS(SELECT 1 FROM vin_glyph_batches WHERE id=$1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

// ============================================================================
// VinGlyphSample
// ============================================================================

type VinGlyphSample struct {
	ID                int64
	BatchID           int64
	Character         string // CHAR(1) 大写
	ArrMode           int16  // VinArrMode 0/1/2/3
	FontID            string
	FontFamilyID      *string
	PositionHint      *int16
	AlphaObjectKey    string
	AlphaSHA256       string
	AlphaSizeBytes    int64
	OriginObjectKey   *string
	OriginSHA256      *string
	OriginSizeBytes   *int64
	FeatureVectorURI  *string
	QCScore           *float32
	CreatedAt         time.Time
}

type VinGlyphSampleRepo struct {
	pool *pgxpool.Pool
}

func NewVinGlyphSampleRepo(pool *pgxpool.Pool) *VinGlyphSampleRepo {
	return &VinGlyphSampleRepo{pool: pool}
}

// Insert 写一条样本。仅 draft 批次允许写（已发布的批次不应再增删，保持版本快照）。
func (r *VinGlyphSampleRepo) Insert(ctx context.Context, s *VinGlyphSample) error {
	s.Character = strings.ToUpper(strings.TrimSpace(s.Character))
	if len(s.Character) != 1 {
		return errors.New("character 必须 1 个字符")
	}

	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var status string
	if err := tx.QueryRow(ctx,
		`SELECT status FROM vin_glyph_batches WHERE id=$1 FOR UPDATE`, s.BatchID,
	).Scan(&status); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if status != "draft" {
		return ErrStateConflict
	}

	const q = `
		INSERT INTO vin_glyph_samples(
			batch_id, character, arr_mode, font_id, font_family_id, position_hint,
			alpha_object_key, alpha_sha256, alpha_size_bytes,
			origin_object_key, origin_sha256, origin_size_bytes,
			feature_vector_uri, qc_score)
		VALUES($1, $2, $3, $4, $5, $6,
		       $7, $8, $9,
		       $10, $11, $12,
		       $13, $14)
		RETURNING id, created_at`
	if err := tx.QueryRow(ctx, q,
		s.BatchID, s.Character, s.ArrMode, s.FontID, s.FontFamilyID, s.PositionHint,
		s.AlphaObjectKey, s.AlphaSHA256, s.AlphaSizeBytes,
		s.OriginObjectKey, s.OriginSHA256, s.OriginSizeBytes,
		s.FeatureVectorURI, s.QCScore,
	).Scan(&s.ID, &s.CreatedAt); err != nil {
		// 23514 = check_violation（character/arr_mode/qc_score CHECK）
		if pgErr, ok := isPgError(err, "23514"); ok {
			_ = pgErr
			return ErrFieldRange
		}
		return err
	}
	return tx.Commit(ctx)
}

// Delete 仅 draft 批次允许删样本。
func (r *VinGlyphSampleRepo) Delete(ctx context.Context, id int64) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var batchID int64
	var status string
	if err := tx.QueryRow(ctx, `
		SELECT s.batch_id, b.status
		FROM vin_glyph_samples s JOIN vin_glyph_batches b ON s.batch_id=b.id
		WHERE s.id=$1 FOR UPDATE OF b`, id,
	).Scan(&batchID, &status); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if status != "draft" {
		return ErrStateConflict
	}
	if _, err := tx.Exec(ctx, `DELETE FROM vin_glyph_samples WHERE id=$1`, id); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ListSamplesFilter 字符级 lookup（cv-engine 主用）。
type ListSamplesFilter struct {
	BatchID      int64  // 必填
	Character    string // ""=不限；否则 1 字符
	PositionHint int16  // 0=不限；1..17
	Limit        int
}

// ListByBatch 列出某批次下的样本，可按字符 / 位置过滤；按 (character, id) 排序便于 cv-engine 一致性。
func (r *VinGlyphSampleRepo) ListByBatch(ctx context.Context, f ListSamplesFilter) ([]VinGlyphSample, error) {
	limit := f.Limit
	if limit <= 0 || limit > 1000 {
		limit = 200
	}
	char := ""
	if f.Character != "" {
		char = strings.ToUpper(f.Character)
	}
	const q = `
		SELECT id, batch_id, character, arr_mode, font_id, font_family_id, position_hint,
		       alpha_object_key, alpha_sha256, alpha_size_bytes,
		       origin_object_key, origin_sha256, origin_size_bytes,
		       feature_vector_uri, qc_score, created_at
		FROM vin_glyph_samples
		WHERE batch_id=$1
		  AND ($2 = '' OR character=$2)
		  AND ($3 = 0 OR position_hint = $3)
		ORDER BY character ASC, id ASC
		LIMIT $4`
	rows, err := r.pool.Query(ctx, q, f.BatchID, char, f.PositionHint, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]VinGlyphSample, 0, limit)
	for rows.Next() {
		var s VinGlyphSample
		if err := rows.Scan(&s.ID, &s.BatchID, &s.Character, &s.ArrMode, &s.FontID, &s.FontFamilyID,
			&s.PositionHint, &s.AlphaObjectKey, &s.AlphaSHA256, &s.AlphaSizeBytes,
			&s.OriginObjectKey, &s.OriginSHA256, &s.OriginSizeBytes,
			&s.FeatureVectorURI, &s.QCScore, &s.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

// CountByCharacter 给一个批次按字符做计数（admin 后台 / cv-engine 健康检查用）。
func (r *VinGlyphSampleRepo) CountByCharacter(ctx context.Context, batchID int64) (map[string]int, error) {
	rows, err := r.pool.Query(ctx,
		`SELECT character, COUNT(*) FROM vin_glyph_samples WHERE batch_id=$1 GROUP BY character`, batchID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make(map[string]int, 33)
	for rows.Next() {
		var c string
		var n int
		if err := rows.Scan(&c, &n); err != nil {
			return nil, err
		}
		out[c] = n
	}
	return out, rows.Err()
}
