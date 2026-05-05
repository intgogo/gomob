// shape-ref（车型 3D 外廓参考库）仓储 — 详见 docs/architecture/server/00-server-overview.md §6.z。
//
// 状态机：draft ──publish──▶ published ──archive──▶ archived
//
//	publish 在事务里把同 vehicle_model_id 的旧 published 自动 archive；
//	partial unique index uq_vehicle_shapes_one_published 兜底，应用层不重试。
//
// 数据模型与 vin-ref 同构，但每条记录代表一个完整 mesh 版本（不再有 batch×sample 的双层），
// 因为：(1) mesh 是单文件资产，没有"按字符聚合"的需求；
// (2) 厂家送达的是一套完整 mesh，而非样本集合。
package repo

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type VehicleShape struct {
	ID             int64
	VehicleModelID int64
	VersionName    string
	Description    *string
	Source         string
	CapturedAt     *time.Time
	CapturedBy     *string

	MeshObjectKey string
	MeshSHA256    string
	MeshSizeBytes int64
	MeshFormat    string // glb / ply / stl / obj / gltf

	TriangleCount *int64
	PointCount    *int64

	BBoxMinX *float32
	BBoxMinY *float32
	BBoxMinZ *float32
	BBoxMaxX *float32
	BBoxMaxY *float32
	BBoxMaxZ *float32

	Coverage *float32
	QCScore  *float32
	QCNotes  *string

	Status      string
	Note        *string
	CreatedBy   *int64
	CreatedAt   time.Time
	UpdatedAt   time.Time
	PublishedAt *time.Time
	ArchivedAt  *time.Time
}

type VehicleShapeRepo struct {
	pool *pgxpool.Pool
}

func NewVehicleShapeRepo(pool *pgxpool.Pool) *VehicleShapeRepo {
	return &VehicleShapeRepo{pool: pool}
}

const shapeColumns = `
	id, vehicle_model_id, version_name, description, source, captured_at, captured_by,
	mesh_object_key, mesh_sha256, mesh_size_bytes, mesh_format,
	triangle_count, point_count,
	bbox_min_x, bbox_min_y, bbox_min_z, bbox_max_x, bbox_max_y, bbox_max_z,
	coverage, qc_score, qc_notes,
	status, note, created_by, created_at, updated_at, published_at, archived_at`

func scanShape(row pgx.Row, s *VehicleShape) error {
	return row.Scan(&s.ID, &s.VehicleModelID, &s.VersionName, &s.Description, &s.Source,
		&s.CapturedAt, &s.CapturedBy,
		&s.MeshObjectKey, &s.MeshSHA256, &s.MeshSizeBytes, &s.MeshFormat,
		&s.TriangleCount, &s.PointCount,
		&s.BBoxMinX, &s.BBoxMinY, &s.BBoxMinZ, &s.BBoxMaxX, &s.BBoxMaxY, &s.BBoxMaxZ,
		&s.Coverage, &s.QCScore, &s.QCNotes,
		&s.Status, &s.Note, &s.CreatedBy, &s.CreatedAt, &s.UpdatedAt,
		&s.PublishedAt, &s.ArchivedAt)
}

// Create 写一条 draft shape；同 (vehicle_model_id, version_name) 重复返 ErrConflict。
func (r *VehicleShapeRepo) Create(ctx context.Context, s *VehicleShape) error {
	const q = `
		INSERT INTO vehicle_shapes(
			vehicle_model_id, version_name, description, source, captured_at, captured_by,
			mesh_object_key, mesh_sha256, mesh_size_bytes, mesh_format,
			triangle_count, point_count,
			bbox_min_x, bbox_min_y, bbox_min_z, bbox_max_x, bbox_max_y, bbox_max_z,
			coverage, qc_score, qc_notes,
			note, created_by, status)
		VALUES($1, $2, $3, $4, $5, $6,
		       $7, $8, $9, $10,
		       $11, $12,
		       $13, $14, $15, $16, $17, $18,
		       $19, $20, $21,
		       $22, $23, 'draft')
		RETURNING id, status, created_at, updated_at`
	source := s.Source
	if source == "" {
		source = "unknown"
	}
	err := r.pool.QueryRow(ctx, q,
		s.VehicleModelID, s.VersionName, s.Description, source, s.CapturedAt, s.CapturedBy,
		s.MeshObjectKey, s.MeshSHA256, s.MeshSizeBytes, s.MeshFormat,
		s.TriangleCount, s.PointCount,
		s.BBoxMinX, s.BBoxMinY, s.BBoxMinZ, s.BBoxMaxX, s.BBoxMaxY, s.BBoxMaxZ,
		s.Coverage, s.QCScore, s.QCNotes,
		s.Note, s.CreatedBy,
	).Scan(&s.ID, &s.Status, &s.CreatedAt, &s.UpdatedAt)
	if err != nil {
		if _, ok := isPgError(err, "23505"); ok {
			return ErrConflict
		}
		if _, ok := isPgError(err, "23503"); ok {
			return ErrNotFound
		}
		// 23514 = check_violation（mesh_format / source / coverage / qc_score）
		if _, ok := isPgError(err, "23514"); ok {
			return ErrFieldRange
		}
		return err
	}
	s.Source = source
	return nil
}

