package laser

import (
	"fmt"
	"math"
)

// PointRegionFilter 定义工位区域墙过滤。
// Points 是 A/融合世界坐标系里的闭合多边形顶点（mm），按 XY 投影判断墙内，Z 方向视为无限高虚拟墙。
// BToA 可选；存在时 unitB 点先变换到 A/融合坐标系再判断，但保留输出的原始 unitB 坐标。
type PointRegionFilter struct {
	Enabled bool         `json:"enabled,omitempty"`
	Points  [][3]float32 `json:"points,omitempty"`
	BToA    []float32    `json:"b_to_a,omitempty"`
}

func (f PointRegionFilter) Normalized() (PointRegionFilter, error) {
	if !f.Enabled {
		return PointRegionFilter{}, nil
	}
	points := make([][3]float32, 0, len(f.Points))
	for i, p := range f.Points {
		if !finite32(p[0]) || !finite32(p[1]) || !finite32(p[2]) {
			return PointRegionFilter{}, fmt.Errorf("第 %d 个区域点不是有效坐标", i+1)
		}
		if len(points) > 0 {
			prev := points[len(points)-1]
			if sameXY(prev, p) {
				continue
			}
		}
		points = append(points, p)
	}
	if len(points) > 2 && sameXY(points[0], points[len(points)-1]) {
		points = points[:len(points)-1]
	}
	if len(points) < 3 {
		return PointRegionFilter{}, fmt.Errorf("区域标定至少需要 3 个非重复点")
	}
	if math.Abs(float64(polygonArea2(points))) < 1e-3 {
		return PointRegionFilter{}, fmt.Errorf("区域标定点不能共线")
	}
	var bToA []float32
	if len(f.BToA) > 0 {
		if len(f.BToA) != 16 {
			return PointRegionFilter{}, fmt.Errorf("b_to_a 必须是 16 个数")
		}
		bToA = make([]float32, 16)
		for i, v := range f.BToA {
			if !finite32(v) {
				return PointRegionFilter{}, fmt.Errorf("b_to_a[%d] 不是有效数值", i)
			}
			bToA[i] = v
		}
	}
	return PointRegionFilter{Enabled: true, Points: points, BToA: bToA}, nil
}

func (f PointRegionFilter) active() bool {
	return f.Enabled && len(f.Points) >= 3
}

func (f PointRegionFilter) matrixForUnit(unit int) ([16]float32, bool) {
	if unit != 1 || len(f.BToA) != 16 {
		return [16]float32{}, false
	}
	var m [16]float32
	copy(m[:], f.BToA)
	return m, true
}

func filterPointFrame(f PointFrame, filter PointRegionFilter) PointFrame {
	if !filter.active() || len(f.XYZmm) == 0 {
		return f
	}
	f.XYZmm = filterXYZByRegion(f.XYZmm, f.Unit, filter)
	return f
}

func filterColorPointFrame(f ColorPointFrame, filter PointRegionFilter) ColorPointFrame {
	if !filter.active() || len(f.XYZmm) == 0 || len(f.RGB) == 0 {
		return f
	}
	f.XYZmm, f.RGB = filterXYZRGBByRegion(f.XYZmm, f.RGB, f.Unit, filter)
	return f
}

func filterXYZForUnit(xyz []float32, unit int, filter PointRegionFilter) []float32 {
	if !filter.active() || len(xyz) == 0 {
		return xyz
	}
	return filterXYZByRegion(xyz, unit, filter)
}

func filterXYZRGBForUnit(xyz []float32, rgb []uint32, unit int, filter PointRegionFilter) ([]float32, []uint32) {
	if !filter.active() || len(xyz) == 0 || len(rgb) == 0 {
		return xyz, rgb
	}
	return filterXYZRGBByRegion(xyz, rgb, unit, filter)
}

func filterXYZByRegion(xyz []float32, unit int, filter PointRegionFilter) []float32 {
	if !filter.active() {
		return xyz
	}
	m, hasMatrix := filter.matrixForUnit(unit)
	out := make([]float32, 0, len(xyz))
	for i := 0; i+2 < len(xyz); i += 3 {
		x, y, z := xyz[i], xyz[i+1], xyz[i+2]
		tx, ty := x, y
		if hasMatrix {
			tx, ty, _ = transformXYZ(x, y, z, m)
		}
		if pointInRegionXY(tx, ty, filter.Points) {
			out = append(out, x, y, z)
		}
	}
	return out
}

func filterXYZRGBByRegion(xyz []float32, rgb []uint32, unit int, filter PointRegionFilter) ([]float32, []uint32) {
	n := len(xyz) / 3
	if len(rgb) < n {
		n = len(rgb)
	}
	if !filter.active() {
		return xyz, rgb
	}
	m, hasMatrix := filter.matrixForUnit(unit)
	outXYZ := make([]float32, 0, n*3)
	outRGB := make([]uint32, 0, n)
	for i := 0; i < n; i++ {
		j := i * 3
		x, y, z := xyz[j], xyz[j+1], xyz[j+2]
		tx, ty := x, y
		if hasMatrix {
			tx, ty, _ = transformXYZ(x, y, z, m)
		}
		if pointInRegionXY(tx, ty, filter.Points) {
			outXYZ = append(outXYZ, x, y, z)
			outRGB = append(outRGB, rgb[i])
		}
	}
	return outXYZ, outRGB
}

func pointInRegionXY(x, y float32, poly [][3]float32) bool {
	if len(poly) < 3 {
		return true
	}
	inside := false
	j := len(poly) - 1
	for i := 0; i < len(poly); i++ {
		xi, yi := poly[i][0], poly[i][1]
		xj, yj := poly[j][0], poly[j][1]
		if pointOnSegmentXY(x, y, xi, yi, xj, yj) {
			return true
		}
		if (yi > y) != (yj > y) {
			crossX := (xj-xi)*(y-yi)/(yj-yi) + xi
			if x <= crossX {
				inside = !inside
			}
		}
		j = i
	}
	return inside
}

func pointOnSegmentXY(x, y, ax, ay, bx, by float32) bool {
	const eps = 1e-3
	cross := (x-ax)*(by-ay) - (y-ay)*(bx-ax)
	if math.Abs(float64(cross)) > eps {
		return false
	}
	dot := (x-ax)*(x-bx) + (y-ay)*(y-by)
	return dot <= eps
}

func polygonArea2(points [][3]float32) float32 {
	var sum float32
	for i := range points {
		j := (i + 1) % len(points)
		sum += points[i][0]*points[j][1] - points[j][0]*points[i][1]
	}
	return sum
}

func transformXYZ(x, y, z float32, m [16]float32) (float32, float32, float32) {
	return m[0]*x + m[1]*y + m[2]*z + m[3],
		m[4]*x + m[5]*y + m[6]*z + m[7],
		m[8]*x + m[9]*y + m[10]*z + m[11]
}

func finite32(v float32) bool {
	return !math.IsNaN(float64(v)) && !math.IsInf(float64(v), 0)
}

func sameXY(a, b [3]float32) bool {
	return math.Abs(float64(a[0]-b[0])) < 1e-3 && math.Abs(float64(a[1]-b[1])) < 1e-3
}
