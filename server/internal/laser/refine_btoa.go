package laser

import "math"

// refine_btoa.go = B→A 外参的点到面精修（Go 版，PCL-free，与 measure.go 同"放 Go 皆可测"范式）。
//
// 为什么需要（2026-07-09 真机诊断，finding_laser_dimension_error_rootcause_2026-07-09）：
// 两单元对向安装，看到的是同一物体/家具/墙的**对立面**——native 的点到点稠密 ICP 会把对立面往一起拉，
// 产生"表面厚度"量级的系统性偏置（真机实测 B→A 沿车长轴错位 ~67mm → 车长 +3.5%）。
// 修法 = 点到面残差 + 法向相容性拒绝（|n_src·n_dst| > 阈值才配对，天然排除对立面），
// 真机 job184 原型验证：从 标记外参 / 旧ICP精修 两种初值收敛到同一变换(差<0.01mm)，
// 车长 1775.3 / 车宽 529.8 vs 真值 1777/533，全链 <1%。
//
// 输入输出均为 runner 世界系(mm，已翻转)。确定性：体素首见去重 + 固定遍历序，无随机源。

// RefineBToAParams 点到面精修参数（默认值即真机验证值）。
type RefineBToAParams struct {
	VoxelLeafMM   float32 // 体素降采样边长
	NormalK       int     // 法向 PCA 近邻数
	Iterations    int
	DistStartMM   float32 // 对应距离上限（随迭代线性退火）
	DistEndMM     float32
	NormalCosMin  float32 // 法向相容 |cos| 下限（排除对立面配对）
	MinPairs      int     // 少于此对应数 → 拒绝精修
	MaxDeltaTrans float32 // 守卫：精修相对初值的平移上限 mm，超出 → 拒绝（防 ICP 飞走）
	MaxDeltaRotDe float32 // 守卫：旋转上限 度
}

// DefaultRefineBToAParams 真机验证默认值。
func DefaultRefineBToAParams() RefineBToAParams {
	return RefineBToAParams{
		VoxelLeafMM: 35, NormalK: 12, Iterations: 25,
		DistStartMM: 200, DistEndMM: 60, NormalCosMin: 0.8, MinPairs: 1000,
		MaxDeltaTrans: 150, MaxDeltaRotDe: 5,
	}
}

// RefineBToAStats 精修统计（落 stats JSONB 监控标定漂移）。
type RefineBToAStats struct {
	Applied      bool    `json:"applied"`
	Pairs        int     `json:"pairs"`
	RMSMM        float32 `json:"rms_mm"`
	DeltaTransMM float32 `json:"delta_trans_mm"` // 精修相对初值的平移量
	DeltaRotDeg  float32 `json:"delta_rot_deg"`  // 精修相对初值的旋转量
	Reason       string  `json:"reason,omitempty"`
}

