package laser

import (
	"math"
	"sort"
)

// axle.go = 轴距/前后悬测量（Go，PCL-free，几何-only）。JCHY §3⑥ caluteDeepWheel/segWheelBottom/
// SortWheelYMin2fMax 的开源等价实现 —— 原厂用 PointSIFT wheel_seg DL 取轴心，gomob 走几何路径。
//
// 物理判据（.dev/vehicle-parts EDA 在原厂 Data/100742 上夯实）：轮是唯一触地的部件，
// 贴地接触带(离地高 < contactH)沿车长轴的点密度峰 = 轴心；车厢侧壁/底盘悬在离地间隙以上、
// 不进接触带，自然排除。轴距=相邻轴心间距，前/后悬=车体两端到首/末轴心的伸出量。
// 验收基线 harness tests/harness/vehicle_axle（轴距 710/399/261、前后悬 261/163）。
//
// 输入 body 须是已裁剪+主簇的车体点（measure.Measure 同款），其 z 为"上"方向：
//   - 设备 ROI 路径：z=设备竖直
//   - 地面相对路径(toGroundFrame)：z=离地高
//   - 裁剪框路径(toBoxFrame)：w=框上向
// DetectAxles 内部按 OBB 角把车长轴转到坐标轴再扫，与 minAreaRectXY 同源。单位 mm。

// AxleParams 轴心检测参数。默认值在 Data/100742 验证；真车需按 ./dev.sh harness 在设备扫描上重标。
type AxleParams struct {
	ContactFrac float32 // 贴地接触带高度占车高比例（默认 0.08；带须 < 离地间隙才只纳入轮）
	ContactMin  float32 // 接触带高度下限 mm（小车/模型用，防 frac 过小）
	BinMM       float32 // 车长轴直方图 bin 宽 mm
	SmoothBins  int     // 密度平滑窗口（bin 数）
	MinAxleGap  float32 // 最小轴距 mm（峰间最小间距，防把单轴拆成两峰）
	EndTrimFrac float32 // 端噪剔除：丢点数 < 峰值×此比例的车长两端 bin

	// 接触带锚定（M13.9，真机 job190 反馈闭环）：默认接触带从"输入点全局最低分位 z0"起——
	// 背景漂移残留/台面杂物可能比车轮更低，把带锚拉到杂物上（轮完全不在带内，检出全是伪轴）。
	// UseAnchor 时改从"车体主簇底部"起：轮是车体自身的最低部件，与支撑面在哪/平不平无关。
	// AnchorZ = 车体底(主簇 z 的 P0.5)，带 = [AnchorZ−AnchorSlack, AnchorZ−AnchorSlack+contactH]。
	UseAnchor   bool
	AnchorZ     float32
	AnchorSlack float32 // 底部余量 mm：容纳被主簇丢掉的悬挂轮略低于主簇底（默认 20）

	// EndExclusionFrac 端部排除带占车长比例（默认 0.04）：轴心不可能贴着车体端部——轮是圆的，
	// 轴心到车头/车尾至少隔一个轮半径；贴端的接触带峰是保险杠下沿/裙边（真机 job195 前脸下沿
	// 距车头端 3% 处成伪轴、且密度压 0.25 阈线在相邻扫描间闪烁）。0=不排除。
	EndExclusionFrac float32
}

// DefaultAxleParams 与 harness analyze.py 一致（Data/100742 达标）。
func DefaultAxleParams() AxleParams {
	return AxleParams{ContactFrac: 0.08, ContactMin: 40, BinMM: 10, SmoothBins: 5, MinAxleGap: 150, EndTrimFrac: 0.05,
		EndExclusionFrac: 0.04}
}

// AxleResult 轴距/前后悬测量结果（沿车长轴，mm）。
type AxleResult struct {
	NumAxles         int       `json:"num_axles"`
	AxleCentersMM    []float32 `json:"axle_centers_mm"`    // 沿车长轴从车头端起的轴心位置
	WheelbasesMM     []float32 `json:"wheelbases_mm"`      // 相邻轴距（NumAxles-1 个）
	TotalWheelbaseMM float32   `json:"total_wheelbase_mm"` // 首轴到末轴跨度
	FrontOverhangMM  float32   `json:"front_overhang_mm"`  // 车头端 → 首轴
	RearOverhangMM   float32   `json:"rear_overhang_mm"`   // 末轴 → 车尾端
	Valid            bool      `json:"valid"`

	// AxleCentersRawMM = 轴心在 OBB 车长轴投影坐标(projectLengthAxis 的 l，升序、未翻转)。
	// 供叠加几何把轴线映回世界系（与 cargobox/overlay 同 l 坐标）；语义量用上面的 from-front 值。
	AxleCentersRawMM []float32 `json:"-"`
}

