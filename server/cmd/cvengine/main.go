// gomob-cvengine — CV 算法引擎，从 gosmart 迁移（M-S10）。
//
// Phase 1 当前（M-S10.1）：
//
//   - cgo 链通：gocv 包真实链接 libopencv_world / libccv / libonnxruntime
//   - 端点：/healthz / /readyz / /cv/v1/version / /cv/v1/echo_dim（IMDecode 真调用）
//   - 鉴权：暂不强制（让 harness 直探 cgo 是否真链上）
//
// Phase 2 后续（M-S10.2）：迁 gosmart/apps/api/ivv 业务路由（vin_detect / vin_compare / 等）
// + JWT/HMAC 双轨鉴权（详见 02-api-contract.md §14.1）。
//
// 环境变量：
//
//	GOMOB_CVENGINE_HTTP_ADDR    HTTP 监听地址（默认 :18810）
//	GOMOB_CVENGINE_REQUIRE_AUTH true 时强制 X-Gomob-User-Id 头（默认 false，dev 直连方便）
//	GOMOB_VINREF_TARGET         vin-ref baseURL（默认 http://127.0.0.1:18058）
//	GOMOB_CVENGINE_MODELS       启动期模型注册：
//	                              "VMET=/data/vmet1.onnx,VINS=/data/vins0.onnx"
//	                            缺省时 /cv/v1/models 返 items=[]；M-S10.4 后由 model-registry 接管
//	LD_LIBRARY_PATH             需含 .so 路径（dev 默认 /usr/local/lib:/usr/local/lib64:/usr/local/onnxruntime/lib）
package main

import (
	"context"
	"errors"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"io.gomob/server/internal/cvengine"
	"io.gomob/server/pkg/logger"
)

func main() {
	log := logger.New("cvengine")

	rootCtx, cancel := context.WithCancel(context.Background())
	defer cancel()
	_ = rootCtx

	h := cvengine.NewHandler()
	// 启动期模型加载（M-S10.4 后将由 model-registry → asset 拉 .onnx 替代）
	if env := os.Getenv("GOMOB_CVENGINE_MODELS"); env != "" {
		results := h.Models().LoadFromEnv(env)
		for _, s := range results {
			if s.Loaded {
				log.Info("model 加载成功", "tag", s.Tag, "path", s.Path, "size_bytes", s.SizeBytes)
			} else {
				log.Warn("model 加载失败", "tag", s.Tag, "path", s.Path, "err", s.Error)
			}
		}
	}
	mux := http.NewServeMux()
	h.Mount(mux)

	addr := envOr("GOMOB_CVENGINE_HTTP_ADDR", ":18810")
	srv := &http.Server{
		Addr:              addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       60 * time.Second, // 大图上传留余
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
	shutdownCtx, sc := context.WithTimeout(context.Background(), 5*time.Second)
	defer sc()
	_ = srv.Shutdown(shutdownCtx)
	log.Info("退出完成")
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
