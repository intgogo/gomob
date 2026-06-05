//go:build laser_cgo

package laser

import (
	"context"
	"log/slog"
	"os"
	"testing"
	"time"
)

// E1 真机端到端：用真实 Runner（真 devctl 门控 SCAN_START/STOP + 真 cgo LiveScan + fake 存储/repo）
// 对真 .101/.102 跑一次受控扫描，验证 live 采集+融合路径（G3 仅回放，live 在此首次真机验证）。
//
// 须真机在场 + 现场清空（电机会转）；-tags laser_cgo 且先 scripts/laser-cgo-setup.sh。env 门控：
//
//	LASER_LIVE_A / LASER_LIVE_B = 两单元 IP（如 192.168.9.101 / 192.168.9.102）
func TestRunnerLiveReal(t *testing.T) {
	ipA, ipB := os.Getenv("LASER_LIVE_A"), os.Getenv("LASER_LIVE_B")
	if ipA == "" || ipB == "" {
		t.Skip("未设 LASER_LIVE_A/LASER_LIVE_B，跳过真机 live 测试")
	}
	ctx := context.Background()

	// 1) 探活两单元（采集子系统须在线；.101 相机故障不阻点云）。
	pa := NewDeviceClient(ipA, 3*time.Second).Probe(ctx)
	pb := NewDeviceClient(ipB, 3*time.Second).Probe(ctx)
	t.Logf("probe A=%+v", pa)
	t.Logf("probe B=%+v", pb)
	if !pa.Reachable || !pa.Online {
		t.Fatalf("unitA 未就绪: %+v", pa)
	}
	if !pb.Reachable || !pb.Online {
		t.Fatalf("unitB 未就绪: %+v", pb)
	}

	// 2) 真 Runner：真 Gate（devctl SCAN_START/STOP）+ 真 LiveScan + fake 存储/repo + 记录 Sink。
	jobs := &fakeJobStore{}
	clouds := &fakeCloudStore{}
	sink := &recordSink{}
	r := NewRunner(jobs, clouds, nil, slog.Default())
	// 角度下发关（false）：本 E2E 只验采集/起停，不在测试里改设备持久化扫描角度。
	r.Gate = NewDevctlGate(ipA, ipB, 0, 90, -180, 20, false, slog.Default())

	job, err := r.Run(ctx, RunSpec{
		JobID:      1,
		SessionKey: "live-e2e",
		UnitAIP:    ipA,
		UnitBIP:    ipB,
		Align:      "icp",
		KeepRatio:  1.0,
	}, sink)
	if err != nil {
		t.Fatalf("Run 失败: %v", err)
	}

	t.Logf("done: status=%s fused=%d unitA=%d unitB=%d sink_frames=%d sink_states=%v",
		job.Status, clouds.counts["fused"], clouds.counts["unit_a"], clouds.counts["unit_b"], sink.frames, sink.statuses)

	// 3) 断言：完成、两单元都有点、融合 = union。
	if job.Status != "done" {
		t.Fatalf("job 应 done，得 %s", job.Status)
	}
	a, b, fused := clouds.counts["unit_a"], clouds.counts["unit_b"], clouds.counts["fused"]
	if a <= 0 || b <= 0 {
		t.Errorf("两单元都应有点: a=%d b=%d", a, b)
	}
	if fused != a+b {
		t.Errorf("union 融合应 fused==a+b: fused=%d a+b=%d", fused, a+b)
	}
	if sink.frames <= 0 {
		t.Error("Sink 应收到流式点帧")
	}
	// 状态序列含 scanning/fusing/done。
	seen := map[string]bool{}
	for _, s := range sink.statuses {
		seen[s] = true
	}
	for _, w := range []string{"scanning", "fusing", "done"} {
		if !seen[w] {
			t.Errorf("状态序列缺 %q: %v", w, sink.statuses)
		}
	}
}
