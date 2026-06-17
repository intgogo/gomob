package laser

import "math"

// overlay.go = 把分割/测量结果(车体框/货箱框/轴线)按 **融合点云世界系** 导出为 3D 几何，
// 供网页在点云上叠加可视分割。测量在变换后的帧 M(车位框系/地面系/设备系)里算，这里求 M→世界
// 逆变换把几何映回融合云坐标，网页直接投影画线即可（不需懂坐标系）。
//
// 三种测量帧的 M→世界都能写成 world = O + m.x·A0 + m.y·A1 + m.z·A2（仿射）：
//   - 设备 ROI：cropROI 只过滤不变换 → 恒等(O=0, A=单位)。
//   - 地面相对：toGroundFrame 的逆 → O=-d·up, (A0,A1,A2)=(right,fwd,up)。
//   - 持久车位框：toBoxFrame 的逆 → O=框心(含 carType 偏移), (A0,A1,A2)=(right,fwd,up)。
// OBB 对齐帧(l=车长,w=车宽,h=上)的盒角/轴线在 M 里是 m=l·Ldir+w·Wdir+h·ez，再经上式到世界。

// VehicleOverlay = 世界系叠加几何（mm，融合云坐标）。盒为 8 角点，轴线为两端点。空=Valid false。
type VehicleOverlay struct {
	Valid       bool            `json:"valid"`
	VehicleBox  [][3]float32    `json:"vehicle_box,omitempty"` // 车体 OBB 8 角点
	HasCargoBox bool            `json:"has_cargo_box"`
	CargoBox    [][3]float32    `json:"cargo_box,omitempty"`  // 货箱 OBB 8 角点
	AxleLines   [][2][3]float32 `json:"axle_lines,omitempty"` // 各轴横向标线两端点
}

// BuildVehicleOverlay 在融合云世界系导出车体框/货箱框/轴线。world=测量用的同一融合云([x,y,z,...] mm)。
func BuildVehicleOverlay(world []float32, p MeasureParams, ap AxleParams, cbp CargoBoxParams) VehicleOverlay {
	roiPts, cleaned, d := measureBody(world, p)
	if !d.Valid || len(cleaned) < 50 {
		return VehicleOverlay{}
	}
	// 车体框用 cleaned(主簇→ROR)，与测量 OBB 一致、剔端噪/离群；轴心/货箱用 roiPts(自带 trim)。
	ax := DetectAxles(roiPts, d.OBBAngleDeg, ap)
	cb := DetectCargoBox(roiPts, d.OBBAngleDeg, cbp)

	O, A0, A1, A2 := invTransform(p)
	ldir, wdir := lengthWidthDirs(cleaned, d.OBBAngleDeg)
	// 世界系 OBB 轴：Lw=ldir.x·A0+ldir.y·A1（ldir.z=0），Ww 同理，Hw=A2。
	lw := [3]float32{ldir[0]*A0[0] + ldir[1]*A1[0], ldir[0]*A0[1] + ldir[1]*A1[1], ldir[0]*A0[2] + ldir[1]*A1[2]}
	ww := [3]float32{wdir[0]*A0[0] + wdir[1]*A1[0], wdir[0]*A0[1] + wdir[1]*A1[1], wdir[0]*A0[2] + wdir[1]*A1[2]}
	hw := A2
	worldOf := func(l, w, h float32) [3]float32 {
		return [3]float32{
			O[0] + l*lw[0] + w*ww[0] + h*hw[0],
			O[1] + l*lw[1] + w*ww[1] + h*hw[1],
			O[2] + l*lw[2] + w*ww[2] + h*hw[2],
		}
	}

	// 车体框：干净车体在 l/w/h 投影系的范围（与测量 OBB 一致）。
	var lMin, lMax, wMin, wMax, hMin, hMax float32 = math.MaxFloat32, -math.MaxFloat32, math.MaxFloat32, -math.MaxFloat32, math.MaxFloat32, -math.MaxFloat32
	for _, q := range cleaned {
		l := q.x*ldir[0] + q.y*ldir[1]
		w := q.x*wdir[0] + q.y*wdir[1]
		lMin, lMax = minf(lMin, l), maxf(lMax, l)
		wMin, wMax = minf(wMin, w), maxf(wMax, w)
		hMin, hMax = minf(hMin, q.z), maxf(hMax, q.z)
	}
	ov := VehicleOverlay{Valid: true}
	ov.VehicleBox = box8(worldOf, lMin, lMax, wMin, wMax, hMin, hMax)

	if cb.Valid && cb.HasBox {
		ov.HasCargoBox = true
		ov.CargoBox = box8(worldOf, cb.BoxLMinMM, cb.BoxLMaxMM,
			cb.BoxWCenterMM-cb.BoxWHalfMM, cb.BoxWCenterMM+cb.BoxWHalfMM, cb.BedZMM, cb.TopZMM)
	}
	if ax.Valid {
		for _, c := range ax.AxleCentersRawMM { // 各轴在车底横跨一条标线
			ov.AxleLines = append(ov.AxleLines, [2][3]float32{
				worldOf(c, wMin, hMin+20), worldOf(c, wMax, hMin+20),
			})
		}
	}
	return ov
}

