package laser

import (
	"encoding/binary"
	"math"
	"os"
	"strconv"
	"strings"
	"testing"
)

// measure_test.go = Go 测量内核验证。核心：对原厂真值会话 Data/100742 复算 LWH ≤2.5%
// （与 C++ host harness vehicle_measure 同基线，证 Go 镜像 == 已验证 C++ 内核）。+ 合成单测 + 合规。

// loadVendorPCD 读原厂 XYZRGB binary PCD（通用解析 FIELDS/SIZE 取 x/y/z）→ [x,y,z,...] mm。
func loadVendorPCD(t *testing.T, path string) []float32 {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Skipf("无真值会话 %s（设备/逆向数据不在本环境）: %v", path, err)
	}
	marker := []byte("DATA binary\n")
	hi := strings.Index(string(data), string(marker))
	if hi < 0 {
		t.Fatalf("%s 非 binary PCD", path)
	}
	hdr := string(data[:hi])
	body := data[hi+len(marker):]
	var fields []string
	var sizes []int
	npts := 0
	for _, ln := range strings.Split(hdr, "\n") {
		f := strings.Fields(strings.TrimRight(ln, "\r"))
		if len(f) == 0 {
			continue
		}
		switch f[0] {
		case "FIELDS":
			fields = f[1:]
		case "SIZE":
			for _, s := range f[1:] {
				n, _ := strconv.Atoi(s)
				sizes = append(sizes, n)
			}
		case "POINTS":
			npts, _ = strconv.Atoi(f[1])
		}
	}
	step, ox, oy, oz := 0, -1, -1, -1
	for i, fn := range fields {
		switch fn {
		case "x":
			ox = step
		case "y":
			oy = step
		case "z":
			oz = step
		}
		if i < len(sizes) {
			step += sizes[i]
		}
	}
	if ox < 0 || oy < 0 || oz < 0 || step == 0 {
		t.Fatalf("%s 缺 x/y/z 字段", path)
	}
	out := make([]float32, 0, npts*3)
	f32 := func(off int) float32 { return math.Float32frombits(binary.LittleEndian.Uint32(body[off : off+4])) }
	for i := 0; i < npts; i++ {
		b := i * step
		if b+step > len(body) {
			break
		}
		x, y, z := f32(b+ox), f32(b+oy), f32(b+oz)
		if !isFinite(x) || !isFinite(y) || !isFinite(z) {
			continue
		}
		out = append(out, x, y, z)
	}
	return out
}

func isFinite(v float32) bool { return !math.IsNaN(float64(v)) && !math.IsInf(float64(v), 0) }

func relErr(meas, truth float32) float64 {
	return math.Abs(float64(meas-truth)) / math.Abs(float64(truth)) * 100
}

const vendorSession = "/root/WindowsR/JCHY_OFFLINE/Data/100742"

// 真值（Result.ini）：1.pcd=(1777,533,759)，2.pcd=(1775,534,761)。
func TestMeasure_VendorGroundTruth(t *testing.T) {
	if p := os.Getenv("JCHY_DATA"); p != "" {
		// 允许覆盖会话目录
	}
	cases := []struct {
		file       string
		eL, eW, eH float32
	}{
		{"1.pcd", 1777, 533, 759},
		{"2.pcd", 1775, 534, 761},
	}
	const tol = 2.5 // % —— M9.2 达标线（<1% 待 M9.2b 反汇编 bound_box/carType，见 docs/16 §10）
	for _, c := range cases {
		pts := loadVendorPCD(t, vendorSession+"/"+c.file)
		d := Measure(pts, DefaultMeasureParams())
		if !d.Valid {
			t.Fatalf("%s 测量无效", c.file)
		}
		eL, eW, eH := relErr(d.LengthMM, c.eL), relErr(d.WidthMM, c.eW), relErr(d.HeightMM, c.eH)
		t.Logf("%s L=%.0f(%.1f%%) W=%.0f(%.1f%%) H=%.0f(%.1f%%) body=%d/%d ratio=%.2f ang=%.1f°",
			c.file, d.LengthMM, eL, d.WidthMM, eW, d.HeightMM, eH, d.BodyPts, d.RawPts, d.BodyRatio, d.OBBAngleDeg)
		if eL > tol || eW > tol || eH > tol {
			t.Errorf("%s 误差超 %.1f%%: L=%.1f%% W=%.1f%% H=%.1f%%", c.file, tol, eL, eW, eH)
		}
	}
}

