package repo

import (
	"context"
	"fmt"
	"os"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// NewPool 从环境变量 GOMOB_DB_DSN 读 PG 连接串；缺则用开发默认。
func NewPool(ctx context.Context) (*pgxpool.Pool, error) {
	dsn := os.Getenv("GOMOB_DB_DSN")
	if dsn == "" {
		dsn = "postgres://gomob:gomob_dev@127.0.0.1:5432/gomob?sslmode=disable"
	}
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("parse dsn: %w", err)
	}
	cfg.MaxConns = 16
	cfg.MaxConnLifetime = time.Hour
	cfg.MaxConnIdleTime = 5 * time.Minute
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("connect pg: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		return nil, fmt.Errorf("ping pg: %w", err)
	}
	return pool, nil
}