// overlayPtr 返回有效叠加的指针，无效返回 nil（事件 omitempty 略去）。
func overlayPtr(o VehicleOverlay) *VehicleOverlay {
	if !o.Valid {
		return nil
	}
	return &o
}

// invTransform 返回测量帧 M→世界的仿射 (O, A0, A1, A2)：world = O + m.x·A0 + m.y·A1 + m.z·A2。
func invTransform(p MeasureParams) (O, A0, A1, A2 [3]float32) {
	switch {
	case p.UseCropBox:
		b := p.Box
		b.Center = [3]float32{b.Center[0] + p.CarOffset[0], b.Center[1] + p.CarOffset[1], b.Center[2] + p.CarOffset[2]}
		right, fwd, up := b.Basis()
		return b.Center, right, fwd, up
	case p.UseGround:
		right, fwd, up := groundBasis(p.GroundN)
		O = [3]float32{-p.GroundD * up[0], -p.GroundD * up[1], -p.GroundD * up[2]}
		return O, right, fwd, up
	default: // UseROI / 无裁剪：M=世界，恒等
		return [3]float32{0, 0, 0}, [3]float32{1, 0, 0}, [3]float32{0, 1, 0}, [3]float32{0, 0, 1}
	}
}

// lengthWidthDirs 返回 OBB 车长/车宽方向（M 系 xy 单位向量），与 projectLengthAxis 取长边一致。
func lengthWidthDirs(body []pt, angleDeg float32) (ldir, wdir [3]float32) {
	const d2r = math.Pi / 180.0
	c, s := float32(math.Cos(float64(angleDeg)*d2r)), float32(math.Sin(float64(angleDeg)*d2r))
	var umin, umax, vmin, vmax float32 = math.MaxFloat32, -math.MaxFloat32, math.MaxFloat32, -math.MaxFloat32
	for _, q := range body {
		u := q.x*c + q.y*s
		v := -q.x*s + q.y*c
		umin, umax = minf(umin, u), maxf(umax, u)
		vmin, vmax = minf(vmin, v), maxf(vmax, v)
	}
	if umax-umin >= vmax-vmin {
		return [3]float32{c, s, 0}, [3]float32{-s, c, 0}
	}
	return [3]float32{-s, c, 0}, [3]float32{c, s, 0}
}

// box8 返回 OBB(l/w/h 范围)的 8 个世界系角点（worldOf 把 l/w/h 投影坐标映回世界）。
func box8(worldOf func(l, w, h float32) [3]float32, lMin, lMax, wMin, wMax, hMin, hMax float32) [][3]float32 {
	return [][3]float32{
		worldOf(lMin, wMin, hMin), worldOf(lMax, wMin, hMin), worldOf(lMax, wMax, hMin), worldOf(lMin, wMax, hMin),
		worldOf(lMin, wMin, hMax), worldOf(lMax, wMin, hMax), worldOf(lMax, wMax, hMax), worldOf(lMin, wMax, hMax),
	}
}

func minf(a, b float32) float32 {
	if a < b {
		return a
	}
	return b
}

func maxf(a, b float32) float32 {
	if a > b {
		return a
	}
	return b
}
