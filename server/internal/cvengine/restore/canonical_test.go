package restore

import (
	"errors"
	"math"
	"math/rand"
	"testing"
)

func TestFitTextAnchorRecoversSimilarityGrid(t *testing.T) {
	theta := 2.0 * math.Pi / 180.0
	observations := syntheticGrid(584.0, 127.0, 62.5, theta)
	anchor, err := fitTextAnchor(observations)
	if err != nil {
		t.Fatal(err)
	}
	assertNear(t, anchor.CenterX, 584.0, 1e-6, "中心 X")
	assertNear(t, anchor.CenterY, 127.0, 1e-6, "中心 Y")
	assertNear(t, anchor.PitchPx, 62.5, 1e-6, "字符节距")
	assertNear(t, anchor.AngleDeg(), 2.0, 1e-6, "基线角")
	assertNear(t, anchor.RMSPx, 0, 1e-6, "格架 RMS")
	if anchor.Text != "0123456789ABCDEFG" {
		t.Fatalf("字符顺序错误: %q", anchor.Text)
	}
}

func TestFitTextAnchorOrderDoesNotAffectResult(t *testing.T) {
	observations := syntheticGrid(610.0, 126.0, 64.0, -1.5*math.Pi/180.0)
	rand.New(rand.NewSource(7)).Shuffle(len(observations), func(i, j int) {
		observations[i], observations[j] = observations[j], observations[i]
	})
	anchor, err := fitTextAnchor(observations)
	if err != nil {
		t.Fatal(err)
	}
	assertNear(t, anchor.CenterX, 610.0, 1e-6, "中心 X")
	assertNear(t, anchor.PitchPx, 64.0, 1e-6, "字符节距")
	assertNear(t, anchor.AngleDeg(), -1.5, 1e-6, "基线角")
}

func TestFitTextAnchorRejectsStarsAndFalseDetections(t *testing.T) {
	observations := syntheticGrid(600.0, 128.0, 64.0, 0.5*math.Pi/180.0)
	observations = append(observations,
		characterObservation{X: 8, Y: 128, Width: 45, Height: 70, Score: 0.92},
		characterObservation{X: 1188, Y: 129, Width: 46, Height: 72, Score: 0.91},
		characterObservation{X: 412, Y: 82, Width: 18, Height: 25, Score: 0.25},
	)
	anchor, err := fitTextAnchor(observations)
	if err != nil {
		t.Fatal(err)
	}
	assertNear(t, anchor.CenterX, 600.0, 1e-6, "中心 X")
	assertNear(t, anchor.PitchPx, 64.0, 1e-6, "字符节距")
	if anchor.Count != vinCharacterCount || anchor.CandidateCount != 20 {
		t.Fatalf("格架计数错误: count=%d candidates=%d", anchor.Count, anchor.CandidateCount)
	}
}

func TestFitTextAnchorInsufficientCharactersFails(t *testing.T) {
	observations := syntheticGrid(600.0, 128.0, 64.0, 0)[:16]
	anchor, err := fitTextAnchor(observations)
	if !errors.Is(err, ErrTextAnchorUnreliable) {
		t.Fatalf("缺字符必须明确失败，实际 %v", err)
	}
	if anchor.CandidateCount != len(observations) {
		t.Fatalf("候选数应保留用于诊断，got=%d want=%d", anchor.CandidateCount, len(observations))
	}
}

func TestCanonicalScaleIsUniform(t *testing.T) {
	directionX, directionY := math.Cos(0.12), math.Sin(0.12)
	a := VinCreatorPixelsPerMM * directionX
	b := VinCreatorPixelsPerMM * directionY
	c := -VinCreatorPixelsPerMM * directionY
	d := VinCreatorPixelsPerMM * directionX
	// AᵀA 的非对角项为 0、对角项相等，即两个奇异值相同。
	if math.Abs(a*b+c*d) > 1e-12 || math.Abs((a*a+c*c)-(b*b+d*d)) > 1e-12 {
		t.Fatalf("规范变换出现 shear/非等比: [[%.6f %.6f][%.6f %.6f]]", a, b, c, d)
	}
}

