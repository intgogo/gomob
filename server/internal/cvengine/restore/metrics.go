// metrics.go —— 规范图上车架号字符串的物理度量。
//
// 度量只有在**同一把尺子**下才自洽：输出画布固定 25px/mm，而字符观测来自 1200×260 探针域。
// 两者的换算比例必须取 `grid.pitchMM / anchor.PitchPx`（真实物理节距 ÷ 探针像素节距），
// 不能用探针铺设时的名义 mmPerPixel —— 后者是 OBB 粗定位给的初值，格架细化后已被 3D 等步长
// 基线修正过（门限内约 1%）。用名义值算出来的字宽会与用户拿刻度尺在图上量到的对不上。
package restore

import (
	"errors"
	"math"
	"sort"
)

// CharacterMetric —— 单个 VIN 字符在 4425×600 规范画布上的度量。
//
// Character/Score 来自 VINS 逐字符观测，仅作诊断参考；权威 VIN 文本以外部 OCR 结果为准。
type CharacterMetric struct {
	Index     int     `json:"index"`
	Character string  `json:"character"`
	Score     float64 `json:"score"`
	CenterXPx float64 `json:"center_x_px"`
	CenterYPx float64 `json:"center_y_px"`
	WidthMM   float64 `json:"width_mm"`
	HeightMM  float64 `json:"height_mm"`
}

// CharacterMetrics —— 车架号字符串在规范图上的整体度量。
//
// 所有 mm 值都是承印平面上的真实物理尺寸；所有 px 值都是 4425×600 输出画布像素
// （px = mm × PixelsPerMM，恒为 25）。两套值互为换算，便于端侧直接叠加标注。
type CharacterMetrics struct {
	PixelsPerMM  float64 `json:"pixels_per_mm"`
	TotalWidthMM float64 `json:"total_width_mm"`
	TotalWidthPx float64 `json:"total_width_px"`
	CenterSpanMM float64 `json:"center_span_mm"`
	PitchMM      float64 `json:"pitch_mm"`
	PitchPx      float64 `json:"pitch_px"`
	GapMM        float64 `json:"gap_mm"`
	GapPx        float64 `json:"gap_px"`
	CharWidthMM  float64 `json:"char_width_mm"`
	CharWidthPx  float64 `json:"char_width_px"`
	CharHeightMM float64 `json:"char_height_mm"`
	CharHeightPx float64 `json:"char_height_px"`
	// 字符串包围盒在输出画布上的位置，配合四周刻度尺可直接读数复核。
	LeftPx      float64           `json:"left_px"`
	RightPx     float64           `json:"right_px"`
	BaselineYPx float64           `json:"baseline_y_px"`
	Characters  []CharacterMetric `json:"characters"`
}

// 规范画布上 VIN 中心的连续坐标：工作画布 5000×678 的几何中心（像素中心系下为 W/2−0.5）
// 减去 FlipAndCropImage 的裁切原点。横向 2211.5 与画布几何中心 2212 差半像素，源于两块
// 画布奇偶性不同——这是原厂裁切的既有事实，不是误差，度量必须按它算才能和图对上。
const (
	canonicalCenterXPx = float64(VinCreatorWorkW)/2 - 0.5 - vinCreatorCropX
	canonicalCenterYPx = float64(VinCreatorWorkH)/2 - 0.5 - vinCreatorCropY
)

