package repo

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Inspection — 查验记录。
//
// 状态机（详见 02-api-contract.md §9.2）：
//
//	created  → scanning → preliminary → pending_review → closed
//
// 非法跳转返 ErrStateConflict。
type Inspection struct {
	ID                 int64
	VehicleID          int64
	InspectorID        int64
	StationID          int64
	PreliminaryVerdict *string         // pass / warning / fail / pending
	PreliminaryReasons json.RawMessage // JSONB；可空
	Status             string          // created / scanning / preliminary / pending_review / closed
	CreatedAt          time.Time
	ClosedAt           *time.Time
}

// 状态机合法跳转 — 任意状态都可跳到 closed（提前结束）。
var allowedTransitions = map[string][]string{
	"created":        {"scanning", "closed"},
	"scanning":       {"preliminary", "closed"},
	"preliminary":    {"pending_review", "closed"},
	"pending_review": {"closed"},
	"closed":         nil,
}

func IsTransitionAllowed(from, to string) bool {
	for _, t := range allowedTransitions[from] {
		if t == to {
			return true
		}
	}
	return false
}

// ErrStateConflict 状态机不允许（PG CAS 失败）。02-api-contract.md §2 错误码 40401。
var ErrStateConflict = errors.New("state transition conflict")

// ErrFieldRange CHECK 约束 / 值越界（10002）。
var ErrFieldRange = errors.New("field value out of range")

type InspectionRepo struct {
	pool *pgxpool.Pool
}

func NewInspectionRepo(pool *pgxpool.Pool) *InspectionRepo {
	return &InspectionRepo{pool: pool}
}

// Create 写入新查验，状态固定 'created'。
func (r *InspectionRepo) Create(ctx context.Context, ins *Inspection) error {
	const q = `
		INSERT INTO inspections (vehicle_id, inspector_id, station_id, status)
		VALUES ($1,$2,$3,'created')
		RETURNING id, status, created_at`
	return r.pool.QueryRow(ctx, q, ins.VehicleID, ins.InspectorID, ins.StationID).
		Scan(&ins.ID, &ins.Status, &ins.CreatedAt)
}

// FindByID 详情。
func (r *InspectionRepo) FindByID(ctx context.Context, id int64) (*Inspection, error) {
	const q = `
		SELECT id, vehicle_id, inspector_id, station_id, preliminary_verdict, preliminary_reasons,
		       status, created_at, closed_at
		FROM inspections WHERE id = $1`
	row := r.pool.QueryRow(ctx, q, id)
	ins := &Inspection{}
	if err := row.Scan(&ins.ID, &ins.VehicleID, &ins.InspectorID, &ins.StationID,
		&ins.PreliminaryVerdict, &ins.PreliminaryReasons,
		&ins.Status, &ins.CreatedAt, &ins.ClosedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return ins, nil
}

// ListFilter 查询过滤条件。InspectorID == 0 → 不限；Status == "" → 不限。
type ListFilter struct {
	InspectorID int64
	Status      string
	Limit       int   // 默认 20，最大 100
	Cursor      int64 // 上一页最后一条的 id；0 = 第一页
}

// List 按 inspector / status 过滤，按 id DESC 分页。返回 items + 下一页 cursor（0 表示已结束）。
func (r *InspectionRepo) List(ctx context.Context, f ListFilter) ([]Inspection, int64, error) {
	limit := f.Limit
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	const q = `
		SELECT id, vehicle_id, inspector_id, station_id, preliminary_verdict, preliminary_reasons,
		       status, created_at, closed_at
		FROM inspections
		WHERE ($1 = 0 OR inspector_id = $1)
		  AND ($2 = '' OR status = $2)
		  AND ($3 = 0 OR id < $3)
		ORDER BY id DESC
		LIMIT $4`
	rows, err := r.pool.Query(ctx, q, f.InspectorID, f.Status, f.Cursor, limit+1)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	items := make([]Inspection, 0, limit+1)
	for rows.Next() {
		var ins Inspection
		if err := rows.Scan(&ins.ID, &ins.VehicleID, &ins.InspectorID, &ins.StationID,
			&ins.PreliminaryVerdict, &ins.PreliminaryReasons,
			&ins.Status, &ins.CreatedAt, &ins.ClosedAt); err != nil {
			return nil, 0, err
		}
		items = append(items, ins)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	// 多取了一条用来判断是否还有下一页
	var next int64
	if len(items) > limit {
		next = items[limit-1].ID
		items = items[:limit]
	}
	return items, next, nil
}

// Transition 用 PG CAS 实现状态机：仅当当前状态在期望集合内才更新。
//
// 失败模式：
//   - 行不存在 → ErrNotFound
//   - 当前状态不在 from 集合内 → ErrStateConflict
func (r *InspectionRepo) Transition(ctx context.Context, id int64, from []string, to string) error {
	if !validTransitionTo(from, to) {
		return ErrStateConflict
	}
	closedExpr := ""
	if to == "closed" {
		closedExpr = ", closed_at = now()"
	}
	q := `UPDATE inspections SET status = $1` + closedExpr +
		` WHERE id = $2 AND status = ANY($3::text[])`
	tag, err := r.pool.Exec(ctx, q, to, id, from)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		// 区分"不存在" vs "状态不匹配"
		var exists bool
		_ = r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM inspections WHERE id = $1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

// validTransitionTo 校验：from 数组中至少有一个状态可跳到 to。
func validTransitionTo(from []string, to string) bool {
	for _, s := range from {
		if IsTransitionAllowed(s, to) {
			return true
		}
	}
	return false
}

// UpdatePreliminary 更新预审结果（不改状态机；状态机由调用方 Transition 显式控制）。
func (r *InspectionRepo) UpdatePreliminary(ctx context.Context, id int64, verdict string, reasons any) error {
	reasonsJSON, err := json.Marshal(reasons)
	if err != nil {
		return err
	}
	const q = `UPDATE inspections SET preliminary_verdict = $2, preliminary_reasons = $3 WHERE id = $1`
	tag, err := r.pool.Exec(ctx, q, id, verdict, reasonsJSON)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}
