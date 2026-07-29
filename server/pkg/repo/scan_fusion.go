package repo

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// scan_fusion_jobs 状态(对标 message_transcripts 队列形态)。
const (
	ScanFusionStatusPending    = "pending"
	ScanFusionStatusProcessing = "processing"
	ScanFusionStatusDone       = "done"
	ScanFusionStatusFailed     = "failed"
)

// ScanFusionJob 一个多视角融合任务：输入 schema v2 原始 RGBD bundle(input_object_key)，派生 GLB(result_object_key)。
type ScanFusionJob struct {
	ID              int64
	SessionKey      string
	InspectionID    *int64
	OwnerUserID     *int64 // 扫描发起者;scan.fusion_done 实时推送的路由键(可空)
	InputObjectKey  string
	FrameCount      int
	Status          string
	ResultObjectKey *string
	Vertices        *int
	Triangles       *int
	Stats           json.RawMessage
	ErrorMessage    *string
	AttemptCount    int
	NextRetryAt     time.Time
	CreatedAt       time.Time
	UpdatedAt       time.Time
}

type ScanFusionCompletion struct {
	ResultObjectKey string
	Vertices        int
	Triangles       int
	Stats           json.RawMessage
}

type ScanFusionRepo struct {
	pool *pgxpool.Pool
}

func NewScanFusionRepo(pool *pgxpool.Pool) *ScanFusionRepo {
	return &ScanFusionRepo{pool: pool}
}

// scanFusionCols 的列顺序必须与 scanFusionJob() 里 row.Scan(...) 的接收参数顺序逐一对应;
// scanFusionColsPrefixed 的 cols 切片同理。任何重排/增删列都必须三处同步改,否则 Scan 会错位读字段。
const scanFusionCols = `id, session_key, inspection_id, owner_user_id, input_object_key, frame_count, status,
	result_object_key, vertices, triangles, stats, error_message,
	attempt_count, next_retry_at, created_at, updated_at`

// Enqueue 幂等入队:同 session_key 已存在则原样返回(端侧重传不重复融合)。
// ownerUserID 为扫描发起者(可空),供 scan.fusion_done 实时推送路由。
func (r *ScanFusionRepo) Enqueue(ctx context.Context, sessionKey, inputObjectKey string,
	inspectionID, ownerUserID *int64, frameCount int) (*ScanFusionJob, error) {
	job := &ScanFusionJob{}
	err := scanFusionJob(r.pool.QueryRow(ctx, `
		INSERT INTO scan_fusion_jobs(session_key, inspection_id, owner_user_id, input_object_key, frame_count, status)
		VALUES($1, $2, $3, $4, $5, 'pending')
		ON CONFLICT (session_key) DO NOTHING
		RETURNING `+scanFusionCols, sessionKey, inspectionID, ownerUserID, inputObjectKey, frameCount), job)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return r.FindBySessionKey(ctx, sessionKey)
		}
		return nil, err
	}
	return job, nil
}

// ClaimNext 领一条待处理任务并置 processing、attempt_count+1(FOR UPDATE SKIP LOCKED,多 worker 安全)。
func (r *ScanFusionRepo) ClaimNext(ctx context.Context, maxAttempts int) (*ScanFusionJob, error) {
	if maxAttempts <= 0 {
		maxAttempts = 3
	}
	tx, err := r.pool.BeginTx(ctx, pgx.TxOptions{})
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	job := &ScanFusionJob{}
	err = scanFusionJob(tx.QueryRow(ctx, `
		WITH next AS (
			SELECT id FROM scan_fusion_jobs
			WHERE status IN ('pending', 'failed')
			  AND next_retry_at <= now()
			  AND attempt_count < $1
			ORDER BY created_at ASC, id ASC
			LIMIT 1
			FOR UPDATE SKIP LOCKED
		)
		UPDATE scan_fusion_jobs j
		SET status='processing', attempt_count=j.attempt_count + 1, updated_at=now()
		FROM next WHERE j.id=next.id
		RETURNING `+scanFusionColsPrefixed("j"), maxAttempts), job)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return job, nil
}

