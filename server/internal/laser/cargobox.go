package laser

import (
	"math"
	"sort"
)

// cargobox.go = 货箱分割 + 外长/外宽/箱深测量（Go，PCL-free，几何-only）。JCHY §3⑥
// calutePickingBox/getCarBoardInnterSize/getCarBoardDeep 的开源等价实现 —— 几何路径，不依赖 DL。
//
// 物理判据（.dev/vehicle-parts EDA 在原厂 Data/100742 上夯实，harness tests/harness/vehicle_cargobox）：
//   - 货箱=车尾侧"顶高接近全局最高"的最长连续段（车头顶矮、且与箱间常有缝）。
//   - 箱顶 rim(顶部薄层)的长/宽=货箱外长/外宽（rim 干净、无轮干扰）。
//   - 箱壁竖直 → 横截面宽度随高度恒定；最长"恒宽"z 段=箱壁，其底=bed floor，箱深=箱顶-bed。
//   - 壁带内宽度直方图最靠中心的两壁峰间距=内宽（壁中距；厚壁时分辨率受限，参考值）。
//
// 输入 body 同 measure/axle：裁剪后/聚类前的车体点（z=上）。内部按 OBB 角把车长/宽轴转到坐标轴。
// 数值验收=合成"车头+开顶货箱"闭环；原厂 100742 无货箱真值(carType=2 未触发箱测)，验分割+外尺寸。

// CargoBoxParams 货箱检测参数。默认在合成真值 + Data/100742 验证；真车按 harness 在设备扫描重标。
type CargoBoxParams struct {
	BinMM        float32 // 车长/高度方向直方图 bin 宽 mm
	TopFrac      float32 // box-like 顶高阈：maxZ ≥ top - TopFrac×车高
	RimFrac      float32 // rim 薄层厚占车高比例（取外长/外宽）
	WidthTolFrac float32 // 恒宽段宽度相对容差（定 bed）
	EndTrimFrac  float32 // 端噪剔除
}

// DefaultCargoBoxParams 与 harness analyze.py 一致。
func DefaultCargoBoxParams() CargoBoxParams {
	return CargoBoxParams{BinMM: 20, TopFrac: 0.06, RimFrac: 0.07, WidthTolFrac: 0.10, EndTrimFrac: 0.05}
}

// CargoBox 货箱测量结果（mm）。HasBox=false 表示无明显货箱结构（如牵引头/平板）。
type CargoBox struct {
	HasBox        bool    `json:"has_box"`
	OuterLengthMM float32 `json:"outer_length_mm"` // 货箱外长（沿车长，前壁→后壁）
	OuterWidthMM  float32 `json:"outer_width_mm"`  // 货箱外宽（箱顶 rim 宽）
	DepthMM       float32 `json:"depth_mm"`        // 货箱深/箱高 = 箱顶 - bed（≈栏板深度）
	TopZMM        float32 `json:"top_z_mm"`        // 箱顶离地高
	BedZMM        float32 `json:"bed_z_mm"`        // 箱底(bed)离地高
	InnerWidthMM  float32 `json:"inner_width_mm"`  // 内宽(壁中距)，参考值；0=未测出
	StartLMM      float32 `json:"start_l_mm"`      // 货箱在车长轴的起始(车头端坐标系)
	Valid         bool    `json:"valid"`

	// 叠加几何用：货箱在 OBB 车长/车宽投影坐标(与 axle/overlay 同 l/w 坐标)的范围，供映回世界系画框。
	BoxLMinMM    float32 `json:"-"`
	BoxLMaxMM    float32 `json:"-"`
	BoxWCenterMM float32 `json:"-"`
	BoxWHalfMM   float32 `json:"-"`
}

