package restore

import (
	"encoding/binary"
	"errors"
	"image/color"
	"math"

	"io.gomob/server/internal/cvengine/gocv"
)

// 输出窗 —— restore_obb.py：OUT_W=1200 / OUT_H=260 / mx=0.08 / my=0.22。
const (
	OutW = 1200
	OutH = 260
	marX = 0.08
	marY = 0.22
)

// frame —— restore_obb.py::restore_obb 的产物：定位好的平面 + OBB 四角(平面内 a,b)。
type frame struct {
	plane  Plane
	right  Vec3
	up     Vec3
	kc     [4]float64 // 彩色内参 fx,fy,cx,cy
	color  gocv.Mat   // 彩色 BGR
	ab     [4][2]float64
	tilt   float64
	width  float64
	height float64
	theta  float64
}

// rayPlaneInplane —— restore_obb.py::ray_plane_inplane。
// 彩色像素 (u,v) → 过相机原点射线 ∩ 平面 → depth 系 3D → 平面内 2D (a,b)（相对 centroid）。
func rayPlaneInplane(u, v float64, kc [4]float64, p Plane, right, up Vec3) (ab [2]float64, P Vec3) {
	fx, fy, cx, cy := kc[0], kc[1], kc[2], kc[3]
	d := Vec3{(u - cx) / fx, (v - cy) / fy, 1.0}
	// t = -(n·0 + D) / (n·d) = -D / (n·d)
	t := -(p.D) / dot3(p.N, d)
	P = scale3(d, t)
	rel := sub3(P, p.Centroid)
	ab = [2]float64{dot3(rel, right), dot3(rel, up)}
	return ab, P
}

// buildFrame —— restore_obb.py::restore_obb：把深度平面 + OBB 检测合成一个 frame。
//
// dets：彩色图上的 OBB 检测（已 NMS）；取 score 最大者。返回 nil + tilt 信息表示 tilt>70 废弃。
func buildFrame(
	depth []byte, dw, dh int,
	kd [4]float64, colorBGR gocv.Mat,
	dets []Detection,
) (*frame, float64, error) {
	pts := backprojectROI(depth, dw, dh, kd[0], kd[1], kd[2], kd[3], defaultROI)
	if len(pts) < 100 {
		return nil, 0, errors.New("深度有效点过少")
	}
	plane, err := ransacPlane(pts)
	if err != nil {
		return nil, 0, err
	}
	tilt := tiltDeg(plane)
	if tilt > MaxTiltDeg {
		return nil, tilt, nil // tilt 门：废弃但非错误
	}
	right, up := planeBasis(plane)

	if len(dets) == 0 {
		return nil, tilt, errors.New("未检测到 VIN OBB")
	}
	// 取 score 最大
	best := dets[0]
	for _, d := range dets[1:] {
		if d.Score > best.Score {
			best = d
		}
	}

	// 彩色内参 = depth × (彩色宽/深度宽)，外参单位阵（restore_obb.py 同光路假设）。
	s := float64(colorBGR.Cols()) / float64(dw)
	kc := [4]float64{kd[0] * s, kd[1] * s, kd[2] * s, kd[3] * s}

	var ab [4][2]float64
	for i, c := range best.Corners {
		ab[i], _ = rayPlaneInplane(c[0], c[1], kc, plane, right, up)
	}
	tl, tr, br, bl := ab[0], ab[1], ab[2], ab[3]
	widthMM := (dist2(tr, tl) + dist2(br, bl)) * 0.5
	heightMM := (dist2(bl, tl) + dist2(br, tr)) * 0.5
	dx := ((tr[0] - tl[0]) + (br[0] - bl[0]))
	dy := ((tr[1] - tl[1]) + (br[1] - bl[1]))
	theta := math.Atan2(dy, dx) * 180.0 / math.Pi

	return &frame{
		plane: plane, right: right, up: up, kc: kc, color: colorBGR, ab: ab,
		tilt: tilt, width: widthMM, height: heightMM, theta: theta,
	}, tilt, nil
}

