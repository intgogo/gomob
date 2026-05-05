// Package core —— cv-engine 模型注册表（M-S10 Phase 2.1）。
//
// 当前阶段提供"按 tag 注册一组 ONNX 模型"的最简 API：
//
//	RegisterONNX(tag, path) → 调 gocv.ReadNet 加载（真链 libopencv_world / libonnxruntime）
//	Get(tag)                → 拿底层 *gocv.Net 给 ivv 业务包用
//	List()                  → 给 /cv/v1/models 列模型 + 加载状态
//
// 后续阶段（M-S10.4）：
//
//	- RegisterONNX 不再读本地文件，改成调 model-registry 拉 active 版本 → asset 下载 .onnx → 加载
//	- 订阅 NATS model.version.activated 触发热更（旧 Net.Release() + 新 RegisterONNX）
//
// 当前 API 形态保持稳定，未来切到 NATS / model-registry 时业务包（ivv）调用面不变。
package core

import (
	"errors"
	"fmt"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"io.gomob/server/internal/cvengine/gocv"
)

// Status 一条模型条目的运行时状态。
type Status struct {
	Tag         string `json:"tag"`         // 业务标签（VMASK / VMET / TOCR ...）
	Path        string `json:"path"`        // 本地文件路径（M-S10.4 后会变成 asset object_key）
	Loaded      bool   `json:"loaded"`      // ReadNet 是否成功
	SizeBytes   int64  `json:"size_bytes"`  // 模型文件大小
	LoadedAt    string `json:"loaded_at,omitempty"`
	Error       string `json:"error,omitempty"`
	OpenCVEmpty bool   `json:"opencv_empty,omitempty"` // ReadNet 没报错但 net.Empty() 仍为 true 的异常
}

// Entry 内部条目：Net + Status。
type Entry struct {
	net    *gocv.Net
	status Status
}

// Registry 全局模型注册表。
type Registry struct {
	mu      sync.RWMutex
	entries map[string]*Entry
}

// New 空 Registry。
func New() *Registry {
	return &Registry{entries: make(map[string]*Entry)}
}

var ErrNotFound = errors.New("model tag 未注册或加载失败")

// RegisterONNX 加载 ONNX 模型。tag 重复时先释放旧 Net 再加载新的（幂等）。
//
// 失败语义：
//
//	- path 不存在 / 不可读 → 返 error，并记录 status.Error；entry 保留以便 /cv/v1/models 暴露失败原因
//	- ReadNet 返 net.Empty() == true → 视作加载失败，opencv_empty=true，net 不进入注册表
//	- 加载成功 → loaded=true
func (r *Registry) RegisterONNX(tag, path string) error {
	tag = strings.TrimSpace(strings.ToUpper(tag))
	if tag == "" {
		return errors.New("tag 必填")
	}
	st := Status{Tag: tag, Path: path}

	fi, err := os.Stat(path)
	if err != nil {
		st.Error = fmt.Sprintf("stat: %v", err)
		r.put(tag, &Entry{status: st})
		return err
	}
	st.SizeBytes = fi.Size()

	// gocv.ReadNet 内部按文件后缀走 onnxruntime 路径（libonnxruntime.so 链上）。
	// 本调用穿过 cgo → libopencv_world → libonnxruntime，是真实的模型加载。
	net := gocv.ReadNet(path, "")
	if net.Empty() {
		st.Error = "ReadNet 返 Empty Net（模型格式不兼容 / OpenCV ONNX 解析失败）"
		st.OpenCVEmpty = true
		r.put(tag, &Entry{status: st})
		return errors.New(st.Error)
	}

	st.Loaded = true
	st.LoadedAt = time.Now().UTC().Format(time.RFC3339Nano)

	// 旧 entry 释放
	if old := r.replace(tag, &Entry{net: &net, status: st}); old != nil && old.net != nil {
		_ = old.net.Release()
	}
	return nil
}

// Get 拿底层 gocv.Net；未注册或加载失败返 ErrNotFound。
func (r *Registry) Get(tag string) (*gocv.Net, error) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	e, ok := r.entries[strings.ToUpper(tag)]
	if !ok || e.net == nil {
		return nil, ErrNotFound
	}
	return e.net, nil
}

// List 返回所有注册过的 tag 状态（含失败的，便于 /cv/v1/models 查问题）。
func (r *Registry) List() []Status {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]Status, 0, len(r.entries))
	for _, e := range r.entries {
		out = append(out, e.status)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Tag < out[j].Tag })
	return out
}

// LoadedCount 返加载成功的模型数。
func (r *Registry) LoadedCount() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	n := 0
	for _, e := range r.entries {
		if e.status.Loaded {
			n++
		}
	}
	return n
}

// ReleaseAll 关停时调用：把所有 net 释放。
func (r *Registry) ReleaseAll() {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, e := range r.entries {
		if e.net != nil {
			_ = e.net.Release()
			e.net = nil
		}
	}
}

func (r *Registry) put(tag string, e *Entry) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.entries[tag] = e
}

func (r *Registry) replace(tag string, e *Entry) *Entry {
	r.mu.Lock()
	defer r.mu.Unlock()
	old := r.entries[tag]
	r.entries[tag] = e
	return old
}

// LoadFromEnv 解析 GOMOB_CVENGINE_MODELS 环境变量并注册：
//
//	GOMOB_CVENGINE_MODELS="VMET=/path/vmet1.onnx,VINS=/path/vins0.onnx"
//
// 返回逐项加载结果（即使部分失败也尽力加载完所有项）。
func (r *Registry) LoadFromEnv(envValue string) []Status {
	envValue = strings.TrimSpace(envValue)
	if envValue == "" {
		return nil
	}
	pairs := strings.Split(envValue, ",")
	results := make([]Status, 0, len(pairs))
	for _, p := range pairs {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		eq := strings.IndexByte(p, '=')
		if eq <= 0 || eq == len(p)-1 {
			// 用 "INVALID:<index>" 当 tag 让多个非法项不冲突；同时进 entries 让 /cv/v1/models 暴露
			tag := fmt.Sprintf("INVALID_%d", len(r.entries))
			st := Status{Tag: tag, Path: p, Error: "格式必须 TAG=path"}
			r.put(tag, &Entry{status: st})
			results = append(results, st)
			continue
		}
		tag := strings.ToUpper(strings.TrimSpace(p[:eq]))
		path := strings.TrimSpace(p[eq+1:])
		_ = r.RegisterONNX(tag, path) // 错误已存进 entry
		// 拉回最终 status
		r.mu.RLock()
		if e, ok := r.entries[tag]; ok {
			results = append(results, e.status)
		}
		r.mu.RUnlock()
	}
	return results
}
