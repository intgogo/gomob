package restore

import (
	"encoding/binary"
	"errors"
	"math"
	"math/rand"
	"sort"

	"io.gomob/server/internal/cvengine/gocv"
)

// MaxTiltDeg 原厂硬门：承印面相对相机倾角 >70° 判废（restoreImageFlow 码 34）。
const MaxTiltDeg = 70.0

// roi 中心 ROI（cx,cy,w,h 归一化）—— restore.py backproject ROI=(0.5,0.5,0.9,0.9)。
var defaultROI = [4]float64{0.5, 0.5, 0.9, 0.9}

// Vec3 三维向量。
type Vec3 [3]float64

// Plane 主平面：法向 n（单位、朝相机）、d（n·P+d=0）、质心、内点比、rms、自适应阈值、中位深度。
type Plane struct {
	N          Vec3
	D          float64
	Centroid   Vec3
	InlierRate float64
	RMS        float64
	Thr        float64
	MedZ       float64
}

// backprojectROI —— restore.py::backproject_roi：中心 ROI 内有效深度像素 → depth 相机系 3D（mm）。
//
// depth 为小端 u16（u16_le_mm）扁平字节；fx/fy 各向异性（eYs3D 深度 fy<<fx）。
func backprojectROI(depth []byte, dw, dh int, fx, fy, cx, cy float64, roi [4]float64) []Vec3 {
	rcx, rcy, rw, rh := roi[0], roi[1], roi[2], roi[3]
	u0 := int((rcx - rw/2) * float64(dw))
	u1 := int((rcx + rw/2) * float64(dw))
	v0 := int((rcy - rh/2) * float64(dh))
	v1 := int((rcy + rh/2) * float64(dh))
	if u0 < 0 {
		u0 = 0
	}
	if u1 > dw {
		u1 = dw
	}
	if v0 < 0 {
		v0 = 0
	}
	if v1 > dh {
		v1 = dh
	}
	pts := make([]Vec3, 0, (u1-u0)*(v1-v0))
	for v := v0; v < v1; v++ {
		row := v * dw
		for u := u0; u < u1; u++ {
			idx := (row + u) * 2
			if idx+1 >= len(depth) {
				continue
			}
			z := float64(binary.LittleEndian.Uint16(depth[idx : idx+2]))
			if z <= 0 {
				continue
			}
			x := (float64(u) - cx) / fx * z
			y := (float64(v) - cy) / fy * z
			pts = append(pts, Vec3{x, y, z})
		}
	}
	return pts
}

// ransacPlane —— restore.py::ransac_plane 的端口。
//
// RANSAC 主平面 n·P+d=0，自适应阈值 max(3.0, 0.8%×中位深度)，找最大内点集；
// 然后内点 LS 精修。Python 用 SVD 精修；Go 无 SVD → 改用原厂 z=ax+by+d 最小二乘
// （正规方程 3×3 用 gocv.Solve 解，等价且 SVD-free），法向 n=normalize(-a,-b,1)，朝相机。
//
// 种子固定 12345，与 Python np.random.RandomState(12345) 对齐采样语义（注：Go 与 numpy
// 的 RNG 序列不同，但 RANSAC 对随机序列不敏感，内点集由几何决定）。
func ransacPlane(pts []Vec3) (Plane, error) {
	const thr0 = 3.0
	const iters = 300
	n := len(pts)
	if n < 3 {
		return Plane{}, errors.New("点数不足，无法拟合平面")
	}
	medZ := medianZ(pts)
	thr := math.Max(thr0, 0.008*medZ)

	rng := rand.New(rand.NewSource(12345))
	bestIn := 0
	var bestN Vec3
	var bestD float64
	bestN = Vec3{0, 0, 1}
	for it := 0; it < iters; it++ {
		a := pts[rng.Intn(n)]
		b := pts[rng.Intn(n)]
		c := pts[rng.Intn(n)]
		nv := cross3(sub3(b, a), sub3(c, a))
		nn := norm3(nv)
		if nn < 1e-6 {
			continue
		}
		nv = scale3(nv, 1.0/nn)
		d := -dot3(nv, a)
		cnt := 0
		for _, p := range pts {
			if math.Abs(dot3(p, nv)+d) <= thr {
				cnt++
			}
		}
		if cnt > bestIn {
			bestIn = cnt
			bestN = nv
			bestD = d
		}
	}

	// 收内点
	inl := make([]Vec3, 0, bestIn)
	for _, p := range pts {
		if math.Abs(dot3(p, bestN)+bestD) <= thr {
			inl = append(inl, p)
		}
	}
	if len(inl) < 3 {
		inl = pts
	}

	// LS 精修：z = a*x + b*y + d（原厂正规方程，SVD-free）。
	pa, pb, pd, ok := fitZPlaneLS(inl)
	if !ok {
		return Plane{}, errors.New("平面 LS 精修失败（正规方程奇异）")
	}
	// 平面 a*x+b*y - z + d = 0 → 法向 (a, b, -1)；归一。
	nv := Vec3{pa, pb, -1}
	nn := norm3(nv)
	nv = scale3(nv, 1.0/nn)
	// d 对应归一后法向：原式 a*x+b*y-z+pd=0 各项 ÷nn
	dd := pd / nn

	// 质心（内点均值）
	centroid := mean3(inl)
	// 朝相机（相机在原点）：与 restore.py 一致用 n·centroid>0 翻转
	if dot3(nv, centroid) > 0 {
		nv = scale3(nv, -1)
		dd = -dd
	}
	// 用归一法向重算 d 以过质心一致（与 Python d=-n·centroid 对齐）
	dd = -dot3(nv, centroid)

	// rms（内点到平面距离）
	var ss float64
	for _, p := range inl {
		e := dot3(sub3(p, centroid), nv)
		ss += e * e
	}
	rms := math.Sqrt(ss / float64(len(inl)))

	return Plane{
		N:          nv,
		D:          dd,
		Centroid:   centroid,
		InlierRate: float64(bestIn) / float64(n),
		RMS:        rms,
		Thr:        thr,
		MedZ:       medZ,
	}, nil
}

