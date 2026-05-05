package repo

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// User — 用户表的 Go 映射（含一些可空字段）。
type User struct {
	ID            int64
	Username      string
	RealName      string
	EmployeeID    string
	StationID     *int64
	PasswordHash  string
	Role          string
	Status        string // pending / active / disabled
	Note          *string
	CreatedAt     time.Time
	ActivatedAt   *time.Time
}

type Station struct {
	ID          int64
	Name        string
	Region      *string
	GatewayAddr *string
}

var ErrNotFound = errors.New("not found")
var ErrConflict = errors.New("conflict")

type UserRepo struct {
	pool *pgxpool.Pool
}

func NewUserRepo(pool *pgxpool.Pool) *UserRepo {
	return &UserRepo{pool: pool}
}

// Create 写入新用户；用户名 / 工号唯一冲突时返回 ErrConflict（含明细字段）。
func (r *UserRepo) Create(ctx context.Context, u *User) error {
	const q = `
		INSERT INTO users (username, real_name, employee_id, password_hash, role, status, note)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		RETURNING id, created_at`
	err := r.pool.QueryRow(ctx, q,
		u.Username, u.RealName, u.EmployeeID, u.PasswordHash, u.Role, u.Status, u.Note,
	).Scan(&u.ID, &u.CreatedAt)
	if err != nil {
		// 23505 = unique_violation
		if pgErr, ok := isPgError(err, "23505"); ok {
			return wrapConflict(pgErr.ConstraintName)
		}
		return err
	}
	return nil
}

// FindByUsername 用于登录。
func (r *UserRepo) FindByUsername(ctx context.Context, username string) (*User, error) {
	const q = `
		SELECT id, username, real_name, employee_id, station_id, password_hash, role, status, note, created_at, activated_at
		FROM users WHERE username = $1`
	row := r.pool.QueryRow(ctx, q, username)
	u := &User{}
	if err := row.Scan(&u.ID, &u.Username, &u.RealName, &u.EmployeeID, &u.StationID,
		&u.PasswordHash, &u.Role, &u.Status, &u.Note, &u.CreatedAt, &u.ActivatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return u, nil
}

// FindByID 用于 /v1/me。
func (r *UserRepo) FindByID(ctx context.Context, id int64) (*User, error) {
	const q = `
		SELECT id, username, real_name, employee_id, station_id, password_hash, role, status, note, created_at, activated_at
		FROM users WHERE id = $1`
	row := r.pool.QueryRow(ctx, q, id)
	u := &User{}
	if err := row.Scan(&u.ID, &u.Username, &u.RealName, &u.EmployeeID, &u.StationID,
		&u.PasswordHash, &u.Role, &u.Status, &u.Note, &u.CreatedAt, &u.ActivatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return u, nil
}

// Activate 把 pending 用户改 active（管理员审核通过）。
// 当前 dev 自动调用，免管理员后台。
func (r *UserRepo) Activate(ctx context.Context, id int64) error {
	const q = `UPDATE users SET status='active', activated_at=now() WHERE id=$1 AND status='pending'`
	tag, err := r.pool.Exec(ctx, q, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		// 区分不存在 vs 状态不对
		var exists bool
		_ = r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE id=$1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

// Reject pending 用户审核驳回 → status=disabled（保留记录便于审计）。
func (r *UserRepo) Reject(ctx context.Context, id int64) error {
	const q = `UPDATE users SET status='disabled' WHERE id=$1 AND status='pending'`
	tag, err := r.pool.Exec(ctx, q, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		_ = r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE id=$1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

// Disable / Enable 已激活用户的禁用 / 恢复（管理员人工干预）。
func (r *UserRepo) Disable(ctx context.Context, id int64) error {
	tag, err := r.pool.Exec(ctx, `UPDATE users SET status='disabled' WHERE id=$1 AND status='active'`, id)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		_ = r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE id=$1)`, id).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

// UpdateRoleAndStation 改用户的 role / station_id（任一可空）。
//
// stationID 语义：
//
//	nil  → 不变
//	*v=-1 → 显式置 NULL
//	*v>0 → 改为指定 station
func (r *UserRepo) UpdateRoleAndStation(ctx context.Context, id int64, role *string, stationID *int64) error {
	const q = `
		UPDATE users
		SET role       = COALESCE($2::text, role),
		    station_id = CASE
		                   WHEN $3::bigint IS NULL THEN station_id
		                   WHEN $3::bigint = -1   THEN NULL
		                   ELSE $3::bigint
		                 END
		WHERE id = $1`
	tag, err := r.pool.Exec(ctx, q, id, role, stationID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotFound
	}
	return nil
}

// ListUsersFilter 列表过滤。
type ListUsersFilter struct {
	Status string // pending / active / disabled / "" = 不限
	Role   string
	Limit  int
	Cursor int64
}

// ListUsers 按 (status, role) 过滤；按 id DESC 分页。
func (r *UserRepo) ListUsers(ctx context.Context, f ListUsersFilter) ([]User, int64, error) {
	limit := f.Limit
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	const q = `
		SELECT id, username, real_name, employee_id, station_id, password_hash, role, status, note, created_at, activated_at
		FROM users
		WHERE ($1 = '' OR status = $1)
		  AND ($2 = '' OR role = $2)
		  AND ($3 = 0 OR id < $3)
		ORDER BY id DESC
		LIMIT $4`
	rows, err := r.pool.Query(ctx, q, f.Status, f.Role, f.Cursor, limit+1)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	items := make([]User, 0, limit+1)
	for rows.Next() {
		var u User
		if err := rows.Scan(&u.ID, &u.Username, &u.RealName, &u.EmployeeID, &u.StationID,
			&u.PasswordHash, &u.Role, &u.Status, &u.Note, &u.CreatedAt, &u.ActivatedAt); err != nil {
			return nil, 0, err
		}
		items = append(items, u)
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

func (r *UserRepo) UpdatePassword(ctx context.Context, id int64, newHash string) error {
	const q = `UPDATE users SET password_hash=$2 WHERE id=$1`
	_, err := r.pool.Exec(ctx, q, id, newHash)
	return err
}

// FindStationByID 用于带出 /v1/me 的检测站。
func (r *UserRepo) FindStationByID(ctx context.Context, id int64) (*Station, error) {
	const q = `SELECT id, name, region, gateway_addr FROM stations WHERE id=$1`
	row := r.pool.QueryRow(ctx, q, id)
	s := &Station{}
	if err := row.Scan(&s.ID, &s.Name, &s.Region, &s.GatewayAddr); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return s, nil
}
