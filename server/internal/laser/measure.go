package laser

import "math"

// measure.go = 车辆外廓测量（Go 版，与构建标签无关）。融合后对点云算 车长/宽/高 + GB7258 合规。
//
// 这是 native/measurement(C++ Eigen) 已 host 验证内核的 Go 镜像（同 pcd.go 镜像 C++ io_pcd 的范式）：
// 测量是 PCL-free 的纯几何，无需 cgo —— 放 Go 让 laserworker 所有构建都能测量、`go test` 即可对
// Data/100742 真值验证（≤2.5%）。C++ 内核仍是 native/Android 端的参考实现，两侧各自对 Result.ini 验。
// 逆向真理源 docs/architecture/16 §3⑥/§6.3；阈值 docs/16 §8 [LIMT]。单位 mm。
//
// 管线：ROI 体裁剪 → 最大连通簇(体素 26-连通 BFS，剥脱离噪声) → 半径离群剔除 → 俯视 minAreaRect
// OBB(长=车长/短=车宽) + Z 跨度(车高)。

// MeasureParams 测量参数。默认取自原厂 setting.ini [Param] + 离线验证（docs/16 §6.3）。
type MeasureParams struct {
	ROIMin, ROIMax  [3]float32
	UseROI          bool
	ClusterLeaf     float32 // 主簇体素边长 mm
	RORRadius       float32 // 半径离群半径 mm
	RORMinNeighbors int
	UseROR          bool
	OBBStepDeg      float32 // OBB 角度扫描步长

	// 地面相对测量（坐标系无关，M9.10）：UseGround 时先把云变换到地面正交基
	// (x=右, y=前, z=离地高)，用 [HeightMin,HeightMax] 高度 band 替代设备系 ROI，
	// 俯视 OBB(长/宽) + 离地最高(车高) 全相对真实地面 → 换任何底座坐标系都成立。
	// GroundN 须为朝"上"(指向车体侧)的单位法向；平面 n·p + GroundD = 0。
	// 与原厂设备系 ROI 路径(UseROI)互斥：JCHY 真值仍走设备系路径不受影响。
	UseGround            bool
	GroundN              [3]float32
	GroundD              float32
	HeightMin, HeightMax float32

	// 持久 3D 裁剪框测量（M9.11，优先级最高）：UseCropBox 时把云变换到框局部系并只留框内点，
	// 再走 主簇→ROR→俯视OBB(长/宽)→上向跨度(高)。框由用户一次圈定(车位)、世界系定向、跨扫描稳定，
	// 不依赖自动地面 RANSAC，且按深度隔离掉同立体角更远的背景(办公室)——这是设备扫描角做不到的。
	UseCropBox bool
	Box        CropBox

	// 车型 carType 偏移（mm，docs/16 §4.3；逆向 JCHY）：选定车型后平移测量区域中心补偿车体位置差。
	// 设备世界系(unit_a≈设备系)下叠加：设备 ROI 路径平移 ROIMin/Max、裁剪框路径平移 Box.Center。
	// 地面相对路径(UseGround)因偏移在设备系、与地面正交基不对齐，暂不应用（待逐型在地面系标定）。零=不偏移。
	CarOffset [3]float32
}

// DefaultMeasureParams 与 C++ measure_types.h MeasureParams 默认一致（原厂设备系 ROI 路径）。
func DefaultMeasureParams() MeasureParams {
	return MeasureParams{
		ROIMin: [3]float32{270, 0, 10}, ROIMax: [3]float32{1000, 2200, 800}, UseROI: true,
		ClusterLeaf: 10, RORRadius: 15, RORMinNeighbors: 12, UseROR: true, OBBStepDeg: 0.25,
	}
}

// GroundMeasureParams 地面相对测量参数：禁用设备系 ROI、启用地面 band。给真机融合云用
// （设备坐标原点随底座而变，硬编码 ROI 不通用；改用已检测地面做参考系）。hMin 略高于地面
// 噪声(去地板)、hMax 覆盖 GB7258 最高车型 + 余量。
func GroundMeasureParams(n [3]float32, d, hMin, hMax float32) MeasureParams {
	p := DefaultMeasureParams()
	p.UseROI = false
	p.UseGround = true
	p.GroundN = n
	p.GroundD = d
	p.HeightMin = hMin
	p.HeightMax = hMax
	return p
}

// CropBoxMeasureParams 持久框测量参数：禁用设备系 ROI / 地面 band，启用框裁剪。
// 用户一次圈定的车位框直接定参考系 + 深度边界，是真机的首选测量路径。
func CropBoxMeasureParams(b CropBox) MeasureParams {
	p := DefaultMeasureParams()
	p.UseROI = false
	p.UseCropBox = true
	p.Box = b
	return p
}

