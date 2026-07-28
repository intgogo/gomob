// ruler.go —— 规范图四周的毫米刻度尺。
//
// 能画物理刻度的前提是 M4.5 之后的规范图已经是**严格 25px/mm 的度量图**（原厂 BF301208 标定
// + 承印平面上的 3D 等步长格架），1px 恒等于 0.04mm。这与 2026-06 那版「按 OBB 宽度归一到固定
// 画布」不同——那时尺度随取景变，画 mm 刻度就是把显示尺度冒充物理尺度，故当时明令禁止。
//
// 刻度只画在**副本**上：干净的规范图仍是 OCR 输入与一致性/逐字节等价验收的基线对象，绝不能
// 被显示层的装饰污染。
package restore

import (
	"image"
	"image/color"
	"math"
	"strconv"

	"io.gomob/server/internal/cvengine/gocv"
)

// 刻度尺寸按「缩略也要能读」定：4425×600 的图在手机上会被缩到屏宽（约 1/4），
// 按图内像素看着刚好的字号缩完只剩 3px 高，等于没有。故主刻度与数字都按 4 倍余量放大，
// 全分辨率下略显粗壮是刻意的——它换来缩略视图里仍分得清主/中/次三级刻度。
const (
	rulerMajorMM  = 10 // 主刻度间隔(mm)，标数字
	rulerMediumMM = 5  // 中刻度间隔(mm)
	rulerMinorPx  = 12 // 次刻度(1mm)长度(px)
	rulerMediumPx = 22
	rulerMajorPx  = 34
	rulerFontSize = 1.5
	rulerTextGap  = 8 // 刻度线末端到数字的间隙(px)
)

// drawCanonicalRuler 在规范图副本的四条边上画毫米刻度尺，原点在左上角。
//
// 上/下边尺共用 X 轴读数（0 → 宽度 mm，自左向右）；左/右边尺共用 Y 轴读数（0 → 高度 mm，
// 自上向下）。对边同刻度而非镜像：任取一条边读数结果相同，量测时不必记住看的是哪条边。
//
// 刻度用黑粗打底 + 白细芯的描边线，不铺底色带：钢板高光区与阴影区都能读，且绝不遮挡字符。
// 4425×600 画布上字符区约占中间 121×10mm，四周留白 ≥7mm，而刻度连数字最多占 2mm。
// 返回新 Mat，调用方负责 Release。
func drawCanonicalRuler(canonical gocv.Mat, metrics CharacterMetrics) gocv.Mat {
	canvas := canonical.Clone()
	width, height := canvas.Cols(), canvas.Rows()
	pixelsPerMM := metrics.PixelsPerMM
	if !isFinite(pixelsPerMM) || pixelsPerMM <= 0 {
		pixelsPerMM = VinCreatorPixelsPerMM
	}

	dark := color.RGBA{R: 12, G: 12, B: 12, A: 0}
	light := color.RGBA{R: 250, G: 250, B: 250, A: 0}
	tick := func(p0, p1 image.Point, thickness int) {
		gocv.Line(&canvas, p0, p1, dark, thickness+3)
		gocv.Line(&canvas, p0, p1, light, thickness)
	}
	label := func(text string, at image.Point) {
		gocv.PutText(&canvas, text, at, gocv.FontHersheySimplex, rulerFontSize, dark, 7)
		gocv.PutText(&canvas, text, at, gocv.FontHersheySimplex, rulerFontSize, light, 3)
	}

	// 横向：上下两条边
	for mm := 0; ; mm++ {
		x := int(math.Round(float64(mm) * pixelsPerMM))
		if x >= width {
			break
		}
		length, thickness := tickLength(mm)
		tick(image.Pt(x, 0), image.Pt(x, length), thickness)
		tick(image.Pt(x, height-1), image.Pt(x, height-1-length), thickness)
		if mm%rulerMajorMM != 0 {
			continue
		}
		// 单位跟在原点读数后面（"0mm"），省掉一处会与刻度数字抢位置的独立单位标注。
		text := strconv.Itoa(mm)
		if mm == 0 {
			text += "mm"
		}
		size := gocv.GetTextSize(text, gocv.FontHersheySimplex, rulerFontSize, 1)
		textX := clampInt(x+rulerTextGap, 0, width-size.X-1)
		label(text, image.Pt(textX, rulerMajorPx+rulerTextGap+size.Y))
		label(text, image.Pt(textX, height-1-rulerMajorPx-rulerTextGap))
	}

	// 纵向：左右两条边
	for mm := 0; ; mm++ {
		y := int(math.Round(float64(mm) * pixelsPerMM))
		if y >= height {
			break
		}
		length, thickness := tickLength(mm)
		tick(image.Pt(0, y), image.Pt(length, y), thickness)
		tick(image.Pt(width-1, y), image.Pt(width-1-length, y), thickness)
		if mm%rulerMajorMM != 0 {
			continue
		}
		// 0 的读数由横向尺在原点处给出，纵向再标一次会与之重叠。
		if mm == 0 {
			continue
		}
		text := strconv.Itoa(mm)
		size := gocv.GetTextSize(text, gocv.FontHersheySimplex, rulerFontSize, 1)
		textY := clampInt(y+size.Y/2, size.Y+1, height-1)
		label(text, image.Pt(rulerMajorPx+rulerTextGap, textY))
		label(text, image.Pt(width-1-rulerMajorPx-rulerTextGap-size.X, textY))
	}
	return canvas
}

// tickLength 返回该毫米位的刻度长度与线宽：三级刻度靠长度与粗细双重区分，
// 只靠长度的话缩略后主/中/次会挤成一样。
func tickLength(mm int) (length, thickness int) {
	switch {
	case mm%rulerMajorMM == 0:
		return rulerMajorPx, 3
	case mm%rulerMediumMM == 0:
		return rulerMediumPx, 2
	default:
		return rulerMinorPx, 1
	}
}
