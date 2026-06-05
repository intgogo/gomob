package laser

import (
	"context"
	"encoding/json"
	"errors"

	"io.gomob/server/pkg/repo"
)

// cropbox_store.go = CropBox 持久化端口 + DB 适配器。runner/handler 依赖接口，测试注入 fake。

// CropBoxStore 持久化每装机点(bayKey，默认 unit_a_ip)的裁剪框。
type CropBoxStore interface {
	// GetCropBox 取框；未设置返回 ok=false（非错误）。
	GetCropBox(ctx context.Context, bayKey string) (box CropBox, ok bool, err error)
	// SaveCropBox 写入/覆盖框。
	SaveCropBox(ctx context.Context, bayKey string, box CropBox) error
}

// DBCropBoxStore 用 repo.LaserCropBoxRepo（PG jsonb）实现 CropBoxStore，JSON 编解码在此收口。
type DBCropBoxStore struct {
	repo *repo.LaserCropBoxRepo
}

func NewDBCropBoxStore(r *repo.LaserCropBoxRepo) *DBCropBoxStore { return &DBCropBoxStore{repo: r} }

func (s *DBCropBoxStore) GetCropBox(ctx context.Context, bayKey string) (CropBox, bool, error) {
	raw, err := s.repo.Get(ctx, bayKey)
	if errors.Is(err, repo.ErrNotFound) {
		return CropBox{}, false, nil
	}
	if err != nil {
		return CropBox{}, false, err
	}
	var b CropBox
	if err := json.Unmarshal(raw, &b); err != nil {
		return CropBox{}, false, err
	}
	return b, true, nil
}

func (s *DBCropBoxStore) SaveCropBox(ctx context.Context, bayKey string, box CropBox) error {
	raw, err := json.Marshal(box)
	if err != nil {
		return err
	}
	return s.repo.Upsert(ctx, bayKey, raw)
}