// render —— restore_obb.py::render。
//
// OBB 四角(平面内) → 固定输出矩形单应（钉死四角）；逐输出像素 → 单应 → 平面内(a,b) → 3D → 彩色采样。
// 返回 OutH×OutW 的 BGR 正射图（无效像素=黑）。
func render(f *frame) (gocv.Mat, error) {
	px0, py0 := marX*OutW, marY*OutH
	px1, py1 := (1-marX)*OutW, (1-marY)*OutH
	outCorners := [4][2]float64{{px0, py0}, {px1, py0}, {px1, py1}, {px0, py1}} // TL,TR,BR,BL

	// H：输出像素 → 平面内 (a,b)（getPerspectiveTransform(out_corners, ab)）。
	H, ok := perspectiveTransform(outCorners, f.ab)
	if !ok {
		return gocv.Mat{}, errors.New("单应求解失败（四角退化）")
	}

	fxc, fyc, cxc, cyc := f.kc[0], f.kc[1], f.kc[2], f.kc[3]
	C := f.plane.Centroid
	right, up := f.right, f.up

	// 逐输出像素算采样坐标 → 填 map1(u)/map2(v) 的 CV32F mat。
	mapU := make([]byte, OutW*OutH*4)
	mapV := make([]byte, OutW*OutH*4)
	for y := 0; y < OutH; y++ {
		for x := 0; x < OutW; x++ {
			gx, gy, gw := float64(x), float64(y), 1.0
			a := (H[0]*gx + H[1]*gy + H[2]*gw)
			b := (H[3]*gx + H[4]*gy + H[5]*gw)
			w := (H[6]*gx + H[7]*gy + H[8]*gw)
			a /= w
			b /= w
			// Q = C + a*right + b*up
			qx := C[0] + a*right[0] + b*up[0]
			qy := C[1] + a*right[1] + b*up[1]
			qz := C[2] + a*right[2] + b*up[2]
			var u, v float32
			if qz > 1e-3 {
				u = float32(fxc*qx/qz + cxc)
				v = float32(fyc*qy/qz + cyc)
			} else {
				// 无效 → 给越界坐标，Remap BORDER_CONSTANT 填黑
				u = -1
				v = -1
			}
			off := (y*OutW + x) * 4
			binary.LittleEndian.PutUint32(mapU[off:], math.Float32bits(u))
			binary.LittleEndian.PutUint32(mapV[off:], math.Float32bits(v))
		}
	}

	m1, err := gocv.NewMatFromBytes(OutH, OutW, gocv.MatTypeCV32F, mapU)
	if err != nil {
		return gocv.Mat{}, err
	}
	defer func() { _ = m1.Release() }()
	m2, err := gocv.NewMatFromBytes(OutH, OutW, gocv.MatTypeCV32F, mapV)
	if err != nil {
		return gocv.Mat{}, err
	}
	defer func() { _ = m2.Release() }()

	out := gocv.NewMat()
	gocv.Remap(f.color, &out, &m1, &m2, gocv.InterpolationLinear,
		gocv.BorderConstant, color.RGBA{R: 0, G: 0, B: 0, A: 0})
	return out, nil
}

// perspectiveTransform —— 等价 cv2.getPerspectiveTransform(src,dst)：求 3×3 单应 H，
// 使 dst = H·src（齐次）。用 8×8 线性方程组 gocv.Solve(LU) 求 8 个未知数，h33=1。
//
// 用 Solve 而非 gocv.GetPerspectiveTransform 的原因：后者 C 侧只收整数 Contour 点，
// 会把亚像素的 ab(平面内 mm) 截断丢精度。这里全程 float64。
func perspectiveTransform(src, dst [4][2]float64) ([9]float64, bool) {
	// 构造 8×8 A 和 8×1 b：每对点出两行。
	A := make([]float64, 8*8)
	B := make([]float64, 8)
	for i := 0; i < 4; i++ {
		x, y := src[i][0], src[i][1]
		X, Y := dst[i][0], dst[i][1]
		r0 := (2 * i) * 8
		A[r0+0] = x
		A[r0+1] = y
		A[r0+2] = 1
		A[r0+3] = 0
		A[r0+4] = 0
		A[r0+5] = 0
		A[r0+6] = -x * X
		A[r0+7] = -y * X
		B[2*i] = X

		r1 := (2*i + 1) * 8
		A[r1+0] = 0
		A[r1+1] = 0
		A[r1+2] = 0
		A[r1+3] = x
		A[r1+4] = y
		A[r1+5] = 1
		A[r1+6] = -x * Y
		A[r1+7] = -y * Y
		B[2*i+1] = Y
	}
	matA, err := gocv.NewMatFromBytes(8, 8, gocv.MatTypeCV64F, f64bytes(A))
	if err != nil {
		return [9]float64{}, false
	}
	defer func() { _ = matA.Release() }()
	matB, err := gocv.NewMatFromBytes(8, 1, gocv.MatTypeCV64F, f64bytes(B))
	if err != nil {
		return [9]float64{}, false
	}
	defer func() { _ = matB.Release() }()
	sol := gocv.NewMat()
	defer func() { _ = sol.Release() }()
	if !gocv.Solve(matA, matB, &sol, gocv.SolveDecompositionLu) {
		return [9]float64{}, false
	}
	var h [9]float64
	for i := 0; i < 8; i++ {
		h[i] = sol.GetDoubleAt(i, 0)
	}
	h[8] = 1.0
	return h, true
}

func dist2(a, b [2]float64) float64 {
	return math.Hypot(a[0]-b[0], a[1]-b[1])
}
