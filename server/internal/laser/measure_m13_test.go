package laser

import (
	"math"
	"testing"
)

// measure_m13_test.go = M13 精度收敛新统计量的单测：
// 宽度 1mm bin 消量化、鲁棒分位跨度抗毛边、支撑面相对车高。

// synthBoxPts 合成实心盒表面点（axis 对齐，原点在 min 角），间距 5mm。
func synthBoxPts(l, w, h float32, ox, oy, oz float32) []float32 {
	var pts []float32
	step := float32(5)
	for x := float32(0); x <= l; x += step {
		for y := float32(0); y <= w; y += step {
			pts = append(pts, ox+x, oy+y, oz+h) // 顶面
		}
		for z := float32(0); z <= h; z += step {
			pts = append(pts, ox+x, oy, oz+z, ox+x, oy+w, oz+z) // 两侧面
		}
	}
	for y := float32(0); y <= w; y += step {
		for z := float32(0); z <= h; z += step {
			pts = append(pts, ox, oy+y, oz+z, ox+l, oy+y, oz+z) // 两端面
		}
	}
	return pts
}

// 鲁棒分位跨度应剔掉端面外附着的稀薄毛边带（真机混合像素=贴表面的稠密雾状带，能过主簇/ROR），
// 极值跨度会被撑大。
func TestSpanTrimPctRejectsSpikes(t *testing.T) {
	box := synthBoxPts(1800, 500, 700, 0, 0, 0)
	// 两端各附着一块 60×40×40mm 稠密雾带(5mm 距，768 点/端 < 全体 0.5% → 分位可裁；
	// 贴端面连通 → 主簇保留；局部稠密 → ROR 保留)
	for x := float32(5); x <= 60; x += 5 {
		for y := float32(230); y <= 270; y += 5 {
			for z := float32(330); z <= 370; z += 5 {
				box = append(box, -x, y, z, 1800+x, y, z)
			}
		}
	}

	p := GroundMeasureParams([3]float32{0, 0, 1}, 0, 30, 5000)
	p.HeightMin = -10 // 盒底 z=0 在地面上，band 从地面起
	d := Measure(box, p)
	if !d.Valid {
		t.Fatal("测量无效")
	}
	if math.Abs(float64(d.LengthMM-1920)) > 15 { // 极值语义: 1800+2×60=1920
		t.Fatalf("极值跨度基线应含毛边 ~1920, got %.1f", d.LengthMM)
	}

	p.SpanTrimPct = 0.5
	d2, _, _, overlay := MeasureFullWithOverlay(box, p, DefaultAxleParams())
	if math.Abs(float64(d2.LengthMM-1800)) > 20 {
		t.Fatalf("鲁棒跨度应剔毛边回 ~1800, got %.1f", d2.LengthMM)
	}
	boxLength, boxWidth, boxHeight := boxEdges(overlay.VehicleBox)
	if math.Abs(boxLength-float64(d2.LengthMM)) > 1 ||
		math.Abs(boxWidth-float64(d2.WidthMM)) > 1 ||
		math.Abs(boxHeight-float64(d2.HeightMM)) > 1 {
		t.Fatalf("canonical overlay 必须与鲁棒 LWH 完全同源: dims=%.1f×%.1f×%.1f overlay=%.1f×%.1f×%.1f",
			d2.LengthMM, d2.WidthMM, d2.HeightMM, boxLength, boxWidth, boxHeight)
	}
}

// WidthBinMM=1 时宽度不再被 10mm bin 量化（能出非 10 倍数）；且灾难残留守卫仍生效。
func TestWidthBinFineNoQuantization(t *testing.T) {
	box := synthBoxPts(1800, 527, 700, 0, 0, 0) // 真宽 527（非 10 倍数）
	p := GroundMeasureParams([3]float32{0, 0, 1}, 0, 30, 5000)
	p.HeightMin = -10
	p.SpanTrimPct = 0.5
	p.WidthSupportFrac = 0.15
	p.WidthBinMM = 1
	d := Measure(box, p)
	if !d.Valid {
		t.Fatal("测量无效")
	}
	if math.Abs(float64(d.WidthMM-527)) > 6 {
		t.Fatalf("1mm bin 宽度应 ~527, got %.1f", d.WidthMM)
	}

	// 灾难侧向残留（一条 2000mm 远的稀疏尾巴）→ 支撑修剪守卫应把宽拉回车身
	tail := box
	for i := 0; i < 40; i++ {
		tail = append(tail, 900, 2500+float32(i), 350)
	}
	d3 := Measure(tail, p)
	if d3.WidthMM > 600 {
		t.Fatalf("灾难残留守卫失效: 宽 %.1f (应 <600)", d3.WidthMM)
	}
}

// 悬空车体 + 背景支撑面：车高应 = 车顶 − 支撑面高，而非前景自身 zSpan。
// 模拟背景相减吃掉车底：车体点从台面上方 45mm 起（tol 侵蚀），台面在背景云里。
func TestSupportRelativeHeight(t *testing.T) {
	const standZ, carH, eaten = 300, 760, 45
	// 前景 = 被吃掉底部 eaten 后的车体（z 从 standZ+eaten 到 standZ+carH）
	car := synthBoxPts(1800, 520, carH-eaten, 200, 200, standZ+eaten)
	// 背景 = 台面（车辆足迹下方整面）
	var bg []float32
	for x := float32(0); x <= 2200; x += 10 {
		for y := float32(0); y <= 900; y += 10 {
			bg = append(bg, x, y, standZ)
		}
	}
	p := GroundMeasureParams([3]float32{0, 0, 1}, 0, 30, 5000)
	p.SpanTrimPct = 0.5

	// 无支撑背景：回退 zSpan → 偏短 (carH − eaten)
	d := Measure(car, p)
	if !d.Valid {
		t.Fatal("测量无效")
	}
	if math.Abs(float64(d.HeightMM-(carH-eaten))) > 10 {
		t.Fatalf("无背景基线应 zSpan≈%.0f, got %.1f", float32(carH-eaten), d.HeightMM)
	}

	// 有支撑背景：车高 = 车顶 − 台面 ≈ carH
	p.SupportBG = bg
	d2, _, _, overlay := MeasureFullWithOverlay(car, p, DefaultAxleParams())
	if math.Abs(float64(d2.HeightMM-carH)) > 10 {
		t.Fatalf("支撑面车高应 ≈%d, got %.1f", carH, d2.HeightMM)
	}
	_, _, overlayHeight := boxEdges(overlay.VehicleBox)
	if math.Abs(overlayHeight-float64(d2.HeightMM)) > 1 {
		t.Fatalf("支撑面车高与 overlay 高度分叉: dim=%.1f overlay=%.1f", d2.HeightMM, overlayHeight)
	}
}
