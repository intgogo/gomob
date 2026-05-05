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

// Model — AI 模型版本元数据。
//
// 状态机（详见 02-api-contract.md / 00-server-overview.md §6.y）：
//
//	draft ──promote_canary──▶ canary ──activate──▶ active
//	  │                          │                    │
//	  └──── archive ──────▶ archived ◀── archive ─────┘
//
// 同 name 至多 1 个 active + 1 个 canary（PG 部分唯一索引强约束）。
// activate 是事务原子操作：旧 active → archived，新版本 → active。
type Model struct {
	ID        int64
	Name      string
	Version   string
	AssetURI  string
	SHA256    string
	Runtime   string  // onnx / tensorrt / ...
	Framework *string
	Metadata  json.RawMessage
	Status    string // draft / canary / active / archived
	CreatedAt time.Time
	UpdatedAt time.Time
}

var modelTransitions = map[string][]string{
	"draft":    {"canary", "active", "archived"},
	"canary":   {"active", "archived"},
	"active":   {"archived"},
	"archived": nil,
}

func IsModelTransitionAllowed(from, to string) bool {
	for _, t := range modelTransitions[from] {
		if t == to {
			return true
		}
	}
	return false
}

type ModelRepo struct {
	pool *pgxpool.Pool
}

func NewModelRepo(pool *pgxpool.Pool) *ModelRepo {
	return &ModelRepo{pool: pool}
}

func (r *ModelRepo) Create(ctx context.Context, m *Model) error {
	if len(m.Metadata) == 0 {
		m.Metadata = []byte("{}")
	}
	if m.Runtime == "" {
		m.Runtime = "onnx"
	}
	const q = `
		INSERT INTO models (name, version, asset_uri, sha256, runtime, framework, metadata, status)
		VALUES ($1,$2,$3,$4,$5,$6,$7,'draft')
		RETURNING id, status, created_at, updated_at`
	err := r.pool.QueryRow(ctx, q,
		m.Name, m.Version, m.AssetURI, m.SHA256, m.Runtime, m.Framework, m.Metadata,
	).Scan(&m.ID, &m.Status, &m.CreatedAt, &m.UpdatedAt)
	if err != nil {
		if pgErr, ok := isPgError(err, "23505"); ok && strings.Contains(pgErr.ConstraintName, "models_name_version") {
			return ErrConflict
		}
		return err
	}
	return nil
}

