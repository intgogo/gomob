// Package judge —— VIN 字形比对核心算法（从 gosmart/apps/api/ivv/judge_vin.go 迁移）。
//
// M-S10 Phase 2 入口：单字符×字符的字形相似度计算 — 不依赖模型加载、不依赖 vin-ref 厂家库。
// 完整 vin_compare 流程（含检测 + WMI 跳过 + 字符级 doCompareVin）留 Phase 2.x 后续迭代。
//
// 核心 API：
//
//	CalculateVinFontDifference(img1, img2 gocv.Mat, method int, centroid bool, logFile string) float64
//	  - method = FONT_DIST_IOU(0)     → 返回交并比 0..1，越大越相似
//	  - method = FONT_DIST_CHAMFER(1) → 返回倒角距离 0+，越小越相似
//	  - centroid = true  → 先按像素重心居中对齐再算
//	  - 内部还做 [-5°..+5°] 小角度旋转搜索找最佳对齐
//
// 与 gosmart 的差异（受控的剥离，非妥协）：
//  1. 删除 `data.GetShadow("vincmp")` 影子图导出 — gosmart 调试用，gomob 用 trace 日志替代
//  2. shadow / IMWrite 块整体移除（保留算法本体；调试图后续可挂在 audit 上）
package judge

import (
	"image"
	"image/color"

	"io.gomob/server/internal/cvengine/gocv"
)

// VIN 字符排列模式（VinArrMode）— 来自 gosmart/apps/api/ivv/judge_vin.go。
const (
	VinArrModeUnknown = 0 // 未知或样本不足
	VinArrModeLine    = 1 // 单行直线
	VinArrModeDLine   = 2 // 双行直线
	VinArrModeArc     = 3 // 弧形
)

// 字形距离方法。
const (
	FONT_DIST_IOU     = 0
	FONT_DIST_CHAMFER = 1
)

// preprocessForAlignment 用 3x3 矩形核做形态学开运算：去小噪点、断细连接。
//
// 输入：二值字符图（背景 0，字符 255）
// 返回：清洁后的新 Mat（caller 拥有）
func preprocessForAlignment(img gocv.Mat) gocv.Mat {
	kernel := gocv.GetStructuringElement(gocv.MorphRect, image.Point{X: 3, Y: 3})
	defer kernel.Release()

	cleanedImg := gocv.NewMat()
	gocv.MorphologyEx(img, &cleanedImg, gocv.MorphOpen, kernel)
	return cleanedImg
}

// AlignImageByCentroid 按像素重心把字符居中对齐。
//
// 思路：
//  1. 形态学开运算去噪
//  2. cv::moments 求几何矩 → m10/m00, m01/m00 = 重心 (cx, cy)
//  3. 计算到图像几何中心的位移 (dx, dy)
//  4. 仿射变换平移
//
// img: 二值图（背景 0，字符 255），建议先 Resize 到统一尺寸（如 64x64）
func AlignImageByCentroid(img gocv.Mat) gocv.Mat {
	if img.Empty() {
		return img.Clone()
	}
	imgPre := preprocessForAlignment(img)
	defer imgPre.Release()

	// true 表示二值图
	moments := gocv.Moments(imgPre, true)
	m00 := moments["m00"]
	if m00 < 1 {
		// 面积太小：无重心可计算
		return img.Clone()
	}
	cx := moments["m10"] / m00
	cy := moments["m01"] / m00

	width := float64(img.Cols())
	height := float64(img.Rows())
	dx := width/2.0 - cx
	dy := height/2.0 - cy

	transMat := gocv.NewMatWithSize(2, 3, gocv.MatTypeCV64F)
	defer transMat.Release()
	transMat.SetDoubleAt(0, 0, 1.0)
	transMat.SetDoubleAt(0, 1, 0.0)
	transMat.SetDoubleAt(0, 2, dx)
	transMat.SetDoubleAt(1, 0, 0.0)
	transMat.SetDoubleAt(1, 1, 1.0)
	transMat.SetDoubleAt(1, 2, dy)

	alignedImg := gocv.NewMat()
	gocv.WarpAffineWithParams(img, &alignedImg, transMat,
		image.Point{X: int(width), Y: int(height)},
		gocv.InterpolationLinear, gocv.BorderConstant, color.RGBA{0, 0, 0, 0})
	return alignedImg
}

// calculateChamferDist 算字符模板到目标的倒角距离（双向求和后由 caller 处理）。
//
// 思路：
//  1. 反转模板（字符变 0，背景变 255）
//  2. 对反转图做 distanceTransform（每像素到最近 0 的欧氏距离）→ distMap
//  3. target 转成 0/1 浮点掩膜
//  4. distMap × target 求和 / target 像素数 = 平均距离
//
// 返回单向距离；caller 通常会做 cost1 + cost2 双向。
func calculateChamferDist(template, target gocv.Mat) float64 {
	invTemplate := gocv.NewMat()
	defer invTemplate.Release()
	gocv.BitwiseNot(template, &invTemplate)

	distMap := gocv.NewMat()
	defer distMap.Release()
	labels := gocv.NewMat()
	defer labels.Release()
	gocv.DistanceTransform(invTemplate, &distMap, &labels,
		gocv.DistL2, gocv.DistanceMaskPrecise, gocv.DistanceLabelCComp)

	target32F := gocv.NewMat()
	defer target32F.Release()
	target.ConvertTo(&target32F, gocv.MatTypeCV32F)
	target32F.MultiplyFloat(1.0 / 255.0)

	resultMap := gocv.NewMat()
	defer resultMap.Release()
	gocv.Multiply(distMap, target32F, &resultMap)

	sumVal := resultMap.Sum()
	nonZeroCount := gocv.CountNonZero(target)
	if nonZeroCount == 0 {
		return 999.0
	}
	return sumVal.Val1 / float64(nonZeroCount)
}