// fitZPlaneLS —— 解 z=a*x+b*y+d 的最小二乘（正规方程 3×3，gocv.Solve LU 分解）。
//
// 正规方程 A^T A · [a b d]^T = A^T z，其中 A 行 = [x y 1]。
func fitZPlaneLS(pts []Vec3) (a, b, d float64, ok bool) {
	var sxx, sxy, sx, syy, sy, sn float64
	var sxz, syz, sz float64
	for _, p := range pts {
		x, y, z := p[0], p[1], p[2]
		sxx += x * x
		sxy += x * y
		sx += x
		syy += y * y
		sy += y
		sn += 1
		sxz += x * z
		syz += y * z
		sz += z
	}
	// 3×3 正规方程矩阵
	atA, err := gocv.NewMatFromBytes(3, 3, gocv.MatTypeCV64F, f64bytes([]float64{
		sxx, sxy, sx,
		sxy, syy, sy,
		sx, sy, sn,
	}))
	if err != nil {
		return 0, 0, 0, false
	}
	defer func() { _ = atA.Release() }()
	atZ, err := gocv.NewMatFromBytes(3, 1, gocv.MatTypeCV64F, f64bytes([]float64{sxz, syz, sz}))
	if err != nil {
		return 0, 0, 0, false
	}
	defer func() { _ = atZ.Release() }()

	sol := gocv.NewMat()
	defer func() { _ = sol.Release() }()
	if !gocv.Solve(atA, atZ, &sol, gocv.SolveDecompositionLu) {
		return 0, 0, 0, false
	}
	a = sol.GetDoubleAt(0, 0)
	b = sol.GetDoubleAt(1, 0)
	d = sol.GetDoubleAt(2, 0)
	return a, b, d, true
}

// tiltDeg —— restore_obb.py：tilt = acos(|nz|)·180/π。
func tiltDeg(p Plane) float64 {
	nz := math.Abs(p.N[2])
	if nz > 1.0 {
		nz = 1.0
	}
	return math.Acos(nz) * 180.0 / math.Pi
}

// planeBasis —— restore.py::plane_basis：图像 Y 朝下 → 真实「上」=-Y 投到平面；right=up×n。
func planeBasis(p Plane) (right, up Vec3) {
	n := p.N
	camUp := Vec3{0, -1, 0}
	up = sub3(camUp, scale3(n, dot3(camUp, n)))
	if norm3(up) < 1e-4 {
		camX := Vec3{1, 0, 0}
		up = sub3(camX, scale3(n, dot3(camX, n)))
	}
	up = scale3(up, 1.0/norm3(up))
	right = cross3(up, n)
	right = scale3(right, 1.0/norm3(right))
	return right, up
}

// ---- 小向量工具 ----

func sub3(a, b Vec3) Vec3   { return Vec3{a[0] - b[0], a[1] - b[1], a[2] - b[2]} }
func scale3(a Vec3, s float64) Vec3 { return Vec3{a[0] * s, a[1] * s, a[2] * s} }
func dot3(a, b Vec3) float64 { return a[0]*b[0] + a[1]*b[1] + a[2]*b[2] }
func norm3(a Vec3) float64   { return math.Sqrt(dot3(a, a)) }
func cross3(a, b Vec3) Vec3 {
	return Vec3{
		a[1]*b[2] - a[2]*b[1],
		a[2]*b[0] - a[0]*b[2],
		a[0]*b[1] - a[1]*b[0],
	}
}

func mean3(pts []Vec3) Vec3 {
	var s Vec3
	for _, p := range pts {
		s[0] += p[0]
		s[1] += p[1]
		s[2] += p[2]
	}
	inv := 1.0 / float64(len(pts))
	return Vec3{s[0] * inv, s[1] * inv, s[2] * inv}
}

func medianZ(pts []Vec3) float64 {
	zs := make([]float64, len(pts))
	for i, p := range pts {
		zs[i] = p[2]
	}
	sort.Float64s(zs)
	m := len(zs) / 2
	if len(zs)%2 == 1 {
		return zs[m]
	}
	return 0.5 * (zs[m-1] + zs[m])
}

// f64bytes —— []float64 → 小端字节（喂 NewMatFromBytes 的 CV64F）。
func f64bytes(v []float64) []byte {
	b := make([]byte, len(v)*8)
	for i, x := range v {
		binary.LittleEndian.PutUint64(b[i*8:], math.Float64bits(x))
	}
	return b
}
