package restore

import (
	"image"
	"image/color"

	"io.gomob/server/internal/cvengine/gocv"
)

// SigInkMax 去阴影质量闸：归一后墨水占比 > 此值 = 噪声/坏采集（真实 VIN 字带稀疏 ~8-12%）。
const SigInkMax = 0.25

// signatureBinarize —— 去阴影 + OCR 级二值化（鲁棒版，2026-06-21 真机 21 组实证重写；端口自 restore_obb.py）。
//
// 原厂 GetSignature3G 的 adaptiveThreshold(131,15) 真机两处失效：① **极性假设固定**——刻字在不同补光下可暗可亮
// （镜面高光灌进刻槽→整片翻转成白字黑底）；② **固定边距怕低对比**——字-底差 < C 时整串丢字成碎片。改：
//
//	cvtColor BGR2GRAY
//	→ bilateralFilter(9,60,60)            保边降噪，除钢板微纹理
//	→ 背景除法平照 norm = clip(d ÷ (GaussianBlur(d,σ21)+1) × 180)   拉平光照不均
//	→ threshold(OTSU|BINARY_INV)          全局自动阈值（平照后普适）
//	→ 极性归一：前景(白)过半 → bitwise_not（墨水稀疏，过半说明刻字偏亮被判前景，反过来）
//	→ findContours 去小斑(minAreaRect 宽高都≤39 填黑)
//	→ morphologyEx OPEN(RECT2) + CLOSE(RECT3)
//
// 返回二值 CV8UC1（前景=黑 0）+ 墨水占比（前景像素/总数，供质量闸）。调用方负责 Release 返回 Mat。
func signatureBinarize(bgr gocv.Mat) (gocv.Mat, float64) {
	gray := gocv.NewMat()
	defer func() { _ = gray.Release() }()
	gocv.CvtColor(bgr, &gray, gocv.ColorBGRToGray)

	// 双边降噪：保边除钢板微纹理
	d := gocv.NewMat()
	defer func() { _ = d.Release() }()
	gocv.BilateralFilter(gray, &d, 9, 60, 60)

	// 背景除法平照：norm = clip(d / (blur(d)+1) * 180)
	bg := gocv.NewMat()
	defer func() { _ = bg.Release() }()
	gocv.GaussianBlur(d, &bg, image.Pt(0, 0), 21, 21, gocv.BorderDefault)

	df := gocv.NewMat()
	defer func() { _ = df.Release() }()
	d.ConvertTo(&df, gocv.MatTypeCV32F)
	bgf := gocv.NewMat()
	defer func() { _ = bgf.Release() }()
	bg.ConvertTo(&bgf, gocv.MatTypeCV32F)
	bgf.AddFloat(1.0) // +1 避免除零

	normf := gocv.NewMat()
	defer func() { _ = normf.Release() }()
	gocv.Divide(df, bgf, &normf)               // d/(bg+1)，浮点
	norm := gocv.NewMat()
	defer func() { _ = norm.Release() }()
	gocv.ConvertScaleAbs(normf, &norm, 180, 0) // saturate(|normf*180|) → uint8

	// 全局 Otsu + BINARY_INV：fg=白=暗于底（假定刻字）
	fg := gocv.NewMat()
	gocv.Threshold(norm, &fg, 0, 255, gocv.ThresholdBinaryInv|gocv.ThresholdOtsu)

	total := fg.Rows() * fg.Cols()
	// 极性归一：真实墨水稀疏，前景(白)过半=刻字在该光照偏亮→反相
	if total > 0 && float64(gocv.CountNonZero(fg))/float64(total) > 0.5 {
		gocv.BitwiseNot(fg, &fg)
	}

	// 去小斑：findContours(EXTERNAL,NONE)；minAreaRect 宽高都≤39（int 截断）→ 填黑
	cnts := gocv.FindContours(fg, gocv.RetrievalExternal, gocv.ChainApproxNone)
	black := color.RGBA{R: 0, G: 0, B: 0, A: 0}
	for i, c := range cnts {
		if len(c) < 3 {
			gocv.DrawContours(&fg, cnts, i, black, -1)
			continue
		}
		rr := gocv.MinAreaRect(c)
		if rr.Width <= 39 && rr.Height <= 39 {
			gocv.DrawContours(&fg, cnts, i, black, -1)
		}
	}

	// 形态学 OPEN(RECT2) + CLOSE(RECT3)
	kOpen := gocv.GetStructuringElement(gocv.MorphRect, image.Pt(2, 2))
	defer func() { _ = kOpen.Release() }()
	gocv.MorphologyEx(fg, &fg, gocv.MorphOpen, kOpen)
	kClose := gocv.GetStructuringElement(gocv.MorphRect, image.Pt(3, 3))
	defer func() { _ = kClose.Release() }()
	gocv.MorphologyEx(fg, &fg, gocv.MorphClose, kClose)

	// 墨水占比（前景=白=刻字；质量闸用）
	ink := 0.0
	if total > 0 {
		ink = float64(gocv.CountNonZero(fg)) / float64(total)
	}

	// 回正：前景=黑
	gocv.BitwiseNot(fg, &fg)
	return fg, ink
}
