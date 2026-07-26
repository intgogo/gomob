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
//   - RegisterONNX 不再读本地文件，改成调 model-registry 拉 active 版本 → asset 下载 .onnx → 加载
//   - 订阅 NATS model.version.activated 触发热更（旧 Net.Release() + 新 RegisterONNX）
//
// 当前 API 形态保持稳定，未来切到 NATS / model-registry 时业务包（ivv）调用面不变。
package core

import (
	"errors"
	"fmt"
	"image"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"io.gomob/server/internal/cvengine/gocv"
)

const ortDeviceIDEnv = "GOMOB_CVENGINE_ORT_DEVICE_ID"

// ortDeviceID 返回底层 ORT 设备语义：-1=CPU 多线程，0..=CUDA，100..=TensorRT 设备号+100。
// 默认必须显式走 -1；旧代码把 0 误当 CPU，在 CPU-only ORT 上会回落成单线程 CPU。
func ortDeviceID() (int, error) {
	raw := strings.TrimSpace(os.Getenv(ortDeviceIDEnv))
	if raw == "" {
		return -1, nil
	}
	deviceID, err := strconv.Atoi(raw)
	if err != nil || deviceID < -1 {
		return 0, fmt.Errorf("%s 必须是 -1 或非负整数，实际 %q", ortDeviceIDEnv, raw)
	}
	return deviceID, nil
}

// Kind 加载方式（决定走哪条 cgo 路径）。
type Kind string

const (
	// KindGeneral 通用 DNN：gocv.ReadNet（cv::dnn 模块加载 ONNX）。适合 metric / classify / 一般 ocr 等。
	KindGeneral Kind = "general"
	// KindMask yolo 实例分割：gocv.CreateORTMask（直接用 onnxruntime + std/mean 预处理 + 自带 RunMask）。
	// VMASK / LTMASK 等用这个。
	KindMask Kind = "mask"
	// KindYolo yolo 检测：gocv.CreateORTYolo（onnxruntime 多输出 + anchors/strides 解码）。
	// VINCHAR 逐字符检测用这个，不能误走 MaskRCNN 解码。
	KindYolo Kind = "yolo"
	// KindCom 通用原始输出模型：gocv.CreateORTCom（onnxruntime 直链，吐扁平 []float32，
	// 后处理由调用方自己做）。yolo-obb（VIN 字符 OBB，输出 [1,6,8400]）用这个。
	KindCom Kind = "com"
)

// Status 一条模型条目的运行时状态。
type Status struct {
	Tag         string `json:"tag"`        // 业务标签（VMASK / VMET / TOCR ...）
	Kind        Kind   `json:"kind"`       // general / mask
	Path        string `json:"path"`       // 本地文件路径（M-S10.4 后会变成 asset object_key）
	Loaded      bool   `json:"loaded"`     // 加载是否成功
	SizeBytes   int64  `json:"size_bytes"` // 模型文件大小
	LoadedAt    string `json:"loaded_at,omitempty"`
	Error       string `json:"error,omitempty"`
	OpenCVEmpty bool   `json:"opencv_empty,omitempty"` // 加载没报错但 net.Empty() 仍为 true 的异常
	// Mask/Yolo 类专用配置。
	Classes []string `json:"classes,omitempty"`
	Strides []int    `json:"strides,omitempty"`
	Anchors []int    `json:"anchors,omitempty"`
}

// Entry 内部条目：Net + Status + 在途推理引用计数。
//
// inflight 防热更 UAF：RunMask / RunCom 取到 net 后会脱离 Registry.mu 跑较长的 cgo 推理，
// 此时若 replace 把旧 entry 顶掉并立即 Release，C++ 侧 net 被销毁 → 在途推理读已释放内存（UAF）。
// 故推理前 acquire（inflight++），推理后 release（inflight--）；replace 顶掉旧 entry 时
// 只有 inflight==0 才立即 Release，否则把释放责任交给最后一个 release 的推理者（延迟到归零）。
type Entry struct {
	net    *gocv.Net
	status Status

	mu       sync.Mutex // 仅保护 inflight / released（不与 Registry.mu 嵌套加锁顺序冲突）
	inflight int        // 当前在途推理数
	released bool       // replace/ReleaseAll 已请求释放；归零后真正 Release
}

// acquire 在途推理 +1，并返回在本次引用期间稳定的 net。
func (e *Entry) acquire() (*gocv.Net, bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.released || e.net == nil {
		return nil, false
	}
	e.inflight++
	return e.net, true
}

