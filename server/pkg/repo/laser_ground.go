package repo

import (
	"context"
	"encoding/json"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// laser_ground_plane（migration 0021）= 持久化工位地面平面，一装机点一行。
// 空工位背景采集时拟合一次入库，扫描测量复用（消逐扫描 RANSAC 重拟合方差，M13）。
// repo 只存原始 JSON（平面结构定义在 internal/laser.GroundPlane，避免上层类型反向依赖 repo）。

type LaserGroundRepo struct {
	pool *pgxpool.Pool
}

func NewLaserGroundRepo(pool *pgxpool.Pool) *LaserGroundRepo {
	return &LaserGroundRepo{pool: pool}
}

// Get 取某装机点的持久地面 JSON；未设置返回 ErrNotFound。
func (r *LaserGroundRepo) Get(ctx context.Context, bayKey string) (json.RawMessage, error) {
	var plane json.RawMessage
	err := r.pool.QueryRow(ctx, `SELECT plane FROM laser_ground_plane WHERE bay_key = $1`, bayKey).Scan(&plane)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return plane, nil
}

// Upsert 写入/覆盖某装机点的持久地面 JSON。
func (r *LaserGroundRepo) Upsert(ctx context.Context, bayKey string, plane json.RawMessage) error {
	_, err := r.pool.Exec(ctx, `
		INSERT INTO laser_ground_plane (bay_key, plane, updated_at) VALUES ($1, $2, now())
		ON CONFLICT (bay_key) DO UPDATE SET plane = EXCLUDED.plane, updated_at = now()`,
		bayKey, plane)
	return err
}
