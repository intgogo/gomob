package laser

import (
	"math"
	"testing"
)

// cargobox_test.go = 货箱测量 Go 内核验证。① 合成"车头+开顶货箱"已知尺寸数值闭环（CI 必跑，
// 含任意旋转证 OBB 无关）；② 原厂 100742 分割 sanity（无货箱真值，验分割+外尺寸与 rim 一致）。
// 与 host harness tests/harness/vehicle_cargobox 同基线。

// makeBoxTruck 合成 车头(矮实心盒) + 开顶货箱(四壁+底,已知内/外尺寸)，绕 Z 旋 angDeg、平移。
// z=上,地面 z=0,车长沿局部 y。
func makeBoxTruck(cabLen, gap, boxLen, outerW, innerW, bed, boxTop, cabRoof, angDeg, tx, ty, step float64) []float32 {
	rad := angDeg * math.Pi / 180
	c, s := math.Cos(rad), math.Sin(rad)
	var out []float32
	emit := func(x, y, z float64) {
		out = append(out, float32(x*c-y*s+tx), float32(x*s+y*c+ty), float32(z))
	}
	W := outerW
	// 车头实心盒外壳，顶 cabRoof(< boxTop)
	for y := 0.0; y < cabLen; y += step {
		for x := -W / 2; x < W/2; x += step {
			emit(x, y, 0)
			emit(x, y, cabRoof)
		}
		for z := 0.0; z < cabRoof; z += step {
			emit(-W/2, y, z)
			emit(W/2, y, z)
		}
	}
	by0 := cabLen + gap
	by1 := by0 + boxLen
	// 货箱：bed 底面(内腔) + 左右内外壁(bed→boxTop)
	for y := by0; y < by1; y += step {
		for x := -innerW / 2; x < innerW/2; x += step {
			emit(x, y, bed)
		}
		for z := bed; z < boxTop; z += step {
			emit(-outerW/2, y, z)
			emit(outerW/2, y, z)
			emit(-innerW/2, y, z)
			emit(innerW/2, y, z)
		}
	}
	for x := -outerW / 2; x < outerW/2; x += step { // 前后壁
		for z := bed; z < boxTop; z += step {
			emit(x, by0, z)
			emit(x, by1, z)
		}
	}
	return out
}

// TestCargoBox_SyntheticGroundTruth 合成货箱已知尺寸闭环（旋转无关）。
func TestCargoBox_SyntheticGroundTruth(t *testing.T) {
	const cabLen, gap, boxLen = 500.0, 80.0, 1000.0
	const outerW, innerW, bed, boxTop, cabRoof = 600.0, 520.0, 300.0, 760.0, 600.0
	for _, ang := range []float64{0, 27, 63} {
		body := makeBoxTruck(cabLen, gap, boxLen, outerW, innerW, bed, boxTop, cabRoof, ang, 700, -200, 15)
		pts := toPoints(body)
		cb := DetectCargoBox(pts, float32(ang), DefaultCargoBoxParams())
		if !cb.Valid || !cb.HasBox {
			t.Fatalf("ang=%.0f° 未检出货箱 valid=%v", ang, cb.Valid)
		}
		t.Logf("ang=%.0f° 外长=%.0f/%.0f 外宽=%.0f/%.0f 箱深=%.0f/%.0f 内宽=%.0f/%.0f",
			ang, cb.OuterLengthMM, boxLen, cb.OuterWidthMM, outerW,
			cb.DepthMM, boxTop-bed, cb.InnerWidthMM, innerW)
		if e := pctErr(cb.OuterLengthMM, boxLen); e > 8 {
			t.Errorf("ang=%.0f° 外长 %.0f vs %.0f 误差 %.1f%%", ang, cb.OuterLengthMM, boxLen, e)
		}
		if e := pctErr(cb.OuterWidthMM, outerW); e > 8 {
			t.Errorf("ang=%.0f° 外宽 %.0f vs %.0f 误差 %.1f%%", ang, cb.OuterWidthMM, outerW, e)
		}
		if e := pctErr(cb.DepthMM, boxTop-bed); e > 12 {
			t.Errorf("ang=%.0f° 箱深 %.0f vs %.0f 误差 %.1f%%", ang, cb.DepthMM, boxTop-bed, e)
		}
	}
}

func makeVehicleWithThinCargoResidual() []float32 {
	body := makeBoxGo(1800, 550, 520, 0, 0, 0, 0, 20)
	add := func(x, y, z float32) {
		body = append(body, x, y, z)
	}
	for x := float32(1200); x <= 1280; x += 10 {
		for y := float32(250); y <= 300; y += 10 {
			for z := float32(300); z <= 2300; z += 20 {
				add(x, y, z)
			}
		}
	}
	return body
}

func TestCargoBox_RejectsThinVerticalResidual(t *testing.T) {
	body := toPoints(makeVehicleWithThinCargoResidual())
	cb := DetectCargoBox(body, 0, DefaultCargoBoxParams())
	if cb.Valid || cb.HasBox {
		t.Fatalf("细长高残留不应识别成货箱: %+v", cb)
	}
	if cargoBoxPhysicallyCredible(CargoBox{OuterLengthMM: 80, OuterWidthMM: 51, DepthMM: 2294}, 1839, 572, 2333, 20) {
		t.Fatal("183 反馈里的 80×51×2294mm 不可能货箱通过了物理闸")
	}
}

// TestCargoBox_VendorSanity 原厂 100742 货箱分割 sanity（无数值真值，验分割合理 + 外尺寸与 rim 一致）。
func TestCargoBox_VendorSanity(t *testing.T) {
	a := loadVendorPCD(t, vendorSession+"/1.pcd")
	b := loadVendorPCD(t, vendorSession+"/2.pcd")
	fused := append(append([]float32{}, a...), b...)
	_, _, cb := MeasureFull(fused, DefaultMeasureParams(), DefaultAxleParams())
	if !cb.Valid || !cb.HasBox {
		t.Fatal("100742 未检出货箱(应有)")
	}
	t.Logf("100742 货箱 外长=%.0f 外宽=%.0f 箱深=%.0f 箱顶=%.0f bed=%.0f 内宽=%.0f",
		cb.OuterLengthMM, cb.OuterWidthMM, cb.DepthMM, cb.TopZMM, cb.BedZMM, cb.InnerWidthMM)
	// rim/EDA 基准：外长≈1046、外宽≈466~506、箱深≈455。
	if cb.OuterLengthMM < 950 || cb.OuterLengthMM > 1150 {
		t.Errorf("外长 %.0f 不在 [950,1150]", cb.OuterLengthMM)
	}
	if cb.OuterWidthMM < 420 || cb.OuterWidthMM > 540 {
		t.Errorf("外宽 %.0f 不在 [420,540]", cb.OuterWidthMM)
	}
	if cb.DepthMM < 380 || cb.DepthMM > 560 {
		t.Errorf("箱深 %.0f 不在 [380,560]", cb.DepthMM)
	}
}

func pctErr(v, truth float32) float64 {
	return math.Abs(float64(v-truth)) / math.Abs(float64(truth)) * 100
}