// 融合 union（两镜头并）测量应仍贴车辆真值（同一辆车）。
func TestMeasure_FusedUnion(t *testing.T) {
	a := loadVendorPCD(t, vendorSession+"/1.pcd")
	b := loadVendorPCD(t, vendorSession+"/2.pcd")
	fused := append(append([]float32{}, a...), b...)
	d := Measure(fused, DefaultMeasureParams())
	if !d.Valid {
		t.Fatal("融合测量无效")
	}
	t.Logf("UNION L=%.0f W=%.0f H=%.0f body=%d", d.LengthMM, d.WidthMM, d.HeightMM, d.BodyPts)
	if relErr(d.LengthMM, 1777) > 2.5 || relErr(d.WidthMM, 533) > 3.0 || relErr(d.HeightMM, 759) > 2.5 {
		t.Errorf("融合 LWH 偏离车辆真值过大: L=%.0f W=%.0f H=%.0f", d.LengthMM, d.WidthMM, d.HeightMM)
	}
}

// 合成盒：旋转不变 + LWH 准（不依赖外部数据，CI 必跑）。
func TestMeasure_SyntheticBox(t *testing.T) {
	box := makeBoxGo(1800, 530, 760, 23, 400, 600, 50, 5)
	p := DefaultMeasureParams()
	p.UseROI = false // 合成坐标非真机 ROI
	d := Measure(box, p)
	if !d.Valid {
		t.Fatal("合成盒测量无效")
	}
	t.Logf("合成盒 L=%.1f W=%.1f H=%.1f", d.LengthMM, d.WidthMM, d.HeightMM)
	if math.Abs(float64(d.LengthMM-1800)) > 15 || math.Abs(float64(d.WidthMM-530)) > 15 || math.Abs(float64(d.HeightMM-760)) > 15 {
		t.Errorf("合成盒 LWH 不准: L=%.1f W=%.1f H=%.1f", d.LengthMM, d.WidthMM, d.HeightMM)
	}
}

func TestMeasure_WidthSupportKeepsDenseBodyWidth(t *testing.T) {
	box := makeBoxGo(1800, 530, 760, 23, 400, 600, 50, 5)
	p := DefaultMeasureParams()
	p.UseROI = false
	p.WidthSupportFrac = 0.15
	d := Measure(box, p)
	if !d.Valid {
		t.Fatal("合成盒测量无效")
	}
	if math.Abs(float64(d.WidthMM-530)) > 25 {
		t.Errorf("密集车体宽度不应被支撑闸削窄: W=%.1f", d.WidthMM)
	}
}

func TestMeasure_LargestClusterDropsDetached(t *testing.T) {
	body := makeBoxGo(1800, 530, 760, 0, 0, 0, 0, 8)
	blob := makeBoxGo(120, 120, 120, 0, 0, 4000, 0, 8) // 远在 +Y 不连通
	mixed := append(append([]float32{}, body...), blob...)
	p := DefaultMeasureParams()
	p.UseROI = false
	p.UseROR = false
	d := Measure(mixed, p)
	if !d.Valid || d.BodyPts != len(body)/3 {
		t.Fatalf("主簇应=车体点数 %d，得 %d", len(body)/3, d.BodyPts)
	}
	if d.LengthMM > 1900 { // 含 blob 会被拉到 ~4000
		t.Errorf("脱离 blob 未剔除，L=%.0f", d.LengthMM)
	}
}

// 地面相对测量（M9.10）：车体盒在规范地面 z=0，整云倾斜(绕X 12°/绕Y 8°)+平移到任意原点，
// 模拟真机底座坐标系（设备 +Z 非真竖直、原点任意）。喂检测到的地面法向，应仍复原 LWH。
func TestMeasure_GroundRelativeTilted(t *testing.T) {
	L, W, H := float32(1800), float32(530), float32(760)
	box := makeBoxGo(L, W, H, 23, 0, 0, 0, 8) // yaw23° 验 OBB 旋转不变；base 在 z=0=地面
	const axDeg, ayDeg = 12.0, 8.0
	Tx, Ty, Tz := float32(-8400), float32(3100), float32(1500) // 任意平移
	tilted := make([]float32, len(box))
	for i := 0; i+2 < len(box); i += 3 {
		x, y, z := rotXY(box[i], box[i+1], box[i+2], axDeg, ayDeg)
		tilted[i], tilted[i+1], tilted[i+2] = x+Tx, y+Ty, z+Tz
	}
	// 倾斜后地面法向 = R·(0,0,1)；平面过基点 R·0+T=T，故 d = -(n·T)。
	nx, ny, nz := rotXY(0, 0, 1, axDeg, ayDeg)
	d := -(nx*Tx + ny*Ty + nz*Tz)
	dm := Measure(tilted, GroundMeasureParams([3]float32{nx, ny, nz}, d, 30, 5000))
	if !dm.Valid {
		t.Fatal("地面相对测量无效")
	}
	t.Logf("倾斜地面盒 L=%.1f W=%.1f H=%.1f (真值 %.0f/%.0f/%.0f) ang=%.1f°",
		dm.LengthMM, dm.WidthMM, dm.HeightMM, L, W, H, dm.OBBAngleDeg)
	if math.Abs(float64(dm.LengthMM-L)) > 25 || math.Abs(float64(dm.WidthMM-W)) > 25 || math.Abs(float64(dm.HeightMM-H)) > 25 {
		t.Errorf("倾斜地面 LWH 不准: L=%.1f W=%.1f H=%.1f", dm.LengthMM, dm.WidthMM, dm.HeightMM)
	}
}