func TestVinCreatorCanonicalOutputContract(t *testing.T) {
	if VinCreatorWorkW != 5000 || VinCreatorWorkH != 678 {
		t.Fatalf("VINCreator 工作画布应为 5000×678，实际 %d×%d", VinCreatorWorkW, VinCreatorWorkH)
	}
	if CanonicalOutW != 4425 || CanonicalOutH != 600 {
		t.Fatalf("VINCreator 用户画布应为 4425×600，实际 %d×%d", CanonicalOutW, CanonicalOutH)
	}
	if vinCreatorCropX != 288 || vinCreatorCropY != 39 {
		t.Fatalf("VINCreator 中心裁切偏移错误: %d,%d", vinCreatorCropX, vinCreatorCropY)
	}
	if vinCreatorCropX+CanonicalOutW > VinCreatorWorkW || vinCreatorCropY+CanonicalOutH > VinCreatorWorkH {
		t.Fatal("VINCreator 用户画布越出工作画布")
	}
	assertNear(
		t,
		float64(CanonicalOutW)/float64(CanonicalOutH),
		4425.0/600.0,
		1e-12,
		"原厂用户画布宽高比",
	)
	assertNear(
		t,
		1.0/VinCreatorPixelsPerMM,
		0.04,
		1e-12,
		"原厂 RecMode=2 毫米每像素",
	)
}

func TestVinCreatorOutputMapsBackToCanonicalProbeWithoutPerImageRegistration(t *testing.T) {
	layout := makeCanonicalProbeLayout()
	assertNear(t, layout.Scale, 0.36, 1e-12, "固定评估缩放")
	assertNear(t, layout.TranslateX, -196.5, 1e-12, "固定评估平移 X")
	assertNear(t, layout.TranslateY, 22.0, 1e-12, "固定评估平移 Y")
	centerX := float64(CanonicalOutW)*0.5*layout.Scale + layout.TranslateX
	centerY := float64(CanonicalOutH)*0.5*layout.Scale + layout.TranslateY
	assertNear(t, centerX, float64(CanonicalProbeW)*0.5, 1e-12, "探针中心 X")
	assertNear(t, centerY, float64(CanonicalProbeH)*0.5, 1e-12, "探针中心 Y")
}

func TestRefineTextSpatialGridStaysOnMeasuredPlane(t *testing.T) {
	frame := &frame{
		plane: Plane{
			Centroid: Vec3{0, 0, 1000},
			N:        Vec3{0, 0, 1},
			D:        -1000,
		},
		right: Vec3{1, 0, 0},
		up:    Vec3{0, 1, 0},
	}
	theta := 7.0 * math.Pi / 180.0
	anchor := textAnchor{
		CenterX:  float64(CanonicalProbeW) * 0.5,
		CenterY:  float64(CanonicalProbeH) * 0.5,
		PitchPx:  canonicalProbeTargetPitchPx,
		Selected: syntheticGrid(600, 130, canonicalProbeTargetPitchPx, theta),
	}
	grid, err := refineTextSpatialGrid(
		frame,
		renderAxes{xdx: 1, ydy: 1},
		1.0,
		anchor,
	)
	if err != nil {
		t.Fatal(err)
	}
	assertNear(t, grid.pitchMM, canonicalProbeTargetPitchPx, 1e-6, "空间字符节距")
	assertNear(t, dot3(grid.axisX, frame.plane.N), 0, 1e-12, "水平轴平面法向分量")
	assertNear(t, dot3(grid.axisY, frame.plane.N), 0, 1e-12, "垂直轴平面法向分量")
	assertNear(t, dot3(grid.axisX, grid.axisY), 0, 1e-12, "输出轴正交")
	assertNear(t, norm3(grid.axisX), 1, 1e-12, "水平轴单位长度")
	assertNear(t, norm3(grid.axisY), 1, 1e-12, "垂直轴单位长度")
}

func syntheticGrid(cx, cy, pitch, theta float64) []characterObservation {
	dx, dy := pitch*math.Cos(theta), pitch*math.Sin(theta)
	observations := make([]characterObservation, vinCharacterCount)
	for i := range observations {
		k := float64(i - vinCharacterCount/2)
		observations[i] = characterObservation{
			X: cx + k*dx, Y: cy + k*dy,
			Width: 42, Height: 68, Score: 0.93, Class: i % len(VinCharacterClasses()),
		}
	}
	return observations
}

func assertNear(t *testing.T, actual, expected, tolerance float64, name string) {
	t.Helper()
	if math.Abs(actual-expected) > tolerance {
		t.Fatalf("%s=%.9f，期望 %.9f±%.9f", name, actual, expected, tolerance)
	}
}
