package laser

import "testing"

func testRegionFilter() PointRegionFilter {
	return PointRegionFilter{
		Enabled: true,
		Points: [][3]float32{
			{0, 0, 0},
			{100, 0, 0},
			{100, 100, 0},
			{0, 100, 0},
		},
	}
}

func TestPointRegionFilterNormalizes(t *testing.T) {
	f, err := (PointRegionFilter{
		Enabled: true,
		Points: [][3]float32{
			{0, 0, 0},
			{100, 0, 0},
			{100, 100, 0},
			{0, 100, 0},
			{0, 0, 0},
		},
		BToA: []float32{1, 0, 0, 10, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1},
	}).Normalized()
	if err != nil {
		t.Fatal(err)
	}
	if !f.Enabled || len(f.Points) != 4 || len(f.BToA) != 16 {
		t.Fatalf("filter=%+v", f)
	}
	if _, err := (PointRegionFilter{Enabled: true, Points: [][3]float32{{0, 0, 0}, {1, 0, 0}}}).Normalized(); err == nil {
		t.Fatal("少于 3 个区域点应报错")
	}
	if _, err := (PointRegionFilter{Enabled: true, Points: [][3]float32{{0, 0, 0}, {1, 0, 0}, {2, 0, 0}}}).Normalized(); err == nil {
		t.Fatal("共线区域点应报错")
	}
}

func TestFilterPointFrameRegion(t *testing.T) {
	filter := testRegionFilter()
	got := filterPointFrame(PointFrame{Unit: 0, XYZmm: []float32{
		50, 50, 0,
		150, 50, 0,
		100, 20, 0,
	}}, filter)
	if got.Points() != 2 {
		t.Fatalf("应保留墙内和边界点，得 %d: %+v", got.Points(), got.XYZmm)
	}
	if got.XYZmm[0] != 50 || got.XYZmm[3] != 100 {
		t.Fatalf("区域过滤结果错误: %+v", got.XYZmm)
	}
}

func TestFilterRegionUsesBToAForUnitB(t *testing.T) {
	filter := testRegionFilter()
	filter.BToA = []float32{
		1, 0, 0, 100,
		0, 1, 0, 0,
		0, 0, 1, 0,
		0, 0, 0, 1,
	}
	got := filterPointFrame(PointFrame{Unit: 1, XYZmm: []float32{
		-50, 50, 0, // 经 B→A 后在墙内，输出仍保留 B 原坐标
		60, 50, 0,
	}}, filter)
	if got.Points() != 1 || got.XYZmm[0] != -50 {
		t.Fatalf("unitB 应用 B→A 判断墙内失败: %+v", got.XYZmm)
	}
}

func TestFilterColorPointFrameKeepsRGBAligned(t *testing.T) {
	filter := testRegionFilter()
	got := filterColorPointFrame(ColorPointFrame{
		Unit: 0,
		XYZmm: []float32{
			10, 10, 0,
			130, 10, 0,
			20, 90, 0,
		},
		RGB: []uint32{0x112233, 0x445566, 0x778899},
	}, filter)
	if got.Points() != 2 || len(got.RGB) != 2 {
		t.Fatalf("应保留 2 个彩色点，得 pts=%d rgb=%d", got.Points(), len(got.RGB))
	}
	if got.RGB[0] != 0x112233 || got.RGB[1] != 0x778899 {
		t.Fatalf("RGB 未按点同步裁剪: %#v", got.RGB)
	}
}
