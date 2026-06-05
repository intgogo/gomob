package laser

import (
	"math"
	"testing"
)

// transformPoints 行优先 4x4 仿射：锁 B→A(res.BToA 行优先)约定。约定错=双框 crop_box_dual
// 把 unitB 点变换到错误世界系、测量静默偏。验证 单位阵不变 / 纯平移 / 绕 Z 90°+平移。
func TestTransformPoints_RowMajor(t *testing.T) {
	approx := func(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-4 }
	// 单位阵：点不变。
	id := [16]float32{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1}
	got := transformPoints([]float32{3, 4, 5}, id)
	if !(approx(got[0], 3) && approx(got[1], 4) && approx(got[2], 5)) {
		t.Errorf("单位阵应不变，得 %v", got)
	}
	// 纯平移 (10,20,30)。
	tr := [16]float32{1, 0, 0, 10, 0, 1, 0, 20, 0, 0, 1, 30, 0, 0, 0, 1}
	got = transformPoints([]float32{1, 2, 3}, tr)
	if !(approx(got[0], 11) && approx(got[1], 22) && approx(got[2], 33)) {
		t.Errorf("平移应 +（10,20,30），得 %v", got)
	}
	// 绕 Z 90°（行优先 [0,-1,0,tx; 1,0,0,ty; 0,0,1,tz]）+ 平移：点(1,0,0)→(tx, 1+ty, tz)。
	rz := [16]float32{0, -1, 0, 10, 1, 0, 0, 20, 0, 0, 1, 30, 0, 0, 0, 1}
	got = transformPoints([]float32{1, 0, 0}, rz)
	if !(approx(got[0], 10) && approx(got[1], 21) && approx(got[2], 30)) {
		t.Errorf("绕Z 90°+平移应 (10,21,30)，得 %v", got)
	}
}

// Up=+Z、Yaw=0：基应为 right=+Y、fwd=+X、up=+Z（groundBasis 对 +Z 的确定性结果：fwd=right×up）。
func TestCropBoxBasis_FlatGround(t *testing.T) {
	b := CropBox{Up: [3]float32{0, 0, 1}, YawDeg: 0}
	r, f, u := b.Basis()
	approx := func(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-5 }
	if !(approx(r[0], 0) && approx(r[1], 1) && approx(r[2], 0)) {
		t.Errorf("right 应≈(0,1,0)，得 %v", r)
	}
	if !(approx(f[0], 1) && approx(f[1], 0) && approx(f[2], 0)) {
		t.Errorf("fwd 应≈(1,0,0)，得 %v", f)
	}
	if !approx(u[2], 1) {
		t.Errorf("up 应≈(0,0,1)，得 %v", u)
	}
}

// Yaw=90° 应把 right/fwd 在地面内旋 90°（仍正交于 up）。
func TestCropBoxBasis_Yaw(t *testing.T) {
	b := CropBox{Up: [3]float32{0, 0, 1}, YawDeg: 90}
	r, f, _ := b.Basis()
	// right0=(0,1,0), fwd0=(1,0,0)；yaw90(c=0,s=1): right=right0*0+fwd0*1=(1,0,0), fwd=-right0*1+fwd0*0=(0,-1,0)
	approx := func(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-5 }
	if !(approx(r[0], 1) && approx(r[1], 0)) {
		t.Errorf("yaw90 right 应≈(1,0,0)，得 %v", r)
	}
	if !(approx(f[0], 0) && approx(f[1], -1)) {
		t.Errorf("yaw90 fwd 应≈(0,-1,0)，得 %v", f)
	}
}

// 框裁剪：中心(1000,0,500)、Up=+Z、Yaw=0、半尺(右300,前500,上200)。right=+Y,fwd=-X,up=+Z。
func TestCropToBox(t *testing.T) {
	b := CropBox{Center: [3]float32{1000, 0, 500}, Up: [3]float32{0, 0, 1}, YawDeg: 0,
		Half: [3]float32{300, 500, 200}}
	in := []float32{
		1000, 0, 500, // 正中 → 收
		1000, 250, 600, // u=250≤300,w=100≤200 → 收
		1000, 400, 500, // u=400>300 → 弃
		1600, 0, 500, // v=-600,|v|>500 → 弃
		1000, 0, 800, // w=300>200 → 弃
	}
	out := CropToBox(in, b)
	if len(out)/3 != 2 {
		t.Fatalf("应保留 2 点，得 %d 点 (%v)", len(out)/3, out)
	}
}

// toBoxFrame 框内点应变换到局部系(u=右,v=前,w=上)且只留框内。
func TestToBoxFrame(t *testing.T) {
	b := CropBox{Center: [3]float32{1000, 0, 500}, Up: [3]float32{0, 0, 1}, YawDeg: 0,
		Half: [3]float32{300, 500, 200}}
	// 点(1000,250,600)：相对框心(0,250,100)；right=+Y→u=250, fwd=-X→v=0, up=+Z→w=100
	got := toBoxFrame([]pt{{1000, 250, 600}}, b)
	if len(got) != 1 {
		t.Fatalf("应留 1 点，得 %d", len(got))
	}
	q := got[0]
	approx := func(a, b float32) bool { return math.Abs(float64(a-b)) < 1e-4 }
	if !(approx(q.x, 250) && approx(q.y, 0) && approx(q.z, 100)) {
		t.Errorf("局部系应≈(250,0,100)，得 (%.1f,%.1f,%.1f)", q.x, q.y, q.z)
	}
}

func TestCropBoxValid(t *testing.T) {
	if !(CropBox{Up: [3]float32{0, 0, 1}, Half: [3]float32{1, 1, 1}}).Valid() {
		t.Error("正常框应 Valid")
	}
	if (CropBox{Up: [3]float32{0, 0, 1}, Half: [3]float32{0, 1, 1}}).Valid() {
		t.Error("零半尺应 invalid")
	}
	if (CropBox{Half: [3]float32{1, 1, 1}}).Valid() {
		t.Error("零 Up 应 invalid")
	}
}