// Dimensions 单次测量结果（mm）。
type Dimensions struct {
	LengthMM    float32 `json:"length_mm"`
	WidthMM     float32 `json:"width_mm"`
	HeightMM    float32 `json:"height_mm"`
	OBBAngleDeg float32 `json:"obb_angle_deg"`
	RawPts      int     `json:"raw_pts"`
	ROIPts      int     `json:"roi_pts"`
	BodyPts     int     `json:"body_pts"`
	BodyRatio   float32 `json:"body_ratio"`
	Valid       bool    `json:"valid"`
}

type vkey = [3]int32

func ifloor(v, leaf float32) int32 { return int32(math.Floor(float64(v) / float64(leaf))) }

// Measure 跑完整测量管线。xyzMM=[x,y,z,...] mm。空/退化输入返回 Valid=false（不 panic）。
func Measure(xyzMM []float32, p MeasureParams) Dimensions {
	d := Dimensions{RawPts: len(xyzMM) / 3}
	if d.RawPts == 0 {
		return d
	}
	pts := toPoints(xyzMM)
	if p.UseCropBox {
		// 持久框：变换到框局部系(u=右,v=前,w=上)并只留框内点。深度隔离背景，不靠自动地面。
		// carType 偏移平移框心（设备/世界系，补偿车体位置差）。
		box := p.Box
		box.Center = [3]float32{box.Center[0] + p.CarOffset[0], box.Center[1] + p.CarOffset[1], box.Center[2] + p.CarOffset[2]}
		pts = toBoxFrame(pts, box)
	} else if p.UseGround {
		// 地面相对：变换到地面正交基(x=右,y=前,z=离地高)后用高度 band 裁剪（去地板/天花/地下）。
		// carType 偏移在设备系、与地面基不对齐，此路径暂不应用。
		pts = toGroundFrame(pts, p.GroundN, p.GroundD)
		const big = float32(1e9)
		pts = cropROI(pts, [3]float32{-big, -big, p.HeightMin}, [3]float32{big, big, p.HeightMax})
	} else if p.UseROI {
		// 设备系 ROI：carType 偏移平移 ROI 上下界（原厂 setCarType 语义）。
		mn := [3]float32{p.ROIMin[0] + p.CarOffset[0], p.ROIMin[1] + p.CarOffset[1], p.ROIMin[2] + p.CarOffset[2]}
		mx := [3]float32{p.ROIMax[0] + p.CarOffset[0], p.ROIMax[1] + p.CarOffset[1], p.ROIMax[2] + p.CarOffset[2]}
		pts = cropROI(pts, mn, mx)
	}
	d.ROIPts = len(pts)
	if len(pts) == 0 {
		return d
	}
	body := largestCluster(pts, p.ClusterLeaf)
	if p.UseROR {
		body = radiusOutlierRemoval(body, p.RORRadius, p.RORMinNeighbors)
	}
	d.BodyPts = len(body)
	if d.ROIPts > 0 {
		d.BodyRatio = float32(d.BodyPts) / float32(d.ROIPts)
	}
	if len(body) == 0 {
		return d
	}
	l, w, ang := minAreaRectXY(body, p.OBBStepDeg)
	d.LengthMM, d.WidthMM, d.OBBAngleDeg = l, w, ang
	if p.UseGround {
		// 地面系下地面在 z=0，车体离地最高即车高（比 zSpan 更贴真实：从地面量到车顶）。
		d.HeightMM = maxZ(body)
	} else {
		d.HeightMM = zSpan(body)
	}
	d.Valid = true
	return d
}

// toGroundFrame 把点变换到地面正交基：x'=p·right, y'=p·fwd, z'=n·p+d(离地高)。
// right/fwd 由 n 叉乘构造（与 PointCloud3dView.setGround 同范式），张成地面平面；
// n 退化(零长)时原样返回。
func toGroundFrame(in []pt, n [3]float32, d float32) []pt {
	nl := float32(math.Sqrt(float64(n[0]*n[0] + n[1]*n[1] + n[2]*n[2])))
	if nl < 1e-6 {
		return in
	}
	ux, uy, uz := n[0]/nl, n[1]/nl, n[2]/nl
	// 取与 up 最不平行的世界轴做参考，叉乘出 right、fwd。
	var rfx, rfy, rfz float32
	if float32(math.Abs(float64(uz))) < 0.9 {
		rfx, rfy, rfz = 0, 0, 1
	} else {
		rfx, rfy, rfz = 1, 0, 0
	}
	rx, ry, rz := uy*rfz-uz*rfy, uz*rfx-ux*rfz, ux*rfy-uy*rfx // right = up × ref
	rl := float32(math.Sqrt(float64(rx*rx + ry*ry + rz*rz)))
	rx, ry, rz = rx/rl, ry/rl, rz/rl
	fx, fy, fz := ry*uz-rz*uy, rz*ux-rx*uz, rx*uy-ry*ux // fwd = right × up
	out := make([]pt, len(in))
	for i, q := range in {
		out[i] = pt{
			q.x*rx + q.y*ry + q.z*rz,
			q.x*fx + q.y*fy + q.z*fz,
			q.x*ux + q.y*uy + q.z*uz + d,
		}
	}
	return out
}

