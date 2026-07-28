// Package vinvision 把外部算法服务适配成 VIN 还原链的视觉观测来源。
// 单独成包是为了打破依赖环：cvengine 与 restore 的测试都要用它，而 restore 不能反向依赖 cvengine。
package vinvision

import (
	"context"

	"io.gomob/server/internal/cvengine/restore"
	"io.gomob/server/internal/vinalgo"
)

// Detector 是本包依赖的最小外部检测能力，便于测试注入假实现。
type Detector interface {
	Detect(
		ctx context.Context,
		method vinalgo.DetectMethod,
		image []byte,
		fileName string,
	) ([]vinalgo.DetectedObject, error)
}

// provider 把外部算法服务适配成 restore.VisionProvider。
//
// 还原链所需的两个模型（VMASK 区域、VINS 字符）都只在 gosmart 一处维护，
// gomob 不再持有任何 VIN 检测权重：还原用的字符观测与最终 OCR 出自同一份模型，
// 不会因两侧模型版本漂移而让几何和识别对不上。
type provider struct {
	detector Detector
}

// New 用外部算法客户端构造还原链的视觉服务。
func New(detector Detector) restore.VisionProvider {
	return provider{detector: detector}
}

// LocateVinRegions 用 VMASK 实例分割检出 VIN 区域旋转框。
func (p provider) LocateVinRegions(
	ctx context.Context,
	imageBytes []byte,
) ([]restore.Detection, error) {
	objects, err := p.detector.Detect(ctx, vinalgo.MethodVMASK, imageBytes, "vin_color.jpg")
	if err != nil {
		return nil, err
	}
	detections := make([]restore.Detection, 0, len(objects))
	for _, obj := range objects {
		// 服务端 extractRotatedRect 主路径已排好 TL/TR/BR/BL，但顶点落在中心线上时会走
		// 只做循环移位的 fallback；NewDetection 统一再规范化一次，避免宽高互换。
		detections = append(detections, restore.NewDetection(obj.Corners, obj.Score))
	}
	return detections, nil
}

// DetectCharacters 用 VINS 检出逐字符观测（送检的是深度平面还原出的 probe 正射图）。
func (p provider) DetectCharacters(
	ctx context.Context,
	imageBytes []byte,
) ([]restore.CharacterBox, error) {
	objects, err := p.detector.Detect(ctx, vinalgo.MethodVINS, imageBytes, "vin_probe.png")
	if err != nil {
		return nil, err
	}
	boxes := make([]restore.CharacterBox, 0, len(objects))
	for _, obj := range objects {
		minX, minY := obj.Corners[0][0], obj.Corners[0][1]
		maxX, maxY := minX, minY
		for _, corner := range obj.Corners[1:] {
			if corner[0] < minX {
				minX = corner[0]
			}
			if corner[0] > maxX {
				maxX = corner[0]
			}
			if corner[1] < minY {
				minY = corner[1]
			}
			if corner[1] > maxY {
				maxY = corner[1]
			}
		}
		boxes = append(boxes, restore.CharacterBox{
			MinX: minX, MinY: minY, MaxX: maxX, MaxY: maxY,
			Character: obj.Class,
			Score:     obj.Score,
		})
	}
	return boxes, nil
}
