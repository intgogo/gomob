package laser

import (
	"math"
	"testing"
)

// axle_test.go = 轴距/前后悬 Go 内核验证。对原厂真值会话 Data/100742 复算（与 host harness
// tests/harness/vehicle_axle 同基线）。真值 Result.ini：轴距 710/399/261、前悬261 后悬163。
// + 合成多轴盒（CI 必跑，不依赖外部数据）验证轴心检测的旋转无关与边界。

// TestAxle_VendorGroundTruth 对 100742 融合云复算轴距/前后悬。
func TestAxle_VendorGroundTruth(t *testing.T) {
	a := loadVendorPCD(t, vendorSession+"/1.pcd")
	b := loadVendorPCD(t, vendorSession+"/2.pcd")
	fused := append(append([]float32{}, a...), b...)

	_, ax, _ := MeasureFull(fused, DefaultMeasureParams(), DefaultAxleParams())
	if !ax.Valid {
		t.Fatal("轴心检测无效")
	}
	t.Logf("检出 %d 轴 centers=%v 轴距=%v 总=%.0f 前悬=%.0f 后悬=%.0f",
		ax.NumAxles, round0(ax.AxleCentersMM), round0(ax.WheelbasesMM),
		ax.TotalWheelbaseMM, ax.FrontOverhangMM, ax.RearOverhangMM)

	truth := []float32{710, 399, 261}
	if ax.NumAxles != 4 {
		t.Fatalf("轴数 %d != 4", ax.NumAxles)
	}
	const tolGap = 6.0 // %
	for i, tg := range truth {
		re := relErr(ax.WheelbasesMM[i], tg)
		if re > tolGap {
			t.Errorf("轴距%d %.0f vs %.0f 误差 %.1f%% > %.0f%%", i+1, ax.WheelbasesMM[i], tg, re, tolGap)
		}
	}
	if re := relErr(ax.FrontOverhangMM, 261); re > 12 {
		t.Errorf("前悬 %.0f vs 261 误差 %.1f%% > 12%%", ax.FrontOverhangMM, re)
	}
	if re := relErr(ax.RearOverhangMM, 163); re > 20 {
		t.Errorf("后悬 %.0f vs 163 误差 %.1f%% > 20%%", ax.RearOverhangMM, re)
	}
}

// TestAxle_SyntheticAxles 合成 4 轴车（盒车体 + 触地轮盘），任意旋转下复算轴距。
func TestAxle_SyntheticAxles(t *testing.T) {
	// 车体盒 + 4 个触地轮（轴心 Y=300/1000/1400/1700，前悬300 后悬…），整体绕 Z 转 31°、平移。
	axleY := []float64{300, 1000, 1400, 1700}
	body := makeAxledVehicle(1800, 520, 760, axleY, 31, 450, -300, 20)
	p := DefaultMeasureParams()
	p.UseROI = false
	p.UseROR = false // 合成壳是稀疏面，ROR 会误删；轴心检测本就走 ROR 前的簇
	_, ax, _ := MeasureFull(body, p, DefaultAxleParams())
	if !ax.Valid || ax.NumAxles != 4 {
		t.Fatalf("合成车轴检测失败 valid=%v n=%d", ax.Valid, ax.NumAxles)
	}
	wantGaps := []float32{700, 400, 300}
	t.Logf("合成 轴距=%v 前悬=%.0f 后悬=%.0f", round0(ax.WheelbasesMM), ax.FrontOverhangMM, ax.RearOverhangMM)
	for i, g := range wantGaps {
		if d := math.Abs(float64(ax.WheelbasesMM[i] - g)); d > 30 {
			t.Errorf("合成轴距%d %.0f vs %.0f 差 %.0fmm > 30", i+1, ax.WheelbasesMM[i], g, d)
		}
	}
}

// makeAxledVehicle 造一辆带触地轮的车：长宽高盒(车体悬在 groundClear 以上) + 各轴一对触地轮盘，
// 绕 Z 旋 angDeg、平移 (tx,ty)，点距 step mm。坐标系 z=上、地面 z=0。
func makeAxledVehicle(L, W, H float64, axleY []float64, angDeg, tx, ty, step float64) []float32 {
	const groundClear = 180.0 // 车体底面离地（轮露在其下）
	const wheelR = 90.0
	rad := angDeg * math.Pi / 180
	c, s := math.Cos(rad), math.Sin(rad)
	var out []float32
	emit := func(x, y, z float64) {
		out = append(out, float32(x*c-y*s+tx), float32(x*s+y*c+ty), float32(z))
	}
	// 车体盒外壳（底面在 groundClear，顶在 H）。x∈[-W/2,W/2], y∈[0,L]
	for y := 0.0; y <= L; y += step {
		for x := -W / 2; x <= W/2; x += step {
			emit(x, y, groundClear) // 底
			emit(x, y, H)           // 顶
		}
		for z := groundClear; z <= H; z += step {
			emit(-W/2, y, z)
			emit(W/2, y, z)
		}
	}
	// 各轴一对触地轮（左右），实心盘从地面 0 到 2*wheelR，沿 y 厚 ~80mm。
	for _, ay := range axleY {
		for _, wx := range []float64{-W / 2, W / 2} {
			for dy := -40.0; dy <= 40; dy += step {
				for z := 0.0; z <= 2*wheelR; z += step {
					for x := wx - 35; x <= wx+35; x += step {
						// 圆盘剖面（y-z 平面内半径 wheelR）
						if (ay+dy-ay)*(ay+dy-ay)+(z-wheelR)*(z-wheelR) <= wheelR*wheelR {
							emit(x, ay+dy, z)
						}
					}
				}
			}
		}
	}
	return out
}

func round0(v []float32) []int {
	out := make([]int, len(v))
	for i, x := range v {
		out[i] = int(math.Round(float64(x)))
	}
	return out
}
