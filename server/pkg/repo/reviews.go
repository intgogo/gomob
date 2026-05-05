package repo

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Review — reviews 表（抽查复核）。
//
// 状态机（详见 02-api-contract.md §9.3）：
//
//	assigned → done
//	   └─ 到期未做 → expired
type Review struct {
	ID           int64
	InspectionID int64
	ReviewerID   *int64
	Decision     *string // correct / incorrect / skipped
	Reason       *string
	AssignedAt   time.Time
	DecidedAt    *time.Time
	ExpireAt     *time.Time
}

type ReviewRepo struct {
	pool *pgxpool.Pool
}

func NewReviewRepo(pool *pgxpool.Pool) *ReviewRepo {
	return &ReviewRepo{pool: pool}
}

// Create 由系统按抽查规则触发（M-S2 阶段，admin 后台或定时任务负责派发）。
// 这里仅暴露 SQL 入口；调用方设置 reviewer_id（可空，待领取）+ expire_at（如 24h 后）。
func (r *ReviewRepo) Create(ctx context.Context, rv *Review) error {
	const q = `
		INSERT INTO reviews (inspection_id, reviewer_id, expire_at)
		VALUES ($1,$2,$3)
		RETURNING id, assigned_at`
	return r.pool.QueryRow(ctx, q, rv.InspectionID, rv.ReviewerID, rv.ExpireAt).
		Scan(&rv.ID, &rv.AssignedAt)
}

func (r *ReviewRepo) FindByID(ctx context.Context, id int64) (*Review, error) {
	const q = `
		SELECT id, inspection_id, reviewer_id, decision, reason, assigned_at, decided_at, expire_at
		FROM reviews WHERE id = $1`
	row := r.pool.QueryRow(ctx, q, id)
	rv := &Review{}
	if err := row.Scan(&rv.ID, &rv.InspectionID, &rv.ReviewerID, &rv.Decision, &rv.Reason,
		&rv.AssignedAt, &rv.DecidedAt, &rv.ExpireAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return rv, nil
}

// ReviewBucket 是 02-api-contract.md §6.1 的 bucket 维度。
type ReviewBucket string

const (
	BucketPending ReviewBucket = "pending"
	BucketDone    ReviewBucket = "done"
	BucketExpired ReviewBucket = "expired"
)

// ListByReviewer：按 reviewer_id 拉某个 bucket 的复核任务，分页（id DESC + cursor）。
//
// pending：reviewer_id = $1, decided_at IS NULL, (expire_at IS NULL OR expire_at > now())
// done   ：reviewer_id = $1, decided_at IS NOT NULL
// expired：reviewer_id = $1, decided_at IS NULL, expire_at <= now()
func (r *ReviewRepo) ListByReviewer(ctx context.Context, reviewerID int64, bucket ReviewBucket, limit int, cursor int64) ([]Review, int64, error) {
	if limit <= 0 || limit > 100 {
		limit = 20
	}
	var where string
	switch bucket {
	case BucketPending:
		where = `reviewer_id = $1 AND decided_at IS NULL AND (expire_at IS NULL OR expire_at > now())`
	case BucketDone:
		where = `reviewer_id = $1 AND decided_at IS NOT NULL`
	case BucketExpired:
		where = `reviewer_id = $1 AND decided_at IS NULL AND expire_at IS NOT NULL AND expire_at <= now()`
	default:
		return nil, 0, errors.New("unknown bucket")
	}
	q := `
		SELECT id, inspection_id, reviewer_id, decision, reason, assigned_at, decided_at, expire_at
		FROM reviews
		WHERE ` + where + ` AND ($2 = 0 OR id < $2)
		ORDER BY id DESC
		LIMIT $3`
	rows, err := r.pool.Query(ctx, q, reviewerID, cursor, limit+1)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	items := make([]Review, 0, limit+1)
	for rows.Next() {
		var rv Review
		if err := rows.Scan(&rv.ID, &rv.InspectionID, &rv.ReviewerID, &rv.Decision, &rv.Reason,
			&rv.AssignedAt, &rv.DecidedAt, &rv.ExpireAt); err != nil {
			return nil, 0, err
		}
		items = append(items, rv)
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

// Decide：reviewer 提交决定。CAS 保证只能改一次（decided_at IS NULL）。
//
//	decision = correct / incorrect / skipped；其它值 → 调用方校验
func (r *ReviewRepo) Decide(ctx context.Context, id, reviewerID int64, decision, reason string) error {
	const q = `
		UPDATE reviews
		SET decision = $3, reason = $4, decided_at = now()
		WHERE id = $1 AND reviewer_id = $2 AND decided_at IS NULL`
	tag, err := r.pool.Exec(ctx, q, id, reviewerID, decision, reason)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		// 区分"不存在 / 不是你的 / 已决"
		var found bool
		_ = r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM reviews WHERE id = $1)`, id).Scan(&found)
		if !found {
			return ErrNotFound
		}
		return ErrStateConflict
	}
	return nil
}
