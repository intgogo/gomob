// pending_calls 仓储 — 离线视频邀请兜底（M-S4.4）。
package repo

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type PendingCall struct {
	ID          int64
	CallID      string          // 客户端 idempotent ID
	CallerID    int64
	CalleeID    int64
	SDPOffer    json.RawMessage
	CreatedAt   time.Time
	ExpireAt    time.Time
	Status      string // pending / delivered / expired / cancelled
	DeliveredAt *time.Time
}

type PendingCallRepo struct {
	pool *pgxpool.Pool
}

func NewPendingCallRepo(pool *pgxpool.Pool) *PendingCallRepo {
	return &PendingCallRepo{pool: pool}
}

// Insert 写一条 pending_call。call_id 重复返 ErrConflict。
func (r *PendingCallRepo) Insert(ctx context.Context, c *PendingCall) error {
	const q = `
		INSERT INTO pending_calls(call_id, caller_id, callee_id, sdp_offer, expire_at, status)
		VALUES($1, $2, $3, $4, $5, 'pending')
		RETURNING id, created_at`
	err := r.pool.QueryRow(ctx, q, c.CallID, c.CallerID, c.CalleeID, c.SDPOffer, c.ExpireAt).
		Scan(&c.ID, &c.CreatedAt)
	if err != nil {
		if pgErr, ok := isPgError(err, "23505"); ok {
			_ = pgErr
			return ErrConflict
		}
		return err
	}
	c.Status = "pending"
	return nil
}

// ListPendingForCallee 返回 callee 当前未过期、未投递的 invite。
func (r *PendingCallRepo) ListPendingForCallee(ctx context.Context, calleeID int64) ([]PendingCall, error) {
	const q = `
		SELECT id, call_id, caller_id, callee_id, sdp_offer, created_at, expire_at, status, delivered_at
		FROM pending_calls
		WHERE callee_id=$1 AND status='pending' AND expire_at > now()
		ORDER BY created_at ASC`
	rows, err := r.pool.Query(ctx, q, calleeID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PendingCall
	for rows.Next() {
		var c PendingCall
		if err := rows.Scan(&c.ID, &c.CallID, &c.CallerID, &c.CalleeID, &c.SDPOffer,
			&c.CreatedAt, &c.ExpireAt, &c.Status, &c.DeliveredAt); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// MarkDelivered call_id 投递成功 → status='delivered'。CAS 保证幂等。
func (r *PendingCallRepo) MarkDelivered(ctx context.Context, callID string) error {
	tag, err := r.pool.Exec(ctx,
		`UPDATE pending_calls SET status='delivered', delivered_at=now()
		 WHERE call_id=$1 AND status='pending'`, callID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrStateConflict
	}
	return nil
}

// SweepExpired 把所有过期的 pending → expired。后台 ticker 定期跑。
func (r *PendingCallRepo) SweepExpired(ctx context.Context) (int64, error) {
	tag, err := r.pool.Exec(ctx,
		`UPDATE pending_calls SET status='expired'
		 WHERE status='pending' AND expire_at <= now()`)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}

// Cancel 主叫主动取消（call.bye 在 pending 阶段）。
func (r *PendingCallRepo) Cancel(ctx context.Context, callID string, callerID int64) error {
	tag, err := r.pool.Exec(ctx,
		`UPDATE pending_calls SET status='cancelled'
		 WHERE call_id=$1 AND caller_id=$2 AND status='pending'`, callID, callerID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		var exists bool
		_ = r.pool.QueryRow(ctx,
			`SELECT EXISTS(SELECT 1 FROM pending_calls WHERE call_id=$1)`, callID).Scan(&exists)
		if !exists {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}

// FindByCallID 用于 callee 端查 invite 详情。
func (r *PendingCallRepo) FindByCallID(ctx context.Context, callID string) (*PendingCall, error) {
	const q = `
		SELECT id, call_id, caller_id, callee_id, sdp_offer, created_at, expire_at, status, delivered_at
		FROM pending_calls WHERE call_id=$1`
	c := &PendingCall{}
	row := r.pool.QueryRow(ctx, q, callID)
	if err := row.Scan(&c.ID, &c.CallID, &c.CallerID, &c.CalleeID, &c.SDPOffer,
		&c.CreatedAt, &c.ExpireAt, &c.Status, &c.DeliveredAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return c, nil
}
