// provider.go —— 还原链依赖的外部视觉服务边界。
//
// 依赖倒置：restore 只声明它要什么观测，由 cvengine handler 用 vinalgo 客户端注入实现，
// harness 则注入录制回放实现。restore 包自身不含模型、不含 HTTP。
//
// 两次调用的输入图不同，不能合并成一次：
//   - LocateVinRegions 吃原始 HLSD8 彩色帧，用于把深度平面拟合限定在承印面
//   - DetectCharacters 吃深度平面还原出的 probe 正射图，用于解 17 字符物理基线
//
// 后者必须发生在前者与深度平面之后，因为 probe 图本身就是平面的产物。
package restore

import "context"

// VisionProvider 提供外部原子视觉观测；返回坐标一律位于传入图像的像素坐标系。
type VisionProvider interface {
	// LocateVinRegions 检出 VIN 区域旋转框。无检出返回空切片而非错误——
	// 那是可重拍的采集判废，不是系统故障。
	LocateVinRegions(ctx context.Context, imageBytes []byte) ([]Detection, error)
	// DetectCharacters 检出逐字符观测，不做选行、拼串或校验位修复。
	DetectCharacters(ctx context.Context, imageBytes []byte) ([]CharacterBox, error)
}

// CharacterBox 一条字符观测：轴对齐框 + 识别出的字符 + 置信度。
//
// Character 只用于诊断串与过滤非 VIN 字符（钢印两端的 ☆ 会被检成 "-"，实测置信度高达
// 0.94，必须靠它剔除）；17 字符格架的几何解算只用框的位置与尺寸，认错字符不影响还原。
type CharacterBox struct {
	MinX      float64 `json:"min_x"`
	MinY      float64 `json:"min_y"`
	MaxX      float64 `json:"max_x"`
	MaxY      float64 `json:"max_y"`
	Character string  `json:"character"`
	Score     float64 `json:"score"`
}

// Width 返回框宽。
func (b CharacterBox) Width() float64 { return b.MaxX - b.MinX }

// Height 返回框高。
func (b CharacterBox) Height() float64 { return b.MaxY - b.MinY }

// CenterX 返回框心横坐标。
func (b CharacterBox) CenterX() float64 { return (b.MinX + b.MaxX) * 0.5 }

// CenterY 返回框心纵坐标。
func (b CharacterBox) CenterY() float64 { return (b.MinY + b.MaxY) * 0.5 }

// IsVinCharacter 判断是否为合法 VIN 字符（0-9、A-Z）。
func (b CharacterBox) IsVinCharacter() bool {
	if len(b.Character) != 1 {
		return false
	}
	c := b.Character[0]
	return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')
}
