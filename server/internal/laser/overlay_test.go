package laser

import (
	"math"
	"testing"
)

// overlay_test.go = 叠加几何验证。盒的世界系 8 角点用 **棱长**(旋转无关)校验：box8 角点序
// 0=(lMin,wMin,hMin) 1=(lMax,..) 3=(..,wMax,..) 4=(..,hMax) → 棱 0-1=长 0-3=宽 0-4=高。

func dist3(a, b [3]float32) float64 {
	dx, dy, dz := float64(a[0]-b[0]), float64(a[1]-b[1]), float64(a[2]-b[2])
	return math.Sqrt(dx*dx + dy*dy + dz*dz)
}

func boxEdges(c [][3]float32) (l, w, h float64) {
	return dist3(c[0], c[1]), dist3(c[0], c[3]), dist3(c[0], c[4])
}

// TestOverlay_BoxTruck 合成车头+货箱：车体框/货箱框棱长匹配已知尺寸（任意旋转）。
func TestOverlay_BoxTruck(t *testing.T) {
	const cabLen, gap, boxLen = 500.0, 80.0, 1000.0
	const outerW, innerW, bed, boxTop, cabRoof = 600.0, 520.0, 300.0, 760.0, 600.0
	totalLen := cabLen + gap + boxLen
	for _, ang := range []float64{0, 27, 63} {
		body := makeBoxTruck(cabLen, gap, boxLen, outerW, innerW, bed, boxTop, cabRoof, ang, 700, -200, 15)
		p := DefaultMeasureParams()
		p.UseROI = false
		p.UseROR = false
		p.ClusterLeaf = 100 // 合成壳无连接底盘 + 点稀疏，粗体素桥接车头/箱使主簇含全车（真机连续不需要）
		ov := BuildVehicleOverlay(body, p, DefaultAxleParams(), DefaultCargoBoxParams())
		if !ov.Valid || len(ov.VehicleBox) != 8 {
			t.Fatalf("ang=%.0f° 叠加无效", ang)
		}
		vl, vw, vh := boxEdges(ov.VehicleBox)
		t.Logf("ang=%.0f° 车体框 L=%.0f W=%.0f H=%.0f", ang, vl, vw, vh)
		// 车体框三棱 = {全长,全宽,全高} 的某排列。
		assertEdgeSet(t, "车体框", []float64{vl, vw, vh}, []float64{totalLen, outerW, boxTop}, 30)
		if !ov.HasCargoBox || len(ov.CargoBox) != 8 {
			t.Fatalf("ang=%.0f° 无货箱框", ang)
		}
		cl, cw, ch := boxEdges(ov.CargoBox)
		t.Logf("ang=%.0f° 货箱框 L=%.0f W=%.0f H=%.0f", ang, cl, cw, ch)
		assertEdgeSet(t, "货箱框", []float64{cl, cw, ch}, []float64{boxLen, outerW, boxTop - bed}, 60)
	}
}

func TestOverlay_RejectsThinCargoResidual(t *testing.T) {
	body := makeVehicleWithThinCargoResidual()
	p := DefaultMeasureParams()
	p.UseROI = false
	p.UseROR = false
	p.ClusterLeaf = 100
	ov := BuildVehicleOverlay(body, p, DefaultAxleParams(), DefaultCargoBoxParams())
	if !ov.Valid {
		t.Fatal("叠加无效")
	}
	if ov.HasCargoBox || len(ov.CargoBox) > 0 {
		t.Fatalf("细长高残留不应绘制货箱叠加: %+v", ov)
	}
}

// TestOverlay_AxleLines 合成 4 轴车：叠加轴线数=轴数，端点横跨车宽。
func TestOverlay_AxleLines(t *testing.T) {
	axleY := []float64{300, 1000, 1400, 1700}
	body := makeAxledVehicle(1800, 520, 760, axleY, 31, 450, -300, 20)
	p := DefaultMeasureParams()
	p.UseROI = false
	p.UseROR = false
	p.ClusterLeaf = 100 // 合成壳稀疏，粗体素保主簇含全车（真机连续不需要）
	ov := BuildVehicleOverlay(body, p, DefaultAxleParams(), DefaultCargoBoxParams())
	if !ov.Valid {
		t.Fatal("叠加无效")
	}
	if len(ov.AxleLines) != 4 {
		t.Fatalf("轴线数 %d != 4", len(ov.AxleLines))
	}
	for i, ln := range ov.AxleLines {
		span := dist3(ln[0], ln[1])
		t.Logf("轴线%d 横跨=%.0f", i+1, span)
		if span < 400 || span > 640 { // ≈车宽 520，允许端噪
			t.Errorf("轴线%d 横跨 %.0f 不在 [400,640]", i+1, span)
		}
	}
}

// TestOverlay_VendorReal 100742：车体框/货箱框/轴线非空且角点落在融合云包围盒内。
func TestOverlay_VendorReal(t *testing.T) {
	a := loadVendorPCD(t, vendorSession+"/1.pcd")
	b := loadVendorPCD(t, vendorSession+"/2.pcd")
	fused := append(append([]float32{}, a...), b...)
	ov := BuildVehicleOverlay(fused, DefaultMeasureParams(), DefaultAxleParams(), DefaultCargoBoxParams())
	if !ov.Valid || len(ov.VehicleBox) != 8 {
		t.Fatal("100742 叠加无效")
	}
	if !ov.HasCargoBox || len(ov.AxleLines) != 4 {
		t.Fatalf("100742 期望有货箱+4 轴线, got box=%v lines=%d", ov.HasCargoBox, len(ov.AxleLines))
	}
	// 角点须落在云包围盒内（含余量），证世界系映射没飞出去。
	var mn, mx [3]float32
	for k := 0; k < 3; k++ {
		mn[k], mx[k] = math.MaxFloat32, -math.MaxFloat32
	}
	for i := 0; i+2 < len(fused); i += 3 {
		for k := 0; k < 3; k++ {
			mn[k] = minf(mn[k], fused[i+k])
			mx[k] = maxf(mx[k], fused[i+k])
		}
	}
	for _, c := range ov.VehicleBox {
		for k := 0; k < 3; k++ {
			if c[k] < mn[k]-100 || c[k] > mx[k]+100 {
				t.Errorf("车体框角点 axis%d=%.0f 飞出云包围盒[%.0f,%.0f]", k, c[k], mn[k], mx[k])
			}
		}
	}
	vl, vw, vh := boxEdges(ov.VehicleBox)
	t.Logf("100742 车体框 L=%.0f W=%.0f H=%.0f；货箱框棱 %v", vl, vw, vh, sliceEdges(ov.CargoBox))
}

func sliceEdges(c [][3]float32) []int {
	if len(c) != 8 {
		return nil
	}
	l, w, h := boxEdges(c)
	return []int{int(l), int(w), int(h)}
}

// assertEdgeSet 校验三棱(无序)匹配期望三值，容差 tol mm。
func assertEdgeSet(t *testing.T, name string, got, want []float64, tol float64) {
	t.Helper()
	used := make([]bool, len(want))
	for _, g := range got {
		ok := false
		for j, w := range want {
			if !used[j] && math.Abs(g-w) <= tol {
				used[j] = true
				ok = true
				break
			}
		}
		if !ok {
			t.Errorf("%s 棱 %.0f 不匹配期望 %v(±%.0f)", name, g, want, tol)
		}
	}
}