// RefineBToA 用点到面 ICP + 法向相容性把 initT(B→A) 精修到毫米级。
// cloudA 为参考(unit_a 世界系)，cloudB 为源(unit_b 设备系)，均 [x,y,z,...] mm。
// 失败/越守卫时返回 initT 原值 + Applied=false（非致命，沿用初值）。
func RefineBToA(cloudA, cloudB []float32, initT [16]float32, p RefineBToAParams) ([16]float32, RefineBToAStats) {
	if p.VoxelLeafMM <= 0 || p.Iterations <= 0 {
		p = DefaultRefineBToAParams()
	}
	dst := voxelFirstSeen(cloudA, p.VoxelLeafMM)
	src := voxelFirstSeen(cloudB, p.VoxelLeafMM)
	if len(dst) < p.MinPairs || len(src) < p.MinPairs {
		return initT, RefineBToAStats{Reason: "点云过稀"}
	}
	nd := estimateNormals(dst, p.VoxelLeafMM, p.NormalK)
	ns := estimateNormals(src, p.VoxelLeafMM, p.NormalK)
	grid := buildGrid(dst, p.DistStartMM)

	T := mat16ToF64(initT)
	var lastPairs int
	var lastRMS float64
	for it := 0; it < p.Iterations; it++ {
		maxd := float64(p.DistStartMM) + (float64(p.DistEndMM)-float64(p.DistStartMM))*float64(it)/float64(maxi(1, p.Iterations-1))
		// 变换源点/源法向到 A 系
		var ata [21]float64 // 6x6 对称上三角
		var atb [6]float64
		pairs := 0
		sse := 0.0
		for i, q := range src {
			sw := applyT64(T, q)
			j, d2 := grid.nearest(sw, maxd)
			if j < 0 {
				continue
			}
			// 法向相容：源法向经旋转后与目标法向同向性(同一侧表面)，拒绝对立面配对。
			nsw := rotT64(T, ns[i])
			cosn := nsw[0]*nd[j][0] + nsw[1]*nd[j][1] + nsw[2]*nd[j][2]
			if math.Abs(cosn) < float64(p.NormalCosMin) {
				continue
			}
			n := nd[j]
			dpt := dst[j]
			r := (sw[0]-dpt[0])*n[0] + (sw[1]-dpt[1])*n[1] + (sw[2]-dpt[2])*n[2]
			// 行 = [p×n, n]，最小化 (row·x + r)^2
			cx := sw[1]*n[2] - sw[2]*n[1]
			cy := sw[2]*n[0] - sw[0]*n[2]
			cz := sw[0]*n[1] - sw[1]*n[0]
			row := [6]float64{cx, cy, cz, n[0], n[1], n[2]}
			k := 0
			for a := 0; a < 6; a++ {
				for b := a; b < 6; b++ {
					ata[k] += row[a] * row[b]
					k++
				}
				atb[a] -= row[a] * r
			}
			pairs++
			sse += r * r
			_ = d2
		}
		if pairs < p.MinPairs {
			return initT, RefineBToAStats{Pairs: pairs, Reason: "重叠面对应不足"}
		}
		lastPairs = pairs
		lastRMS = math.Sqrt(sse / float64(pairs))
		x, ok := solveSym6(ata, atb)
		if !ok {
			return initT, RefineBToAStats{Pairs: pairs, Reason: "法向几何退化(欠约束)"}
		}
		T = composeSE3(x, T)
		step := math.Sqrt(x[0]*x[0]+x[1]*x[1]+x[2]*x[2])*1000 + math.Sqrt(x[3]*x[3]+x[4]*x[4]+x[5]*x[5])
		if step < 0.01 {
			break
		}
	}

	dTrans, dRot := deltaSE3(mat16ToF64(initT), T)
	st := RefineBToAStats{Pairs: lastPairs, RMSMM: float32(lastRMS),
		DeltaTransMM: float32(dTrans), DeltaRotDeg: float32(dRot)}
	if float32(dTrans) > p.MaxDeltaTrans || float32(dRot) > p.MaxDeltaRotDe {
		st.Reason = "精修量超守卫上限(疑似 ICP 发散)，沿用初值"
		return initT, st
	}
	st.Applied = true
	return f64ToMat16(T), st
}

// ---- 几何/线代辅助（确定性，无随机源） ----

// voxelFirstSeen 体素首见去重降采样（确定性：按输入序保留每体素第一个点）。
func voxelFirstSeen(xyz []float32, leaf float32) [][3]float64 {
	seen := make(map[vkey]struct{}, len(xyz)/6)
	out := make([][3]float64, 0, len(xyz)/6)
	for i := 0; i+2 < len(xyz); i += 3 {
		x, y, z := xyz[i], xyz[i+1], xyz[i+2]
		if !isFiniteSane(x) || !isFiniteSane(y) || !isFiniteSane(z) {
			continue
		}
		k := vkey{ifloor(x, leaf), ifloor(y, leaf), ifloor(z, leaf)}
		if _, ok := seen[k]; ok {
			continue
		}
		seen[k] = struct{}{}
		out = append(out, [3]float64{float64(x), float64(y), float64(z)})
	}
	return out
}

// estimateNormals 网格近邻 PCA 法向（协方差最小特征向量）。近邻取 3×3×3 邻域内最近 k 个。
func estimateNormals(pts [][3]float64, cell float32, k int) [][3]float64 {
	g := buildGrid(pts, cell*2)
	out := make([][3]float64, len(pts))
	idx := make([]int, 0, 64)
	for i, q := range pts {
		idx = g.neighbors(q, idx[:0])
		// 取邻域内最近 k 个（含自身）
		if len(idx) > k {
			// 简单选择：按距离部分排序（k 小，直接选择排序前 k）
			for a := 0; a < k; a++ {
				best := a
				da := dist2(pts[idx[a]], q)
				for b := a + 1; b < len(idx); b++ {
					if db := dist2(pts[idx[b]], q); db < da {
						best, da = b, db
					}
				}
				idx[a], idx[best] = idx[best], idx[a]
			}
			idx = idx[:k]
		}
		if len(idx) < 3 {
			out[i] = [3]float64{0, 0, 1}
			continue
		}
		var cx, cy, cz float64
		for _, j := range idx {
			cx += pts[j][0]
			cy += pts[j][1]
			cz += pts[j][2]
		}
		n := float64(len(idx))
		cx, cy, cz = cx/n, cy/n, cz/n
		var xx, xy, xz, yy, yz, zz float64
		for _, j := range idx {
			dx, dy, dz := pts[j][0]-cx, pts[j][1]-cy, pts[j][2]-cz
			xx += dx * dx
			xy += dx * dy
			xz += dx * dz
			yy += dy * dy
			yz += dy * dz
			zz += dz * dz
		}
		out[i] = smallestEigvec3(xx, xy, xz, yy, yz, zz)
	}
	return out
}

