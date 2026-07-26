package restore

import (
	"encoding/binary"
	"errors"
	"image"
	"image/color"
	"math"
	"strconv"

	"io.gomob/server/internal/cvengine/gocv"
)

// 输出参数 —— 固定 mm/px 度量网格（端口自 native/vin/ortho_rectify.cpp + tests/harness/vin_restore/ortho_metric.py）。
// 旧版「OBB 四角单应(钉角点) + 宽度归一到 1200」会把真实几何掩盖成"看着正"、且尺度随取景变；改为在平面上铺
// **固定物理尺寸网格** → 输出严格 metric、视角无关(同 VIN 不同角度同尺寸)、可叠刻度尺。与原厂 VINCreator 还原一致。
const (
	PxMM      = 0.20 // 度量诊断图每像素物理尺寸(mm)
	marginXMM = 10.0 // 左右物理边距(mm)
	marginYMM = 5.0  // 上下物理边距(mm)
	MaxOutW   = 2400 // 输出宽上限(钳，防异常 OBB)
	MaxOutH   = 600  // 输出高上限(钳)

	// 逐字符检测探针保持 1200×260：该尺寸已经由历史真机数据和 VINCHAR 模型闭环。
	CanonicalProbeW        = 1200
	CanonicalProbeH        = 260
	CanonicalProbeContentW = 1080

	// VINCreator RecMode=2 先在 5000×678 工作画布上按 25px/mm 生成物理正射图，再以原像素尺度
	// 中心裁成用户文件 4425×600。受控原厂 native 实验证明：修改 Print 尺寸只改变裁切画布，
	// 字符像素节距不变；因此这里禁止把内容按画布宽度二次缩放。
	VinCreatorPixelsPerMM = 25.0
	VinCreatorWorkW       = 5000
	VinCreatorWorkH       = 678
	CanonicalOutW         = 4425
	CanonicalOutH         = 600
	vinCreatorCropX       = 288
	vinCreatorCropY       = 39

	canonicalMinWidthMM = 40.0
	canonicalMaxWidthMM = 260.0
)

// frame —— restore_obb.py::restore_obb 的产物：定位好的平面 + OBB 四角(平面内 a,b)。
type frame struct {
	plane       Plane
	right       Vec3
	up          Vec3
	color       gocv.Mat // 彩色 BGR
	ab          [4][2]float64
	tilt        float64
	width       float64
	height      float64
	theta       float64
	calibration *VinCalibration
}

// add3 / matVec3 —— 3D 向量加 / 3×3 矩阵乘向量（外参 R|t 变换用）。
func add3(a, b Vec3) Vec3 { return Vec3{a[0] + b[0], a[1] + b[1], a[2] + b[2]} }
func matVec3(m [3][3]float64, v Vec3) Vec3 {
	return Vec3{
		m[0][0]*v[0] + m[0][1]*v[1] + m[0][2]*v[2],
		m[1][0]*v[0] + m[1][1]*v[1] + m[1][2]*v[2],
		m[2][0]*v[0] + m[2][1]*v[1] + m[2][2]*v[2],
	}
}

// rayPlaneInplane 把彩色像素经原厂私有模型逆解成世界射线，再与深度承印平面相交。
func rayPlaneInplane(column, row float64, calibration *VinCalibration, p Plane, right, up Vec3) (ab [2]float64, point Vec3, err error) {
	origin, direction, err := calibration.color.rayFromColorPixel(column, row)
	if err != nil {
		return [2]float64{}, Vec3{}, err
	}
	denominator := dot3(p.N, direction)
	if math.Abs(denominator) <= 1e-12 {
		return [2]float64{}, Vec3{}, errors.New("彩色像素射线与 VIN 平面平行")
	}
	distance := -(dot3(p.N, origin) + p.D) / denominator
	point = add3(origin, scale3(direction, distance))
	rel := sub3(point, p.Centroid)
	ab = [2]float64{dot3(rel, right), dot3(rel, up)}
	return ab, point, nil
}

