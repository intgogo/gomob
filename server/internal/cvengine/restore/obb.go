// Package restore —— VIN 数码拓印还原管线（端口自 tests/harness/vin_restore/*.py，逐函数对齐）。
//
// 链路：彩色 + 深度 → 深度 RANSAC 承印面 → YOLO OBB 限定范围 → VINCHAR 拟合 17 位刚性格架 →
// 从原始彩色图按物理平面一次 Remap 到固定规范画布。
//
// 一致性以 vin_restore_consistency harness 的固定坐标直接比较为准，禁止评估前配准。
package restore

import (
	"image"
	"math"
	"sort"

	"io.gomob/server/internal/cvengine/gocv"
)

// OBB 解码常量 —— 与 obb.py 完全一致。
const (
	obbConf   = 0.5 // CONF：score 门限
	obbNMSIoU = 0.4 // NMS_IOU：旋转框 NMS 阈值
	obbInp    = 640 // INP：letterbox 边长
	obbPad    = 114 // PAD：letterbox 填充灰度
)

// Detection 一个 OBB 检测：四角点（color px，TL/TR/BR/BL 顺序）+ 长轴朝向 + score。
type Detection struct {
	Corners [4][2]float64 // TL,TR,BR,BL
	Angle   float64       // 长轴朝向（度）
	Score   float64
}

// ObbDetector 持有 KindCom 注册的 yolo-obb 模型（通过 runner 抽象，便于 handler/smoke 复用）。
type comRunner interface {
	RunCom(tag string, blob gocv.Mat) ([]float32, error)
}

// Detect —— 1:1 复刻 obb.py::ObbDetector.detect。
//
// 输入 bgr（OpenCV 解码的彩色 Mat，BGR 通道）；返回经旋转 NMS 后的检测列表。
//   - preprocess：letterbox 640 + pad114 → BGR2RGB → ÷255 → NCHW（由 BlobFromImage 完成 ÷255+NCHW）
//   - 推理：CreateORTCom 吐 [1,6,8400] 原始张量
//   - 解码：转置后每行 [cx,cy,w,h,score,angleRad]；score≥0.5；rotatedNms IoU>0.4
func Detect(runner comRunner, tag string, bgr gocv.Mat) ([]Detection, error) {
	blob, ratio, padX, padY := preprocess(bgr)
	defer func() { _ = blob.Release() }()

	raw, err := runner.RunCom(tag, blob)
	if err != nil {
		return nil, err
	}

	// 输出 [1,6,8400]：raw 扁平 = 6 行 × 8400 列（行优先）。
	// 转置后每个 anchor i 取 6 个属性：cx=raw[0*N+i] cy=raw[1*N+i] w=raw[2*N+i] h=raw[3*N+i]
	//   score=raw[4*N+i] angRad=raw[5*N+i]。
	const nAttr = 6
	if len(raw) == 0 || len(raw)%nAttr != 0 {
		return nil, nil
	}
	n := len(raw) / nAttr

	cands := make([]Detection, 0, 16)
	for i := 0; i < n; i++ {
		cx := float64(raw[0*n+i])
		cy := float64(raw[1*n+i])
		w := float64(raw[2*n+i])
		h := float64(raw[3*n+i])
		score := float64(raw[4*n+i])
		ang := float64(raw[5*n+i])
		if score < obbConf || w <= 1 || h <= 1 {
			continue
		}
		// 反 letterbox 回原图坐标
		cx = (cx - float64(padX)) / ratio
		cy = (cy - float64(padY)) / ratio
		w /= ratio
		h /= ratio
		angDeg := normAngle(ang * 180.0 / math.Pi)
		corners := rotatedCorners(cx, cy, w, h, angDeg)
		cands = append(cands, Detection{
			Corners: corners,
			Angle:   orientDeg(corners),
			Score:   score,
		})
	}
	return rotatedNMS(cands), nil
}

