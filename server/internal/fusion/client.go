// Package fusion 把多视角 RGBD 融合任务桥接到自托管 fusion_service(Open3D)。
package fusion

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type Config struct {
	ServiceURL       string
	MinIOEndpoint    string
	MinIOAccessKey   string
	MinIOSecretKey   string
	MinIOUseSSL      bool
	Bucket           string
	PollInterval     time.Duration
	RetryAfter       time.Duration
	MaxAttempts      int
	MaxBundleBytes   int64
	ConfThreshold    int
	EnableConfidence bool
	VoxelSizeMm      float64
	Texture          bool // 烘焙 UV-atlas 纹理(否则仅顶点色 GLB)
	TexSize          int
}

func (c Config) normalized() Config {
	c.ServiceURL = strings.TrimRight(strings.TrimSpace(c.ServiceURL), "/")
	if c.MinIOEndpoint == "" {
		c.MinIOEndpoint = "127.0.0.1:9000"
	}
	if c.MinIOAccessKey == "" {
		c.MinIOAccessKey = "gomob"
	}
	if c.MinIOSecretKey == "" {
		c.MinIOSecretKey = "gomob_dev_minio"
	}
	if c.Bucket == "" {
		c.Bucket = "gomob-assets"
	}
	if c.PollInterval <= 0 {
		c.PollInterval = 2 * time.Second
	}
	if c.RetryAfter <= 0 {
		c.RetryAfter = 30 * time.Second
	}
	if c.MaxAttempts <= 0 {
		c.MaxAttempts = 3
	}
	if c.MaxBundleBytes <= 0 {
		c.MaxBundleBytes = 512 * 1024 * 1024 // 多视角 RGBD bundle 可观,放宽到 512MB
	}
	if c.ConfThreshold <= 0 {
		c.ConfThreshold = 80
	}
	if c.VoxelSizeMm <= 0 {
		c.VoxelSizeMm = 6.0
	}
	if c.TexSize <= 0 {
		c.TexSize = 1024
	}
	return c
}

// FuseResult fusion_service /fuse 的返回:GLB 字节 + 统计(走响应头)。
type FuseResult struct {
	GLB        []byte
	Vertices   int
	Triangles  int
	FrameCount int
	FusionMs   int
}

type ServiceClient struct {
	cfg    Config
	mc     *minio.Client
	client *http.Client
}

func NewServiceClient(cfg Config) (*ServiceClient, error) {
	cfg = cfg.normalized()
	if cfg.ServiceURL == "" {
		return nil, errors.New("fusion service url is empty")
	}
	mc, err := minio.New(cfg.MinIOEndpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.MinIOAccessKey, cfg.MinIOSecretKey, ""),
		Secure: cfg.MinIOUseSSL,
	})
	if err != nil {
		return nil, err
	}
	return &ServiceClient{
		cfg:    cfg,
		mc:     mc,
		client: &http.Client{Timeout: 10 * time.Minute}, // 融合较重,放宽超时
	}, nil
}

// ReadBundle 从 MinIO 拉 RgbdShot bundle zip。
func (c *ServiceClient) ReadBundle(ctx context.Context, objectKey string) ([]byte, error) {
	obj, err := c.mc.GetObject(ctx, c.cfg.Bucket, objectKey, minio.GetObjectOptions{})
	if err != nil {
		return nil, err
	}
	defer obj.Close()
	data, err := io.ReadAll(io.LimitReader(obj, c.cfg.MaxBundleBytes+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > c.cfg.MaxBundleBytes {
		return nil, fmt.Errorf("bundle 超过限制 %d bytes", c.cfg.MaxBundleBytes)
	}
	if len(data) == 0 {
		return nil, errors.New("bundle 为空")
	}
	return data, nil
}

// Fuse 把 bundle POST 到 fusion_service /fuse,拿回 GLB + 统计。
func (c *ServiceClient) Fuse(ctx context.Context, bundle []byte) (FuseResult, error) {
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	fw, err := mw.CreateFormFile("bundle", "bundle.zip")
	if err != nil {
		return FuseResult{}, err
	}
	if _, err := fw.Write(bundle); err != nil {
		return FuseResult{}, err
	}
	fields := map[string]string{
		"conf_threshold":    strconv.Itoa(c.cfg.ConfThreshold),
		"enable_confidence": strconv.FormatBool(c.cfg.EnableConfidence),
		"voxel_size_mm":     strconv.FormatFloat(c.cfg.VoxelSizeMm, 'f', -1, 64),
		"texture":           strconv.FormatBool(c.cfg.Texture),
		"tex_size":          strconv.Itoa(c.cfg.TexSize),
	}
	for k, v := range fields {
		if err := mw.WriteField(k, v); err != nil {
			return FuseResult{}, err
		}
	}
	if err := mw.Close(); err != nil {
		return FuseResult{}, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.cfg.ServiceURL+"/fuse", &body)
	if err != nil {
		return FuseResult{}, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := c.client.Do(req)
	if err != nil {
		return FuseResult{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
		return FuseResult{}, fmt.Errorf("fusion http=%d body=%s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	glb, err := io.ReadAll(resp.Body)
	if err != nil {
		return FuseResult{}, err
	}
	if len(glb) == 0 {
		return FuseResult{}, errors.New("fusion 返回空 GLB")
	}
	return FuseResult{
		GLB:        glb,
		Vertices:   atoiHeader(resp, "X-Vertices"),
		Triangles:  atoiHeader(resp, "X-Triangles"),
		FrameCount: atoiHeader(resp, "X-Frame-Count"),
		FusionMs:   atoiHeader(resp, "X-Fusion-Ms"),
	}, nil
}

// PutResult 把 GLB 存回 MinIO。
func (c *ServiceClient) PutResult(ctx context.Context, objectKey string, glb []byte) error {
	_, err := c.mc.PutObject(ctx, c.cfg.Bucket, objectKey, bytes.NewReader(glb), int64(len(glb)),
		minio.PutObjectOptions{ContentType: "model/gltf-binary"})
	return err
}

func atoiHeader(resp *http.Response, key string) int {
	n, _ := strconv.Atoi(strings.TrimSpace(resp.Header.Get(key)))
	return n
}