// calculateIoUSimilarity 算两二值图的交并比 0..1。
//
// 尺寸不一致直接返 0；并集为 0 也返 0。
func calculateIoUSimilarity(img1, img2 gocv.Mat) float64 {
	if img1.Rows() != img2.Rows() || img1.Cols() != img2.Cols() {
		return 0.0
	}
	intersection := gocv.NewMat()
	defer intersection.Release()
	union := gocv.NewMat()
	defer union.Release()
	gocv.BitwiseAnd(img1, img2, &intersection)
	gocv.BitwiseOr(img1, img2, &union)
	areaI := float64(gocv.CountNonZero(intersection))
	areaU := float64(gocv.CountNonZero(union))
	if areaU == 0 {
		return 0.0
	}
	return areaI / areaU
}

// CalculateVinFontDifference 比对两个字符图的字形差异，按 method 返 IoU 或倒角距离。
//
// 流程（与 gosmart 等价 — 仅去掉调试 shadow 图导出）：
//  1. 可选：按重心对齐
//  2. -5°..+5° 小角度旋转搜索 img2，找最大 IoU 的角度
//  3. 用最佳角度旋转 img2
//  4. 5x5 矩形核膨胀 img1 / img2（让笔画稍胖更稳）
//  5. 按 method 返：
//     - IOU：calculateIoUSimilarity (0..1，越大越相似)
//     - CHAMFER：calculateChamferDist 双向相加（0+，越小越相似）
//
// img1, img2: 二值灰度图（背景 0，字符 255），尺寸一致；
// method: FONT_DIST_IOU 或 FONT_DIST_CHAMFER；
// centroid: true 表示先做重心对齐；
// logFile: 仅给 caller 标识用，本函数不再写盘（gosmart 时代写 shadow 图，gomob 删掉）。
func CalculateVinFontDifference(img1, img2 gocv.Mat, method int, centroid bool, logFile string) float64 {
	_ = logFile
	img1Align := img1
	img2Align := img2
	if centroid {
		img1Align = AlignImageByCentroid(img1)
		defer img1Align.Release()
		img2Align = AlignImageByCentroid(img2)
		defer img2Align.Release()
	}

	// 小角度旋转搜索
	bestAngle := 0.0
	bestIoU := calculateIoUSimilarity(img1Align, img2Align)
	rows, cols := img2Align.Rows(), img2Align.Cols()
	center := image.Pt(cols/2, rows/2)
	for deg := -5; deg <= 5; deg++ {
		if deg == 0 {
			continue
		}
		angle := float64(deg)
		rotMat := gocv.GetRotationMatrix2D(center, angle, 1.0)
		rotated := gocv.NewMat()
		gocv.WarpAffineWithParams(img2Align, &rotated, rotMat, image.Pt(cols, rows),
			gocv.InterpolationLinear, gocv.BorderConstant, color.RGBA{0, 0, 0, 0})
		rotMat.Release()
		iou := calculateIoUSimilarity(img1Align, rotated)
		if iou > bestIoU {
			bestIoU = iou
			bestAngle = angle
		}
		rotated.Release()
	}

	img2Final := img2Align
	if bestAngle != 0 {
		rotMat := gocv.GetRotationMatrix2D(center, bestAngle, 1.0)
		img2Rotated := gocv.NewMat()
		gocv.WarpAffineWithParams(img2Align, &img2Rotated, rotMat, image.Pt(cols, rows),
			gocv.InterpolationLinear, gocv.BorderConstant, color.RGBA{0, 0, 0, 0})
		rotMat.Release()
		defer img2Rotated.Release()
		img2Final = img2Rotated
	}

	kernel := gocv.GetStructuringElement(gocv.MorphRect, image.Pt(5, 5))
	defer kernel.Release()
	img1Dilate := gocv.NewMat()
	img2Dilate := gocv.NewMat()
	defer img1Dilate.Release()
	defer img2Dilate.Release()
	gocv.Dilate(img1Align, &img1Dilate, kernel)
	gocv.Dilate(img2Final, &img2Dilate, kernel)

	if method == FONT_DIST_CHAMFER {
		c1 := calculateChamferDist(img1Dilate, img2Dilate)
		c2 := calculateChamferDist(img2Dilate, img1Dilate)
		return c1 + c2
	}
	return calculateIoUSimilarity(img1Dilate, img2Dilate)
}
