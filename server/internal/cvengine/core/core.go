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
	"image"
	"os"
	"sort"
	"strings"
	"sync"
	"time"

	"io.gomob/server/internal/cvengine/gocv"
)

// Kind 加载方式（决定走哪条 cgo 路径）。
type Kind string

const (
	// KindGeneral 通用 DNN：gocv.ReadNet（cv::dnn 模块加载 ONNX）。适合 metric / classify / 一般 ocr 等。
	KindGeneral Kind = "general"
	// KindMask yolo 实例分割：gocv.CreateORTMask（直接用 onnxruntime + std/mean 预处理 + 自带 RunMask）。
	// VMASK / LTMASK 等用这个。
	KindMask Kind = "mask"
)

// Status 一条模型条目的运行时状态。
type Status struct {
	Tag         string `json:"tag"`         // 业务标签（VMASK / VMET / TOCR ...）
	Kind        Kind   `json:"kind"`        // general / mask
	Path        string `json:"path"`        // 本地文件路径（M-S10.4 后会变成 asset object_key）
	Loaded      bool   `json:"loaded"`      // 加载是否成功
	SizeBytes   int64  `json:"size_bytes"`  // 模型文件大小
	LoadedAt    string `json:"loaded_at,omitempty"`
	Error       string `json:"error,omitempty"`
	OpenCVEmpty bool   `json:"opencv_empty,omitempty"` // 加载没报错但 net.Empty() 仍为 true 的异常
	// Mask 类专用：注册时用的 classes / iSize / std / mean
	Classes []string `json:"classes,omitempty"`
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

	st.Kind = KindGeneral
	st.Loaded = true
	st.LoadedAt = time.Now().UTC().Format(time.RFC3339Nano)

	// 旧 entry 释放
	if old := r.replace(tag, &Entry{net: &net, status: st}); old != nil && old.net != nil {
		_ = old.net.Release()
	}
	return nil
}

// MaskOptions yolo mask 模型配置（用于 CreateORTMask 的预处理参数）。
type MaskOptions struct {
	Classes []string     // 类名列表，例如 VMASK = ["vin"]
	IWidth  int          // 输入宽（0 让 onnxruntime 从模型 input shape 自动推）
	IHeight int          // 输入高（同上）
	IChan   int          // 输入通道（0 自动）
	Std     float64      // 归一化 std（默认 1.0）
	Mean    gocv.Scalar  // 归一化 mean（默认 0）
}

// DefaultMaskOptions 给 VMASK 一类 yolo seg 默认值（与 gosmart .ini 缺省一致）。
func DefaultMaskOptions(classes ...string) MaskOptions {
	if len(classes) == 0 {
		classes = []string{"obj"}
	}
	return MaskOptions{
		Classes: classes,
		IWidth:  0, IHeight: 0, IChan: 0, // 自动
		Std:  1.0,
		Mean: gocv.Scalar{Val1: 0, Val2: 0, Val3: 0, Val4: 0},
	}
}

