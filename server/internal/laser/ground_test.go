package laser

import (
	"math"
	"testing"
)

// 合成：z=0 水平地面网格 + 上方一个箱体（物体）。期望法向≈+Z、定向朝上、Valid。
func TestDetectGroundHorizontal(t *testing.T) {
	var xyz []float32
	for gx := -2000; gx <= 2000; gx += 50 { // 地面 81×81 点
		for gy := -2000; gy <= 2000; gy += 50 {
			xyz = append(xyz, float32(gx), float32(gy), 0)
		}
	}
	for bx := -500; bx <= 500; bx += 50 { // 上方箱体（z=200..800）
		for by := -300; by <= 300; by += 50 {
			for bz := 200; bz <= 800; bz += 100 {
				xyz = append(xyz, float32(bx), float32(by), float32(bz))
			}
		}
	}
	g := DetectGround(xyz, DefaultGroundParams())
	if !g.Valid {
		t.Fatalf("应检出地面，得 Valid=false (%+v)", g)
	}
	if math.Abs(float64(g.NZ)) < 0.99 {
		t.Errorf("法向应≈±Z，得 (%.3f,%.3f,%.3f)", g.NX, g.NY, g.NZ)
	}
	if g.NZ < 0 { // 定向：物体在 +Z 上方 → 法向应朝 +Z
		t.Errorf("法向应朝上(+Z)，得 NZ=%.3f", g.NZ)
	}
	// 平面应过 z≈0：法向≈(0,0,1) 时 d≈0
	if math.Abs(float64(g.D)) > 50 {
		t.Errorf("地面 offset 应≈0，得 d=%.1f", g.D)
	}
}

// 合成倾斜地面：绕 X 轴倾 ~5.6°（z = tan(5.6°)*y）。期望法向带相应 Y 分量。
func TestDetectGroundTilted(t *testing.T) {
	tilt := math.Tan(5.6 * math.Pi / 180)
	var xyz []float32
	for gx := -2000; gx <= 2000; gx += 50 {
		for gy := -2000; gy <= 2000; gy += 50 {
			z := tilt * float64(gy)
			xyz = append(xyz, float32(gx), float32(gy), float32(z))
		}
	}
	g := DetectGround(xyz, DefaultGroundParams())
	if !g.Valid {
		t.Fatalf("应检出倾斜地面，得 Valid=false")
	}
	// 法向应≈(0, -sin, cos)，倾角 ~5.6°
	ang := math.Acos(math.Abs(float64(g.NZ))) * 180 / math.Pi
	if math.Abs(ang-5.6) > 2 {
		t.Errorf("倾角应≈5.6°，得 %.1f°（法向 %.3f,%.3f,%.3f）", ang, g.NX, g.NY, g.NZ)
	}
}

func TestDetectGroundDegenerate(t *testing.T) {
	if g := DetectGround(nil, DefaultGroundParams()); g.Valid {
		t.Error("空输入应 Valid=false")
	}
	if g := DetectGround([]float32{0, 0, 0, 1, 1, 1}, DefaultGroundParams()); g.Valid {
		t.Error("点太少应 Valid=false")
	}
}

// 爆表/NaN 点不应毁拟合（被 isFiniteSane 剔除）。
func TestDetectGroundIgnoresGarbage(t *testing.T) {
	var xyz []float32
	for gx := -1000; gx <= 1000; gx += 40 {
		for gy := -1000; gy <= 1000; gy += 40 {
			xyz = append(xyz, float32(gx), float32(gy), 0)
		}
	}
	xyz = append(xyz, 1e37, 2e36, float32(math.NaN()), -5e34, 1e30, 7e33) // 2 个垃圾点
	g := DetectGround(xyz, DefaultGroundParams())
	if !g.Valid || math.Abs(float64(g.NZ)) < 0.99 {
		t.Errorf("含垃圾点仍应稳健检出地面，得 %+v", g)
	}
}
