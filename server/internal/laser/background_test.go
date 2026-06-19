package laser

import (
	"math"
	"math/rand"
	"testing"
)

// background_test.go = 背景相减算法的合成真值验证：房间(地/墙/天花)当背景，房间+车当 live，
// 减后应干净抠出车，且测量 LWH = 已知车尺寸。证"固定安装→背景可减"在数值上闭环（旋转/噪声鲁棒）。

// addPlaneGrid 往 out 追加一个轴对齐矩形面的栅格点（沿 a/b 两轴铺，第三轴固定）。
// axis: 0=平面在 x=fixed(铺 y,z) 1=y=fixed(铺 x,z) 2=z=fixed(铺 x,y)。
func addPlaneGrid(out []float32, axis int, fixed, a0, a1, b0, b1, step float32) []float32 {
	for a := a0; a <= a1; a += step {
		for b := b0; b <= b1; b += step {
			switch axis {
			case 0:
				out = append(out, fixed, a, b)
			case 1:
				out = append(out, a, fixed, b)
			default:
				out = append(out, a, b, fixed)
			}
		}
	}
	return out
}

// makeRoom 造一个空房间点云：地面 z=0 + 4 面墙(x=±X,y=±Y,z∈[0,H]) + 天花 z=H。
func makeRoom(X, Y, H, step float32) []float32 {
	var p []float32
	p = addPlaneGrid(p, 2, 0, -X, X, -Y, Y, step)   // 地
	p = addPlaneGrid(p, 2, H, -X, X, -Y, Y, step)   // 天花
	p = addPlaneGrid(p, 0, -X, -Y, Y, 0, H, step)   // 墙 x=-X
	p = addPlaneGrid(p, 0, X, -Y, Y, 0, H, step)    // 墙 x=+X
	p = addPlaneGrid(p, 1, -Y, -X, X, 0, H, step)   // 墙 y=-Y
	p = addPlaneGrid(p, 1, Y, -X, X, 0, H, step)    // 墙 y=+Y
	return p
}

// makeVehicleShell 造一个箱体车壳(顶面+4 侧面,坐地)，中心(cx,cy)，长 L(x)/宽 W(y)/高 Vh，可绕 z 转 angDeg。
func makeVehicleShell(cx, cy, L, W, Vh, step, angDeg float32) []float32 {
	var raw []float32
	hl, hw := L/2, W/2
	raw = addPlaneGrid(raw, 2, Vh, -hl, hl, -hw, hw, step) // 车顶
	raw = addPlaneGrid(raw, 0, -hl, -hw, hw, 0, Vh, step)  // 前面 x=-hl
	raw = addPlaneGrid(raw, 0, hl, -hw, hw, 0, Vh, step)   // 后面 x=+hl
	raw = addPlaneGrid(raw, 1, -hw, -hl, hl, 0, Vh, step)  // 左 y=-hw
	raw = addPlaneGrid(raw, 1, hw, -hl, hl, 0, Vh, step)   // 右 y=+hw
	c := float32(math.Cos(float64(angDeg) * math.Pi / 180))
	s := float32(math.Sin(float64(angDeg) * math.Pi / 180))
	out := make([]float32, 0, len(raw))
	for i := 0; i+2 < len(raw); i += 3 {
		x, y, z := raw[i], raw[i+1], raw[i+2]
		out = append(out, cx+x*c-y*s, cy+x*s+y*c, z)
	}
	return out
}

// jitter 给点云加 ±amp mm 的确定性抖动(模拟传感器噪声/配准残差)。
func jitter(xyz []float32, amp float32, seed int64) []float32 {
	r := rand.New(rand.NewSource(seed))
	out := make([]float32, len(xyz))
	for i, v := range xyz {
		out[i] = v + (r.Float32()*2-1)*amp
	}
	return out
}

func TestBackgroundSubtract_RecoversVehicle(t *testing.T) {
	const step = 30
	bg := makeRoom(3000, 3000, 3000, step) // 6×6×3m 空房间
	veh := makeVehicleShell(200, -300, 4000, 1800, 1500, step, 18)
	bgVehCnt := len(veh) / 3

	// live = 抖动后的房间 + 车（房间抖动 < tol/2，应被减掉；车无背景近邻，应保留）
	live := append(jitter(bg, 15, 1), veh...)

	fg := SubtractBackground(live, bg, DefaultBackgroundParams())
	fgCnt := len(fg) / 3
	t.Logf("背景=%d live=%d 车真值=%d 前景=%d", len(bg)/3, len(live)/3, bgVehCnt, fgCnt)

	// 前景点数应接近车真值（残留背景散点 < 5%，侵蚀车体 < 5%）
	ratio := float64(fgCnt) / float64(bgVehCnt)
	if ratio < 0.92 || ratio > 1.10 {
		t.Errorf("前景/车真值=%.3f 偏离[0.92,1.10]：残留背景或过度侵蚀", ratio)
	}

	// 前景跨度应≈车包围盒(旋转后 xy 略大)，z≈车高；绝不应出现房间尺度(6000/3000)
	sx, sy, sz := foregroundBBoxSpan(fg)
	t.Logf("前景跨度 X=%.0f Y=%.0f Z=%.0f", sx, sy, sz)
	if sz > 1700 {
		t.Errorf("前景 Z 跨度=%.0f 过大(车高应≈1500)，疑似残留墙/天花", sz)
	}
	if sx > 4600 || sy > 4600 {
		t.Errorf("前景 XY 跨度异常(%.0f,%.0f)，疑似残留房间", sx, sy)
	}

	// 减后直接测量(无 ROI，整前景主簇→OBB)：LWH 应= 车 4000/1800/1500。
	// 合成壳 30mm 间隔：leaf=100 桥接点距+面间空隙、关 ROR(稀疏壳无密邻)；真机密云走默认。
	mp := DefaultMeasureParams()
	mp.UseROI = false
	mp.UseROR = false
	mp.ClusterLeaf = 100
	d := Measure(fg, mp)
	t.Logf("减后测量: valid=%v L=%.0f W=%.0f H=%.0f body=%d", d.Valid, d.LengthMM, d.WidthMM, d.HeightMM, d.BodyPts)
	if !d.Valid {
		t.Fatal("减后测量无效")
	}
	chk := func(name string, got, want, tolFrac float32) {
		if math.Abs(float64(got-want)) > float64(want*tolFrac) {
			t.Errorf("%s=%.0f 偏离真值%.0f 超 %.0f%%", name, got, want, tolFrac*100)
		}
	}
	chk("车长", d.LengthMM, 4000, 0.04)
	chk("车宽", d.WidthMM, 1800, 0.05)
	chk("车高", d.HeightMM, 1500, 0.05)
}

// TestBackgroundSubtract_NoBgPassthrough 背景空时原样返回(由上层兜底，不静默清空)。
func TestBackgroundSubtract_NoBgPassthrough(t *testing.T) {
	veh := makeVehicleShell(0, 0, 4000, 1800, 1500, 50, 0)
	got := SubtractBackground(veh, nil, DefaultBackgroundParams())
	if len(got) != len(veh) {
		t.Errorf("背景空应原样返回，got %d want %d", len(got), len(veh))
	}
}