// DetectAxles 在 OBB 对齐后的车体点上检测轴心，算轴距 + 前后悬。
// obbAngleDeg = minAreaRectXY 返回的旋转角；body 为裁剪+主簇后的车体点（z=上）。
// 退化输入（点太少/检出<2 轴）返回 Valid=false。
func DetectAxles(body []pt, obbAngleDeg float32, p AxleParams) AxleResult {
	var r AxleResult
	if len(body) < 50 {
		return r
	}
	// 1) 投到 OBB 对齐的车长/车宽轴坐标（取转后跨度更大的轴为车长；宽轴供轮对形态检验）。
	ls, ws, zs := projectLWZ(body, obbAngleDeg)
	lMin, lMax := minMax(ls)
	if lMax-lMin < p.MinAxleGap {
		return r
	}
	// 2) 端噪剔除：沿 l 粗分 bin，裁掉两端稀疏 bin，得有效车长 [lo,hi]。
	lo, hi := trimEnds(ls, lMin, lMax, p.BinMM, p.EndTrimFrac)
	if hi-lo < p.MinAxleGap {
		return r
	}
	// 3) 接触带下锚 z0 + 带高。默认=输入点鲁棒低分位（平整地面、无残留时 ≈ 轮底）；
	//    UseAnchor 时=车体主簇底（抗支撑面起伏与低垂残留，见 AxleParams 注释）。
	z0 := percentile(zs, 0.3)
	if p.UseAnchor {
		slack := p.AnchorSlack
		if slack <= 0 {
			// 40mm: 主簇底(P0.5, 经 ROR)比真实轮底略高 —— JCHY 真值数据实测 20mm 余量使带上移
			// 吃进侧裙(轴距 +6%)，40mm 与旧全局低分位锚重合、真值回归不动。
			slack = 40
		}
		z0 = p.AnchorZ - slack
	}
	height := percentile(zs, 99.7) - z0
	contactH := p.ContactFrac * height
	if contactH < p.ContactMin {
		contactH = p.ContactMin
	}
	// 4) 接触带点沿 l 的密度直方图 + 平滑。
	nb := int((hi-lo)/p.BinMM) + 1
	if nb < 3 {
		return r
	}
	dens := make([]float64, nb)
	for i := range ls {
		if zs[i] < z0 || zs[i] >= z0+contactH || ls[i] < lo || ls[i] > hi {
			continue
		}
		b := int((ls[i] - lo) / p.BinMM)
		if b >= 0 && b < nb {
			dens[b]++
		}
	}
	sig := boxSmooth(dens, p.SmoothBins)
	// 5) 找峰=轴心。
	maxv := 0.0
	for _, v := range sig {
		if v > maxv {
			maxv = v
		}
	}
	if maxv <= 0 {
		return r
	}
	minGapBins := int(p.MinAxleGap / p.BinMM)
	peaks := findPeaks1D(sig, 0.25*maxv, 0.20*maxv, minGapBins)
	if len(peaks) < 2 {
		return r
	}
	// 6) 每峰用密度加权细化轴心（l 坐标），并做轮对形态检验：真轴=左右轮对，接触带内该 l 切片
	//    的宽度分布两侧有支撑、车宽中段空；保险杠/箱面残留等横贯全宽的墙状结构中段占比高 → 剔除
	//    （真机 job195 前保险杠下沿进接触带成伪轴、前悬塌到 59mm）。
	wLo := percentile(ws, 2)
	wHi := percentile(ws, 98)
	centers := make([]float32, 0, len(peaks))
	for _, pk := range peaks {
		a, b := pk-8, pk+8
		if a < 0 {
			a = 0
		}
		if b >= nb {
			b = nb - 1
		}
		var sw, swc float64
		for i := a; i <= b; i++ {
			c := lo + (float32(i)+0.5)*p.BinMM
			sw += sig[i]
			swc += sig[i] * float64(c)
		}
		if sw <= 0 {
			continue
		}
		center := float32(swc / sw)
		// 端部排除：轴心距车体任一端 < EndExclusionFrac×车长 → 保险杠/裙边端部结构，非轮。
		if p.EndExclusionFrac > 0 {
			endBand := p.EndExclusionFrac * (hi - lo)
			if center-lo < endBand || hi-center < endBand {
				continue
			}
		}
		if wheelPairLike(ls, ws, zs, center, 3*p.BinMM, z0, contactH, wLo, wHi) {
			centers = append(centers, center)
		}
	}
	sort.Slice(centers, func(i, j int) bool { return centers[i] < centers[j] })
	if len(centers) < 2 {
		return r
	}
	r.AxleCentersRawMM = append([]float32(nil), centers...) // 原始 l 坐标(未翻转)，供叠加几何
	// 7) 判车头端并统一成"从车头端起"坐标（front=lo, rear=hi），再算轴距 + 前后悬。
	//    车头端=邻接最大轴距且其外侧轴数更少的一端（单转向轴 vs 后轴组）。
	if frontIsHighEnd(adjDiff(centers)) {
		for i, c := range centers { // 关于 [lo,hi] 中点镜像，使车头端落到 lo
			centers[i] = lo + (hi - c)
		}
		sort.Slice(centers, func(i, j int) bool { return centers[i] < centers[j] })
	}
	gaps := adjDiff(centers)
	r.NumAxles = len(centers)
	r.AxleCentersMM = centers
	r.WheelbasesMM = gaps
	r.TotalWheelbaseMM = centers[len(centers)-1] - centers[0]
	r.FrontOverhangMM = centers[0] - lo
	r.RearOverhangMM = hi - centers[len(centers)-1]
	r.Valid = true
	return r
}

