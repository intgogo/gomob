package laser

import (
	"math"
	"math/rand"
)

// ground.go = 地面平面检测（Go 版，PCL-free 纯几何，与 measure.go 同范式）。融合后对点云 RANSAC
// 拟合最大支持平面=地面，给出"上"方向(单位法向)+离地参考。一份数据两用：
//   ① 端侧视角预设的 up 基准 —— 设备世界系 Z 轴并非真竖直（实测约偏 5.6°），用真实地面法向摆正；
//   ② 车高=离地高度（比 Z 跨度更准，待测量 ROI 重标后接入）。
// 单位 mm。阈值 30mm（实测融合云 14.5% 点落在地面平面）。

// GroundPlane 地面平面：nx*x+ny*y+nz*z + d = 0，法向已定向指向点云主体一侧(=“上”)。
type GroundPlane struct {
	NX          float32 `json:"nx"`
	NY          float32 `json:"ny"`
	NZ          float32 `json:"nz"`
	D           float32 `json:"d"`
	InlierRatio float32 `json:"inlier_ratio"`
	Valid       bool    `json:"valid"`
}

// GroundParams RANSAC 参数。
type GroundParams struct {
	Threshold      float32    // 内点到平面距离阈值 mm
	Iterations     int        // RANSAC 迭代次数
	SampleCap      int        // 子采样上限（提速；内点统计在子样本上）
	MinInlierRatio float32    // 低于此判 Valid=false
	UpHint         [3]float32 // 重力先验"上"方向（设备世界系 Z≈竖直；实测地面法向偏 Z 仅 5.6°）
	MaxTiltDeg     float32    // 候选平面法向与 UpHint 夹角上限，超此判为墙面剔除
}

// DefaultGroundParams 默认参数。UpHint=+Z + 35° 容差：整屋扫描里墙面(竖直)与地面内点数相当，
// 仅靠"最大平面"会误选墙；重力先验只接受近水平平面=地面（真理源 docs/16；实测地面≈Z 偏 5.6°）。
func DefaultGroundParams() GroundParams {
	return GroundParams{
		Threshold: 30, Iterations: 600, SampleCap: 60000, MinInlierRatio: 0.05,
		UpHint: [3]float32{0, 0, 1}, MaxTiltDeg: 35,
	}
}

// DetectGround 对 xyzMM=[x,y,z,...] mm 做 RANSAC 地面拟合。空/退化返回 Valid=false（不 panic）。
// 确定性：固定随机种子，便于测试复现。
func DetectGround(xyzMM []float32, p GroundParams) GroundPlane {
	// 仅用有限且在合理量程内的点（剔除设备偶发的爆表/NaN 点，否则毁拟合）。
	pts := make([]pt, 0, len(xyzMM)/3)
	for i := 0; i+2 < len(xyzMM); i += 3 {
		x, y, z := xyzMM[i], xyzMM[i+1], xyzMM[i+2]
		if isFiniteSane(x) && isFiniteSane(y) && isFiniteSane(z) {
			pts = append(pts, pt{x, y, z})
		}
	}
	if len(pts) < 3 {
		return GroundPlane{}
	}

	// 子采样提速：RANSAC 内点统计在子样本上（确定性步长抽样，保持空间均匀）。
	sample := pts
	if len(pts) > p.SampleCap {
		stride := len(pts) / p.SampleCap
		sample = make([]pt, 0, p.SampleCap)
		for i := 0; i < len(pts); i += stride {
			sample = append(sample, pts[i])
		}
	}

	// 重力先验：归一化 UpHint，候选平面法向与之夹角 > MaxTiltDeg 的（墙面）剔除。
	uh := [3]float64{float64(p.UpHint[0]), float64(p.UpHint[1]), float64(p.UpHint[2])}
	uhLen := math.Sqrt(uh[0]*uh[0] + uh[1]*uh[1] + uh[2]*uh[2])
	useHint := uhLen > 1e-6
	if useHint {
		uh[0] /= uhLen
		uh[1] /= uhLen
		uh[2] /= uhLen
	}
	cosMax := math.Cos(float64(p.MaxTiltDeg) * math.Pi / 180) // |n·up| 须 ≥ 此值

	rng := rand.New(rand.NewSource(42))
	thr := float64(p.Threshold)
	var bestN [3]float64
	var bestD float64
	bestInl := 0
	for it := 0; it < p.Iterations; it++ {
		a := sample[rng.Intn(len(sample))]
		b := sample[rng.Intn(len(sample))]
		c := sample[rng.Intn(len(sample))]
		// 法向 = (b-a)×(c-a)
		v1 := [3]float64{float64(b.x - a.x), float64(b.y - a.y), float64(b.z - a.z)}
		v2 := [3]float64{float64(c.x - a.x), float64(c.y - a.y), float64(c.z - a.z)}
		n := [3]float64{v1[1]*v2[2] - v1[2]*v2[1], v1[2]*v2[0] - v1[0]*v2[2], v1[0]*v2[1] - v1[1]*v2[0]}
		norm := math.Sqrt(n[0]*n[0] + n[1]*n[1] + n[2]*n[2])
		if norm < 1e-6 {
			continue
		}
		n[0] /= norm
		n[1] /= norm
		n[2] /= norm
		// 重力先验：拒绝近竖直平面（墙）——只保留近水平=地面候选。
		if useHint && math.Abs(n[0]*uh[0]+n[1]*uh[1]+n[2]*uh[2]) < cosMax {
			continue
		}
		d := -(n[0]*float64(a.x) + n[1]*float64(a.y) + n[2]*float64(a.z))
		inl := 0
		for _, q := range sample {
			if math.Abs(n[0]*float64(q.x)+n[1]*float64(q.y)+n[2]*float64(q.z)+d) < thr {
				inl++
			}
		}
		if inl > bestInl {
			bestInl = inl
			bestN = n
			bestD = d
		}
	}
	if bestInl == 0 {
		return GroundPlane{}
	}

	// 定向：法向指向"上"。有 UpHint 时令 n·up>0（与设备竖直同向，顶视俯看正确）；否则用带符号
	// 距离之和（指向点云主体一侧=远离地面）。
	if useHint {
		if bestN[0]*uh[0]+bestN[1]*uh[1]+bestN[2]*uh[2] < 0 {
			bestN[0], bestN[1], bestN[2], bestD = -bestN[0], -bestN[1], -bestN[2], -bestD
		}
	} else {
		var sumSigned float64
		for _, q := range sample {
			sumSigned += bestN[0]*float64(q.x) + bestN[1]*float64(q.y) + bestN[2]*float64(q.z) + bestD
		}
		if sumSigned < 0 {
			bestN[0], bestN[1], bestN[2], bestD = -bestN[0], -bestN[1], -bestN[2], -bestD
		}
	}

	ratio := float32(bestInl) / float32(len(sample))
	return GroundPlane{
		NX: float32(bestN[0]), NY: float32(bestN[1]), NZ: float32(bestN[2]), D: float32(bestD),
		InlierRatio: ratio, Valid: ratio >= p.MinInlierRatio,
	}
}

// isFiniteSane 与 handlePts/fitTo 阈值一致：有限且 |v|≤50m(mm)。
func isFiniteSane(v float32) bool {
	f := float64(v)
	return !math.IsNaN(f) && !math.IsInf(f, 0) && math.Abs(f) <= 50000
}