func TestMeasure_GroundRelativeElevatedObjectUsesBodySpan(t *testing.T) {
	L, W, H := float32(1800), float32(530), float32(760)
	box := makeBoxGo(L, W, H, 17, 0, 0, 300, 8)
	dm := Measure(box, GroundMeasureParams([3]float32{0, 0, 1}, 0, 30, 5000))
	if !dm.Valid {
		t.Fatal("离地物体测量无效")
	}
	t.Logf("离地物体 L=%.1f W=%.1f H=%.1f", dm.LengthMM, dm.WidthMM, dm.HeightMM)
	if math.Abs(float64(dm.HeightMM-H)) > 25 {
		t.Errorf("离地物体车高应取自身高度 %.0f，得 %.1f", H, dm.HeightMM)
	}
}

// rotXY 先绕 X 转 axDeg、再绕 Y 转 ayDeg。正交旋转保长度，故离地高 h=n·p+d 恒等于原 z。
func rotXY(x, y, z float32, axDeg, ayDeg float64) (float32, float32, float32) {
	ax, ay := axDeg*math.Pi/180, ayDeg*math.Pi/180
	cx, sx := math.Cos(ax), math.Sin(ax)
	x1, y1, z1 := float64(x), float64(y)*cx-float64(z)*sx, float64(y)*sx+float64(z)*cx
	cy, sy := math.Cos(ay), math.Sin(ay)
	return float32(x1*cy + z1*sy), float32(y1), float32(-x1*sy + z1*cy)
}

func TestCheckCompliance(t *testing.T) {
	lim := DefaultLimits()
	if c := CheckCompliance(Dimensions{LengthMM: 11000, WidthMM: 2500, HeightMM: 3900, Valid: true}, lim); !c.Determined || !c.Compliant {
		t.Errorf("合规车应判合规: %v", c.Violations)
	}
	c := CheckCompliance(Dimensions{LengthMM: 13000, WidthMM: 2600, HeightMM: 4100, Valid: true}, lim)
	if !c.Determined || c.Compliant || len(c.Violations) != 3 {
		t.Errorf("超限车应判 3 项违规，得 %v", c.Violations)
	}
	if c := CheckCompliance(Dimensions{Valid: false}, lim); c.Determined || c.Compliant {
		t.Error("无效测量不应判合规")
	}
	if c := ComplianceForVehicleType(Dimensions{LengthMM: 11000, WidthMM: 2500, HeightMM: 3900, Valid: true}, -1); c.Determined || c.Reason != "vehicle_type_missing" {
		t.Fatalf("缺车型必须未判定，got=%+v", c)
	}
	if c := ComplianceForVehicleType(Dimensions{LengthMM: 11000, WidthMM: 2500, HeightMM: 3900, Valid: true}, 1); c.Determined || c.Reason != "rule_unavailable" {
		t.Fatalf("缺法规规则必须未判定，got=%+v", c)
	}
}

// makeBoxGo 盒表面密采点（mm），yaw 绕 Z 旋转后平移到 (cx,cy,cz)，step 间距。
func makeBoxGo(L, W, H, yawDeg, cx, cy, cz, step float32) []float32 {
	r := float64(yawDeg) * math.Pi / 180
	cs, sn := float32(math.Cos(r)), float32(math.Sin(r))
	var out []float32
	add := func(x, y, z float32) {
		out = append(out, x*cs-y*sn+cx, x*sn+y*cs+cy, z+cz)
	}
	for x := float32(0); x <= L; x += step {
		for y := float32(0); y <= W; y += step {
			add(x, y, 0)
			add(x, y, H)
		}
		for z := float32(0); z <= H; z += step {
			add(x, 0, z)
			add(x, W, z)
		}
	}
	for y := float32(0); y <= W; y += step {
		for z := float32(0); z <= H; z += step {
			add(0, y, z)
			add(L, y, z)
		}
	}
	return out
}
