// gomob-cvengine — CV 算法引擎，从 gosmart 迁移（M-S10）。
//
// 启动期模型加载支持两套路径（按优先级）：
//
//  1. M-S10.4 主路径：GOMOB_CVENGINE_MODEL_NAMES="VMASK,VMET" 时通过 model-registry → MinIO
//     拉 active 版本；订阅 NATS model.version.activated 热更
//  2. dev 旁路：GOMOB_CVENGINE_MODELS="VMASK:mask=/path/x.onnx:vin,VMET=/path/y.onnx" 时
//     直读本地路径（不经 model-registry，方便单机调试）
//
// 环境变量：
//
//	GOMOB_CVENGINE_HTTP_ADDR     HTTP 监听地址（默认 :18810）
//	GOMOB_CVENGINE_REQUIRE_AUTH  true 时强制 X-Gomob-User-Id 头（默认 false，dev 直连方便）
//
//	# 主路径（M-S10.4 model-registry）
//	GOMOB_CVENGINE_MODEL_NAMES   逗号分隔的 model name 列表（同时是 cv-engine 注册 tag）
//	GOMOB_MODELREGISTRY_TARGET   默认 http://127.0.0.1:18057
//	GOMOB_MINIO_ENDPOINT/...     MinIO 配置（同 asset / shape-ref）
//	GOMOB_CVENGINE_MODEL_CACHE   缓存目录（默认 .dev/cvengine_models）
//	GOMOB_NATS_URL               NATS URL（默认 nats://127.0.0.1:4222）；空字符串=禁用 NATS 热更
//
//	# dev 旁路
//	GOMOB_CVENGINE_MODELS        "TAG=path,TAG:mask=path:cls" 直接本地加载
//
//	LD_LIBRARY_PATH              需含 .so 路径（dev 默认 /usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/nats-io/nats.go"

	"io.gomob/server/internal/cvengine"
	"io.gomob/server/internal/cvengine/loader"
	"io.gomob/server/pkg/hmacauth"
	"io.gomob/server/pkg/logger"
)