// preprocess —— obb.py::_preprocess：letterbox 到 640×640、pad=114，返回 BlobFromImage 造的 NCHW blob。
//
// Python：resize(保比) → 居中贴到 114 灰底画布 → BGR2RGB → ÷255 → NCHW。
// 这里：手工 letterbox 到 640×640 RGB Mat（swapRB），再 BlobFromImage(std=1/255, swapRB=false) 完成 ÷255+NCHW。
func preprocess(bgr gocv.Mat) (blob gocv.Mat, ratio float64, padX, padY int) {
	w := bgr.Cols()
	h := bgr.Rows()
	ratio = math.Min(float64(obbInp)/float64(w), float64(obbInp)/float64(h))
	nw := int(float64(w) * ratio)
	if nw < 1 {
		nw = 1
	}
	nh := int(float64(h) * ratio)
	if nh < 1 {
		nh = 1
	}
	padX = (obbInp - nw) / 2
	padY = (obbInp - nh) / 2

	resized := gocv.NewMat()
	defer func() { _ = resized.Release() }()
	gocv.Resize(bgr, &resized, image.Pt(nw, nh), 0, 0, gocv.InterpolationLinear)

	// 114 灰底画布（CV8UC3，BGR 与输入一致）。
	gray114 := gocv.Scalar{Val1: obbPad, Val2: obbPad, Val3: obbPad, Val4: 0}
	canvas := gocv.NewMatWithSizeFromScalar(gray114, obbInp, obbInp, gocv.MatTypeCV8UC3)
	defer func() { _ = canvas.Release() }()

	roi := canvas.Region(image.Rect(padX, padY, padX+nw, padY+nh))
	resized.CopyTo(&roi)
	_ = roi.Release()

	// BlobFromImage：std=1/255 → 像素 ÷255；swapRB=true → BGR→RGB（等价 Python cvtColor BGR2RGB）。
	blob = gocv.BlobFromImage(canvas, 1.0/255.0, image.Pt(obbInp, obbInp),
		gocv.NewScalar(0, 0, 0, 0), true, false)
	return blob, ratio, padX, padY
}

// normAngle —— obb.py::_norm_angle：把角度规约到 [-90, 90)。
func normAngle(a float64) float64 {
	for a >= 90.0 {
		a -= 180.0
	}
	for a < -90.0 {
		a += 180.0
	}
	return a
}

// rotatedCorners —— obb.py::_rotated_corners + _sort_corners。
// 本地角点(±w/2,±h/2) 旋转 angDeg、平移到 (cx,cy)，再 sortCorners 排 TL/TR/BR/BL。
func rotatedCorners(cx, cy, w, h, angDeg float64) [4][2]float64 {
	r := angDeg / 180.0 * math.Pi
	cs, sn := math.Cos(r), math.Sin(r)
	hw, hh := w/2, h/2
	local := [4][2]float64{{-hw, -hh}, {hw, -hh}, {hw, hh}, {-hw, hh}}
	var pts [4][2]float64
	for i, p := range local {
		x, y := p[0], p[1]
		pts[i][0] = x*cs + cx - y*sn
		pts[i][1] = x*sn + cy + y*cs
	}
	return sortCorners(pts)
}

// sortCorners —— obb.py::_sort_corners：按 x+y 取 TL/BR，按 x-y 取 TR/BL。
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
	tl := pts[bySum[0]]
	br := pts[bySum[3]]
	tr := pts[byDiff[3]]
	bl := pts[byDiff[0]]
	return [4][2]float64{tl, tr, br, bl}
}

// orientDeg —— obb.py::_orient_deg：取长轴朝向（水平边 vs 竖直边平均向量取长者）。
func orientDeg(c [4][2]float64) float64 {
	// c = TL,TR,BR,BL
	hx := ((c[1][0] - c[0][0]) + (c[2][0] - c[3][0])) * 0.5
	hy := ((c[1][1] - c[0][1]) + (c[2][1] - c[3][1])) * 0.5
	vx := ((c[2][0] - c[1][0]) + (c[3][0] - c[0][0])) * 0.5
	vy := ((c[2][1] - c[1][1]) + (c[3][1] - c[0][1])) * 0.5
	var a float64
	if math.Hypot(hx, hy) < math.Hypot(vx, vy) {
		a = math.Atan2(vy, vx)
	} else {
		a = math.Atan2(hy, hx)
	}
	return normAngle(a * 180.0 / math.Pi)
}