// smallestEigvec3 3×3 对称矩阵最小特征值对应的单位特征向量（Jacobi 旋转，确定性）。
func smallestEigvec3(xx, xy, xz, yy, yz, zz float64) [3]float64 {
	a := [3][3]float64{{xx, xy, xz}, {xy, yy, yz}, {xz, yz, zz}}
	v := [3][3]float64{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}
	for sweep := 0; sweep < 12; sweep++ {
		off := math.Abs(a[0][1]) + math.Abs(a[0][2]) + math.Abs(a[1][2])
		if off < 1e-12 {
			break
		}
		for p := 0; p < 2; p++ {
			for q := p + 1; q < 3; q++ {
				if math.Abs(a[p][q]) < 1e-15 {
					continue
				}
				theta := (a[q][q] - a[p][p]) / (2 * a[p][q])
				t := 1 / (math.Abs(theta) + math.Sqrt(theta*theta+1))
				if theta < 0 {
					t = -t
				}
				c := 1 / math.Sqrt(t*t+1)
				s := t * c
				for r := 0; r < 3; r++ {
					apr, aqr := a[p][r], a[q][r]
					a[p][r] = c*apr - s*aqr
					a[q][r] = s*apr + c*aqr
				}
				for r := 0; r < 3; r++ {
					arp, arq := a[r][p], a[r][q]
					a[r][p] = c*arp - s*arq
					a[r][q] = s*arp + c*arq
					vrp, vrq := v[r][p], v[r][q]
					v[r][p] = c*vrp - s*vrq
					v[r][q] = s*vrp + c*vrq
				}
			}
		}
	}
	mi := 0
	if a[1][1] < a[mi][mi] {
		mi = 1
	}
	if a[2][2] < a[mi][mi] {
		mi = 2
	}
	n := [3]float64{v[0][mi], v[1][mi], v[2][mi]}
	l := math.Sqrt(n[0]*n[0] + n[1]*n[1] + n[2]*n[2])
	if l < 1e-12 {
		return [3]float64{0, 0, 1}
	}
	return [3]float64{n[0] / l, n[1] / l, n[2] / l}
}

// ptGrid 均匀网格近邻索引（cell 固定，查询扫 3×3×3 邻域）。
type ptGrid struct {
	cell float32
	m    map[vkey][]int32
	pts  [][3]float64
}

func buildGrid(pts [][3]float64, cell float32) *ptGrid {
	g := &ptGrid{cell: cell, m: make(map[vkey][]int32, len(pts)), pts: pts}
	for i, q := range pts {
		k := vkey{ifloor(float32(q[0]), cell), ifloor(float32(q[1]), cell), ifloor(float32(q[2]), cell)}
		g.m[k] = append(g.m[k], int32(i))
	}
	return g
}

// neighbors 返回 q 所在 3×3×3 邻域内全部点索引（供法向 PCA）。
func (g *ptGrid) neighbors(q [3]float64, buf []int) []int {
	ci, cj, ck := ifloor(float32(q[0]), g.cell), ifloor(float32(q[1]), g.cell), ifloor(float32(q[2]), g.cell)
	for dx := int32(-1); dx <= 1; dx++ {
		for dy := int32(-1); dy <= 1; dy++ {
			for dz := int32(-1); dz <= 1; dz++ {
				for _, j := range g.m[vkey{ci + dx, cj + dy, ck + dz}] {
					buf = append(buf, int(j))
				}
			}
		}
	}
	return buf
}

// nearest 距离 q 最近且 ≤maxd 的点索引；无则 -1。maxd 须 ≤ cell（构建时用 DistStartMM 保证）。
func (g *ptGrid) nearest(q [3]float64, maxd float64) (int, float64) {
	ci, cj, ck := ifloor(float32(q[0]), g.cell), ifloor(float32(q[1]), g.cell), ifloor(float32(q[2]), g.cell)
	best := -1
	bestD := maxd * maxd
	for dx := int32(-1); dx <= 1; dx++ {
		for dy := int32(-1); dy <= 1; dy++ {
			for dz := int32(-1); dz <= 1; dz++ {
				for _, j := range g.m[vkey{ci + dx, cj + dy, ck + dz}] {
					d := dist2(g.pts[j], q)
					if d <= bestD {
						bestD = d
						best = int(j)
					}
				}
			}
		}
	}
	return best, bestD
}

func dist2(a, b [3]float64) float64 {
	dx, dy, dz := a[0]-b[0], a[1]-b[1], a[2]-b[2]
	return dx*dx + dy*dy + dz*dz
}

