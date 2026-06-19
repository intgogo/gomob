// Package loader —— cv-engine 启动期 / NATS 热更时的模型加载流水线。
//
// 链路：
//
//	model-registry GetActive(name) → 拿 asset_uri + sha256 + metadata
//	                              → MinIO 直拉（已签名走 PresignedGetObject 不行 — 内部服务直连）
//	                              → 缓存到 GOMOB_CVENGINE_MODEL_CACHE 目录
//	                              → SHA256 校验
//	                              → core.RegisterMaskONNX / RegisterComONNX / RegisterONNX（按 metadata.kind: mask|com|general）
//
// M-S10.5 加 NATS 订阅 model.version.activated，事件来时调本包 Reload(name)。
package loader

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"

	"io.gomob/server/internal/cvengine/core"
	"io.gomob/server/internal/cvengine/gocv"
	"io.gomob/server/internal/cvengine/modelregistryclient"
)

// Config 加载器全部依赖。
type Config struct {
	Registry *core.Registry

	// model-registry baseURL（默认 http://127.0.0.1:18057）
	ModelRegistryTarget string

	// MinIO（与 asset / shape-ref / vinref 共用 bucket）
	MinIOEndpoint  string
	MinIOAccessKey string
	MinIOSecretKey string
	MinIOUseSSL    bool
	Bucket         string

	// 缓存目录（默认 .dev/cvengine_models/）
	CacheDir string
	Log      *slog.Logger
}

// Loader 把 model-registry → MinIO → core.Registry 串成一条流水线。
type Loader struct {
	cfg Config
	mc  *minio.Client
	mr  *modelregistryclient.Client
}

func New(cfg Config) (*Loader, error) {
	if cfg.Registry == nil {
		return nil, errors.New("Registry 必填")
	}
	if cfg.MinIOEndpoint == "" {
		cfg.MinIOEndpoint = "127.0.0.1:9000"
	}
	if cfg.Bucket == "" {
		cfg.Bucket = "gomob-assets"
	}
	if cfg.CacheDir == "" {
		cfg.CacheDir = ".dev/cvengine_models"
	}
	if cfg.ModelRegistryTarget == "" {
		cfg.ModelRegistryTarget = "http://127.0.0.1:18057"
	}
	if err := os.MkdirAll(cfg.CacheDir, 0o755); err != nil {
		return nil, fmt.Errorf("缓存目录: %w", err)
	}

	mc, err := minio.New(cfg.MinIOEndpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(cfg.MinIOAccessKey, cfg.MinIOSecretKey, ""),
		Secure: cfg.MinIOUseSSL,
	})
	if err != nil {
		return nil, fmt.Errorf("minio: %w", err)
	}
	return &Loader{
		cfg: cfg,
		mc:  mc,
		mr:  modelregistryclient.NewClient(cfg.ModelRegistryTarget),
	}, nil
}

// Result 单个 tag 的加载结果（给 main 启动期日志用）。
type Result struct {
	Tag         string
	Name        string
	Version     string
	Kind        string
	CachedPath  string
	SizeBytes   int64
	SHA256OK    bool
	Loaded      bool
	Error       error
}

// LoadByName 给一个 model 名（同时是 cv-engine 注册 tag），跑完整流水线。
//
// tag 与 name 同一字符串约定 —— gomob 没必要分两套命名。
func (l *Loader) LoadByName(ctx context.Context, name string) Result {
	r := Result{Tag: strings.ToUpper(name), Name: name}

	// 1. model-registry 拿 active 元数据
	m, err := l.mr.GetActive(ctx, name)
	if err != nil {
		r.Error = fmt.Errorf("modelregistry GetActive: %w", err)
		return r
	}
	r.Version = m.Version
	meta := modelregistryclient.ParseMetadata(m.Metadata)
	r.Kind = meta.Kind
	if r.Kind == "" {
		r.Kind = "general"
	}

	// 2. MinIO 直拉到本地缓存
	cached := filepath.Join(l.cfg.CacheDir, fmt.Sprintf("%s_%s.onnx", strings.ToLower(name), m.Version))
	r.CachedPath = cached
	if err := l.fetchToFile(ctx, m.AssetURI, cached); err != nil {
		r.Error = fmt.Errorf("MinIO 下载 %s: %w", m.AssetURI, err)
		return r
	}
	fi, _ := os.Stat(cached)
	if fi != nil {
		r.SizeBytes = fi.Size()
	}

	// 3. SHA256 校验（model-registry 入库时 admin 写的预期值）
	if m.SHA256 != "" {
		got, err := sha256File(cached)
		if err != nil {
			r.Error = fmt.Errorf("sha256 计算: %w", err)
			return r
		}
		if !strings.EqualFold(got, m.SHA256) {
			r.Error = fmt.Errorf("sha256 不一致：期望=%s 实际=%s", m.SHA256, got)
			return r
		}
		r.SHA256OK = true
	}

	// 4. 进 core.Registry
	switch r.Kind {
	case "mask":
		opts := core.DefaultMaskOptions(meta.Classes...)
		if meta.IWidth > 0 {
			opts.IWidth = meta.IWidth
		}
		if meta.IHeight > 0 {
			opts.IHeight = meta.IHeight
		}
		if meta.IChan > 0 {
			opts.IChan = meta.IChan
		}
		if meta.Std > 0 {
			opts.Std = meta.Std
		}
		if err := l.cfg.Registry.RegisterMaskONNX(r.Tag, cached, opts); err != nil {
			r.Error = fmt.Errorf("RegisterMaskONNX: %w", err)
			return r
		}
	case "com":
		// 通用原始输出模型（gocv.CreateORTCom，吐扁平 []float32，后处理调用方做；如 yolo-obb VIN 字符 OBB）。
		// std 归一系数：metadata.std 优先；缺省 1/255（÷255，与端侧 yolo-obb 预处理一致）。mean 默认 0。
		std := meta.Std
		if std <= 0 {
			std = 1.0 / 255.0
		}
		if err := l.cfg.Registry.RegisterComONNX(r.Tag, cached, std, gocv.Scalar{}); err != nil {
			r.Error = fmt.Errorf("RegisterComONNX: %w", err)
			return r
		}
	default:
		if err := l.cfg.Registry.RegisterONNX(r.Tag, cached); err != nil {
			r.Error = fmt.Errorf("RegisterONNX: %w", err)
			return r
		}
	}
	r.Loaded = true
	return r
}

// LoadNames 批量按名加载；失败的项进 Result.Error，不阻塞其它项。
func (l *Loader) LoadNames(ctx context.Context, names []string) []Result {
	out := make([]Result, 0, len(names))
	for _, n := range names {
		n = strings.TrimSpace(n)
		if n == "" {
			continue
		}
		out = append(out, l.LoadByName(ctx, n))
	}
	return out
}

// fetchToFile 从 MinIO 拉对象到本地文件。
func (l *Loader) fetchToFile(ctx context.Context, objectKey, dst string) error {
	rctx, cancel := context.WithTimeout(ctx, 5*time.Minute) // 大模型 ~300MB 留余量
	defer cancel()
	obj, err := l.mc.GetObject(rctx, l.cfg.Bucket, objectKey, minio.GetObjectOptions{})
	if err != nil {
		return err
	}
	defer obj.Close()

	tmp := dst + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	defer f.Close()

	if _, err := io.Copy(f, obj); err != nil {
		_ = os.Remove(tmp)
		return err
	}
	if err := f.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, dst)
}

func sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
