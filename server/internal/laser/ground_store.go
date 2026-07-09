package laser

import (
	"context"
	"encoding/json"
	"errors"

	"io.gomob/server/pkg/repo"
)

// ground_store.go = 持久地面平面端口 + DB 适配器（与 cropbox_store.go 同范式）。
// 空工位背景采集时拟合一次入库；扫描测量复用，消逐扫描 RANSAC 重拟合方差（M13）。

// GroundStore 持久化每装机点(bayKey，默认 unit_a_ip)的地面平面。
type GroundStore interface {
	// GetGround 取持久地面；未设置返回 ok=false（非错误）。
	GetGround(ctx context.Context, bayKey string) (g GroundPlane, ok bool, err error)
	// SaveGround 写入/覆盖持久地面。
	SaveGround(ctx context.Context, bayKey string, g GroundPlane) error
}

// DBGroundStore 用 repo.LaserGroundRepo（PG jsonb）实现 GroundStore，JSON 编解码在此收口。
type DBGroundStore struct {
	repo *repo.LaserGroundRepo
}

func NewDBGroundStore(r *repo.LaserGroundRepo) *DBGroundStore { return &DBGroundStore{repo: r} }

func (s *DBGroundStore) GetGround(ctx context.Context, bayKey string) (GroundPlane, bool, error) {
	raw, err := s.repo.Get(ctx, bayKey)
	if errors.Is(err, repo.ErrNotFound) {
		return GroundPlane{}, false, nil
	}
	if err != nil {
		return GroundPlane{}, false, err
	}
	var g GroundPlane
	if err := json.Unmarshal(raw, &g); err != nil {
		return GroundPlane{}, false, err
	}
	if !g.Valid {
		return GroundPlane{}, false, nil
	}
	return g, true, nil
}

func (s *DBGroundStore) SaveGround(ctx context.Context, bayKey string, g GroundPlane) error {
	raw, err := json.Marshal(g)
	if err != nil {
		return err
	}
	return s.repo.Upsert(ctx, bayKey, raw)
}
