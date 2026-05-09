package repo

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// InspectionAsset — inspection_assets 表（图片 / 3D 扫描 / 视频 / PDF）。
type InspectionAsset struct {
	ID           int64
	InspectionID int64
	Kind         string // vin_plate / nameplate / exterior / scan3d / video / pdf
	ObjectKey    string
	SHA256       string
	SizeBytes    int64
	MIME         string
	Metadata     json.RawMessage
	CreatedAt    time.Time
}

// UploadSession — 分片上传中间态（M-S2.3）。
type UploadSession struct {
	UploadID         string // 短 ID，用户可见（URL safe）
	S3UploadID       string // MinIO 真实 multipart upload id
	UserID           int64
	InspectionID     *int64
	Bucket           string
	ObjectKey        string
	Kind             string
	ExpectedSize     int64
	ExpectedSHA256   string
	MIME             string
	ChunkSize        int32
	Status           string // pending / completed / aborted / expired
	CreatedAt        time.Time
	CompletedAt      *time.Time
	CompletedAssetID *int64
}

type AssetRepo struct {
	pool *pgxpool.Pool
}

func NewAssetRepo(pool *pgxpool.Pool) *AssetRepo {
	return &AssetRepo{pool: pool}
}

// CreateInspectionAsset 上传 complete 后落 inspection_assets。
func (r *AssetRepo) CreateInspectionAsset(ctx context.Context, a *InspectionAsset) error {
	const q = `
		INSERT INTO inspection_assets (inspection_id, kind, object_key, sha256, size_bytes, mime, metadata)
		VALUES ($1,$2,$3,$4,$5,$6,$7)
		RETURNING id, created_at`
	var inspectionID *int64
	if a.InspectionID > 0 {
		inspectionID = &a.InspectionID
	}
	return r.pool.QueryRow(ctx, q,
		inspectionID, a.Kind, a.ObjectKey, a.SHA256, a.SizeBytes, a.MIME, a.Metadata,
	).Scan(&a.ID, &a.CreatedAt)
}

func (r *AssetRepo) FindAssetByID(ctx context.Context, id int64) (*InspectionAsset, error) {
	const q = `
		SELECT id, COALESCE(inspection_id, 0), kind, object_key, sha256, size_bytes, mime, metadata, created_at
		FROM inspection_assets WHERE id = $1`
	row := r.pool.QueryRow(ctx, q, id)
	a := &InspectionAsset{}
	if err := row.Scan(&a.ID, &a.InspectionID, &a.Kind, &a.ObjectKey, &a.SHA256,
		&a.SizeBytes, &a.MIME, &a.Metadata, &a.CreatedAt); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return a, nil
}

func (r *AssetRepo) ListByInspection(ctx context.Context, inspectionID int64) ([]InspectionAsset, error) {
	const q = `
		SELECT id, inspection_id, kind, object_key, sha256, size_bytes, mime, metadata, created_at
		FROM inspection_assets WHERE inspection_id = $1 ORDER BY id`
	rows, err := r.pool.Query(ctx, q, inspectionID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var items []InspectionAsset
	for rows.Next() {
		var a InspectionAsset
		if err := rows.Scan(&a.ID, &a.InspectionID, &a.Kind, &a.ObjectKey, &a.SHA256,
			&a.SizeBytes, &a.MIME, &a.Metadata, &a.CreatedAt); err != nil {
			return nil, err
		}
		items = append(items, a)
	}
	return items, rows.Err()
}

// CreateUploadSession 写入新会话；调用方先在 MinIO 拿到 s3 upload id。
func (r *AssetRepo) CreateUploadSession(ctx context.Context, s *UploadSession) error {
	const q = `
		INSERT INTO upload_sessions
			(upload_id, s3_upload_id, user_id, inspection_id, bucket, object_key, kind,
			 expected_size, expected_sha256, mime, chunk_size, status)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,'pending')
		RETURNING created_at`
	return r.pool.QueryRow(ctx, q,
		s.UploadID, s.S3UploadID, s.UserID, s.InspectionID, s.Bucket, s.ObjectKey, s.Kind,
		s.ExpectedSize, s.ExpectedSHA256, s.MIME, s.ChunkSize,
	).Scan(&s.CreatedAt)
}

func (r *AssetRepo) FindUploadSession(ctx context.Context, uploadID string) (*UploadSession, error) {
	const q = `
		SELECT upload_id, s3_upload_id, user_id, inspection_id, bucket, object_key, kind,
		       expected_size, expected_sha256, mime, chunk_size, status, created_at, completed_at, completed_asset_id
		FROM upload_sessions WHERE upload_id = $1`
	row := r.pool.QueryRow(ctx, q, uploadID)
	s := &UploadSession{}
	if err := row.Scan(&s.UploadID, &s.S3UploadID, &s.UserID, &s.InspectionID, &s.Bucket,
		&s.ObjectKey, &s.Kind, &s.ExpectedSize, &s.ExpectedSHA256, &s.MIME, &s.ChunkSize,
		&s.Status, &s.CreatedAt, &s.CompletedAt, &s.CompletedAssetID); err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return s, nil
}

// CompleteUploadSession：把 session 标 completed，关联到 inspection_asset。
func (r *AssetRepo) CompleteUploadSession(ctx context.Context, uploadID string, assetID int64) error {
	const q = `
		UPDATE upload_sessions
		SET status='completed', completed_at=now(), completed_asset_id=$2
		WHERE upload_id=$1 AND status='pending'`
	tag, err := r.pool.Exec(ctx, q, uploadID, assetID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrStateConflict
	}
	return nil
}

func (r *AssetRepo) AbortUploadSession(ctx context.Context, uploadID string) error {
	_, err := r.pool.Exec(ctx,
		`UPDATE upload_sessions SET status='aborted' WHERE upload_id=$1 AND status='pending'`,
		uploadID)
	return err
}