// wheelPairLike 轮对形态检验：接触带内、|l−center|≤halfWin 的点，其车宽坐标按整车宽
// [wLo,wHi] 分三段——真轴(左右轮对/单侧可见轮)中段占比低；横贯全宽的墙(保险杠/箱面残留)
// 中段占比 ~1/3。中段占比 > 0.25 判非轮。点数不足(<20)不否决(稀疏别误杀)。
func wheelPairLike(ls, ws, zs []float32, centerL, halfWin, z0, contactH, wLo, wHi float32) bool {
	third := (wHi - wLo) / 3
	if third <= 0 {
		return true
	}
	m0, m1 := wLo+third, wHi-third
	total, mid := 0, 0
	for i := range ls {
		if zs[i] < z0 || zs[i] >= z0+contactH {
			continue
		}
		d := ls[i] - centerL
		if d < -halfWin || d > halfWin {
			continue
		}
		total++
		if ws[i] >= m0 && ws[i] <= m1 {
			mid++
		}
	}
	if total < 20 {
		return true
	}
	return float32(mid)/float32(total) <= 0.25
}

// adjDiff 升序序列的相邻差。
func adjDiff(v []float32) []float32 {
	if len(v) < 2 {
		return nil
	}
	out := make([]float32, len(v)-1)
	for i := 1; i < len(v); i++ {
		out[i-1] = v[i] - v[i-1]
	}
	return out
}

