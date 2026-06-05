package repo

import (
	"context"
	"encoding/json"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// laser_crop_box（migration 0019）= 持久 3D 裁剪框（车位/扫描区），一装机点一行。
// repo 只存原始 JSON（box 结构定义在 internal/laser.CropBox，避免上层类型反向依赖 repo）。

type LaserCropBoxRepo struct {
	pool *pgxpool.Pool
}

func NewLaserCropBoxRepo(pool *pgxpool.Pool) *LaserCropBoxRepo {
	return &LaserCropBoxRepo{pool: pool}
}

// Get 取某装机点的裁剪框 JSON；未设置返回 ErrNotFound。
func (r *LaserCropBoxRepo) Get(ctx context.Context, bayKey string) (json.RawMessage, error) {
	var box json.RawMessage
	err := r.pool.QueryRow(ctx, `SELECT box FROM laser_crop_box WHERE bay_key = $1`, bayKey).Scan(&box)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return box, nil
}

// Upsert 写入/覆盖某装机点的裁剪框 JSON。
func (r *LaserCropBoxRepo) Upsert(ctx context.Context, bayKey string, box json.RawMessage) error {
	_, err := r.pool.Exec(ctx, `
		INSERT INTO laser_crop_box (bay_key, box, updated_at) VALUES ($1, $2, now())
		ON CONFLICT (bay_key) DO UPDATE SET box = EXCLUDED.box, updated_at = now()`,
		bayKey, box)
	return err
}