// release 在途推理 -1；若已被请求释放且归零，真正 Release 底层 net。
func (e *Entry) release() {
	e.mu.Lock()
	e.inflight--
	var net *gocv.Net
	if e.released && e.inflight <= 0 && e.net != nil {
		net = e.net
		e.net = nil
	}
	e.mu.Unlock()
	// 取出后置于锁外释放（C.Net_Release 可能较慢，且不需持锁）
	if net != nil {
		_ = net.Release()
	}
}

// requestRelease 标记释放：无在途推理立即 Release，否则延迟到最后一个推理归零。
// 返回是否已立即释放（仅用于调试 / 测试断言，调用方可忽略）。
func (e *Entry) requestRelease() {
	e.mu.Lock()
	if e.released {
		e.mu.Unlock()
		return
	}
	e.released = true
	doNow := e.inflight <= 0 && e.net != nil
	var net *gocv.Net
	if doNow {
		net = e.net
		e.net = nil
	}
	e.mu.Unlock()
	if doNow {
		_ = net.Release()
	}
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
//   - path 不存在 / 不可读 → 返 error，并记录 status.Error；entry 保留以便 /cv/v1/models 暴露失败原因
//   - ReadNet 返 net.Empty() == true → 视作加载失败，opencv_empty=true，net 不进入注册表
//   - 加载成功 → loaded=true
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

	// 旧 entry 释放：经 requestRelease 走在途引用计数，避免热更与在途推理竞态导致 UAF。
	if old := r.replace(tag, &Entry{net: &net, status: st}); old != nil {
		old.requestRelease()
	}
	return nil
}

// MaskOptions yolo mask 模型配置（用于 CreateORTMask 的预处理参数）。
type MaskOptions struct {
	Classes []string    // 类名列表，例如 VMASK = ["vin"]
	IWidth  int         // 输入宽（0 让 onnxruntime 从模型 input shape 自动推）
	IHeight int         // 输入高（同上）
	IChan   int         // 输入通道（0 自动）
	Std     float64     // 归一化 std（默认 1.0）
	Mean    gocv.Scalar // 归一化 mean（默认 0）
}

// YoloOptions 是传统 YOLOv5 三输出检测模型的解码配置。
type YoloOptions struct {
	Classes []string
	Strides []int
	Anchors []int
}

var defaultYoloStrides = []int{32, 16, 8}
var defaultYoloAnchors = []int{
	116, 90, 156, 198, 373, 326,
	30, 61, 62, 45, 59, 119,
	10, 13, 16, 30, 33, 23,
}

// DefaultYoloOptions 对齐 GoSmart YOLOv5 默认 anchors/strides。
func DefaultYoloOptions(classes ...string) YoloOptions {
	if len(classes) == 0 {
		classes = []string{"obj"}
	}
	return YoloOptions{
		Classes: append([]string(nil), classes...),
		Strides: append([]int(nil), defaultYoloStrides...),
		Anchors: append([]int(nil), defaultYoloAnchors...),
	}
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

	deviceID, err := ortDeviceID()
	if err != nil {
		st.Error = err.Error()
		r.put(tag, &Entry{status: st})
		return err
	}
	iSize := image.Point{X: opts.IWidth, Y: opts.IHeight}
	net := gocv.CreateORTMask(deviceID, 0, opts.Classes, weights, iSize, opts.IChan, opts.Std, opts.Mean)
	if net == nil || net.Empty() {
		st.Error = "CreateORTMask 失败（onnxruntime 加载或 input shape 推断失败）"
		st.OpenCVEmpty = true
		r.put(tag, &Entry{status: st})
		return errors.New(st.Error)
	}

	st.Loaded = true
	st.LoadedAt = time.Now().UTC().Format(time.RFC3339Nano)

	if old := r.replace(tag, &Entry{net: net, status: st}); old != nil {
		old.requestRelease()
	}
	return nil
}