func maxZ(body []pt) float32 {
	m := float32(-math.MaxFloat32)
	for _, q := range body {
		if q.z > m {
			m = q.z
		}
	}
	return m
}

type pt struct{ x, y, z float32 }

func toPoints(xyz []float32) []pt {
	n := len(xyz) / 3
	out := make([]pt, n)
	for i := 0; i < n; i++ {
		out[i] = pt{xyz[3*i], xyz[3*i+1], xyz[3*i+2]}
	}
	return out
}

func cropROI(in []pt, mn, mx [3]float32) []pt {
	out := in[:0:0]
	for _, q := range in {
		if q.x >= mn[0] && q.x <= mx[0] && q.y >= mn[1] && q.y <= mx[1] && q.z >= mn[2] && q.z <= mx[2] {
			out = append(out, q)
		}
	}
	return out
}

// largestCluster 体素 26-连通 BFS，取点数最多的连通体，返回原分辨率中属该连通体的点。
func largestCluster(in []pt, leaf float32) []pt {
	if leaf <= 0 || len(in) == 0 {
		return in
	}
	voxID := make(map[vkey]int, len(in))
	var keys []vkey
	var voxPts []int
	ptVk := make([]vkey, len(in))
	for i, q := range in {
		k := vkey{ifloor(q.x, leaf), ifloor(q.y, leaf), ifloor(q.z, leaf)}
		ptVk[i] = k
		if id, ok := voxID[k]; ok {
			voxPts[id]++
		} else {
			voxID[k] = len(keys)
			keys = append(keys, k)
			voxPts = append(voxPts, 1)
		}
	}
	seen := make([]bool, len(keys))
	var best map[vkey]struct{}
	bestPts := -1
	stack := make([]int, 0, 256)
	for s := range keys {
		if seen[s] {
			continue
		}
		stack = stack[:0]
		stack = append(stack, s)
		seen[s] = true
		comp := []int{}
		pts := 0
		for len(stack) > 0 {
			cur := stack[len(stack)-1]
			stack = stack[:len(stack)-1]
			comp = append(comp, cur)
			pts += voxPts[cur]
			c := keys[cur]
			for dx := int32(-1); dx <= 1; dx++ {
				for dy := int32(-1); dy <= 1; dy++ {
					for dz := int32(-1); dz <= 1; dz++ {
						if dx == 0 && dy == 0 && dz == 0 {
							continue
						}
						if id, ok := voxID[vkey{c[0] + dx, c[1] + dy, c[2] + dz}]; ok && !seen[id] {
							seen[id] = true
							stack = append(stack, id)
						}
					}
				}
			}
		}
		if pts > bestPts {
			bestPts = pts
			best = make(map[vkey]struct{}, len(comp))
			for _, id := range comp {
				best[keys[id]] = struct{}{}
			}
		}
	}
	out := make([]pt, 0, bestPts)
	for i, q := range in {
		if _, ok := best[ptVk[i]]; ok {
			out = append(out, q)
		}
	}
	return out
}

// radiusOutlierRemoval 保留「半径内邻居数 >= minN」的点（均匀网格 cell=radius 加速，27 邻域，早退）。
func radiusOutlierRemoval(in []pt, radius float32, minN int) []pt {
	if radius <= 0 || minN <= 0 || len(in) == 0 {
		return in
	}
	grid := make(map[vkey][]int32, len(in))
	for i, q := range in {
		k := vkey{ifloor(q.x, radius), ifloor(q.y, radius), ifloor(q.z, radius)}
		grid[k] = append(grid[k], int32(i))
	}
	r2 := radius * radius
	out := in[:0:0]
	for i, q := range in {
		ci, cj, ck := ifloor(q.x, radius), ifloor(q.y, radius), ifloor(q.z, radius)
		cnt := 0
	scan:
		for dx := int32(-1); dx <= 1; dx++ {
			for dy := int32(-1); dy <= 1; dy++ {
				for dz := int32(-1); dz <= 1; dz++ {
					for _, j := range grid[vkey{ci + dx, cj + dy, ck + dz}] {
						if int(j) == i {
							continue
						}
						o := in[j]
						ddx, ddy, ddz := o.x-q.x, o.y-q.y, o.z-q.z
						if ddx*ddx+ddy*ddy+ddz*ddz <= r2 {
							cnt++
							if cnt >= minN {
								break scan
							}
						}
					}
				}
			}
		}
		if cnt >= minN {
			out = append(out, q)
		}
	}
	return out
}

