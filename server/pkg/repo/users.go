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
		return ErrNotFound
	}
	return nil
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