// buildFrame —— restore_obb.py::restore_obb：把深度平面 + OBB 检测合成一个 frame。
//
// dets：彩色图上的 OBB 检测（已 NMS）；取 score 最大者。返回 nil + tilt 信息表示 tilt>70 废弃。
func buildFrame(
	depth []byte, dw, dh int,
	calibration *VinCalibration,
	colorBGR gocv.Mat,
	dets []Detection,
) (*frame, float64, error) {
	if len(dets) == 0 {
		return nil, 0, ErrVinNotDetected
	}
	// 取 score 最大的 OBB（VIN 文字区）—— 先拿它，把平面拟合限到承印面。
	best := dets[0]
	for _, d := range dets[1:] {
		if d.Score > best.Score {
			best = d
		}
	}

	if calibration == nil || colorBGR.Cols() != calibration.key.ColorWidth || colorBGR.Rows() != calibration.key.ColorHeight {
		return nil, 0, errors.New("彩色图尺寸与原厂标定 profile 不一致")
	}
	// 深度视差先按原厂基线/焦距恢复毫米坐标，再经原厂 CCameraModel 投到 HLSD8 OBB。
	pts, err := backprojectColorOBB(
		depth, dw, dh, calibration, best.Corners, 0.96, 1.35,
	)
	if err != nil {
		return nil, 0, err
	}
	if len(pts) < 100 {
		return nil, 0, errors.New("彩色 OBB 投影对应的深度有效点过少")
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

	var ab [4][2]float64
	for i, c := range best.Corners {
		ab[i], _, err = rayPlaneInplane(c[0], c[1], calibration, plane, right, up)
		if err != nil {
			return nil, 0, err
		}
	}
	tl, tr, br, bl := ab[0], ab[1], ab[2], ab[3]
	widthMM := (dist2(tr, tl) + dist2(br, bl)) * 0.5
	heightMM := (dist2(bl, tl) + dist2(br, tr)) * 0.5
	dx := ((tr[0] - tl[0]) + (br[0] - bl[0]))
	dy := ((tr[1] - tl[1]) + (br[1] - bl[1]))
	theta := math.Atan2(dy, dx) * 180.0 / math.Pi

	return &frame{
		plane: plane, right: right, up: up, color: colorBGR, ab: ab,
		tilt: tilt, width: widthMM, height: heightMM, theta: theta,
		calibration: calibration,
	}, tilt, nil
}

type renderAxes struct {
	cx, cy          float64
	xdx, xdy        float64
	ydx, ydy        float64
	contentWidthMM  float64
	contentHeightMM float64
}

func frameRenderAxes(f *frame) renderAxes {
	tl, tr, br, bl := f.ab[0], f.ab[1], f.ab[2], f.ab[3]
	cx := (tl[0] + tr[0] + br[0] + bl[0]) * 0.25
	cy := (tl[1] + tr[1] + br[1] + bl[1]) * 0.25
	xdx := (tr[0] - tl[0]) + (br[0] - bl[0])
	xdy := (tr[1] - tl[1]) + (br[1] - bl[1])
	xn := math.Hypot(xdx, xdy) + 1e-9
	xdx, xdy = xdx/xn, xdy/xn
	ydx, ydy := -xdy, xdx
	if (bl[0]-tl[0])*ydx+(bl[1]-tl[1])*ydy < 0 {
		ydx, ydy = -ydx, -ydy
	}
	return renderAxes{
		cx:              cx,
		cy:              cy,
		xdx:             xdx,
		xdy:             xdy,
		ydx:             ydx,
		ydy:             ydy,
		contentWidthMM:  (dist2(tr, tl) + dist2(br, bl)) * 0.5,
		contentHeightMM: (dist2(bl, tl) + dist2(br, tr)) * 0.5,
	}
}

// renderCanonicalProbe 生成逐字符检测用的粗规范图。
// 它只由原图整行 OBB 提供近似视野；最终坐标由 17 字符格架重新确定，不把该图直接返回给用户。
func renderCanonicalProbe(f *frame) (gocv.Mat, renderAxes, float64, error) {
	axes := frameRenderAxes(f)
	if axes.contentWidthMM < canonicalMinWidthMM || axes.contentWidthMM > canonicalMaxWidthMM {
		return gocv.Mat{}, renderAxes{}, 0, errors.New("VIN OBB 物理宽度异常，拒绝规范化")
	}
	mmPerPixel := axes.contentWidthMM / float64(CanonicalProbeContentW)
	probe, _, _, err := renderGrid(
		f, axes, CanonicalProbeW, CanonicalProbeH, mmPerPixel,
		color.RGBA{},
	)
	return probe, axes, mmPerPixel, err
}

// renderTextCanonical 用 17 字符等间距约束细化一条 3D 基线，再从原始彩色图一次采样到固定画布。
// 细化只求“同一平面上的等步长直线”，中心深度仍由深度平面给出；输出轴保持正交、同一 mm/px，
// 不做自由仿射、非等比拉伸、ECC 或逐字符分段变形。
func renderTextCanonical(
	f *frame,
	probeAxes renderAxes,
	probeMMPerPixel float64,
	anchor textAnchor,
) (gocv.Mat, int, int, error) {
	grid, err := refineTextSpatialGrid(f, probeAxes, probeMMPerPixel, anchor)
	if err != nil {
		return gocv.Mat{}, 0, 0, err
	}
	work, _, _, err := renderSpatialGrid(
		f, grid.center, grid.axisX, grid.axisY,
		VinCreatorWorkW, VinCreatorWorkH, 1.0/VinCreatorPixelsPerMM,
		color.RGBA{R: 128, G: 128, B: 128},
	)
	if err != nil {
		return gocv.Mat{}, 0, 0, err
	}
	defer func() { _ = work.Release() }()
	return cropVinCreatorOutput(work)
}

// cropVinCreatorOutput 复刻原厂 FlipAndCropImage 的生产裁切：5000×678 → 4425×600。
// 两个画布奇偶性不同，原厂横向取 ceil((5000-4425)/2)=288；纵向严格居中 39。
func cropVinCreatorOutput(work gocv.Mat) (gocv.Mat, int, int, error) {
	if work.Cols() != VinCreatorWorkW || work.Rows() != VinCreatorWorkH {
		return gocv.Mat{}, 0, 0, errors.New("VINCreator 工作画布尺寸非法")
	}
	roi := work.Region(image.Rect(
		vinCreatorCropX,
		vinCreatorCropY,
		vinCreatorCropX+CanonicalOutW,
		vinCreatorCropY+CanonicalOutH,
	))
	defer func() { _ = roi.Release() }()
	return roi.Clone(), CanonicalOutW, CanonicalOutH, nil
}

type textSpatialGrid struct {
	center        Vec3
	axisX, axisY  Vec3
	pitchMM       float64
	normalizedRMS float64
}

// refineTextSpatialGrid 把探针中的 17 个字符中心还原成彩色相机射线，并求一条穿过深度平面中心的
// 等步长 3D 直线。它只补偿深度平面/旧外参留下的一维透视残差，解的自由度远低于图像配准。
func refineTextSpatialGrid(
	f *frame,
	probeAxes renderAxes,
	probeMMPerPixel float64,
	anchor textAnchor,
) (textSpatialGrid, error) {
	if len(anchor.Selected) != vinCharacterCount {
		return textSpatialGrid{}, ErrTextAnchorUnreliable
	}
	center := probePoint3D(
		f, probeAxes, probeMMPerPixel,
		anchor.CenterX, anchor.CenterY,
	)
	probeRight := normalizeVec3(add3(
		scale3(f.right, probeAxes.xdx),
		scale3(f.up, probeAxes.xdy),
	))
	probeDown := normalizeVec3(add3(
		scale3(f.right, probeAxes.ydx),
		scale3(f.up, probeAxes.ydy),
	))
	if norm3(probeRight) < 1e-9 || norm3(probeDown) < 1e-9 {
		return textSpatialGrid{}, errors.New("VIN 探针空间轴退化")
	}

	rays := make([]Vec3, vinCharacterCount)
	weights := make([]float64, vinCharacterCount)
	for i, observation := range anchor.Selected {
		point := probePoint3D(
			f, probeAxes, probeMMPerPixel,
			observation.X, observation.Y,
		)
		rays[i] = normalizeVec3(point)
		weights[i] = math.Max(observation.Score, charConfidenceMin)
	}

	// 文字实际位于深度拟合出的承印平面内。求解只能在该平面的两个正交基上进行，禁止让 RGB
	// 射线把基线“掀离”深度平面，否则会用一张图的内容误差伪造新的 3D 倾角。
	basis := [2]Vec3{probeRight, probeDown}
	var step Vec3
	for iteration := 0; iteration < 5; iteration++ {
		var normal [2][2]float64
		var rhs [2]float64
		for i, ray := range rays {
			k := float64(i - vinCharacterCount/2)
			if k == 0 {
				continue
			}
			weight := weights[i]
			projectedCenter := sub3(center, scale3(ray, dot3(ray, center)))
			for row := 0; row < 2; row++ {
				rhs[row] -= weight * k * dot3(basis[row], projectedCenter)
				for col := 0; col < 2; col++ {
					projectedBasis := sub3(
						basis[col],
						scale3(ray, dot3(ray, basis[col])),
					)
					normal[row][col] += weight * k * k * dot3(basis[row], projectedBasis)
				}
			}
		}
		coefficients, ok := solve2x2(normal, rhs)
		if !ok {
			return textSpatialGrid{}, errors.New("VIN 文字平面内基线求解退化")
		}
		step = add3(
			scale3(probeRight, coefficients[0]),
			scale3(probeDown, coefficients[1]),
		)
		if norm3(step) < 1e-6 {
			return textSpatialGrid{}, errors.New("VIN 文字平面内基线求解退化")
		}
		pitch := norm3(step)
		for i, ray := range rays {
			k := float64(i - vinCharacterCount/2)
			point := add3(center, scale3(step, k))
			residual := norm3(sub3(point, scale3(ray, dot3(ray, point)))) / pitch
			robust := 1.0
			if residual > 0.12 {
				robust = 0.12 / residual
			}
			weights[i] = math.Max(anchor.Selected[i].Score, charConfidenceMin) * robust
		}
	}

	if dot3(step, probeRight) < 0 {
		step = scale3(step, -1)
	}
	pitchMM := norm3(step)
	probePitchMM := anchor.PitchPx * probeMMPerPixel
	if !isFinite(pitchMM) || pitchMM <= 0 || math.Abs(pitchMM/probePitchMM-1.0) > anchorScaleDeltaMax {
		return textSpatialGrid{}, ErrTextAnchorUnreliable
	}

	axisX := scale3(step, 1.0/pitchMM)
	axisY := sub3(probeDown, scale3(axisX, dot3(probeDown, axisX)))
	axisY = normalizeVec3(axisY)
	if norm3(axisY) < 1e-9 {
		return textSpatialGrid{}, errors.New("VIN 文字 3D 垂直轴退化")
	}
	if dot3(axisY, probeDown) < 0 {
		axisY = scale3(axisY, -1)
	}

	var residual2 float64
	for i, ray := range rays {
		k := float64(i - vinCharacterCount/2)
		point := add3(center, scale3(step, k))
		residual := norm3(sub3(point, scale3(ray, dot3(ray, point)))) / pitchMM
		residual2 += residual * residual
	}
	normalizedRMS := math.Sqrt(residual2 / float64(vinCharacterCount))
	if normalizedRMS > anchorNormalizedRMSMax {
		return textSpatialGrid{}, ErrTextAnchorUnreliable
	}
	return textSpatialGrid{
		center: center, axisX: axisX, axisY: axisY,
		pitchMM: pitchMM, normalizedRMS: normalizedRMS,
	}, nil
}

func probePoint3D(
	f *frame,
	axes renderAxes,
	mmPerPixel, x, y float64,
) Vec3 {
	dx := (x - float64(CanonicalProbeW)*0.5) * mmPerPixel
	dy := (y - float64(CanonicalProbeH)*0.5) * mmPerPixel
	a := axes.cx + dx*axes.xdx + dy*axes.ydx
	b := axes.cy + dx*axes.xdy + dy*axes.ydy
	return add3(
		f.plane.Centroid,
		add3(scale3(f.right, a), scale3(f.up, b)),
	)
}

func normalizeVec3(value Vec3) Vec3 {
	norm := norm3(value)
	if norm < 1e-12 {
		return Vec3{}
	}
	return scale3(value, 1.0/norm)
}

func solve2x2(matrix [2][2]float64, rhs [2]float64) ([2]float64, bool) {
	scale := math.Max(
		math.Max(math.Abs(matrix[0][0]), math.Abs(matrix[0][1])),
		math.Max(math.Abs(matrix[1][0]), math.Abs(matrix[1][1])),
	)
	determinant := matrix[0][0]*matrix[1][1] - matrix[0][1]*matrix[1][0]
	if scale <= 0 || math.Abs(determinant) <= 1e-12*scale*scale {
		return [2]float64{}, false
	}
	return [2]float64{
		(rhs[0]*matrix[1][1] - matrix[0][1]*rhs[1]) / determinant,
		(matrix[0][0]*rhs[1] - rhs[0]*matrix[1][0]) / determinant,
	}, true
}

// renderMetric 生成固定 mm/px 的度量诊断图；不作为用户可见/OCR 主结果。
// 它保留真实物理尺度，便于用历史原始采集复算 WidthMM/HeightMM 和刻度尺。
//
// 平面上以 OBB 中心为原点、OBB 长轴为 x 轴、固定物理尺寸(PxMM)铺刚性网格；逐输出像素 → 平面 3D 点 Q →
// 彩色投影采样。不钉角点 → 如实反映真实几何、严格 metric（同 VIN 不同角度同尺寸 = 视角无关）。
// 返回彩色 BGR(未叠刻度) + 实际输出宽高（无效像素=黑）。
func renderMetric(f *frame) (gocv.Mat, int, int, error) {
	axes := frameRenderAxes(f)
	wMM := axes.contentWidthMM + 2*marginXMM
	hMM := axes.contentHeightMM + 2*marginYMM
	outW := clampInt(int(math.Round(wMM/PxMM)), 8, MaxOutW)
	outH := clampInt(int(math.Round(hMM/PxMM)), 8, MaxOutH)
	return renderGrid(f, axes, outW, outH, PxMM, color.RGBA{})
}

func renderGrid(
	f *frame,
	axes renderAxes,
	outW, outH int,
	mmPerPixel float64,
	border color.RGBA,
) (gocv.Mat, int, int, error) {
	if outW <= 0 || outH <= 0 || !isFinite(mmPerPixel) || mmPerPixel <= 0 {
		return gocv.Mat{}, 0, 0, errors.New("正射画布参数无效")
	}
	center := add3(
		f.plane.Centroid,
		add3(scale3(f.right, axes.cx), scale3(f.up, axes.cy)),
	)
	axisX := normalizeVec3(add3(scale3(f.right, axes.xdx), scale3(f.up, axes.xdy)))
	axisY := normalizeVec3(add3(scale3(f.right, axes.ydx), scale3(f.up, axes.ydy)))
	return renderSpatialGrid(f, center, axisX, axisY, outW, outH, mmPerPixel, border)
}

func renderSpatialGrid(
	f *frame,
	center, axisX, axisY Vec3,
	outW, outH int,
	mmPerPixel float64,
	border color.RGBA,
) (gocv.Mat, int, int, error) {
	if outW <= 0 || outH <= 0 || !isFinite(mmPerPixel) || mmPerPixel <= 0 ||
		norm3(axisX) < 1e-9 || norm3(axisY) < 1e-9 {
		return gocv.Mat{}, 0, 0, errors.New("正射空间网格参数无效")
	}
	halfW := float64(outW) / 2.0
	halfH := float64(outH) / 2.0

	mapU := make([]byte, outW*outH*4)
	mapV := make([]byte, outW*outH*4)
	for j := 0; j < outH; j++ {
		dy := (float64(j) + 0.5 - halfH) * mmPerPixel
		for i := 0; i < outW; i++ {
			dx := (float64(i) + 0.5 - halfW) * mmPerPixel
			qx := center[0] + dx*axisX[0] + dy*axisY[0]
			qy := center[1] + dx*axisX[1] + dy*axisY[1]
			qz := center[2] + dx*axisX[2] + dy*axisY[2]
			var u, v float32 = -1, -1 // 无效 → 越界, Remap BORDER_CONSTANT 填灰
			column, row, ok := f.calibration.color.projectWorldToColor(Vec3{qx, qy, qz})
			if ok {
				u = float32(column)
				v = float32(row)
			}
			off := (j*outW + i) * 4
			binary.LittleEndian.PutUint32(mapU[off:], math.Float32bits(u))
			binary.LittleEndian.PutUint32(mapV[off:], math.Float32bits(v))
		}
	}

	m1, err := gocv.NewMatFromBytes(outH, outW, gocv.MatTypeCV32F, mapU)
	if err != nil {
		return gocv.Mat{}, 0, 0, err
	}
	defer func() { _ = m1.Release() }()
	m2, err := gocv.NewMatFromBytes(outH, outW, gocv.MatTypeCV32F, mapV)
	if err != nil {
		return gocv.Mat{}, 0, 0, err
	}
	defer func() { _ = m2.Release() }()

	out := gocv.NewMat()
	gocv.Remap(f.color, &out, &m1, &m2, gocv.InterpolationLinear,
		gocv.BorderConstant, border)
	return out, outW, outH, nil
}

func isFinite(value float64) bool {
	return !math.IsNaN(value) && !math.IsInf(value, 0)
}

// drawRuler —— 沿正射图**左边 + 底边**直接画 metric 刻度尺，精到**毫米级**（2026-06-24 用户定）。
// 1mm 次刻度（短）/ 5mm 中刻度 / 10mm 主刻度（长 + 标数）。原点在左下角（底尺向右增、左尺向上增）。
// 刻度用**描边线**（先黑粗后白细）画在底/左空白边距上（render 留 marginXMM/marginYMM 空板区）：任意底色
// （高光板面/阴影）可读，**不铺暗带、不盖 VIN 字符**——修掉旧版暗带遮内容 + 数字撞刻度线的毛病。
// 固定 PxMM → 刻度物理准（依赖原厂视差焦距与基线）。返回与输入同尺寸的新 Mat（不扩边；调用方 Release）。
func drawRuler(ortho gocv.Mat) gocv.Mat {
	h, w := ortho.Rows(), ortho.Cols()
	canvas := ortho.Clone()
	dark := color.RGBA{R: 12, G: 12, B: 12, A: 0}
	light := color.RGBA{R: 250, G: 250, B: 250, A: 0}
	tick := func(p0, p1 image.Point) { // 描边刻度线：黑(粗)打底 + 白(细)芯 → 任意底色可读
		gocv.Line(&canvas, p0, p1, dark, 2)
		gocv.Line(&canvas, p0, p1, light, 1)
	}
	label := func(s string, at image.Point) {
		gocv.PutText(&canvas, s, at, gocv.FontHersheySimplex, 0.3, dark, 3)
		gocv.PutText(&canvas, s, at, gocv.FontHersheySimplex, 0.3, light, 1)
	}

	by := h - 1
	// 数字只标首尾两个（用户 2026-06-24）：刻度线全留，数字仅最左(0)与最右/最顶的主刻度。
	lastMajorX := int(float64(w-1)*PxMM) / 10 * 10
	lastMajorY := int(float64(by)*PxMM) / 10 * 10
	// 底边水平尺：原点(0)在左下，向右增
	for mm := 0; ; mm++ {
		x := int(math.Round(float64(mm) / PxMM))
		if x >= w {
			break
		}
		switch {
		case mm%10 == 0: // 主刻度（首尾才标数）
			tick(image.Pt(x, by), image.Pt(x, by-9))
			if mm == 0 || mm == lastMajorX {
				s := strconv.Itoa(mm)
				tx := x + 2
				if tx > w-len(s)*7-1 {
					tx = w - len(s)*7 - 1
				}
				label(s, image.Pt(tx, by-11))
			}
		case mm%5 == 0:
			tick(image.Pt(x, by), image.Pt(x, by-5))
		default:
			tick(image.Pt(x, by), image.Pt(x, by-3))
		}
	}
	// 左边竖直尺：原点(0)在左下，向上增（0 数字由底尺给，左尺仅标顶端那个=尾）
	for mm := 0; ; mm++ {
		y := by - int(math.Round(float64(mm)/PxMM))
		if y < 0 {
			break
		}
		switch {
		case mm%10 == 0:
			tick(image.Pt(0, y), image.Pt(9, y))
			if mm == lastMajorY && mm > 0 {
				ly := y + 4
				if ly < 10 {
					ly = 10
				}
				label(strconv.Itoa(mm), image.Pt(11, ly))
			}
		case mm%5 == 0:
			tick(image.Pt(0, y), image.Pt(5, y))
		default:
			tick(image.Pt(0, y), image.Pt(3, y))
		}
	}
	return canvas
}

func dist2(a, b [2]float64) float64 {
	return math.Hypot(a[0]-b[0], a[1]-b[1])
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