// DetectCargoBox 在 OBB 对齐后的车体点上分割货箱并测外长/外宽/箱深。
// obbAngleDeg = minAreaRectXY 返回角；body=裁剪后/聚类前车体点（z=上）。无货箱/退化返回 Valid=false。
func DetectCargoBox(body []pt, obbAngleDeg float32, p CargoBoxParams) CargoBox {
	var r CargoBox
	if len(body) < 200 || p.BinMM <= 0 {
		return r
	}
	ls, ws, zs := projectLWZ(body, obbAngleDeg)
	lMin, lMax := minMax(ls)
	lo, hi := trimEnds(ls, lMin, lMax, p.BinMM, p.EndTrimFrac)
	if hi-lo < 3*p.BinMM {
		return r
	}
	ground := percentile(zs, 0.3)
	top := percentile(zs, 99.5)
	vehH := top - ground
	if vehH <= 0 {
		return r
	}

	// 1) 沿车长 maxZ 剖面 → box-like 段（顶高接近全局最高）→ 最长连续段=货箱区。
	nb := int((hi-lo)/p.BinMM) + 1
	maxZ := make([]float32, nb)
	cnt := make([]int, nb)
	for i := range ls {
		if ls[i] < lo || ls[i] > hi {
			continue
		}
		b := int((ls[i] - lo) / p.BinMM)
		if b < 0 || b >= nb {
			continue
		}
		cnt[b]++
		if zs[i] > maxZ[b] {
			maxZ[b] = zs[i]
		}
	}
	thr := top - p.TopFrac*vehH
	bestS, bestE, runS := -1, -1, -1
	for b := 0; b <= nb; b++ {
		on := b < nb && cnt[b] > 20 && maxZ[b] >= thr
		if on && runS < 0 {
			runS = b
		}
		if !on && runS >= 0 {
			if bestE < 0 || (b-1-runS) > (bestE-bestS) {
				bestS, bestE = runS, b-1
			}
			runS = -1
		}
	}
	if bestS < 0 {
		return r // 无货箱结构（牵引头/平板等）
	}
	bl0 := lo + float32(bestS)*p.BinMM
	bl1 := lo + float32(bestE+1)*p.BinMM

	// 2) 货箱区点 → rim 取外宽，区间长=外长。
	var bw, bz []float32
	for i := range ls {
		if ls[i] >= bl0 && ls[i] <= bl1 {
			bw = append(bw, ws[i])
			bz = append(bz, zs[i])
		}
	}
	if len(bw) < 100 {
		return r
	}
	rimZ := top - p.RimFrac*vehH
	var rimW []float32
	for i := range bz {
		if bz[i] > rimZ {
			rimW = append(rimW, bw[i])
		}
	}
	outerW := spanPct(bw, 2, 98)
	if len(rimW) > 20 {
		outerW = spanPct(rimW, 2, 98)
	}
	r.OuterLengthMM = bl1 - bl0
	r.OuterWidthMM = outerW

	// 3) bed：箱壁竖直 → 宽度随 z 恒定；找最长恒宽 z 段，其底=bed。
	znb := int(vehH/p.BinMM) + 1
	widthZ := make([]float32, znb)
	{
		buckets := make([][]float32, znb)
		for i := range bz {
			zb := int((bz[i] - ground) / p.BinMM)
			if zb >= 0 && zb < znb {
				buckets[zb] = append(buckets[zb], bw[i])
			}
		}
		for b := 0; b < znb; b++ {
			if len(buckets[b]) > 30 {
				widthZ[b] = spanPct(buckets[b], 2, 98)
			}
		}
	}
	bed := ground
	bestLen := 0
	for b := 0; b < znb; {
		if widthZ[b] <= 0 {
			b++
			continue
		}
		ref := widthZ[b]
		j := b
		for j < znb && widthZ[j] > 0 && absf32(widthZ[j]-ref) < p.WidthTolFrac*ref {
			j++
		}
		if j-b > bestLen {
			bestLen = j - b
			bed = ground + float32(b)*p.BinMM
		}
		if j > b {
			b = j
		} else {
			b++
		}
	}
	r.TopZMM = top
	r.BedZMM = bed
	r.DepthMM = top - bed

	// 4) 内宽（壁中距，参考）：壁带内宽度直方图最靠中心的两壁峰间距。
	r.InnerWidthMM = innerWallSpan(bw, bz, bed+0.1*r.DepthMM, top-0.1*r.DepthMM)

	r.StartLMM = bl0 - lo
	// 叠加几何坐标（与 axle/overlay 同 l/w 投影系）：货箱 l 范围 + 宽心/半宽。
	r.BoxLMinMM, r.BoxLMaxMM = bl0, bl1
	r.BoxWCenterMM = pctVal(bw, 2)/2 + pctVal(bw, 98)/2
	r.BoxWHalfMM = outerW / 2
	r.HasBox = true
	r.Valid = true
	return r
}

