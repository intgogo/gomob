package laser

import "math"

// background.go = 空工位背景相减（路 B，全自动抠车）。固定安装下扫描仪不动 → 先扫一次空工位当背景，
// 之后每次扫描里"在背景中无近邻"的点即前景=车。两云须同坐标系(unit_a 世界系)。PCL-free 纯几何。
//
// 物理判据：固定安装 → 静态房间(地/墙/天花/固定设备)每次扫到的点位置不变；唯一变的是开进来的车。
// 故 live 点若在背景云里 tol 范围内有近邻=静态背景，删；无近邻=车(或新进入的动态物)，留。
// 比 JCHY 固定 ROI 更自动：不需逐车型标定裁剪框，空工位扫一次即可。原厂(docs/16 §6.3)只做固定
// PassThrough，没吃透"固定安装→背景可减"这个条件。
//
// tol 吸收传感器噪声 + ICP 残差 + 小配准漂移；过小→残留背景散点，过大→侵蚀车体边界。默认 40mm，
// 由 harness background_subtract 扫参标定（实测设备噪声后重标）。减后仍需 主簇/ROR 去散点(见 measure)。

// BackgroundParams 背景相减参数。
type BackgroundParams struct {
	TolMM float32 // 近邻判定阈值 mm：live 点到最近背景点 <= tol 视为背景剔除
}

// DefaultBackgroundParams 默认 tol=40mm（待真机噪声 harness 重标）。
func DefaultBackgroundParams() BackgroundParams {
	return BackgroundParams{TolMM: 40}
}

// SubtractBackground 从 live=[x,y,z,...] 里剔除在 bg 中 tol 内有近邻的点，返回前景 [x,y,z,...]。
// 背景体素哈希(leaf=tol)+27 邻域精确距离，O(n+m)。bg 空 → 原样返回 live（无背景=不减，由上层兜底）。
func SubtractBackground(live, bg []float32, p BackgroundParams) []float32 {
	tol := p.TolMM
	if tol <= 0 {
		tol = 40
	}
	if len(bg) < 3 || len(live) < 3 {
		return live
	}
	// 背景点按 leaf=tol 体素分桶；leaf=tol 保证 tol 内的点必在 27 邻域桶里。
	grid := make(map[vkey][]int32, len(bg)/3)
	for i := 0; i+2 < len(bg); i += 3 {
		k := vkey{ifloor(bg[i], tol), ifloor(bg[i+1], tol), ifloor(bg[i+2], tol)}
		grid[k] = append(grid[k], int32(i))
	}
	tol2 := tol * tol
	out := make([]float32, 0, len(live)/4)
	for i := 0; i+2 < len(live); i += 3 {
		x, y, z := live[i], live[i+1], live[i+2]
		ci, cj, ck := ifloor(x, tol), ifloor(y, tol), ifloor(z, tol)
		isBg := false
	scan:
		for dx := int32(-1); dx <= 1; dx++ {
			for dy := int32(-1); dy <= 1; dy++ {
				for dz := int32(-1); dz <= 1; dz++ {
					for _, j := range grid[vkey{ci + dx, cj + dy, ck + dz}] {
						ddx, ddy, ddz := bg[j]-x, bg[j+1]-y, bg[j+2]-z
						if ddx*ddx+ddy*ddy+ddz*ddz <= tol2 {
							isBg = true
							break scan
						}
					}
				}
			}
		}
		if !isBg {
			out = append(out, x, y, z)
		}
	}
	return out
}

// foregroundBBoxSpan 前景点云三轴跨度(诊断/合理性闸用)。空返回 0。
func foregroundBBoxSpan(xyz []float32) (sx, sy, sz float32) {
	if len(xyz) < 3 {
		return 0, 0, 0
	}
	mnx, mny, mnz := float32(math.MaxFloat32), float32(math.MaxFloat32), float32(math.MaxFloat32)
	mxx, mxy, mxz := float32(-math.MaxFloat32), float32(-math.MaxFloat32), float32(-math.MaxFloat32)
	for i := 0; i+2 < len(xyz); i += 3 {
		x, y, z := xyz[i], xyz[i+1], xyz[i+2]
		mnx, mxx = minf(mnx, x), maxf(mxx, x)
		mny, mxy = minf(mny, y), maxf(mxy, y)
		mnz, mxz = minf(mnz, z), maxf(mxz, z)
	}
	return mxx - mnx, mxy - mny, mxz - mnz
}