func (r *ScanFusionRepo) Complete(ctx context.Context, id int64, c ScanFusionCompletion) (*ScanFusionJob, error) {
	stats := c.Stats
	if len(stats) == 0 {
		stats = json.RawMessage(`{}`)
	}
	job := &ScanFusionJob{}
	err := scanFusionJob(r.pool.QueryRow(ctx, `
		UPDATE scan_fusion_jobs
		SET status='done', result_object_key=$2, vertices=$3, triangles=$4, stats=$5,
		    error_message=NULL, updated_at=now()
		WHERE id=$1
		RETURNING `+scanFusionCols, id, c.ResultObjectKey, c.Vertices, c.Triangles, stats), job)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return job, nil
}

func (r *ScanFusionRepo) Fail(ctx context.Context, id int64, message string,
	retryAfter time.Duration, maxAttempts int) (*ScanFusionJob, error) {
	if retryAfter <= 0 {
		retryAfter = time.Minute
	}
	if maxAttempts <= 0 {
		maxAttempts = 3
	}
	retrySeconds := int64(retryAfter.Seconds())
	if retrySeconds < 1 {
		retrySeconds = 1
	}
	job := &ScanFusionJob{}
	err := scanFusionJob(r.pool.QueryRow(ctx, `
		UPDATE scan_fusion_jobs
		SET status='failed', error_message=$2,
		    next_retry_at=CASE WHEN attempt_count < $4 THEN now() + ($3 * interval '1 second') ELSE next_retry_at END,
		    updated_at=now()
		WHERE id=$1
		RETURNING `+scanFusionCols, id, message, retrySeconds, maxAttempts), job)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return job, nil
}

func (r *ScanFusionRepo) FindBySessionKey(ctx context.Context, sessionKey string) (*ScanFusionJob, error) {
	job := &ScanFusionJob{}
	err := scanFusionJob(r.pool.QueryRow(ctx,
		`SELECT `+scanFusionCols+` FROM scan_fusion_jobs WHERE session_key=$1`, sessionKey), job)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return job, nil
}

func (r *ScanFusionRepo) FindByID(ctx context.Context, id int64) (*ScanFusionJob, error) {
	job := &ScanFusionJob{}
	err := scanFusionJob(r.pool.QueryRow(ctx,
		`SELECT `+scanFusionCols+` FROM scan_fusion_jobs WHERE id=$1`, id), job)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return job, nil
}

// scanFusionColsPrefixed 给 ClaimNext 的 RETURNING 加表别名前缀。
func scanFusionColsPrefixed(alias string) string {
	cols := []string{"id", "session_key", "inspection_id", "owner_user_id", "input_object_key", "frame_count", "status",
		"result_object_key", "vertices", "triangles", "stats", "error_message",
		"attempt_count", "next_retry_at", "created_at", "updated_at"}
	out := ""
	for i, c := range cols {
		if i > 0 {
			out += ", "
		}
		out += alias + "." + c
	}
	return out
}

type scanFusionScanner interface {
	Scan(dest ...any) error
}

func scanFusionJob(row scanFusionScanner, job *ScanFusionJob) error {
	var inspectionID, ownerUserID sql.NullInt64
	var resultKey, errMsg sql.NullString
	var vertices, triangles sql.NullInt32
	var stats []byte
	err := row.Scan(
		&job.ID, &job.SessionKey, &inspectionID, &ownerUserID, &job.InputObjectKey, &job.FrameCount, &job.Status,
		&resultKey, &vertices, &triangles, &stats, &errMsg,
		&job.AttemptCount, &job.NextRetryAt, &job.CreatedAt, &job.UpdatedAt,
	)
	if err != nil {
		return err
	}
	if inspectionID.Valid {
		job.InspectionID = &inspectionID.Int64
	}
	if ownerUserID.Valid {
		job.OwnerUserID = &ownerUserID.Int64
	}
	if resultKey.Valid {
		job.ResultObjectKey = &resultKey.String
	}
	if vertices.Valid {
		v := int(vertices.Int32)
		job.Vertices = &v
	}
	if triangles.Valid {
		t := int(triangles.Int32)
		job.Triangles = &t
	}
	if len(stats) == 0 {
		job.Stats = json.RawMessage(`{}`)
	} else {
		job.Stats = append(json.RawMessage(nil), stats...)
	}
	if errMsg.Valid {
		job.ErrorMessage = &errMsg.String
	}
	return nil
}