// RegisterYoloONNX 加载传统 YOLOv5 三输出检测模型。
func (r *Registry) RegisterYoloONNX(tag, path string, opts YoloOptions) error {
	tag = strings.TrimSpace(strings.ToUpper(tag))
	if tag == "" {
		return errors.New("tag 必填")
	}
	if len(opts.Classes) == 0 || len(opts.Strides) == 0 || len(opts.Anchors) == 0 {
		return errors.New("yolo classes/strides/anchors 必填")
	}
	if len(opts.Anchors) != len(opts.Strides)*6 {
		return fmt.Errorf("yolo anchors 数量必须为 strides×6，实际 anchors=%d strides=%d",
			len(opts.Anchors), len(opts.Strides))
	}
	for _, stride := range opts.Strides {
		if stride <= 0 {
			return errors.New("yolo stride 必须为正数")
		}
	}
	for _, anchor := range opts.Anchors {
		if anchor <= 0 {
			return errors.New("yolo anchor 必须为正数")
		}
	}
	st := Status{
		Tag: tag, Kind: KindYolo, Path: path,
		Classes: append([]string(nil), opts.Classes...),
		Strides: append([]int(nil), opts.Strides...),
		Anchors: append([]int(nil), opts.Anchors...),
	}

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

	deviceID, err := ortDeviceID()
	if err != nil {
		st.Error = err.Error()
		r.put(tag, &Entry{status: st})
		return err
	}
	net := gocv.CreateORTYolo(deviceID, 0, opts.Classes, weights, opts.Strides, opts.Anchors)
	if net == nil || net.Empty() {
		st.Error = "CreateORTYolo 失败（onnxruntime 加载或输出 shape 不兼容）"
		st.OpenCVEmpty = true
		r.put(tag, &Entry{status: st})
		return errors.New(st.Error)
	}
	st.Loaded = true
	st.LoadedAt = time.Now().UTC().Format(time.RFC3339Nano)
	if old := r.replace(tag, &Entry{net: net, status: st}); old != nil {
		old.requestRelease()
	}
	return nil
}

// RegisterComONNX 加载通用原始输出模型（gocv.CreateORTCom 路径）。
//
// 用于 yolo-obb 这类「模型只吐原始张量、后处理在 Go 里做」的情形。
// iSize / iChan 传 0 让 onnxruntime 从 input shape 自动推（yolo-obb 固定 1×3×640×640）。
//
// std=1/255、mean=0 等价于把 0..255 像素归一到 0..1（与端侧 ÷255 预处理一致）。
// 失败语义同 RegisterMaskONNX。
func (r *Registry) RegisterComONNX(tag, path string, std float64, mean gocv.Scalar) error {
	tag = strings.TrimSpace(strings.ToUpper(tag))
	if tag == "" {
		return errors.New("tag 必填")
	}
	st := Status{Tag: tag, Kind: KindCom, Path: path}

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

	// iSize/iChan = 0 → 从模型 input shape 自动推断（CreateORTCom 内部 ORTSession_GetInputShapes）。
	deviceID, err := ortDeviceID()
	if err != nil {
		st.Error = err.Error()
		r.put(tag, &Entry{status: st})
		return err
	}
	net := gocv.CreateORTCom(deviceID, 0, weights, image.Point{}, 0, std, mean)
	if net == nil || net.Empty() {
		st.Error = "CreateORTCom 失败（onnxruntime 加载或 input shape 推断失败）"
		st.OpenCVEmpty = true
		r.put(tag, &Entry{status: st})
		return errors.New(st.Error)
	}

	st.Loaded = true
	st.LoadedAt = time.Now().UTC().Format(time.RFC3339Nano)

	if old := r.replace(tag, &Entry{net: net, status: st}); old != nil {
		old.requestRelease()
	}
	return nil
}

// RunCom 跑 KindCom 模型；从注册表拿 net 后调 gocv.RunCom，返回原始 []float32 张量。
//
// blob 必须由调用方用 gocv.BlobFromImage 造好（NCHW，已含归一/swapRB/尺寸）。
func (r *Registry) RunCom(tag string, blob gocv.Mat) ([]float32, error) {
	r.mu.RLock()
	e, ok := r.entries[strings.ToUpper(tag)]
	r.mu.RUnlock()
	if !ok {
		return nil, ErrNotFound
	}
	if e.status.Kind != KindCom {
		return nil, ErrWrongKind
	}
	// acquire 期间持有在途引用：即便此刻热更 replace 顶掉本 entry，底层 net 也延迟到归零才 Release，
	// 不会在 gocv.RunCom 跑 cgo 推理时被销毁（UAF 防护）。
	net, acquired := e.acquire()
	if !acquired {
		return nil, ErrNotFound
	}
	defer e.release()
	return gocv.RunCom(net, blob), nil
}

