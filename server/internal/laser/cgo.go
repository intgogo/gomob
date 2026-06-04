//go:build laser_cgo

// cgo.go = 把已 byte 验证的 C++ 激光管线（liblidar_scan.a）经 cgo 接进 Go——M8' 的承重接缝。
// 链路已端到端验证（见架构文档 §9.2，真机录制数据 fused=414万 与 result 精确一致）。
//
// 关键模式：
//   - C trampoline（pointTramp/statusTramp）精确匹配 C-ABI 的 const 签名，转调 Go //export；
//   - 用 runtime/cgo.Handle 把 *ScanCallbacks 安全穿过 C 的 void* user（GC 安全，替代包级全局，
//     支持每次扫描独立回调；C-ABI 本身一次一会话）；
//   - 流式点在回调内立即从 C 内存拷成 Go []float32（C 缓冲仅回调期间有效）。
//
// 构建：须 -tags laser_cgo，且先跑 scripts/laser-cgo-setup.sh 把 lidar 的 liblidar_scan.a + 头
// 软链进 internal/laser/native/。LDFLAGS 取自架构文档 §9.2 已验证集合（仅 PCL 核心，无
// OpenCV/Ceres/VTK/Qt）。PCL/flann/yaml-cpp/zstd/boost 为系统库（默认 -L 可寻），仅
// liblidar_scan.a 需自定义 -L${SRCDIR}/native。
package laser

/*
#cgo CFLAGS: -I${SRCDIR}/native/include
#cgo LDFLAGS: -L${SRCDIR}/native -llidar_scan -lpcl_common -lpcl_io -lpcl_kdtree -lpcl_search -lpcl_octree -lpcl_filters -lpcl_registration -lpcl_sample_consensus -lpcl_features -lflann_cpp -lyaml-cpp -lzstd -lboost_system -lboost_filesystem -lstdc++ -lpthread -lm
#include <stdint.h>
#include <stdlib.h>
#include "lidar_scan.h"

// Go //export 声明（cgo 生成于 _cgo_export.h，这里前置声明以供 trampoline 引用）。
void goPointCB(uintptr_t handle, int unit, float* xyz, int n, float h);
void goStatusCB(uintptr_t handle, char* state, int a, int b);

// trampolines：匹配 LidarPointCB/LidarStatusCB 的 const 签名，把 void* user 还原成
// uintptr_t handle 交 Go //export。
static void pointTramp(void* u, int unit, const float* xyz, int n, float h) {
    goPointCB((uintptr_t)u, unit, (float*)xyz, n, h);
}
static void statusTramp(void* u, const char* s, int a, int b) {
    goStatusCB((uintptr_t)u, (char*)s, a, b);
}

static int run_live(const char* ipA, const char* ipB, const char* align, const char* site,
                    float keep, uintptr_t handle, LidarScanResult* out) {
    return lidar_scan_live(ipA, ipB, align, site, keep, pointTramp, statusTramp, (void*)handle, out);
}
static int run_replay(const char* binA, const char* binB, const char* align, const char* site,
                      float keep, uintptr_t handle, LidarScanResult* out) {
    return lidar_scan_replay(binA, binB, align, site, keep, pointTramp, statusTramp, (void*)handle, out);
}
*/
import "C"

import (
	"runtime/cgo"
	"unsafe"
)

//export goPointCB
func goPointCB(handle C.uintptr_t, unit C.int, xyz *C.float, n C.int, hAngle C.float) {
	if n <= 0 || xyz == nil {
		return
	}
	cb, ok := cgo.Handle(handle).Value().(*ScanCallbacks)
	if !ok || cb.OnPoints == nil {
		return
	}
	count := int(n) * 3
	// C 缓冲仅本次回调有效 → 立即拷成 Go 切片。
	src := unsafe.Slice((*float32)(unsafe.Pointer(xyz)), count)
	dst := make([]float32, count)
	copy(dst, src)
	cb.OnPoints(PointFrame{Unit: int(unit), XYZmm: dst, HAngleDeg: float32(hAngle)})
}

//export goStatusCB
func goStatusCB(handle C.uintptr_t, state *C.char, a C.int, b C.int) {
	cb, ok := cgo.Handle(handle).Value().(*ScanCallbacks)
	if !ok || cb.OnStatus == nil {
		return
	}
	cb.OnStatus(C.GoString(state), int(a), int(b))
}

// runScan 统一 live/replay 编排：建 handle、备 CString、调 C、组装 ScanResult。
func runScan(replay bool, aArg, bArg, align, siteJSON string, keepRatio float32, cb ScanCallbacks) (ScanResult, error) {
	h := cgo.NewHandle(&cb)
	defer h.Delete()

	cA, cB := C.CString(aArg), C.CString(bArg)
	defer C.free(unsafe.Pointer(cA))
	defer C.free(unsafe.Pointer(cB))

	if align == "" {
		align = "icp"
	}
	cAlign := C.CString(align)
	defer C.free(unsafe.Pointer(cAlign))

	var cSite *C.char
	if siteJSON != "" {
		cSite = C.CString(siteJSON)
		defer C.free(unsafe.Pointer(cSite))
	}

	var res C.LidarScanResult
	var rc C.int
	if replay {
		rc = C.run_replay(cA, cB, cAlign, cSite, C.float(keepRatio), C.uintptr_t(h), &res)
	} else {
		rc = C.run_live(cA, cB, cAlign, cSite, C.float(keepRatio), C.uintptr_t(h), &res)
	}

	out := ScanResult{
		PtsA:      int(res.pts_a),
		PtsB:      int(res.pts_b),
		Fused:     int(res.fused),
		AfterCrop: int(res.after_crop),
		Align:     C.GoString(&res.align[0]),
		Error:     C.GoString(&res.error[0]),
	}
	for i := 0; i < 16; i++ {
		out.BToA[i] = float32(res.b_to_a[i])
	}
	if rc != 0 {
		return out, &ScanError{Code: int(rc), Msg: out.Error}
	}
	return out, nil
}

// LiveScan 实时扫描两单元(ipA/ipB)并融合，阻塞至完成/错误/取消。回调流式推点。
func LiveScan(ipA, ipB, align, siteJSON string, keepRatio float32, cb ScanCallbacks) (ScanResult, error) {
	return runScan(false, ipA, ipB, align, siteJSON, keepRatio, cb)
}

// ReplayScan 离线回放录制的 PTS .bin（host 测试 / 无硬件），其余同 LiveScan。
func ReplayScan(binA, binB, align, siteJSON string, keepRatio float32, cb ScanCallbacks) (ScanResult, error) {
	return runScan(true, binA, binB, align, siteJSON, keepRatio, cb)
}

// CancelScan 协作取消当前扫描会话（C-ABI 一次一会话；停采集，live 下设备 SCAN_STOP 由上层 devctl 发）。
func CancelScan() { C.lidar_scan_cancel() }