func mat16ToF64(m [16]float32) [16]float64 {
	var o [16]float64
	for i, v := range m {
		o[i] = float64(v)
	}
	return o
}

func f64ToMat16(m [16]float64) [16]float32 {
	var o [16]float32
	for i, v := range m {
		o[i] = float32(v)
	}
	return o
}

func applyT64(T [16]float64, p [3]float64) [3]float64 {
	return [3]float64{
		T[0]*p[0] + T[1]*p[1] + T[2]*p[2] + T[3],
		T[4]*p[0] + T[5]*p[1] + T[6]*p[2] + T[7],
		T[8]*p[0] + T[9]*p[1] + T[10]*p[2] + T[11],
	}
}

func rotT64(T [16]float64, n [3]float64) [3]float64 {
	return [3]float64{
		T[0]*n[0] + T[1]*n[1] + T[2]*n[2],
		T[4]*n[0] + T[5]*n[1] + T[6]*n[2],
		T[8]*n[0] + T[9]*n[1] + T[10]*n[2],
	}
}

// solveSym6 解对称 6×6 线性系(上三角紧存 21 元) A x = b，高斯消元 + 部分主元。
func solveSym6(ataU [21]float64, atb [6]float64) ([6]float64, bool) {
	var a [6][7]float64
	k := 0
	for i := 0; i < 6; i++ {
		for j := i; j < 6; j++ {
			a[i][j] = ataU[k]
			a[j][i] = ataU[k]
			k++
		}
		a[i][6] = atb[i]
	}
	for c := 0; c < 6; c++ {
		p := c
		for r := c + 1; r < 6; r++ {
			if math.Abs(a[r][c]) > math.Abs(a[p][c]) {
				p = r
			}
		}
		if math.Abs(a[p][c]) < 1e-9 {
			return [6]float64{}, false
		}
		a[c], a[p] = a[p], a[c]
		for r := c + 1; r < 6; r++ {
			f := a[r][c] / a[c][c]
			for j := c; j < 7; j++ {
				a[r][j] -= f * a[c][j]
			}
		}
	}
	var x [6]float64
	for r := 5; r >= 0; r-- {
		s := a[r][6]
		for j := r + 1; j < 6; j++ {
			s -= a[r][j] * x[j]
		}
		x[r] = s / a[r][r]
	}
	return x, true
}

// composeSE3 T ← exp([omega, t]) · T（小角度 Rodrigues）。x=[wx,wy,wz,tx,ty,tz]。
func composeSE3(x [6]float64, T [16]float64) [16]float64 {
	w := [3]float64{x[0], x[1], x[2]}
	ang := math.Sqrt(w[0]*w[0] + w[1]*w[1] + w[2]*w[2])
	var R [9]float64
	if ang < 1e-12 {
		R = [9]float64{1, 0, 0, 0, 1, 0, 0, 0, 1}
	} else {
		kx, ky, kz := w[0]/ang, w[1]/ang, w[2]/ang
		c, s := math.Cos(ang), math.Sin(ang)
		one := 1 - c
		R = [9]float64{
			c + kx*kx*one, kx*ky*one - kz*s, kx*kz*one + ky*s,
			ky*kx*one + kz*s, c + ky*ky*one, ky*kz*one - kx*s,
			kz*kx*one - ky*s, kz*ky*one + kx*s, c + kz*kz*one,
		}
	}
	var o [16]float64
	for r := 0; r < 3; r++ {
		for c := 0; c < 4; c++ {
			o[r*4+c] = R[r*3+0]*T[0*4+c] + R[r*3+1]*T[1*4+c] + R[r*3+2]*T[2*4+c]
		}
		o[r*4+3] += x[3+r]
	}
	o[15] = 1
	return o
}

// deltaSE3 两个 4×4 位姿差：平移差范数(mm) + 旋转差角(度)。
func deltaSE3(a, b [16]float64) (transMM, rotDeg float64) {
	dx, dy, dz := b[3]-a[3], b[7]-a[7], b[11]-a[11]
	transMM = math.Sqrt(dx*dx + dy*dy + dz*dz)
	// 旋转差角: trace(Ra^T Rb) = 1 + 2cos(theta)
	tr := 0.0
	for i := 0; i < 3; i++ {
		for j := 0; j < 3; j++ {
			tr += a[i*4+j] * b[i*4+j] // (Ra^T Rb) 的迹 = sum Ra[ij]*Rb[ij]
		}
	}
	cv := (tr - 1) / 2
	if cv > 1 {
		cv = 1
	}
	if cv < -1 {
		cv = -1
	}
	rotDeg = math.Acos(cv) * 180 / math.Pi
	return
}

func maxi(a, b int) int {
	if a > b {
		return a
	}
	return b
}