func main() {
	log := logger.New("cvengine")

	rootCtx, cancel := context.WithCancel(context.Background())
	defer cancel()

	h := cvengine.NewHandler()

	// 主路径：M-S10.4 model-registry
	var ld *loader.Loader
	if names := os.Getenv("GOMOB_CVENGINE_MODEL_NAMES"); names != "" {
		l, err := loader.New(loader.Config{
			Registry:            h.Models(),
			ModelRegistryTarget: envOr("GOMOB_MODELREGISTRY_TARGET", "http://127.0.0.1:18057"),
			MinIOEndpoint:       envOr("GOMOB_MINIO_ENDPOINT", "127.0.0.1:9000"),
			MinIOAccessKey:      envOr("GOMOB_MINIO_ACCESS_KEY", "gomob"),
			MinIOSecretKey:      envOr("GOMOB_MINIO_SECRET_KEY", "gomob_dev_minio"),
			MinIOUseSSL:         os.Getenv("GOMOB_MINIO_USE_SSL") == "true",
			Bucket:              envOr("GOMOB_MINIO_BUCKET", "gomob-assets"),
			CacheDir:            envOr("GOMOB_CVENGINE_MODEL_CACHE", ".dev/cvengine_models"),
			Log:                 log,
		})
		if err != nil {
			log.Error("loader 初始化失败", "err", err)
			os.Exit(1)
		}
		ld = l
		nameList := splitCSV(names)
		log.Info("通过 model-registry 加载模型", "names", nameList)
		results := ld.LoadNames(rootCtx, nameList)
		for _, r := range results {
			if r.Loaded {
				log.Info("model 加载成功",
					"tag", r.Tag, "version", r.Version, "kind", r.Kind,
					"size_bytes", r.SizeBytes, "sha256_ok", r.SHA256OK)
			} else {
				log.Warn("model 加载失败", "tag", r.Tag, "version", r.Version, "err", r.Error)
			}
		}
	}

	// 旁路：dev 直接读本地（与 model-registry 路径互不冲突，可同时启用）
	if env := os.Getenv("GOMOB_CVENGINE_MODELS"); env != "" {
		results := h.Models().LoadFromEnv(env)
		for _, s := range results {
			if s.Loaded {
				log.Info("model 加载成功（dev local）",
					"tag", s.Tag, "kind", s.Kind, "path", s.Path, "size_bytes", s.SizeBytes)
			} else {
				log.Warn("model 加载失败（dev local）",
					"tag", s.Tag, "path", s.Path, "err", s.Error)
			}
		}
	}
	if err := h.ValidateRequiredDependencies(); err != nil {
		log.Error("cv-engine 必需依赖未就绪，拒绝启动", "err", err)
		os.Exit(1)
	}

	// NATS 订阅 model.version.activated → 重新加载（M-S10.5）
	natsURL := envOr("GOMOB_NATS_URL", "nats://127.0.0.1:4222")
	var nc *nats.Conn
	if natsURL != "" && ld != nil {
		conn, err := nats.Connect(natsURL,
			nats.Timeout(3*time.Second),
			nats.ReconnectWait(2*time.Second),
			nats.MaxReconnects(-1),
		)
		if err != nil {
			log.Warn("NATS 连接失败，不订阅热更（dev 模式可忽略）", "err", err, "url", natsURL)
		} else {
			nc = conn
			_, err := nc.Subscribe("model.version.activated", func(msg *nats.Msg) {
				// payload: {"name":"VMASK","version":"v2","status":"active",...}
				name := extractJSONField(msg.Data, "name")
				if name == "" {
					return
				}
				log.Info("收到 NATS 热更事件", "topic", msg.Subject, "name", name)
				ctx, c := context.WithTimeout(rootCtx, 2*time.Minute)
				defer c()
				r := ld.LoadByName(ctx, name)
				if r.Loaded {
					log.Info("热更成功", "tag", r.Tag, "version", r.Version, "kind", r.Kind)
				} else {
					log.Warn("热更失败", "tag", r.Tag, "err", r.Error)
				}
			})
			if err != nil {
				log.Warn("NATS 订阅失败", "err", err)
			} else {
				log.Info("NATS 订阅 model.version.activated", "url", natsURL)
			}
		}
	}

	mux := http.NewServeMux()
	h.Mount(mux)

	// HMAC 验签（M-S10.2c）：secret 空 → noop；非空时按 GOMOB_CVENGINE_HMAC_REQUIRED
	// 决定是否强制每个请求都带签名（生产建议 true；dev / harness 直连默认 false 兼容）。
	// /healthz / /readyz 始终绕过 HMAC（k8s probe 不签名）。
	hmacSecret := os.Getenv("GOMOB_HMAC_SECRET")
	hmacRequired := os.Getenv("GOMOB_CVENGINE_HMAC_REQUIRED") == "true"
	verifier := hmacauth.NewVerifier(hmacSecret, hmacRequired, nil)
	var rootHandler http.Handler = mux
	if !verifier.Disabled() {
		signed := verifier.Middleware(mux)
		rootHandler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/healthz" || r.URL.Path == "/readyz" {
				mux.ServeHTTP(w, r)
				return
			}
			signed.ServeHTTP(w, r)
		})
		log.Info("HMAC 验签已启用", "required", hmacRequired)
	}

	addr := envOr("GOMOB_CVENGINE_HTTP_ADDR", ":18810")
	srv := &http.Server{
		Addr:              addr,
		Handler:           rootHandler,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second,
		WriteTimeout:      60 * time.Second,
		IdleTimeout:       2 * time.Minute,
		MaxHeaderBytes:    1 << 16,
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, os.Interrupt, syscall.SIGTERM)
	go func() {
		log.Info("HTTP 监听", "addr", addr)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("HTTP 异常退出", "err", err)
			cancel()
		}
	}()

	<-stop
	log.Info("收到退出信号，开始 graceful shutdown")
	if nc != nil {
		nc.Close()
	}
	shutdownCtx, sc := context.WithTimeout(context.Background(), 5*time.Second)
	defer sc()
	_ = srv.Shutdown(shutdownCtx)
	// 先停 HTTP 不再收新请求，再释放模型 net（C++ onnxruntime / opencv session）。
	// 经在途引用计数：若仍有在途推理，net 延迟到归零才真正 Release，避免 UAF。
	h.Models().ReleaseAll()
	log.Info("退出完成")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func splitCSV(s string) []string {
	parts := strings.Split(s, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	return out
}

// extractJSONField 不引 encoding/json：只在 NATS payload 里抠 "name":"..." 字段。
//
// NATS payload 的格式由 model-registry publishActivated 决定（详见 modelregistry/handler.go）；
// 字段顺序、空格都已知，简单字符串匹配足够。退化时返 ""，外层日志容忍。
func extractJSONField(data []byte, field string) string {
	s := string(data)
	key := `"` + field + `":`
	idx := strings.Index(s, key)
	if idx < 0 {
		return ""
	}
	rest := strings.TrimSpace(s[idx+len(key):])
	if !strings.HasPrefix(rest, `"`) {
		return ""
	}
	rest = rest[1:]
	end := strings.Index(rest, `"`)
	if end < 0 {
		return ""
	}
	return rest[:end]
}