// buildCharacterMetrics 把探针域的逐字符观测与 3D 格架合成规范画布上的物理度量。
func buildCharacterMetrics(anchor textAnchor, grid textSpatialGrid) (CharacterMetrics, error) {
	if len(anchor.Selected) != vinCharacterCount {
		return CharacterMetrics{}, ErrTextAnchorUnreliable
	}
	if !isFinite(anchor.PitchPx) || anchor.PitchPx <= 0 ||
		!isFinite(grid.pitchMM) || grid.pitchMM <= 0 {
		return CharacterMetrics{}, errors.New("字符度量尺度非法")
	}
	// 探针像素 → 真实毫米；再乘 25px/mm 即得输出画布像素。
	mmPerProbePx := grid.pitchMM / anchor.PitchPx
	probePxToOutputPx := mmPerProbePx * VinCreatorPixelsPerMM
	pitchPx := grid.pitchMM * VinCreatorPixelsPerMM

	widths := make([]float64, vinCharacterCount)
	heights := make([]float64, vinCharacterCount)
	characters := make([]CharacterMetric, vinCharacterCount)

	// 沿格架方向的一维坐标：用它取首尾字符的外缘，得到字符串真实总宽。
	// 字符框是轴对齐 AABB，而残余倾角实测仅 ±0.3°，此处不做旋转投影修正——那会用
	// 一个远小于框本身量化误差的角度制造伪精度。
	directionX, directionY := anchor.DirectionX, anchor.DirectionY
	alongMin, alongMax := math.Inf(1), math.Inf(-1)

	for i, observation := range anchor.Selected {
		k := float64(i - vinCharacterCount/2)
		widths[i] = observation.Width
		heights[i] = observation.Height

		// 观测相对拟合格架的残差，分解到格架的行方向与垂直方向。
		fitX := anchor.CenterX + k*anchor.PitchPx*directionX
		fitY := anchor.CenterY + k*anchor.PitchPx*directionY
		residualX := observation.X - fitX
		residualY := observation.Y - fitY
		along := residualX*directionX + residualY*directionY
		// 图像坐标 y 向下，(x,y)→(−y,x) 即行方向顺时针 90°，与输出画布 axisY（向下）同向。
		across := -residualX*directionY + residualY*directionX

		centerX := canonicalCenterXPx + k*pitchPx + along*probePxToOutputPx
		centerY := canonicalCenterYPx + across*probePxToOutputPx

		offset := (observation.X-anchor.CenterX)*directionX +
			(observation.Y-anchor.CenterY)*directionY
		alongMin = math.Min(alongMin, offset-observation.Width*0.5)
		alongMax = math.Max(alongMax, offset+observation.Width*0.5)

		characters[i] = CharacterMetric{
			Index:     i,
			Character: observation.Class,
			Score:     observation.Score,
			CenterXPx: centerX,
			CenterYPx: centerY,
			WidthMM:   observation.Width * mmPerProbePx,
			HeightMM:  observation.Height * mmPerProbePx,
		}
	}

	charWidthMM := median(widths) * mmPerProbePx
	charHeightMM := median(heights) * mmPerProbePx
	totalWidthMM := (alongMax - alongMin) * mmPerProbePx
	centerSpanMM := float64(vinCharacterCount-1) * grid.pitchMM
	// 字符间空隙 = 中心节距 − 字宽中位数。首尾字形差异大时它只是均值意义上的空隙，
	// 逐字符真实空隙可由 Characters 的中心与宽度自行相减。
	gapMM := grid.pitchMM - charWidthMM

	return CharacterMetrics{
		PixelsPerMM:  VinCreatorPixelsPerMM,
		TotalWidthMM: totalWidthMM,
		TotalWidthPx: totalWidthMM * VinCreatorPixelsPerMM,
		CenterSpanMM: centerSpanMM,
		PitchMM:      grid.pitchMM,
		PitchPx:      pitchPx,
		GapMM:        gapMM,
		GapPx:        gapMM * VinCreatorPixelsPerMM,
		CharWidthMM:  charWidthMM,
		CharWidthPx:  charWidthMM * VinCreatorPixelsPerMM,
		CharHeightMM: charHeightMM,
		CharHeightPx: charHeightMM * VinCreatorPixelsPerMM,
		LeftPx:       canonicalCenterXPx + alongMin*probePxToOutputPx,
		RightPx:      canonicalCenterXPx + alongMax*probePxToOutputPx,
		BaselineYPx:  canonicalCenterYPx,
		Characters:   characters,
	}, nil
}

func median(values []float64) float64 {
	if len(values) == 0 {
		return 0
	}
	sorted := append([]float64(nil), values...)
	sort.Float64s(sorted)
	middle := len(sorted) / 2
	if len(sorted)%2 == 1 {
		return sorted[middle]
	}
	return (sorted[middle-1] + sorted[middle]) * 0.5
}