// minAreaRectXY 俯视最小面积外接矩形：长边/短边 + 取最小面积的旋转角(度)。
// 大点云优化：角度扫描在等距抽样子集(≤120k)上跑，最终在全量上按最优角算精确跨度。
func minAreaRectXY(body []pt, step float32) (length, width, angle float32) {
	if len(body) == 0 {
		return 0, 0, 0
	}
	if step <= 0 {
		step = 0.25
	}
	const cap = 120000
	sample := body
	if len(body) > cap {
		stride := len(body) / cap
		sample = make([]pt, 0, cap+1)
		for i := 0; i < len(body); i += stride {
			sample = append(sample, body[i])
		}
	}
	const deg2rad = math.Pi / 180.0
	bestArea := math.MaxFloat64
	bestAng := 0.0
	for a := 0.0; a < 90.0; a += float64(step) {
		c, s := math.Cos(a*deg2rad), math.Sin(a*deg2rad)
		umin, vmin := math.MaxFloat64, math.MaxFloat64
		umax, vmax := -math.MaxFloat64, -math.MaxFloat64
		for _, q := range sample {
			u := float64(q.x)*c + float64(q.y)*s
			v := -float64(q.x)*s + float64(q.y)*c
			if u < umin {
				umin = u
			}
			if u > umax {
				umax = u
			}
			if v < vmin {
				vmin = v
			}
			if v > vmax {
				vmax = v
			}
		}
		if (umax-umin)*(vmax-vmin) < bestArea {
			bestArea = (umax - umin) * (vmax - vmin)
			bestAng = a
		}
	}
	// 最优角下，全量精确跨度。
	c, s := math.Cos(bestAng*deg2rad), math.Sin(bestAng*deg2rad)
	umin, vmin := math.MaxFloat64, math.MaxFloat64
	umax, vmax := -math.MaxFloat64, -math.MaxFloat64
	for _, q := range body {
		u := float64(q.x)*c + float64(q.y)*s
		v := -float64(q.x)*s + float64(q.y)*c
		if u < umin {
			umin = u
		}
		if u > umax {
			umax = u
		}
		if v < vmin {
			vmin = v
		}
		if v > vmax {
			vmax = v
		}
	}
	du, dv := float32(umax-umin), float32(vmax-vmin)
	if du >= dv {
		return du, dv, float32(bestAng)
	}
	return dv, du, float32(bestAng)
}

func zSpan(body []pt) float32 {
	zmin, zmax := float32(math.MaxFloat32), float32(-math.MaxFloat32)
	for _, q := range body {
		if q.z < zmin {
			zmin = q.z
		}
		if q.z > zmax {
			zmax = q.z
		}
	}
	return zmax - zmin
}

// --- GB7258-2017 合规（docs/16 §8 [LIMT]）---

// Limits 外廓硬限值（mm），默认取原厂 setting.ini [LIMT]。
type Limits struct {
	MaxLengthMM float32
	MaxWidthMM  float32
	MaxHeightMM float32
}

// DefaultLimits = 原厂 [LIMT] carlength/carwidth/carheight（GB7258 通用外廓上限）。
func DefaultLimits() Limits {
	return Limits{MaxLengthMM: 12000, MaxWidthMM: 2550, MaxHeightMM: 4000}
}

// LimitsForVehicleType 按车型返回外廓限值。GB7258-2017 §4.15/§11 对不同类别（货车/半挂列车/牵引车…）
// 车长上限不同（如货车≤12m、半挂列车≤17.1m），但**逐车型的已核验限值表尚未逆向/录入**，此处先对所有
// 车型返回通用 [LIMT]（宽 2550/高 4000 各类一致；车长按型细化待补）。**不编造逐型数值**。
// TODO(M9.2b)：从 GB7258-2017 类别表 / 原厂 setting.ini 录入逐型限值，替换此通用回退（docs/16 §8）。
func LimitsForVehicleType(vehicleTypeID int) Limits {
	return DefaultLimits()
}

// Compliance 合规结论。
type Compliance struct {
	Compliant  bool     `json:"compliant"`
	Violations []string `json:"violations,omitempty"`
}

// CheckCompliance 据 [LIMT] 判超限。测量无效时视为不可判定（Compliant=false + 说明）。
func CheckCompliance(d Dimensions, lim Limits) Compliance {
	if !d.Valid {
		return Compliance{Compliant: false, Violations: []string{"测量无效，无法判定合规"}}
	}
	var v []string
	if d.LengthMM > lim.MaxLengthMM {
		v = append(v, "车长超限")
	}
	if d.WidthMM > lim.MaxWidthMM {
		v = append(v, "车宽超限")
	}
	if d.HeightMM > lim.MaxHeightMM {
		v = append(v, "车高超限")
	}
	return Compliance{Compliant: len(v) == 0, Violations: v}
}