// RegisterMaskONNX 加载 yolo mask 模型（gocv.CreateORTMask 路径）。
//
// 与 RegisterONNX 的区别：
//   - 走 onnxruntime 直链，不经 cv::dnn 模块
//   - 自带 std/mean 预处理 + 内部一个 goroutine 串行化推理（onnxruntime 非线程安全）
//   - 输出格式特化为 (contours, rrects, classes, scores)
//
// 失败语义同 RegisterONNX。
func (r *Registry) RegisterMaskONNX(tag, path string, opts MaskOptions) error {
	tag = strings.TrimSpace(strings.ToUpper(tag))
	if tag == "" {
		return errors.New("tag 必填")
	}
	st := Status{Tag: tag, Kind: KindMask, Path: path, Classes: opts.Classes}

	fi, err := os.Stat(path)
	if err != nil {
		st.Error = fmt.Sprintf("stat: %v", err)
		r.put(tag, &Entry{status: st})
		return err
	}
	st.SizeBytes = fi.Size()

	weights, err := os.ReadFile(path)
	if err != nil {
		st.Error = fmt.Sprintf("read: %v", err)
		r.put(tag, &Entry{status: st})
		return err
	}

	// gpuId=0 modelId=0 走 CPU 路径；gosmart 时代加 100 走 TensorRT，本机当前未配 GPU 不切。
	iSize := image.Point{X: opts.IWidth, Y: opts.IHeight}
	net := gocv.CreateORTMask(0, 0, opts.Classes, weights, iSize, opts.IChan, opts.Std, opts.Mean)
	if net == nil || net.Empty() {
		st.Error = "CreateORTMask 失败（onnxruntime 加载或 input shape 推断失败）"
		st.OpenCVEmpty = true
		r.put(tag, &Entry{status: st})
		return errors.New(st.Error)
	}

	st.Loaded = true
	st.LoadedAt = time.Now().UTC().Format(time.RFC3339Nano)

	if old := r.replace(tag, &Entry{net: net, status: st}); old != nil && old.net != nil {
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

// LoadFromEnv 解析 GOMOB_CVENGINE_MODELS 环境变量并注册。语法：
//
//	GOMOB_CVENGINE_MODELS="VMET=/path/vmet1.onnx,VMASK:mask=/path/vins0.onnx[:cls1|cls2]"
//
// 形式：
//
//	TAG=path                   走 KindGeneral（gocv.ReadNet）
//	TAG:mask=path              走 KindMask（gocv.CreateORTMask），classes=["obj"] 默认
//	TAG:mask=path:cls1|cls2    classes 自定义（| 分隔）
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
			tag := fmt.Sprintf("INVALID_%d", len(r.entries))
			st := Status{Tag: tag, Path: p, Error: "格式必须 TAG=path 或 TAG:mask=path"}
			r.put(tag, &Entry{status: st})
			results = append(results, st)
			continue
		}
		left := p[:eq]
		rest := p[eq+1:]

		tag := strings.ToUpper(strings.TrimSpace(left))
		kind := KindGeneral
		// TAG:mask=path → kind=mask
		if colon := strings.IndexByte(left, ':'); colon > 0 {
			tag = strings.ToUpper(strings.TrimSpace(left[:colon]))
			k := strings.ToLower(strings.TrimSpace(left[colon+1:]))
			if k == string(KindMask) {
				kind = KindMask
			}
		}

		// rest 可能再带 :cls1|cls2 给 mask classes
		path := rest
		var classes []string
		if kind == KindMask {
			if colon := strings.IndexByte(rest, ':'); colon > 0 {
				path = rest[:colon]
				classes = strings.Split(rest[colon+1:], "|")
			}
			if len(classes) == 0 {
				classes = []string{"obj"}
			}
		}
		path = strings.TrimSpace(path)

		switch kind {
		case KindMask:
			_ = r.RegisterMaskONNX(tag, path, DefaultMaskOptions(classes...))
		default:
			_ = r.RegisterONNX(tag, path)
		}
		r.mu.RLock()
		if e, ok := r.entries[tag]; ok {
			results = append(results, e.status)
		}
		r.mu.RUnlock()
	}
	return results
}

// RunMask 跑 mask 模型；从注册表拿 net 后调 gocv.RunMask。
//
// 仅 KindMask 注册的模型可用；KindGeneral 模型调本函数返 ErrWrongKind。
func (r *Registry) RunMask(tag string, img gocv.Mat, confThreshold, maskThreshold, nmsThreshold, rudeScale float32) (
	contours [][]image.Point, rrects []gocv.RotatedRect, classes []string, scores []float32, err error,
) {
	r.mu.RLock()
	e, ok := r.entries[strings.ToUpper(tag)]
	r.mu.RUnlock()
	if !ok || e.net == nil {
		err = ErrNotFound
		return
	}
	if e.status.Kind != KindMask {
		err = ErrWrongKind
		return
	}
	var ids []int
	contours, rrects, ids, scores = gocv.RunMask(e.net, img, confThreshold, maskThreshold, nmsThreshold, rudeScale)
	classes = gocv.GetClasses(e.net, ids)
	return
}

// ErrWrongKind 调用了与注册 Kind 不匹配的方法（如对 KindGeneral 调 RunMask）。
var ErrWrongKind = errors.New("model kind 不匹配（需要 mask kind）")
