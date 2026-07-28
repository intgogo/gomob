package restore

import (
	"math"
	"testing"

	"io.gomob/server/internal/cvengine/gocv"
)

// syntheticAnchor 造一组理想的 17 字符观测：完全等距、水平、同尺寸。
// 度量换算的正确性必须先在解析可算的输入上钉死，真机数据只能验证「不崩、量级对」。
func syntheticAnchor(pitchPx, widthPx, heightPx float64) textAnchor {
	selected := make([]characterObservation, vinCharacterCount)
	for i := range selected {
		k := float64(i - vinCharacterCount/2)
		selected[i] = characterObservation{
			X:      float64(CanonicalProbeW)*0.5 + k*pitchPx,
			Y:      float64(CanonicalProbeH) * 0.5,
			Width:  widthPx,
			Height: heightPx,
			Score:  0.9,
			Class:  "A",
		}
	}
	return textAnchor{
		CenterX:    float64(CanonicalProbeW) * 0.5,
		CenterY:    float64(CanonicalProbeH) * 0.5,
		DirectionX: 1,
		DirectionY: 0,
		PitchPx:    pitchPx,
		Count:      vinCharacterCount,
		Selected:   selected,
	}
}

func TestBuildCharacterMetricsIdealGrid(t *testing.T) {
	const (
		probePitchPx = 64.0
		probeWidthPx = 40.0
		probeHeight  = 90.0
		pitchMM      = 7.0
	)
	anchor := syntheticAnchor(probePitchPx, probeWidthPx, probeHeight)
	metrics, err := buildCharacterMetrics(anchor, textSpatialGrid{pitchMM: pitchMM})
	if err != nil {
		t.Fatalf("buildCharacterMetrics: %v", err)
	}

	// 探针 64px = 物理 7mm ⇒ 每探针像素 7/64 mm。
	mmPerProbePx := pitchMM / probePitchPx
	wantCharWidth := probeWidthPx * mmPerProbePx
	wantCharHeight := probeHeight * mmPerProbePx
	// 总宽 = 首末字符中心跨距 + 一个字宽（首字符左半 + 末字符右半）。
	wantTotalWidth := float64(vinCharacterCount-1)*pitchMM + wantCharWidth

	assertClose(t, "PitchMM", metrics.PitchMM, pitchMM)
	assertClose(t, "CharWidthMM", metrics.CharWidthMM, wantCharWidth)
	assertClose(t, "CharHeightMM", metrics.CharHeightMM, wantCharHeight)
	assertClose(t, "TotalWidthMM", metrics.TotalWidthMM, wantTotalWidth)
	assertClose(t, "CenterSpanMM", metrics.CenterSpanMM, float64(vinCharacterCount-1)*pitchMM)
	assertClose(t, "GapMM", metrics.GapMM, pitchMM-wantCharWidth)

	// mm 与画布 px 必须是同一把尺子的两种读数。
	assertClose(t, "PitchPx", metrics.PitchPx, pitchMM*VinCreatorPixelsPerMM)
	assertClose(t, "TotalWidthPx", metrics.TotalWidthPx, wantTotalWidth*VinCreatorPixelsPerMM)
	assertClose(t, "CharWidthPx", metrics.CharWidthPx, wantCharWidth*VinCreatorPixelsPerMM)
	assertClose(t, "CharHeightPx", metrics.CharHeightPx, wantCharHeight*VinCreatorPixelsPerMM)

	if len(metrics.Characters) != vinCharacterCount {
		t.Fatalf("字符度量数量 = %d，要 %d", len(metrics.Characters), vinCharacterCount)
	}
	// 第 9 个字符（索引 8）落在画布中心，其余按物理节距等距展开。
	assertClose(t, "第 9 字符中心 X", metrics.Characters[8].CenterXPx, canonicalCenterXPx)
	assertClose(t, "第 9 字符中心 Y", metrics.Characters[8].CenterYPx, canonicalCenterYPx)
	for i, character := range metrics.Characters {
		k := float64(i - vinCharacterCount/2)
		want := canonicalCenterXPx + k*pitchMM*VinCreatorPixelsPerMM
		assertClose(t, "字符中心 X", character.CenterXPx, want)
		assertClose(t, "字符中心 Y", character.CenterYPx, canonicalCenterYPx)
	}
	// 包围盒左右缘与总宽自洽。
	assertClose(t, "包围盒宽", metrics.RightPx-metrics.LeftPx, metrics.TotalWidthPx)
	// 字符全部落在画布内，否则四周刻度尺会被内容压住。
	if metrics.LeftPx <= 0 || metrics.RightPx >= float64(CanonicalOutW) {
		t.Errorf("字符包围盒越界: left=%.1f right=%.1f 画布宽 %d",
			metrics.LeftPx, metrics.RightPx, CanonicalOutW)
	}
}