// rotatedNMS —— obb.py::_rotated_nms：按 score 降序，IoU≤0.4 才保留。
func rotatedNMS(cands []Detection) []Detection {
	sort.SliceStable(cands, func(i, j int) bool { return cands[i].Score > cands[j].Score })
	keep := make([]Detection, 0, len(cands))
	for _, c := range cands {
		ok := true
		for _, k := range keep {
			if rotatedIoU(c.Corners, k.Corners) > obbNMSIoU {
				ok = false
				break
			}
		}
		if ok {
			keep = append(keep, c)
		}
	}
	return keep
}

// rotatedIoU —— obb.py::_rotated_iou：两凸四边形交/并。
// Python 用 cv2.intersectConvexConvex；这里用 Sutherland–Hodgman 凸裁剪 + shoelace，对凸多边形等价。
func rotatedIoU(a, b [4][2]float64) float64 {
	pa := a[:]
	pb := b[:]
	inter := convexClipArea(pa, pb)
	if inter <= 0 {
		return 0
	}
	aa := polyArea(pa)
	ab := polyArea(pb)
	u := aa + ab - inter
	if u <= 1e-6 {
		return 0
	}
	return inter / u
}

// polyArea —— shoelace（绝对值）。
func polyArea(p [][2]float64) float64 {
	n := len(p)
	if n < 3 {
		return 0
	}
	s := 0.0
	for i := 0; i < n; i++ {
		j := (i + 1) % n
		s += p[i][0]*p[j][1] - p[j][0]*p[i][1]
	}
	return math.Abs(s) * 0.5
}

// convexClipArea —— 用 clip 凸多边形裁剪 subject 凸多边形，返回交集面积（Sutherland–Hodgman）。
func convexClipArea(subject, clip [][2]float64) float64 {
	out := make([][2]float64, len(subject))
	copy(out, subject)
	// 保证 clip 为逆时针（shoelace 有向面积 > 0），使 inside 判定方向一致。
	cl := ensureCCW(clip)
	for i := 0; i < len(cl); i++ {
		a := cl[i]
		b := cl[(i+1)%len(cl)]
		if len(out) == 0 {
			break
		}
		input := out
		out = out[:0:0]
		out = make([][2]float64, 0, len(input)+1)
		for j := 0; j < len(input); j++ {
			cur := input[j]
			prev := input[(j-1+len(input))%len(input)]
			curIn := cross(a, b, cur) >= 0
			prevIn := cross(a, b, prev) >= 0
			if curIn {
				if !prevIn {
					if p, ok := segIntersect(prev, cur, a, b); ok {
						out = append(out, p)
					}
				}
				out = append(out, cur)
			} else if prevIn {
				if p, ok := segIntersect(prev, cur, a, b); ok {
					out = append(out, p)
				}
			}
		}
	}
	return polyArea(out)
}

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

// segIntersect —— 线段 p1p2 与直线 a→b 的交点（用于多边形裁剪）。
func segIntersect(p1, p2, a, b [2]float64) ([2]float64, bool) {
	d1x, d1y := p2[0]-p1[0], p2[1]-p1[1]
	d2x, d2y := b[0]-a[0], b[1]-a[1]
	denom := d1x*d2y - d1y*d2x
	if math.Abs(denom) < 1e-12 {
		return [2]float64{}, false
	}
	t := ((a[0]-p1[0])*d2y - (a[1]-p1[1])*d2x) / denom
	return [2]float64{p1[0] + t*d1x, p1[1] + t*d1y}, true
}
