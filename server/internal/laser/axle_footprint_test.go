package laser

import (
	"math"
	"testing"
)

// axle_footprint_test.go = M13 轴距足迹裁剪 + 合理性闸测试（真机 job189 网页反馈:
// "总轴距算出来比车长还要长"——足迹外前景残留把接触带地面锚/端点 trim 锚到杂物上）。

// 合成场景：车体(带两个贴地轮) + 车头前方 600mm 处地面杂物堆(足迹外)。
// 旧行为：杂物堆进接触带成假轴/拉长 trim 端点；新行为：足迹裁剪后只见真轮。
func synthVehicleWithWheelsAndJunk() (all []float32, bodyLen float32) {
	var pts []float32
	step := float32(10) // 10mm=体素邻接(主簇连通)；ROR 对稀疏面壳会误删，测试关 ROR(同 TestAxle_SyntheticAxles)
	const L, W, H, clearance = 1800, 520, 700, 120
	// 车体壳(悬在离地间隙上)
	for x := float32(0); x <= L; x += step {
		for y := float32(0); y <= W; y += step {
			pts = append(pts, x, y, clearance+H) // 顶
		}
		for z := float32(clearance); z <= clearance+H; z += step {
			pts = append(pts, x, 0, z, x, W, z) // 两侧
		}
	}
	for y := float32(0); y <= W; y += step {
		for z := float32(clearance); z <= clearance+H; z += step {
			pts = append(pts, 0, y, z, L, y, z) // 两端
		}
	}
	// 两个贴地轮(x=350/1450 处，地面 z=0 到 clearance)
	for _, ax := range []float32{350, 1450} {
		for x := ax - 60; x <= ax+60; x += 5 {
			for y := float32(60); y <= 140; y += 5 {
				for z := float32(0); z <= clearance; z += 5 {
					pts = append(pts, x, y, z, x, W-y, z)
				}
			}
		}
	}
	// 足迹外地面杂物堆(车头前方 600mm，贴地稠密)
	for x := float32(-700); x <= -600; x += 5 {
		for y := float32(150); y <= 350; y += 5 {
			for z := float32(0); z <= 60; z += 5 {
				pts = append(pts, x, y, z)
			}
		}
	}
	// 前端"保险杠角"：贴车头端(l∈[0,40])两侧、接触带高度的稠密结构——距端 ~1% 车长，
	// 端部排除应剔除(真机 job195 前脸下沿伪轴场景)；不剔则会多出一根 3% 前悬的假轴。
	for x := float32(0); x <= 40; x += 5 {
		for y := float32(20); y <= 90; y += 5 {
			for z := float32(0); z <= clearance; z += 5 {
				pts = append(pts, x, y, z, x, W-y, z)
			}
		}
	}
	return pts, L
}

func TestAxleFootprintClipRejectsOutsideJunk(t *testing.T) {
	pts, bodyLen := synthVehicleWithWheelsAndJunk()
	p := GroundMeasureParams([3]float32{0, 0, 1}, 0, 30, 5000)
	p.HeightMin = -10
	p.UseROR = false // 合成壳是稀疏面，ROR 会误删（同 TestAxle_SyntheticAxles）
	d, axle, _ := MeasureFull(pts, p, DefaultAxleParams())
	if !d.Valid {
		t.Fatal("测量无效")
	}
	if !axle.Valid {
		t.Fatalf("应检出真轴, got %+v", axle)
	}
	if axle.NumAxles != 2 {
		t.Fatalf("应检出 2 轴(杂物堆被足迹裁剪排除), got %d 轴 centers=%v", axle.NumAxles, axle.AxleCentersMM)
	}
	if math.Abs(float64(axle.TotalWheelbaseMM-1100)) > 60 {
		t.Fatalf("总轴距应 ≈1100(350↔1450), got %.1f", axle.TotalWheelbaseMM)
	}
	if axle.TotalWheelbaseMM > d.LengthMM {
		t.Fatalf("总轴距 %.1f 不得超过车长 %.1f", axle.TotalWheelbaseMM, d.LengthMM)
	}
	// 前后悬锚在车体足迹端点：前悬+总轴距+后悬 ≈ 车长
	sum := axle.FrontOverhangMM + axle.TotalWheelbaseMM + axle.RearOverhangMM
	if math.Abs(float64(sum-bodyLen)) > 100 {
		t.Fatalf("前悬+轴距+后悬=%.1f 应 ≈ 车长 %.0f", sum, bodyLen)
	}
}

// 合理性闸单测：总轴距>车长 → 整个结果作废(不出物理不可能数)。
func TestGateAxlePlausibility(t *testing.T) {
	bad := AxleResult{Valid: true, NumAxles: 4, TotalWheelbaseMM: 1862}
	d := Dimensions{Valid: true, LengthMM: 1768, BodyRatio: 0.8}
	if got := gateAxlePlausibility(bad, d); got.Valid {
		t.Fatalf("总轴距>车长应作废, got %+v", got)
	}
	ok := AxleResult{Valid: true, NumAxles: 2, TotalWheelbaseMM: 1100}
	if got := gateAxlePlausibility(ok, d); !got.Valid || got.TotalWheelbaseMM != 1100 {
		t.Fatalf("合理结果不应被闸, got %+v", got)
	}
	// 主簇退化(车长不可信)时不判闸——沿用检测结果。
	weak := Dimensions{Valid: true, LengthMM: 10, BodyRatio: 0.05}
	if got := gateAxlePlausibility(bad, weak); !got.Valid {
		t.Fatalf("主簇退化时不应误杀, got %+v", got)
	}
}
