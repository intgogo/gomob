package repo

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// AuditLogEntry — audit_log 表的查询视图（写路径在 pkg/audit/postgres.go）。
type AuditLogEntry struct {
	ID        int64
	UserID    *int64
	Action    string
	Target    *string
	Before    json.RawMessage
	After     json.RawMessage
	IP        *string // INET 字段以字符串读出
	CreatedAt time.Time
}

type AuditFilter struct {
	UserID    int64     // 0 = 不限
	Action    string    // 精确匹配；带 % 走 ILIKE
	Target    string    // 精确匹配
	From      time.Time // zero = 不限
	To        time.Time // zero = 不限
	Limit     int
	Cursor    int64
}

type AuditRepo struct {
	pool *pgxpool.Pool
}

func NewAuditRepo(pool *pgxpool.Pool) *AuditRepo {
	return &AuditRepo{pool: pool}
}

// List 按过滤条件分页查询；id DESC + cursor。
func (r *AuditRepo) List(ctx context.Context, f AuditFilter) ([]AuditLogEntry, int64, error) {
	limit := f.Limit
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	useLike := strings.Contains(f.Action, "%")

	q := `
		SELECT id, user_id, action, target, before, after, host(ip), created_at
		FROM audit_log
		WHERE ($1 = 0     OR user_id = $1)
		  AND ($2 = ''    OR `
	if useLike {
		q += `action ILIKE $2`
	} else {
		q += `action = $2`
	}
	q += `)
		  AND ($3 = ''    OR target = $3)
		  AND ($4::timestamptz IS NULL OR created_at >= $4)
		  AND ($5::timestamptz IS NULL OR created_at <= $5)
		  AND ($6 = 0     OR id < $6)
		ORDER BY id DESC
		LIMIT $7`

	var fromParam, toParam any
	if !f.From.IsZero() {
		fromParam = f.From
	}
	if !f.To.IsZero() {
		toParam = f.To
	}
	rows, err := r.pool.Query(ctx, q, f.UserID, f.Action, f.Target, fromParam, toParam, f.Cursor, limit+1)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()

	items := make([]AuditLogEntry, 0, limit+1)
	for rows.Next() {
		var e AuditLogEntry
		if err := rows.Scan(&e.ID, &e.UserID, &e.Action, &e.Target,
			&e.Before, &e.After, &e.IP, &e.CreatedAt); err != nil {
			return nil, 0, err
		}
		items = append(items, e)
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
