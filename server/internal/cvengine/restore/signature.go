package restore

import (
	"image"
	"image/color"

	"io.gomob/server/internal/cvengine/gocv"
)

// signatureBinarize —— 原厂 GetSignature3G 真去阴影/二值化（端口自 restore_obb.py::signature_binarize，逐指令对齐）。
//
//	cvtColor BGR2GRAY → adaptiveThreshold(GAUSSIAN, BINARY, blockSize=131, C=15)
//	→ bitwise_not（笔画→白，便于连通域）
//	→ erode(CROSS 3×3) + dilate(RECT 5×5)
//	→ findContours(EXTERNAL, NONE) 去小斑：minAreaRect 宽高都≤39（int 截断）填黑
//	→ bitwise_not 回正（前景=黑）
//	→ morphologyEx CLOSE(RECT3) + OPEN(RECT3)
//
// 入 bgr（BGR 彩色 Mat），出二值 CV8UC1（前景=黑 0），调用方负责 Release 返回值。
func signatureBinarize(bgr gocv.Mat) gocv.Mat {
	gray := gocv.NewMat()
	defer func() { _ = gray.Release() }()
	gocv.CvtColor(bgr, &gray, gocv.ColorBGRToGray)

	b := gocv.NewMat()
	gocv.AdaptiveThreshold(gray, &b, 255, gocv.AdaptiveThresholdGaussian, gocv.ThresholdBinary, 131, 15)

	// 反相：笔画→白
	gocv.BitwiseNot(b, &b)

	// erode CROSS 3×3
	kCross3 := gocv.GetStructuringElement(gocv.MorphCross, image.Pt(3, 3))
	defer func() { _ = kCross3.Release() }()
	gocv.Erode(b, &b, kCross3)

	// dilate RECT 5×5
	kRect5 := gocv.GetStructuringElement(gocv.MorphRect, image.Pt(5, 5))
	defer func() { _ = kRect5.Release() }()
	gocv.Dilate(b, &b, kRect5)

	// 去小斑：findContours(EXTERNAL,NONE)；minAreaRect 宽高都≤39（int 截断）→ 填黑
	cnts := gocv.FindContours(b, gocv.RetrievalExternal, gocv.ChainApproxNone)
	black := color.RGBA{R: 0, G: 0, B: 0, A: 0}
	for i, c := range cnts {
		if len(c) < 3 {
			// 点太少 minAreaRect 不稳；Python 也会拿到退化框，宽高多半 0 → 一律视作小斑填黑
			gocv.DrawContours(&b, cnts, i, black, -1)
			continue
		}
		rr := gocv.MinAreaRect(c)
		// gocv.MinAreaRect 已返 int Width/Height，等价 Python int(w)/int(h)
		if rr.Width <= 39 && rr.Height <= 39 {
			gocv.DrawContours(&b, cnts, i, black, -1)
		}
	}

	// 回正：前景=黑
	gocv.BitwiseNot(b, &b)

	// CLOSE + OPEN RECT3
	kRect3 := gocv.GetStructuringElement(gocv.MorphRect, image.Pt(3, 3))
	defer func() { _ = kRect3.Release() }()
	gocv.MorphologyEx(b, &b, gocv.MorphClose, kRect3)
	gocv.MorphologyEx(b, &b, gocv.MorphOpen, kRect3)

	return b
}