func (r *VehicleShapeRepo) FindByID(ctx context.Context, id int64) (*VehicleShape, error) {
	q := `SELECT ` + shapeColumns + ` FROM vehicle_shapes WHERE id=$1`
	s := &VehicleShape{}
	if err := scanShape(r.pool.QueryRow(ctx, q, id), s); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return s, nil
}

// FindActive 拿 vehicle_model 当前 published shape（partial unique 保证至多 1）。
func (r *VehicleShapeRepo) FindActive(ctx context.Context, vehicleModelID int64) (*VehicleShape, error) {
	q := `SELECT ` + shapeColumns + ` FROM vehicle_shapes
	      WHERE vehicle_model_id=$1 AND status='published' LIMIT 1`
	s := &VehicleShape{}
	if err := scanShape(r.pool.QueryRow(ctx, q, vehicleModelID), s); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return s, nil
}

type ShapeListFilter struct {
	VehicleModelID int64
	Status         string
	Limit          int
	Cursor         int64
}

func (r *VehicleShapeRepo) List(ctx context.Context, f ShapeListFilter) ([]VehicleShape, int64, error) {
	limit := f.Limit
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	q := `SELECT ` + shapeColumns + ` FROM vehicle_shapes
		WHERE vehicle_model_id=$1
		  AND ($2 = '' OR status=$2)
		  AND ($3 = 0 OR id < $3)
		ORDER BY id DESC LIMIT $4`
	rows, err := r.pool.Query(ctx, q, f.VehicleModelID, f.Status, f.Cursor, limit+1)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	out := make([]VehicleShape, 0, limit+1)
	for rows.Next() {
		var s VehicleShape
		if err := scanShape(rows, &s); err != nil {
			return nil, 0, err
		}
		out = append(out, s)
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

// VehicleShapePatch 改 draft 元数据；其它状态 ErrStateConflict（保护已发布数据稳定）。
type VehicleShapePatch struct {
	VersionName *string `json:"version_name"`
	Description *string `json:"description"`
	Source      *string `json:"source"`
	CapturedAt  *time.Time `json:"captured_at"`
	CapturedBy  *string `json:"captured_by"`
	Coverage    *float32 `json:"coverage"`
	QCScore     *float32 `json:"qc_score"`
	QCNotes     *string `json:"qc_notes"`
	Note        *string `json:"note"`
}

func (r *VehicleShapeRepo) Patch(ctx context.Context, id int64, p VehicleShapePatch) error {
	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var status string
	if err := tx.QueryRow(ctx, `SELECT status FROM vehicle_shapes WHERE id=$1`, id).Scan(&status); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if status != "draft" {
		return ErrStateConflict
	}
	const q = `
		UPDATE vehicle_shapes SET
			version_name = COALESCE($2, version_name),
			description  = COALESCE($3, description),
			source       = COALESCE($4, source),
			captured_at  = COALESCE($5, captured_at),
			captured_by  = COALESCE($6, captured_by),
			coverage     = COALESCE($7, coverage),
			qc_score     = COALESCE($8, qc_score),
			qc_notes     = COALESCE($9, qc_notes),
			note         = COALESCE($10, note)
		WHERE id=$1`
	tag, err := tx.Exec(ctx, q, id,
		p.VersionName, p.Description, p.Source, p.CapturedAt, p.CapturedBy,
		p.Coverage, p.QCScore, p.QCNotes, p.Note)
	if err != nil {
		if _, ok := isPgError(err, "23505"); ok {
			return ErrConflict
		}
		if _, ok := isPgError(err, "23514"); ok {
			return ErrFieldRange
		}
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return tx.Commit(ctx)
}

// Publish 把 draft → published；同 vehicle_model 旧 published 在同事务内 archive。
func (r *VehicleShapeRepo) Publish(ctx context.Context, id int64) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var vmID int64
	var status string
	if err := tx.QueryRow(ctx,
		`SELECT vehicle_model_id, status FROM vehicle_shapes WHERE id=$1 FOR UPDATE`, id,
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
		// 幂等
		return tx.Commit(ctx)
	}
	if _, err := tx.Exec(ctx,
		`UPDATE vehicle_shapes SET status='archived', archived_at=now()
		 WHERE vehicle_model_id=$1 AND status='published' AND id<>$2`, vmID, id); err != nil {
		return err
	}
	tag, err := tx.Exec(ctx,
		`UPDATE vehicle_shapes SET status='published', published_at=now()
		 WHERE id=$1 AND status='draft'`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrStateConflict
	}
	return tx.Commit(ctx)
}

func (r *VehicleShapeRepo) Archive(ctx context.Context, id int64) error {
	tag, err := r.pool.Exec(ctx,
		`UPDATE vehicle_shapes SET status='archived', archived_at=now()
		 WHERE id=$1 AND status IN ('draft','published')`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		_ = r.pool.QueryRow(ctx,
			`SELECT EXISTS(SELECT 1 FROM vehicle_shapes WHERE id=$1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

// DeleteDraft 仅允许删 draft 版本；已发布历史保留以追溯。
func (r *VehicleShapeRepo) DeleteDraft(ctx context.Context, id int64) error {
	tag, err := r.pool.Exec(ctx,
		`DELETE FROM vehicle_shapes WHERE id=$1 AND status='draft'`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		_ = r.pool.QueryRow(ctx,
			`SELECT EXISTS(SELECT 1 FROM vehicle_shapes WHERE id=$1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}