// pctVal 返回升序分位值。
func pctVal(v []float32, pct float64) float32 {
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

// projectLWZ 按 OBB 角旋转，返回每点的车长坐标 l、车宽坐标 w、上向 z。
// 转后 u=x·c+y·s, v=-x·s+y·c；跨度更大者为车长 l、另一为车宽 w。
func projectLWZ(body []pt, angleDeg float32) (ls, ws, zs []float32) {
	const d2r = math.Pi / 180.0
	c, s := math.Cos(float64(angleDeg)*d2r), math.Sin(float64(angleDeg)*d2r)
	us := make([]float32, len(body))
	vs := make([]float32, len(body))
	zs = make([]float32, len(body))
	umin, umax := math.MaxFloat64, -math.MaxFloat64
	vmin, vmax := math.MaxFloat64, -math.MaxFloat64
	for i, q := range body {
		u := float64(q.x)*c + float64(q.y)*s
		v := -float64(q.x)*s + float64(q.y)*c
		us[i], vs[i], zs[i] = float32(u), float32(v), q.z
		umin, umax = math.Min(umin, u), math.Max(umax, u)
		vmin, vmax = math.Min(vmin, v), math.Max(vmax, v)
	}
	if umax-umin >= vmax-vmin {
		return us, vs, zs
	}
	return vs, us, zs
}

// spanPct 返回 [loPct, hiPct] 分位跨度（鲁棒极值，剔散点）。
func spanPct(v []float32, loPct, hiPct float64) float32 {
	if len(v) == 0 {
		return 0
	}
	c := append([]float32(nil), v...)
	sort.Slice(c, func(i, j int) bool { return c[i] < c[j] })
	at := func(p float64) float32 {
		idx := int(p / 100.0 * float64(len(c)-1))
		if idx < 0 {
			idx = 0
		}
		if idx >= len(c) {
			idx = len(c) - 1
		}
		return c[idx]
	}
	return at(hiPct) - at(loPct)
}

// innerWallSpan 壁带内宽度直方图，取最靠中心的左/右壁峰间距（内腔宽）。失败返回 0。
func innerWallSpan(bw, bz []float32, zLo, zHi float32) float32 {
	var band []float32
	for i := range bz {
		if bz[i] > zLo && bz[i] < zHi {
			band = append(band, bw[i])
		}
	}
	if len(band) < 50 {
		return 0
	}
	wmin, wmax := minMax(band)
	if wmax-wmin <= 0 {
		return 0
	}
	const nbin = 50
	bw2 := (wmax - wmin) / nbin
	hist := make([]float64, nbin)
	for _, x := range band {
		b := int((x - wmin) / bw2)
		if b >= 0 && b < nbin {
			hist[b]++
		}
	}
	sig := boxSmooth(hist, 3)
	maxv := 0.0
	for _, v := range sig {
		if v > maxv {
			maxv = v
		}
	}
	if maxv <= 0 {
		return 0
	}
	peaks := findPeaks1D(sig, 0.30*maxv, 0.15*maxv, 4)
	if len(peaks) < 2 {
		return 0
	}
	center := wmin + (wmax-wmin)/2
	leftInner, rightInner := float32(math.MaxFloat32*-1), float32(math.MaxFloat32)
	haveL, haveR := false, false
	for _, pk := range peaks {
		x := wmin + (float32(pk)+0.5)*bw2
		if x < center {
			if x > leftInner {
				leftInner = x
				haveL = true
			}
		} else if x < rightInner {
			rightInner = x
			haveR = true
		}
	}
	if haveL && haveR {
		return rightInner - leftInner
	}
	return 0
}

func absf32(a float32) float32 {
	if a < 0 {
		return -a
	}
	return a
}
