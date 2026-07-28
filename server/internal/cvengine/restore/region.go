// region.go —— VIN 区域观测（外部 VMASK 实例分割给出，取代此前逆向来的本地 yolo-obb）。
//
// 本文件只做「把外部四角点规范化成 TL/TR/BR/BL」这一件事，不含任何推理：
// letterbox、解码、旋转 NMS 全部由 gosmart 服务端完成，模型权重与版本也只在那一处维护。
package restore

import "sort"

// Detection 一个 VIN 区域观测：四角点（彩色图像素坐标系，TL/TR/BR/BL）+ 置信度。
//
// 不再保留朝向角字段：图像域的角度含透视，不是物理朝向；真实朝向由 buildFrame 把四角射线
// 投到深度承印平面后在平面内重算（render.go）。此前从 obb.py 端口过来的 Angle 字段全仓库无
// 消费点，随本次远程化一并删除。
type Detection struct {
	Corners [4][2]float64 `json:"corners"`
	Score   float64       `json:"score"`
}

// NewDetection 把外部返回的四角点规范化成 TL/TR/BR/BL 并构造观测。
//
// 为什么必须自己再排一次：gosmart 的 extractRotatedRect 主路径会排好序，但当旋转框顶点
// 恰好落在中心线上（整数坐标相等）时会掉进 fallback 分支，只对 Contour 做循环移位，
// 不保证角序。buildFrame 靠 corners[0..3] 的语义算宽高与基线，顺序错会让宽高互换。
func NewDetection(corners [4][2]float64, score float64) Detection {
	return Detection{Corners: sortCorners(corners), Score: score}
}

// ensureCCW 把多边形顶点统一成逆时针序（供 plane.go 的凸多边形内外判定使用）。
func ensureCCW(p [][2]float64) [][2]float64 {
	s := 0.0
	for i := 0; i < len(p); i++ {
		j := (i + 1) % len(p)
		s += p[i][0]*p[j][1] - p[j][0]*p[i][1]
	}
	if s < 0 {
		r := make([][2]float64, len(p))
		for i := range p {
			r[len(p)-1-i] = p[i]
		}
		return r
	}
	return p
}

// cross —— 点 p 相对有向边 a→b 的叉积（>0 在左侧/内侧）。
func cross(a, b, p [2]float64) float64 {
	return (b[0]-a[0])*(p[1]-a[1]) - (b[1]-a[1])*(p[0]-a[0])
}

// sortCorners 按 x+y 取 TL/BR、按 x-y 取 TR/BL。
// 与原厂 obb.py::_sort_corners 同口径；VIN 条带始终接近水平或竖直，不存在该判据失效的
// 近 45° 情形。
func sortCorners(pts [4][2]float64) [4][2]float64 {
	idx := []int{0, 1, 2, 3}
	bySum := append([]int(nil), idx...)
	byDiff := append([]int(nil), idx...)
	sort.SliceStable(bySum, func(i, j int) bool {
		return (pts[bySum[i]][0] + pts[bySum[i]][1]) < (pts[bySum[j]][0] + pts[bySum[j]][1])
	})
	sort.SliceStable(byDiff, func(i, j int) bool {
		return (pts[byDiff[i]][0] - pts[byDiff[i]][1]) < (pts[byDiff[j]][0] - pts[byDiff[j]][1])
	})
	return [4][2]float64{
		pts[bySum[0]],  // TL
		pts[byDiff[3]], // TR
		pts[bySum[3]],  // BR
		pts[byDiff[0]], // BL
	}
}
