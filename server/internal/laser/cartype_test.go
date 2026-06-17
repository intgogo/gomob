package laser

import "testing"

func TestCarTypeOffsetTable(t *testing.T) {
	cases := map[int][3]float32{
		0:   {-20, -35, 0},   // 牵引头
		2:   {-20, -35, -10}, // 常规
		51:  {90, -100, 10},  // 光板挂车
		13:  {0, 0, 0},       // 自卸式（零偏移）
		-1:  {0, 0, 0},       // 未选
		999: {0, 0, 0},       // 未知编号
	}
	for id, want := range cases {
		if got := CarTypeOffset(id); got != want {
			t.Errorf("CarTypeOffset(%d)=%v，期望 %v", id, got, want)
		}
	}
}

// carType 偏移平移裁剪框中心：框心右移后，原框右界外、新框内的点应被纳入。
func TestCarOffsetShiftsCropBox(t *testing.T) {
	box := CropBox{Center: [3]float32{0, 0, 0}, Up: [3]float32{0, 0, 1}, YawDeg: 0,
		Half: [3]float32{100, 100, 100}} // right=+Y,fwd=+X,up=+Z → 框内 |y|≤100,|x|≤100,|z|≤100
	// 点在 y=150（原框外，u=150>100）；偏移把框心沿 right(+Y) 移 +100 → 新框 |y-100|≤100 即 y∈[0,200] → 纳入。
	xyz := []float32{0, 150, 0, 0, 150, 0, 0, 150, 0}
	for i := 0; i < 200; i++ {
		xyz = append(xyz, 0, 150, 0)
	}
	base := CropBoxMeasureParams(box)
	if d := Measure(xyz, base); d.ROIPts != 0 {
		t.Fatalf("无偏移时 y=150 应全在框外，得 ROIPts=%d", d.ROIPts)
	}
	shifted := CropBoxMeasureParams(box)
	shifted.CarOffset = [3]float32{0, 100, 0} // 沿世界 +Y 移框心 100
	if d := Measure(xyz, shifted); d.ROIPts == 0 {
		t.Errorf("偏移后 y=150 应落入框，得 ROIPts=0")
	}
}
