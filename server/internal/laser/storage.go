package laser

import (
	"bytes"
	"context"
	"fmt"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// storage.go = CloudStore 的 MinIO 实现：把点云编码为 binary PCD 上传 gomob-assets。
// 对象键约定：laser-scans/<sessionKey>/<name>.pcd（name ∈ fused|unit_a|unit_b）。
// 端侧凭 object key 走 asset presign 下载（复用现有 /v1/assets 链路，G5 接）。

// MinIOConfig 复用平台 MinIO 约定（同 fusion.Config 字段）。
type MinIOConfig struct {
	Endpoint  string
	AccessKey string
	SecretKey string
	UseSSL    bool
	Bucket    string
}

func (c MinIOConfig) withDefaults() MinIOConfig {
	if c.Endpoint == "" {
		c.Endpoint = "127.0.0.1:9000"
	}
	if c.AccessKey == "" {
		c.AccessKey = "gomob"
	}
	if c.SecretKey == "" {
		c.SecretKey = "gomob_dev_minio"
	}
	if c.Bucket == "" {
		c.Bucket = "gomob-assets"
	}
	return c
}

// MinIOCloudStore 实现 CloudStore。
type MinIOCloudStore struct {
	mc     *minio.Client
	bucket string
}

// NewMinIOCloudStore 建 MinIO 客户端。
func NewMinIOCloudStore(cfg MinIOConfig) (*MinIOCloudStore, error) {
	cfg = cfg.withDefaults()
	mc, err := minio.New(cfg.Endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.AccessKey, cfg.SecretKey, ""),
		Secure: cfg.UseSSL,
	})
	if err != nil {
		return nil, err
	}
	return &MinIOCloudStore{mc: mc, bucket: cfg.Bucket}, nil
}

// LaserObjectKey 返回一朵云的对象键。
func LaserObjectKey(sessionKey, name string) string {
	return fmt.Sprintf("laser-scans/%s/%s.pcd", sessionKey, name)
}

// PutCloud 编码 PCD 并上传，返回对象键。
func (s *MinIOCloudStore) PutCloud(ctx context.Context, sessionKey, name string, xyzMM []float32) (string, error) {
	pcd, err := EncodePCDBinary(xyzMM)
	if err != nil {
		return "", err
	}
	key := LaserObjectKey(sessionKey, name)
	putCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	defer cancel()
	_, err = s.mc.PutObject(putCtx, s.bucket, key, bytes.NewReader(pcd), int64(len(pcd)),
		minio.PutObjectOptions{ContentType: "application/octet-stream"})
	if err != nil {
		return "", fmt.Errorf("上传 %s 失败: %w", key, err)
	}
	return key, nil
}
