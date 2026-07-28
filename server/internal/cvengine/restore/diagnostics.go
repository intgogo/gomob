// diagnostics.go —— 一致性验收的坐标契约。
//
// 判定「同一物体多角度还原是否落在同一固定坐标」时，需要把 4425×600 用户画布放回逐字符
// 检测已验证的 1200×260 探针域再测量。这套相似变换必须是**全样本相同的常量**：它只由
// 25px/mm → 9px/mm 的评估口径决定，不读图像内容、不按每张图的检测结果重新缩放，也不做
// 任何逐图配准——否则测的就不再是一致性，而是配准算法的能力。
//
// 放在生产代码而非测试文件，是因为观测录制工具与验收测试必须用同一份实现：两边送检的图像
// 字节要逐字节相同，回放才寻址得到同一条录制。
package restore

import (
	"fmt"
	"image"
	"image/color"

	"io.gomob/server/internal/cvengine/gocv"
)

// CanonicalProbeLayout 是输出画布到探针域的固定相似变换。
type CanonicalProbeLayout struct {
	Scale                  float64
	TranslateX, TranslateY float64
}

// MakeCanonicalProbeLayout 返回评估坐标契约。固定 0.36 = 探针 9px/mm ÷ 输出 25px/mm。
func MakeCanonicalProbeLayout() CanonicalProbeLayout {
	const scale = 0.36
	return CanonicalProbeLayout{
		Scale:      scale,
		TranslateX: (float64(CanonicalProbeW) - float64(CanonicalOutW)*scale) * 0.5,
		TranslateY: (float64(CanonicalProbeH) - float64(CanonicalOutH)*scale) * 0.5,
	}
}

// ToOutputCoordinates 把探针域坐标映回 4425×600 输出画布坐标。
func (l CanonicalProbeLayout) ToOutputCoordinates(x, y float64) (float64, float64) {
	return (x - l.TranslateX) / l.Scale, (y - l.TranslateY) / l.Scale
}

// RenderCanonicalProbeView 把 4425×600 输出图按固定相似变换渲染到 1200×260 探针画布。
// 调用方负责释放返回的 Mat。
func RenderCanonicalProbeView(output gocv.Mat) (gocv.Mat, CanonicalProbeLayout, error) {
	layout := MakeCanonicalProbeLayout()
	matrix, err := gocv.NewMatFromBytes(
		2, 3, gocv.MatTypeCV64F,
		f64bytes([]float64{
			layout.Scale, 0, layout.TranslateX,
			0, layout.Scale, layout.TranslateY,
		}),
	)
	if err != nil {
		return gocv.Mat{}, layout, fmt.Errorf("创建固定探针相似变换: %w", err)
	}
	defer func() { _ = matrix.Release() }()

	canvas := gocv.NewMat()
	gocv.WarpAffineWithParams(
		output, &canvas, matrix,
		image.Pt(CanonicalProbeW, CanonicalProbeH),
		gocv.InterpolationLinear,
		gocv.BorderConstant,
		color.RGBA{R: 128, G: 128, B: 128},
	)
	return canvas, layout, nil
}