// TestBuildCharacterMetricsUsesRefinedPitch 钉住尺度基准的选择：
// 换算比例必须来自 3D 细化后的物理节距，而不是探针铺设时的名义 mm/px。
// 两者在门限内可差约 1%，用错的话用户拿刻度尺量图会与返回值对不上。
func TestBuildCharacterMetricsUsesRefinedPitch(t *testing.T) {
	anchor := syntheticAnchor(64.0, 40.0, 90.0)
	coarse, err := buildCharacterMetrics(anchor, textSpatialGrid{pitchMM: 7.0})
	if err != nil {
		t.Fatalf("buildCharacterMetrics: %v", err)
	}
	refined, err := buildCharacterMetrics(anchor, textSpatialGrid{pitchMM: 7.07})
	if err != nil {
		t.Fatalf("buildCharacterMetrics: %v", err)
	}
	ratio := refined.CharWidthMM / coarse.CharWidthMM
	if math.Abs(ratio-7.07/7.0) > 1e-9 {
		t.Errorf("字宽未随细化节距等比变化: ratio=%.9f 期望 %.9f", ratio, 7.07/7.0)
	}
	if refined.TotalWidthMM <= coarse.TotalWidthMM {
		t.Errorf("细化节距变大后总宽应变大: %.4f → %.4f", coarse.TotalWidthMM, refined.TotalWidthMM)
	}
}

func TestBuildCharacterMetricsRejectsIncompleteAnchor(t *testing.T) {
	anchor := syntheticAnchor(64.0, 40.0, 90.0)
	anchor.Selected = anchor.Selected[:16]
	if _, err := buildCharacterMetrics(anchor, textSpatialGrid{pitchMM: 7.0}); err == nil {
		t.Fatal("不足 17 字符时必须报错，不能给出半截度量")
	}
	full := syntheticAnchor(64.0, 40.0, 90.0)
	if _, err := buildCharacterMetrics(full, textSpatialGrid{pitchMM: 0}); err == nil {
		t.Fatal("节距为 0 时必须报错")
	}
}

// TestDrawCanonicalRulerPreservesCanvas 钉住刻度尺的两条硬约束：
// 不改画布尺寸（一致性验收依赖 4425×600），且不改原图（OCR 输入必须干净）。
func TestDrawCanonicalRulerPreservesCanvas(t *testing.T) {
	canonical := gocv.NewMatWithSize(CanonicalOutH, CanonicalOutW, gocv.MatTypeCV8UC3)
	defer func() { _ = canonical.Release() }()
	before, err := gocv.IMEncode(gocv.PNGFileExt, canonical)
	if err != nil {
		t.Fatalf("编码原图: %v", err)
	}

	metrics := CharacterMetrics{PixelsPerMM: VinCreatorPixelsPerMM}
	ruled := drawCanonicalRuler(canonical, metrics)
	defer func() { _ = ruled.Release() }()

	if ruled.Cols() != CanonicalOutW || ruled.Rows() != CanonicalOutH {
		t.Fatalf("刻度尺改了画布尺寸: %dx%d，要 %dx%d",
			ruled.Cols(), ruled.Rows(), CanonicalOutW, CanonicalOutH)
	}
	after, err := gocv.IMEncode(gocv.PNGFileExt, canonical)
	if err != nil {
		t.Fatalf("重新编码原图: %v", err)
	}
	if len(before) != len(after) {
		t.Fatal("原图被刻度尺就地修改了：OCR 输入必须保持干净")
	}
	for i := range before {
		if before[i] != after[i] {
			t.Fatal("原图被刻度尺就地修改了：OCR 输入必须保持干净")
		}
	}

	ruledPNG, err := gocv.IMEncode(gocv.PNGFileExt, ruled)
	if err != nil {
		t.Fatalf("编码带尺图: %v", err)
	}
	if len(ruledPNG) == len(before) {
		t.Fatal("带尺图与原图字节数相同，刻度尺很可能没画上")
	}
}

// TestDrawCanonicalRulerKeepsCharacterAreaClear 证明刻度尺不会压到字符区。
// 四周刻度带（含数字）的占用必须小于字符包围盒到画布边缘的留白。
func TestDrawCanonicalRulerKeepsCharacterAreaClear(t *testing.T) {
	// 真机量级：节距 ~7mm、字宽 ~4.4mm、字高 ~10mm。
	anchor := syntheticAnchor(64.0, 40.0, 90.0)
	metrics, err := buildCharacterMetrics(anchor, textSpatialGrid{pitchMM: 7.0})
	if err != nil {
		t.Fatalf("buildCharacterMetrics: %v", err)
	}
	// 刻度线最长 rulerMajorPx，数字再往内约一个字高，取两倍主刻度作为保守上界。
	const rulerBandPx = 2 * rulerMajorPx
	topPx := metrics.BaselineYPx - metrics.CharHeightPx*0.5
	bottomPx := float64(CanonicalOutH) - (metrics.BaselineYPx + metrics.CharHeightPx*0.5)
	if topPx <= rulerBandPx || bottomPx <= rulerBandPx {
		t.Errorf("上下留白不足以容纳刻度带: top=%.1f bottom=%.1f，刻度带 %d",
			topPx, bottomPx, rulerBandPx)
	}
	if metrics.LeftPx <= rulerBandPx ||
		float64(CanonicalOutW)-metrics.RightPx <= rulerBandPx {
		t.Errorf("左右留白不足以容纳刻度带: left=%.1f right余=%.1f，刻度带 %d",
			metrics.LeftPx, float64(CanonicalOutW)-metrics.RightPx, rulerBandPx)
	}
}

func assertClose(t *testing.T, name string, got, want float64) {
	t.Helper()
	if math.Abs(got-want) > 1e-6 {
		t.Errorf("%s = %.9f，要 %.9f", name, got, want)
	}
}