// CheckKind 检查 tag 已加载且模型类型符合预期，不向调用方暴露可被热更的裸指针。
func (r *Registry) CheckKind(tag string, expected Kind) error {
	r.mu.RLock()
	e, ok := r.entries[strings.ToUpper(tag)]
	r.mu.RUnlock()
	if !ok {
		return ErrNotFound
	}
	if e.status.Kind != expected {
		return ErrWrongKind
	}
	e.mu.Lock()
	available := !e.released && e.net != nil
	e.mu.Unlock()
	if !available {
		return ErrNotFound
	}
	return nil
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
//
// 经 requestRelease 走在途引用计数：关停瞬间若仍有在途推理，net 延迟到归零才真正 Release，避免 UAF。
// ORT 与 OpenCV 异步 worker 会先退出并完成线程内资源清理，再析构 native 句柄。
// Atlas/Lynxi 尚无安全 Destroy API，Release 会明确报错并拒绝误走 OpenCV 析构。
func (r *Registry) ReleaseAll() {
	r.mu.Lock()
	entries := make([]*Entry, 0, len(r.entries))
	for _, e := range r.entries {
		entries = append(entries, e)
	}
	r.mu.Unlock()
	for _, e := range entries {
		e.requestRelease()
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
//	GOMOB_CVENGINE_MODELS="VMET=/path/vmet1.onnx,VMASK:mask=/path/vmask.onnx:vin,VINCHAR:yolo=/path/vins.onnx:0|1|..."
//
// 形式：
//
//	TAG=path                   走 KindGeneral（gocv.ReadNet）
//	TAG:mask=path              走 KindMask（gocv.CreateORTMask），classes=["obj"] 默认
//	TAG:mask=path:cls1|cls2    classes 自定义（| 分隔）
//	TAG:yolo=path:cls1|cls2    走 KindYolo，anchors/strides 用 GoSmart 默认值
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
		// TAG:mask=path → kind=mask；TAG:yolo=path → kind=yolo；TAG:com=path → kind=com
		if colon := strings.IndexByte(left, ':'); colon > 0 {
			tag = strings.ToUpper(strings.TrimSpace(left[:colon]))
			k := strings.ToLower(strings.TrimSpace(left[colon+1:]))
			switch k {
			case string(KindMask):
				kind = KindMask
			case string(KindYolo):
				kind = KindYolo
			case string(KindCom):
				kind = KindCom
			}
		}

		// rest 可能再带 :cls1|cls2 给 mask/yolo classes
		path := rest
		var classes []string
		if kind == KindMask || kind == KindYolo {
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
		case KindYolo:
			_ = r.RegisterYoloONNX(tag, path, DefaultYoloOptions(classes...))
		case KindCom:
			// 通用原始输出：默认 std=1/255、mean=0（÷255 归一），与端侧 yolo-obb 预处理一致。
			_ = r.RegisterComONNX(tag, path, 1.0/255.0, gocv.Scalar{})
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
	if !ok {
		err = ErrNotFound
		return
	}
	if e.status.Kind != KindMask {
		err = ErrWrongKind
		return
	}
	// acquire 防热更 UAF：见 RunCom 注释。
	net, acquired := e.acquire()
	if !acquired {
		err = ErrNotFound
		return
	}
	defer e.release()
	var ids []int
	contours, rrects, ids, scores = gocv.RunMask(net, img, confThreshold, maskThreshold, nmsThreshold, rudeScale)
	classes = gocv.GetClasses(net, ids)
	return
}

// RunYolo 跑 KindYolo 模型，返回原图坐标框、类别 id 和置信度。
func (r *Registry) RunYolo(
	tag string,
	img gocv.Mat,
	confThreshold, nmsThreshold, rudeScale float32,
) ([]image.Rectangle, []int, []float32, error) {
	r.mu.RLock()
	e, ok := r.entries[strings.ToUpper(tag)]
	r.mu.RUnlock()
	if !ok {
		return nil, nil, nil, ErrNotFound
	}
	if e.status.Kind != KindYolo {
		return nil, nil, nil, ErrWrongKind
	}
	net, acquired := e.acquire()
	if !acquired {
		return nil, nil, nil, ErrNotFound
	}
	defer e.release()
	boxes, ids, scores := gocv.RunYolo(net, img, confThreshold, nmsThreshold, rudeScale)
	return boxes, ids, scores, nil
}

// ErrWrongKind 调用了与注册 Kind 不匹配的方法（如对 KindGeneral 调 RunMask）。
var ErrWrongKind = errors.New("model kind 不匹配")