// projectLengthAxis 把点按 OBB 角旋转，返回每点的车长轴坐标 l 与上向坐标 z。
// 转后 u=x·c+y·s, v=-x·s+y·c；车长轴取 u/v 中跨度更大者（与 minAreaRectXY 取长边一致）。
func projectLengthAxis(body []pt, angleDeg float32) (ls, zs []float32) {
	const deg2rad = math.Pi / 180.0
	c, s := math.Cos(float64(angleDeg)*deg2rad), math.Sin(float64(angleDeg)*deg2rad)
	us := make([]float32, len(body))
	vs := make([]float32, len(body))
	zs = make([]float32, len(body))
	umin, umax := math.MaxFloat64, -math.MaxFloat64
	vmin, vmax := math.MaxFloat64, -math.MaxFloat64
	for i, q := range body {
		u := float64(q.x)*c + float64(q.y)*s
		v := -float64(q.x)*s + float64(q.y)*c
		us[i], vs[i], zs[i] = float32(u), float32(v), q.z
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
	if umax-umin >= vmax-vmin {
		return us, zs
	}
	return vs, zs
}

// trimEnds 沿 l 粗分 bin，裁掉两端点数 < 峰值×frac 的稀疏 bin，返回有效车长 [lo,hi]。
func trimEnds(ls []float32, lMin, lMax, binMM, frac float32) (float32, float32) {
	nb := int((lMax-lMin)/binMM) + 1
	if nb < 3 {
		return lMin, lMax
	}
	h := make([]int, nb)
	for _, l := range ls {
		b := int((l - lMin) / binMM)
		if b >= 0 && b < nb {
			h[b]++
		}
	}
	maxc := 0
	for _, c := range h {
		if c > maxc {
			maxc = c
		}
	}
	thr := int(float32(maxc) * frac)
	first, last := -1, -1
	for i, c := range h {
		if c > thr {
			if first < 0 {
				first = i
			}
			last = i
		}
	}
	if first < 0 {
		return lMin, lMax
	}
	return lMin + float32(first)*binMM, lMin + float32(last+1)*binMM
}

// frontIsHighEnd 判断车头是否在车长轴大端：邻接最大轴距的两轴中，外侧轴数更少的一侧为车头
// （单转向轴 vs 后轴组）。返回 true=车头在 l 大端。最大轴距居中（两侧轴数相等）时默认车头在小端。
func frontIsHighEnd(gaps []float32) bool {
	if len(gaps) == 0 {
		return false
	}
	mi := 0
	for i, g := range gaps {
		if g > gaps[mi] {
			mi = i
		}
	}
	leftAxles := mi + 1           // 最大间距左侧轴数
	rightAxles := len(gaps) - mi  // 右侧轴数
	return rightAxles < leftAxles // 右侧（l 大端）轴更少 → 车头在大端
}

// findPeaks1D 1D 找峰：高度阈 + 突出度 + 最小间距（scipy.find_peaks 子集）。
func findPeaks1D(sig []float64, heightThr, prom float64, minGapBins int) []int {
	n := len(sig)
	var cand []int
	for i := 1; i < n-1; i++ {
		if sig[i] >= sig[i-1] && sig[i] > sig[i+1] && sig[i] >= heightThr {
			cand = append(cand, i)
		}
	}
	// 突出度：峰 − max(左基, 右基)，基=向两侧到更高样本前的最小值。
	var ok []int
	for _, pk := range cand {
		lv := sig[pk]
		for i := pk - 1; i >= 0; i-- {
			if sig[i] > sig[pk] {
				break
			}
			if sig[i] < lv {
				lv = sig[i]
			}
		}
		rv := sig[pk]
		for i := pk + 1; i < n; i++ {
			if sig[i] > sig[pk] {
				break
			}
			if sig[i] < rv {
				rv = sig[i]
			}
		}
		if sig[pk]-math.Max(lv, rv) >= prom {
			ok = append(ok, pk)
		}
	}
	// 最小间距：按高度降序贪心抑制近邻。
	sort.Slice(ok, func(a, b int) bool { return sig[ok[a]] > sig[ok[b]] })
	var kept []int
	for _, pk := range ok {
		good := true
		for _, k := range kept {
			if iabs(pk-k) < minGapBins {
				good = false
				break
			}
		}
		if good {
			kept = append(kept, pk)
		}
	}
	sort.Ints(kept)
	return kept
}

func boxSmooth(in []float64, win int) []float64 {
	if win < 2 {
		return in
	}
	n := len(in)
	out := make([]float64, n)
	half := win / 2
	for i := 0; i < n; i++ {
		var s float64
		var c int
		for j := i - half; j <= i+half; j++ {
			if j >= 0 && j < n {
				s += in[j]
				c++
			}
		}
		out[i] = s / float64(c)
	}
	return out
}

func percentile(v []float32, pct float64) float32 {
	if len(v) == 0 {
		return 0
	}
	c := append([]float32(nil), v...)
	sort.Slice(c, func(i, j int) bool { return c[i] < c[j] })
	idx := int(pct / 100.0 * float64(len(c)-1))
	if idx < 0 {
		idx = 0
	}
	if idx >= len(c) {
		idx = len(c) - 1
	}
	return c[idx]
}

func minMax(v []float32) (float32, float32) {
	mn, mx := float32(math.MaxFloat32), float32(-math.MaxFloat32)
	for _, x := range v {
		if x < mn {
			mn = x
		}
		if x > mx {
			mx = x
		}
	}
	return mn, mx
}

func iabs(a int) int {
	if a < 0 {
		return -a
	}
	return a
}
