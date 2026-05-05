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

// VehicleModel — 车型档案库主数据。
//
// 状态机（详见 02-api-contract.md §13）：
//
//	draft  ── publish ─▶ published
//	   │                    │
//	   └─ archive ──────▶ archived ◀── archive ─────────┘
//
// archived 是终态；不允许从 archived 回到任何状态。
type VehicleModel struct {
	ID                  int64
	Make                string
	Series              string
	Year                *int32          // 可空（同型号无年款细分时 NULL）
	EngineType          *string         // EV / PHEV / ICE / HEV
	OutlineFeatures     json.RawMessage // {length_mm, width_mm, ...}
	ComplianceCheckList json.RawMessage // ["合规项-001", ...]
	ManufacturerDocURL  *string
	Status              string // draft / published / archived
	CreatedAt           time.Time
	UpdatedAt           time.Time
}

// 状态机合法跳转。
var vmTransitions = map[string][]string{
	"draft":     {"published", "archived"},
	"published": {"archived"},
	"archived":  nil,
}

func IsVMTransitionAllowed(from, to string) bool {
	for _, t := range vmTransitions[from] {
		if t == to {
			return true
		}
	}
	return false
}

type VehicleModelRepo struct {
	pool *pgxpool.Pool
}

func NewVehicleModelRepo(pool *pgxpool.Pool) *VehicleModelRepo {
	return &VehicleModelRepo{pool: pool}
}

// Create 写入新档案，状态固定 'draft'。冲突（make/series/year 三元组重复）返 ErrConflict。
func (r *VehicleModelRepo) Create(ctx context.Context, m *VehicleModel) error {
	if len(m.OutlineFeatures) == 0 {
		m.OutlineFeatures = []byte("{}")
	}
	if len(m.ComplianceCheckList) == 0 {
		m.ComplianceCheckList = []byte("[]")
	}
	const q = `
		INSERT INTO vehicle_models
			(make, series, year, engine_type, outline_features, compliance_check_list, manufacturer_doc_url, status)
		VALUES ($1,$2,$3,$4,$5,$6,$7,'draft')
		RETURNING id, status, created_at, updated_at`
	err := r.pool.QueryRow(ctx, q,
		m.Make, m.Series, m.Year, m.EngineType, m.OutlineFeatures, m.ComplianceCheckList, m.ManufacturerDocURL,
	).Scan(&m.ID, &m.Status, &m.CreatedAt, &m.UpdatedAt)
	if err != nil {
		if pgErr, ok := isPgError(err, "23505"); ok && strings.Contains(pgErr.ConstraintName, "vehicle_models_msy") {
			return ErrConflict
		}
		return err
	}
	return nil
}

func (r *VehicleModelRepo) FindByID(ctx context.Context, id int64) (*VehicleModel, error) {
	const q = `
		SELECT id, make, series, year, engine_type, outline_features, compliance_check_list,
		       manufacturer_doc_url, status, created_at, updated_at
		FROM vehicle_models WHERE id = $1`
	row := r.pool.QueryRow(ctx, q, id)
	m := &VehicleModel{}
	if err := row.Scan(&m.ID, &m.Make, &m.Series, &m.Year, &m.EngineType,
		&m.OutlineFeatures, &m.ComplianceCheckList, &m.ManufacturerDocURL,
		&m.Status, &m.CreatedAt, &m.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return m, nil
}

// VehicleListFilter 列表过滤。
//
//	IncludeAllStatus=false ⇒ 只返 published（普通用户）
//	IncludeAllStatus=true  ⇒ 全状态（admin 后台）
type VehicleListFilter struct {
	Make             string
	Series           string
	Year             int32
	Keyword          string // 模糊匹配 make/series（ILIKE）
	IncludeAllStatus bool
	Limit            int
	Cursor           int64
}

func (r *VehicleModelRepo) List(ctx context.Context, f VehicleListFilter) ([]VehicleModel, int64, error) {
	limit := f.Limit
	if limit <= 0 || limit > 100 {
		limit = 20
	}

	q := `
		SELECT id, make, series, year, engine_type, outline_features, compliance_check_list,
		       manufacturer_doc_url, status, created_at, updated_at
		FROM vehicle_models
		WHERE ($1 = '' OR make = $1)
		  AND ($2 = '' OR series = $2)
		  AND ($3 = 0 OR year = $3)
		  AND ($4 = '' OR make ILIKE '%' || $4 || '%' OR series ILIKE '%' || $4 || '%')
		  AND ($5 OR status = 'published')
		  AND ($6 = 0 OR id < $6)
		ORDER BY id DESC
		LIMIT $7`
	rows, err := r.pool.Query(ctx, q,
		f.Make, f.Series, f.Year, f.Keyword, f.IncludeAllStatus, f.Cursor, limit+1,
	)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	items := make([]VehicleModel, 0, limit+1)
	for rows.Next() {
		var m VehicleModel
		if err := rows.Scan(&m.ID, &m.Make, &m.Series, &m.Year, &m.EngineType,
			&m.OutlineFeatures, &m.ComplianceCheckList, &m.ManufacturerDocURL,
			&m.Status, &m.CreatedAt, &m.UpdatedAt); err != nil {
			return nil, 0, err
		}
		items = append(items, m)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	var next int64
	if len(items) > limit {
		next = items[limit-1].ID
		items = items[:limit]
	}
	return items, next, nil
}

// Patch 修订档案字段；只允许 status='draft' 时改主体字段；其它状态 ErrStateConflict。
//
// nil 字段表示不变（PATCH 语义）。json tag 与 02-api-contract.md §13 字段名对齐。
type VehicleModelPatch struct {
	EngineType          *string         `json:"engine_type"`
	OutlineFeatures     json.RawMessage `json:"outline_features"`
	ComplianceCheckList json.RawMessage `json:"compliance_check_list"`
	ManufacturerDocURL  *string         `json:"manufacturer_doc_url"`
}

func (r *VehicleModelRepo) Patch(ctx context.Context, id int64, p VehicleModelPatch) error {
	const q = `
		UPDATE vehicle_models
		SET engine_type           = COALESCE($2, engine_type),
		    outline_features      = COALESCE($3::jsonb, outline_features),
		    compliance_check_list = COALESCE($4::jsonb, compliance_check_list),
		    manufacturer_doc_url  = COALESCE($5, manufacturer_doc_url)
		WHERE id = $1 AND status = 'draft'`
	tag, err := r.pool.Exec(ctx, q, id, p.EngineType, p.OutlineFeatures, p.ComplianceCheckList, p.ManufacturerDocURL)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		_ = r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM vehicle_models WHERE id=$1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict // 已 published / archived 不能改主体
	}
	return nil
}

// Transition 用 PG CAS 切状态：draft→published / draft→archived / published→archived。
func (r *VehicleModelRepo) Transition(ctx context.Context, id int64, from []string, to string) error {
	if !validVMTransitionTo(from, to) {
		return ErrStateConflict
	}
	const q = `UPDATE vehicle_models SET status = $1 WHERE id = $2 AND status = ANY($3::text[])`
	tag, err := r.pool.Exec(ctx, q, to, id, from)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		_ = r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM vehicle_models WHERE id=$1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

func validVMTransitionTo(from []string, to string) bool {
	for _, s := range from {
		if IsVMTransitionAllowed(s, to) {
			return true
		}
	}
	return false
}