func (r *ModelRepo) FindByID(ctx context.Context, id int64) (*Model, error) {
	const q = `
		SELECT id, name, version, asset_uri, sha256, runtime, framework, metadata, status, created_at, updated_at
		FROM models WHERE id = $1`
	row := r.pool.QueryRow(ctx, q, id)
	m := &Model{}
	if err := row.Scan(&m.ID, &m.Name, &m.Version, &m.AssetURI, &m.SHA256, &m.Runtime,
		&m.Framework, &m.Metadata, &m.Status, &m.CreatedAt, &m.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return m, nil
}

// FindActive 拿 (name, status='active') 的唯一记录。
func (r *ModelRepo) FindActive(ctx context.Context, name string) (*Model, error) {
	return r.findByNameStatus(ctx, name, "active")
}

func (r *ModelRepo) FindCanary(ctx context.Context, name string) (*Model, error) {
	return r.findByNameStatus(ctx, name, "canary")
}

func (r *ModelRepo) findByNameStatus(ctx context.Context, name, status string) (*Model, error) {
	const q = `
		SELECT id, name, version, asset_uri, sha256, runtime, framework, metadata, status, created_at, updated_at
		FROM models WHERE name = $1 AND status = $2 LIMIT 1`
	row := r.pool.QueryRow(ctx, q, name, status)
	m := &Model{}
	if err := row.Scan(&m.ID, &m.Name, &m.Version, &m.AssetURI, &m.SHA256, &m.Runtime,
		&m.Framework, &m.Metadata, &m.Status, &m.CreatedAt, &m.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return m, nil
}

func (r *ModelRepo) ListByName(ctx context.Context, name string) ([]Model, error) {
	const q = `
		SELECT id, name, version, asset_uri, sha256, runtime, framework, metadata, status, created_at, updated_at
		FROM models WHERE name = $1 ORDER BY id DESC`
	rows, err := r.pool.Query(ctx, q, name)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var items []Model
	for rows.Next() {
		var m Model
		if err := rows.Scan(&m.ID, &m.Name, &m.Version, &m.AssetURI, &m.SHA256, &m.Runtime,
			&m.Framework, &m.Metadata, &m.Status, &m.CreatedAt, &m.UpdatedAt); err != nil {
			return nil, err
		}
		items = append(items, m)
	}
	return items, rows.Err()
}

// PromoteCanary：把 (id) 设 canary。前置：当前 status ∈ {draft, canary}（同名 canary 已存在则替换 = 旧 canary 归档）。
//
// 用事务：
//  1. 拿 name；其它 canary → archived
//  2. 当前 → canary（CAS 校验状态）
func (r *ModelRepo) PromoteCanary(ctx context.Context, id int64) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var name, status string
	if err := tx.QueryRow(ctx, `SELECT name, status FROM models WHERE id=$1`, id).Scan(&name, &status); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if !IsModelTransitionAllowed(status, "canary") && status != "canary" {
		return ErrStateConflict
	}
	// 同 name 其它 canary → archived
	if _, err := tx.Exec(ctx,
		`UPDATE models SET status='archived' WHERE name=$1 AND status='canary' AND id<>$2`,
		name, id); err != nil {
		return err
	}
	// 当前 → canary（idempotent）
	if _, err := tx.Exec(ctx,
		`UPDATE models SET status='canary' WHERE id=$1 AND status IN ('draft','canary')`, id); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// Activate：(id) → active。前置：当前 ∈ {draft, canary, active}（active 重复 idempotent）。
//
// 同 name 其它 active 自动归档。
func (r *ModelRepo) Activate(ctx context.Context, id int64) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	var name, status string
	if err := tx.QueryRow(ctx, `SELECT name, status FROM models WHERE id=$1`, id).Scan(&name, &status); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return ErrNotFound
		}
		return err
	}
	if status == "archived" {
		return ErrStateConflict
	}
	// 同 name 其它 active → archived
	if _, err := tx.Exec(ctx,
		`UPDATE models SET status='archived' WHERE name=$1 AND status='active' AND id<>$2`,
		name, id); err != nil {
		return err
	}
	// 当前 → active
	if _, err := tx.Exec(ctx,
		`UPDATE models SET status='active' WHERE id=$1 AND status IN ('draft','canary','active')`, id); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (r *ModelRepo) Archive(ctx context.Context, id int64) error {
	const q = `UPDATE models SET status='archived' WHERE id=$1 AND status IN ('draft','canary','active')`
	tag, err := r.pool.Exec(ctx, q, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		_ = r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM models WHERE id=$1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

// ----- 灰度路由 -----

type ModelRoute struct {
	Name             string
	CanaryPct        int16           // 0..100
	CanaryUserFilter json.RawMessage // {"user_ids":[...]} 等
	UpdatedAt        time.Time
}

type ModelRouteRepo struct {
	pool *pgxpool.Pool
}

func NewModelRouteRepo(pool *pgxpool.Pool) *ModelRouteRepo {
	return &ModelRouteRepo{pool: pool}
}

// Upsert 写入 / 更新路由（按 name）。
func (r *ModelRouteRepo) Upsert(ctx context.Context, ro *ModelRoute) error {
	if len(ro.CanaryUserFilter) == 0 {
		ro.CanaryUserFilter = []byte("{}")
	}
	const q = `
		INSERT INTO model_routes (name, canary_pct, canary_user_filter)
		VALUES ($1, $2, $3)
		ON CONFLICT (name) DO UPDATE
		SET canary_pct         = EXCLUDED.canary_pct,
		    canary_user_filter = EXCLUDED.canary_user_filter
		RETURNING updated_at`
	return r.pool.QueryRow(ctx, q, ro.Name, ro.CanaryPct, ro.CanaryUserFilter).Scan(&ro.UpdatedAt)
}

func (r *ModelRouteRepo) Find(ctx context.Context, name string) (*ModelRoute, error) {
	const q = `SELECT name, canary_pct, canary_user_filter, updated_at FROM model_routes WHERE name = $1`
	row := r.pool.QueryRow(ctx, q, name)
	ro := &ModelRoute{}
	if err := row.Scan(&ro.Name, &ro.CanaryPct, &ro.CanaryUserFilter, &ro.UpdatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return ro, nil
}
