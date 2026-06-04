//go:build laser_cgo

package laser

import (
	"os"
	"sync"
	"sync/atomic"
	"testing"
)

// cgo host 测试：ReplayScan 回放真机录制 PTS .bin，验证流式点穿过 cgo 进 Go、计数一致、
// 状态序列、协作取消。对应 lidar 仓 tests/test_capi.cpp 的 Go 侧镜像。
// 须 -tags laser_cgo 且先跑 scripts/laser-cgo-setup.sh；.bin 路径经 env 传入（无则 skip）。
//
//	LASER_BIN_A / LASER_BIN_B = 录制的 PTS 流（如 /root/lilw/lidar/out_live/car_10x_pts.bin）

func bins(t *testing.T) (string, string) {
	a, b := os.Getenv("LASER_BIN_A"), os.Getenv("LASER_BIN_B")
	if a == "" || b == "" {
		t.Skip("未设 LASER_BIN_A/LASER_BIN_B，跳过 cgo 回放测试")
	}
	return a, b
}

func TestCgoReplayStreaming(t *testing.T) {
	binA, binB := bins(t)

	var ptsA, ptsB, ptsFused int64
	var callsA, callsB int64
	var mu sync.Mutex
	var states []string

	res, err := ReplayScan(binA, binB, "icp", "", 1.0, ScanCallbacks{
		OnPoints: func(f PointFrame) {
			// live 模式两线程并发；这里 replay 顺序，但仍按并发安全写。
			switch f.Unit {
			case 0:
				atomic.AddInt64(&ptsA, int64(f.Points()))
				atomic.AddInt64(&callsA, 1)
			case 1:
				atomic.AddInt64(&ptsB, int64(f.Points()))
				atomic.AddInt64(&callsB, 1)
			case 2:
				atomic.AddInt64(&ptsFused, int64(f.Points()))
			}
			// 抽样校验 mm 量级。
			if len(f.XYZmm) >= 3 {
				for _, v := range f.XYZmm[:3] {
					if v < -25000 || v > 25000 {
						t.Errorf("点超量程: %.1f", v)
						break
					}
				}
			}
		},
		OnStatus: func(state string, a, b int) {
			mu.Lock()
			states = append(states, state)
			mu.Unlock()
		},
	})
	if err != nil {
		t.Fatalf("ReplayScan 失败: %v", err)
	}

	t.Logf("rc=ok align=%s a=%d b=%d fused=%d | stream a=%d b=%d fused=%d states=%v",
		res.Align, res.PtsA, res.PtsB, res.Fused, ptsA, ptsB, ptsFused, states)

	if ptsA == 0 || ptsB == 0 {
		t.Error("两单元都应有流式点")
	}
	if callsA < 10 || callsB < 10 {
		t.Errorf("逐帧回调应多次：callsA=%d callsB=%d", callsA, callsB)
	}
	if int64(res.PtsA) != ptsA || int64(res.PtsB) != ptsB {
		t.Errorf("out 计数与流式累计不符: out(a=%d b=%d) stream(a=%d b=%d)", res.PtsA, res.PtsB, ptsA, ptsB)
	}
	if res.Fused != res.PtsA+res.PtsB {
		t.Errorf("union 融合应 fused==a+b: fused=%d a+b=%d", res.Fused, res.PtsA+res.PtsB)
	}
	if int64(res.Fused) != ptsFused {
		t.Errorf("融合云流式点数与 fused 不符: stream=%d out=%d", ptsFused, res.Fused)
	}
	if res.Align != "icp" && res.Align != "none" {
		t.Errorf("align 应为 icp 或 none(未收敛)，得 %q", res.Align)
	}
	// 状态序列含 scanning/fusing/done。
	seen := map[string]bool{}
	for _, s := range states {
		seen[s] = true
	}
	for _, want := range []string{"scanning", "fusing", "done"} {
		if !seen[want] {
			t.Errorf("状态序列缺 %q: %v", want, states)
		}
	}
}

func TestCgoReplayKeepRatio(t *testing.T) {
	binA, binB := bins(t)
	full, err := ReplayScan(binA, binB, "none", "", 1.0, ScanCallbacks{})
	if err != nil {
		t.Fatalf("full 失败: %v", err)
	}
	half, err := ReplayScan(binA, binB, "none", "", 0.5, ScanCallbacks{})
	if err != nil {
		t.Fatalf("half 失败: %v", err)
	}
	if half.Fused <= 0 || half.Fused >= full.Fused {
		t.Errorf("keep=0.5 融合点数应严格少于全量: half=%d full=%d", half.Fused, full.Fused)
	}
}

func TestCgoReplayCancel(t *testing.T) {
	binA, binB := bins(t)
	var n int64
	_, err := ReplayScan(binA, binB, "none", "", 1.0, ScanCallbacks{
		OnPoints: func(f PointFrame) {
			if atomic.AddInt64(&n, 1) == 5 {
				CancelScan()
			}
		},
	})
	se, ok := err.(*ScanError)
	if !ok || !se.Cancelled() {
		t.Fatalf("取消应返回 ScanError{Code:2}，得 %v", err)
	}
}
