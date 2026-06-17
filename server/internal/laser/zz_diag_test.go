package laser

import (
	"math"
	"os"
	"testing"
)

// 临时诊断：对真机融合云复现外廓测量，看哪条路径、什么结果、点云边界。诊断后删除。
func TestZZDiagReal(t *testing.T) {
	path := "../../../.dev/scan168/fused.pcd"
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Skipf("无 %s: %v", path, err)
	}
	xyz, err := DecodePCDBinary(raw)
	if err != nil {
		t.Fatalf("解析失败: %v", err)
	}
	n := len(xyz) / 3
	t.Logf("点数=%d", n)

	// 边界 + 质心
	var mn, mx [3]float32
	for i := 0; i < 3; i++ {
		mn[i], mx[i] = math.MaxFloat32, -math.MaxFloat32
	}
	var cx, cy, cz float64
	for i := 0; i < n; i++ {
		for j := 0; j < 3; j++ {
			v := xyz[3*i+j]
			if v < mn[j] {
				mn[j] = v
			}
			if v > mx[j] {
				mx[j] = v
			}
		}
		cx += float64(xyz[3*i])
		cy += float64(xyz[3*i+1])
		cz += float64(xyz[3*i+2])
	}
	t.Logf("X[%.0f,%.0f] span=%.0f", mn[0], mx[0], mx[0]-mn[0])
	t.Logf("Y[%.0f,%.0f] span=%.0f", mn[1], mx[1], mx[1]-mn[1])
	t.Logf("Z[%.0f,%.0f] span=%.0f", mn[2], mx[2], mx[2]-mn[2])
	t.Logf("质心=(%.0f,%.0f,%.0f)", cx/float64(n), cy/float64(n), cz/float64(n))

	// 路径①：设备系 ROI 默认（无车位框/无地面时的回退）
	dRoi := Measure(xyz, DefaultMeasureParams())
	t.Logf("device_roi: valid=%v L=%.0f W=%.0f H=%.0f roiPts=%d bodyPts=%d ang=%.1f",
		dRoi.Valid, dRoi.LengthMM, dRoi.WidthMM, dRoi.HeightMM, dRoi.ROIPts, dRoi.BodyPts, dRoi.OBBAngleDeg)

	// 路径②：无 ROI（直接整云主簇→OBB），看主簇是不是地面
	pNoRoi := DefaultMeasureParams()
	pNoRoi.UseROI = false
	dNo := Measure(xyz, pNoRoi)
	t.Logf("no_roi(整云主簇): valid=%v L=%.0f W=%.0f H=%.0f roiPts=%d bodyPts=%d",
		dNo.Valid, dNo.LengthMM, dNo.WidthMM, dNo.HeightMM, dNo.ROIPts, dNo.BodyPts)

	// 路径③：地面相对（车位框缺、地面有效时 runner 走这条）
	g := DetectGround(xyz, DefaultGroundParams())
	t.Logf("ground: valid=%v n=(%.3f,%.3f,%.3f) d=%.0f inlier=%.2f",
		g.Valid, g.NX, g.NY, g.NZ, g.D, g.InlierRatio)
	if g.Valid {
		gp := GroundMeasureParams([3]float32{g.NX, g.NY, g.NZ}, g.D, 30, 5000)
		dG := Measure(xyz, gp)
		t.Logf("ground_meas: valid=%v L=%.0f W=%.0f H=%.0f roiPts=%d bodyPts=%d ratio=%.2f",
			dG.Valid, dG.LengthMM, dG.WidthMM, dG.HeightMM, dG.ROIPts, dG.BodyPts, dG.BodyRatio)

		// 地面系下，离地高度 band 内枚举所有连通簇，看车是否能跟墙/天花分离。
		gpts := toGroundFrame(toPoints(xyz), [3]float32{g.NX, g.NY, g.NZ}, g.D)
		band := make([]pt, 0, len(gpts))
		for _, q := range gpts {
			if q.z >= 80 && q.z <= 5000 { // 抬高离地裕度，断开车轮-地面桥
				band = append(band, q)
			}
		}
		t.Logf("band(离地80~5000) 点数=%d", len(band))
		for _, leaf := range []float32{20, 40} {
			comps := allClusters(band, leaf)
			t.Logf("--- leaf=%.0f 簇数=%d (按点数前6)", leaf, len(comps))
			for i, c := range comps {
				if i >= 6 {
					break
				}
				bb := bboxOf(c)
				t.Logf("  簇#%d pts=%d X[%.0f,%.0f]=%.0f Y[%.0f,%.0f]=%.0f Z[%.0f,%.0f]=%.0f",
					i, len(c), bb[0][0], bb[1][0], bb[1][0]-bb[0][0],
					bb[0][1], bb[1][1], bb[1][1]-bb[0][1], bb[0][2], bb[1][2], bb[1][2]-bb[0][2])
			}
		}
	}
}

// allClusters 体素 26-连通枚举所有簇，按点数降序返回（诊断用）。
func allClusters(in []pt, leaf float32) [][]pt {
	voxID := make(map[vkey]int, len(in))
	var keys []vkey
	ptVk := make([]vkey, len(in))
	member := make(map[vkey][]int)
	for i, q := range in {
		k := vkey{ifloor(q.x, leaf), ifloor(q.y, leaf), ifloor(q.z, leaf)}
		ptVk[i] = k
		if _, ok := voxID[k]; !ok {
			voxID[k] = len(keys)
			keys = append(keys, k)
		}
		member[k] = append(member[k], i)
	}
	seen := make([]bool, len(keys))
	var comps [][]vkey
	stack := make([]int, 0, 256)
	for s := range keys {
		if seen[s] {
			continue
		}
		stack = stack[:0]
		stack = append(stack, s)
		seen[s] = true
		var comp []vkey
		for len(stack) > 0 {
			cur := stack[len(stack)-1]
			stack = stack[:len(stack)-1]
			comp = append(comp, keys[cur])
			c := keys[cur]
			for dx := int32(-1); dx <= 1; dx++ {
				for dy := int32(-1); dy <= 1; dy++ {
					for dz := int32(-1); dz <= 1; dz++ {
						if id, ok := voxID[vkey{c[0] + dx, c[1] + dy, c[2] + dz}]; ok && !seen[id] {
							seen[id] = true
							stack = append(stack, id)
						}
					}
				}
			}
		}
		comps = append(comps, comp)
	}
	out := make([][]pt, 0, len(comps))
	for _, comp := range comps {
		var ps []pt
		for _, k := range comp {
			for _, idx := range member[k] {
				ps = append(ps, in[idx])
			}
		}
		out = append(out, ps)
	}
	for i := 0; i < len(out); i++ {
		for j := i + 1; j < len(out); j++ {
			if len(out[j]) > len(out[i]) {
				out[i], out[j] = out[j], out[i]
			}
		}
	}
	return out
}

func bboxOf(ps []pt) [2][3]float32 {
	var mn, mx [3]float32
	for i := 0; i < 3; i++ {
		mn[i], mx[i] = math.MaxFloat32, -math.MaxFloat32
	}
	for _, q := range ps {
		v := [3]float32{q.x, q.y, q.z}
		for j := 0; j < 3; j++ {
			if v[j] < mn[j] {
				mn[j] = v[j]
			}
			if v[j] > mx[j] {
				mx[j] = v[j]
			}
		}
	}
	return [2][3]float32{mn, mx}
}
