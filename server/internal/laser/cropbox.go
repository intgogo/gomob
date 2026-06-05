package laser

import "math"

// cropbox.go = "在点云上圈持久 3D 框(车位/扫描区) → 软件裁剪 → 框内测量"(M9.11)的核心。
//
// 为什么不是反算扫描角：设备只有 pan(水平) + 俯仰两个角度闸门、无深度/距离闸门
// (lidar_filter_zone 是竖直角范围不是距离)。真机实测把扫描限到"车"的 pan×俯仰立体角后，
// 远处办公室点仍保留 99%——因为办公室在传感器视线方向上藏在车的同一立体角里，
// 任何扫描角组合都分不开。唯一能按深度隔离的是 3D 框软件裁剪。
//
// 两台单元螺丝固定 → 框定义在 unit_a/融合世界系即跨扫描稳定，不依赖不可靠的自动地面 RANSAC。
// 用户一次圈定车位框 → 持久化 → 每次扫描裁到框内 → 框内点直接量 L×W×H。

// CropBox = 世界系定向裁剪框(OBB)。
// Up=朝上单位向量(地面法向，用户从自动地面种子起可翻转/微调)；Right/Fwd 由 Up + YawDeg 确定性构造；
// Center=框心(mm，unit_a/融合世界系)；Half={右半宽, 前半长, 上半高}(mm)。
type CropBox struct {
	Center [3]float32 `json:"center"`
	Up     [3]float32 `json:"up"`
	YawDeg float32    `json:"yaw_deg"`
	Half   [3]float32 `json:"half"` // [hRight, hFwd, hUp] mm
}

// Valid 框非退化（有正体积、Up 非零）。
func (b CropBox) Valid() bool {
	return b.Half[0] > 0 && b.Half[1] > 0 && b.Half[2] > 0 &&
		(b.Up[0] != 0 || b.Up[1] != 0 || b.Up[2] != 0)
}

// groundBasis 由地面法向确定性构造正交基：up=单位法向，right=up×ref，fwd=right×up。
// ref 取与 up 最不平行的世界轴（|nz|<0.9 用 +Z 否则 +X），保证 right 不退化。
func groundBasis(n [3]float32) (right, fwd, up [3]float32) {
	nl := float32(math.Sqrt(float64(n[0]*n[0] + n[1]*n[1] + n[2]*n[2])))
	if nl < 1e-6 {
		return [3]float32{1, 0, 0}, [3]float32{0, 1, 0}, [3]float32{0, 0, 1}
	}
	ux, uy, uz := n[0]/nl, n[1]/nl, n[2]/nl
	var rfx, rfy, rfz float32
	if float32(math.Abs(float64(uz))) < 0.9 {
		rfx, rfy, rfz = 0, 0, 1
	} else {
		rfx, rfy, rfz = 1, 0, 0
	}
	rx, ry, rz := uy*rfz-uz*rfy, uz*rfx-ux*rfz, ux*rfy-uy*rfx
	rl := float32(math.Sqrt(float64(rx*rx + ry*ry + rz*rz)))
	rx, ry, rz = rx/rl, ry/rl, rz/rl
	fx, fy, fz := ry*uz-rz*uy, rz*ux-rx*uz, rx*uy-ry*ux
	return [3]float32{rx, ry, rz}, [3]float32{fx, fy, fz}, [3]float32{ux, uy, uz}
}

// Basis 返回框的正交基(right,fwd,up)：先由 Up 取地面基，再绕 Up 旋 YawDeg 得 footprint 朝向（车头）。
func (b CropBox) Basis() (right, fwd, up [3]float32) {
	r0, f0, u := groundBasis(b.Up)
	yaw := float64(b.YawDeg) * math.Pi / 180
	c, s := float32(math.Cos(yaw)), float32(math.Sin(yaw))
	right = [3]float32{r0[0]*c + f0[0]*s, r0[1]*c + f0[1]*s, r0[2]*c + f0[2]*s}
	fwd = [3]float32{-r0[0]*s + f0[0]*c, -r0[1]*s + f0[1]*c, -r0[2]*s + f0[2]*c}
	return right, fwd, u
}

// CropToBox 保留落在框内的点，返回框内点（仍是原世界系扁平坐标 [x,y,z,...]）。供预览/可视化。
func CropToBox(xyzMM []float32, b CropBox) []float32 {
	right, fwd, up := b.Basis()
	out := make([]float32, 0, len(xyzMM)/4)
	n := len(xyzMM) / 3
	for i := 0; i < n; i++ {
		x, y, z := xyzMM[3*i], xyzMM[3*i+1], xyzMM[3*i+2]
		dx, dy, dz := x-b.Center[0], y-b.Center[1], z-b.Center[2]
		u := dx*right[0] + dy*right[1] + dz*right[2]
		v := dx*fwd[0] + dy*fwd[1] + dz*fwd[2]
		w := dx*up[0] + dy*up[1] + dz*up[2]
		if abs32(u) <= b.Half[0] && abs32(v) <= b.Half[1] && abs32(w) <= b.Half[2] {
			out = append(out, x, y, z)
		}
	}
	return out
}

// toBoxFrame 把点变换到框局部系(u=右,v=前,w=上，相对框心)并只保留框内点。供框内测量。
func toBoxFrame(in []pt, b CropBox) []pt {
	right, fwd, up := b.Basis()
	out := make([]pt, 0, len(in))
	for _, q := range in {
		dx, dy, dz := q.x-b.Center[0], q.y-b.Center[1], q.z-b.Center[2]
		u := dx*right[0] + dy*right[1] + dz*right[2]
		v := dx*fwd[0] + dy*fwd[1] + dz*fwd[2]
		w := dx*up[0] + dy*up[1] + dz*up[2]
		if abs32(u) <= b.Half[0] && abs32(v) <= b.Half[1] && abs32(w) <= b.Half[2] {
			out = append(out, pt{u, v, w})
		}
	}
	return out
}

func abs32(v float32) float32 {
	if v < 0 {
		return -v
	}
	return v
}
