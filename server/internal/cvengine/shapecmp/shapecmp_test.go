package shapecmp

import (
	"math"
	"testing"
)

func f32(v float32) *float32 { return &v }

func TestIoU3D_Identical(t *testing.T) {
	a := &BBox{0, 0, 0, 1, 1, 1}
	if iou := IoU3D(a, a); math.Abs(iou-1.0) > 1e-9 {
		t.Fatalf("identical 应 IoU=1，got %f", iou)
	}
}

func TestIoU3D_HalfOverlap(t *testing.T) {
	a := &BBox{0, 0, 0, 1, 1, 1}
	// b 沿 X 平移 0.5，重叠体积 0.5*1*1=0.5
	b := &BBox{0.5, 0, 0, 1.5, 1, 1}
	got := IoU3D(a, b)
	// inter=0.5, union=1+1-0.5=1.5, iou=1/3
	want := 1.0 / 3
	if math.Abs(got-want) > 1e-9 {
		t.Fatalf("期望 %f，got %f", want, got)
	}
}

func TestIoU3D_NoOverlap(t *testing.T) {
	a := &BBox{0, 0, 0, 1, 1, 1}
	b := &BBox{2, 2, 2, 3, 3, 3}
	if iou := IoU3D(a, b); iou != 0 {
		t.Fatalf("应 IoU=0，got %f", iou)
	}
}

func TestIoU3D_Degenerate(t *testing.T) {
	a := &BBox{0, 0, 0, 0, 1, 1} // 0 厚度
	b := &BBox{0, 0, 0, 1, 1, 1}
	if iou := IoU3D(a, b); iou != 0 {
		t.Fatalf("退化 bbox 应 IoU=0，got %f", iou)
	}
}

func TestRatioScore(t *testing.T) {
	cases := []struct {
		r    float64
		want float64
	}{
		{1.0, 1.0},
		{1.1, 0.9},
		{0.9, 0.9},
		{2.0, 0.0}, // |delta|=1 → 0
		{0.0, 0.0},
		{-1.0, 0.0},
	}
	for _, c := range cases {
		got := ratioScore(c.r)
		if math.Abs(got-c.want) > 1e-9 {
			t.Errorf("ratioScore(%f) want %f got %f", c.r, c.want, got)
		}
	}
}

func TestCompute_AllFieldsPresent(t *testing.T) {
	scan := Metadata{
		TriangleCount: 1000,
		PointCount:    600,
		BBox:          &BBox{0, 0, 0, 1, 1, 1},
		Coverage:      f32(0.85),
		QCScore:       f32(0.90),
	}
	ref := Metadata{
		TriangleCount: 1000,
		PointCount:    600,
		BBox:          &BBox{0, 0, 0, 1, 1, 1},
		Coverage:      f32(0.85),
		QCScore:       f32(0.90),
	}
	m := Compute(scan, ref)
	if m.BBoxIoU < 0.999 || m.TriRatio != 1 || m.PointRatio != 1 {
		t.Fatalf("完美对齐应全 1，got %+v", m)
	}
	if s := m.Score(); s < 0.999 {
		t.Fatalf("完美对齐应 score≈1，got %f", s)
	}
	v, _ := Verdict(m, m.Score(), 0.85, 0.60)
	if v != "pass" {
		t.Fatalf("完美对齐应 pass，got %s", v)
	}
}

func TestCompute_MissingBBox_StillScores(t *testing.T) {
	scan := Metadata{TriangleCount: 1000, PointCount: 600, Coverage: f32(0.8), QCScore: f32(0.85)}
	ref := Metadata{TriangleCount: 1000, PointCount: 600, Coverage: f32(0.8), QCScore: f32(0.85)}
	m := Compute(scan, ref)
	if !m.BBoxMissing {
		t.Fatal("应 BBoxMissing")
	}
	if s := m.Score(); s < 0.999 {
		t.Fatalf("除 bbox 外完美对齐应 score≈1，got %f", s)
	}
	v, reasons := Verdict(m, m.Score(), 0.85, 0.60)
	if v != "pass" {
		t.Fatalf("缺 bbox 但其它完美应 pass，got %s reasons=%v", v, reasons)
	}
	hasMissing := false
	for _, r := range reasons {
		if r == "bbox_missing" {
			hasMissing = true
		}
	}
	if !hasMissing {
		t.Fatalf("应 reasons 含 bbox_missing，got %v", reasons)
	}
}

func TestVerdict_LowBBoxIoU_Demote(t *testing.T) {
	// 元数据全好但 bbox 半重叠 → 应 warning + bbox_iou_low
	scan := Metadata{
		TriangleCount: 1000, PointCount: 600,
		BBox:     &BBox{0.7, 0, 0, 1.7, 1, 1}, // x 偏移 0.7 → IoU ≈ 0.176
		Coverage: f32(0.85), QCScore: f32(0.90),
	}
	ref := Metadata{
		TriangleCount: 1000, PointCount: 600,
		BBox:     &BBox{0, 0, 0, 1, 1, 1},
		Coverage: f32(0.85), QCScore: f32(0.90),
	}
	m := Compute(scan, ref)
	if m.BBoxIoU >= 0.5 {
		t.Fatalf("期望低 IoU，got %f", m.BBoxIoU)
	}
	v, reasons := Verdict(m, m.Score(), 0.85, 0.60)
	if v != "warning" {
		t.Fatalf("低 bbox IoU 应 warning，got %s reasons=%v", v, reasons)
	}
}

func TestVerdict_LargeMismatch_Fail(t *testing.T) {
	scan := Metadata{
		TriangleCount: 200, PointCount: 100,
		BBox:     &BBox{0, 0, 0, 0.3, 0.3, 0.3},
		Coverage: f32(0.30), QCScore: f32(0.40),
	}
	ref := Metadata{
		TriangleCount: 1000, PointCount: 600,
		BBox:     &BBox{0, 0, 0, 1, 1, 1},
		Coverage: f32(0.90), QCScore: f32(0.95),
	}
	m := Compute(scan, ref)
	v, _ := Verdict(m, m.Score(), 0.85, 0.60)
	if v != "fail" {
		t.Fatalf("大幅不匹配应 fail，got %s score=%f m=%+v", v, m.Score(), m)
	}
}

func TestCompute_AllMissing_ScoreZero(t *testing.T) {
	scan := Metadata{}
	ref := Metadata{}
	m := Compute(scan, ref)
	if s := m.Score(); s != 0 {
		t.Fatalf("全缺数据应 score=0，got %f", s)
	}
}
