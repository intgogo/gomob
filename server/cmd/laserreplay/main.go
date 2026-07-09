// laserreplay = 激光外廓测量离线复算工具（M13，开发/harness 用）。
// 对已落盘的扫描点云（unit_a/unit_b/背景 PCD）用与 laserworker 完全相同的生产管线复算外廓：
// B→A 点到面精修 → 融合重建 → 背景相减 → 持久地面(从背景拟合) → 测量（鲁棒分位跨度 + 1mm bin +
// 支撑面车高）。输出 JSON 供 harness 判定（tests/harness/laser_repeatability）。
//
// 用法:
//
//	laserreplay -unit-a a.pcd -unit-b b.pcd -bg bg.pcd -init-btoa "r00,r01,...,15 个逗号分隔" [-no-refine]
//
// 或直接对已融合云复算（跳过精修/融合）:
//
//	laserreplay -fused fused.pcd -bg bg.pcd
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strconv"
	"strings"

	"io.gomob/server/internal/laser"
)

func main() {
	unitA := flag.String("unit-a", "", "unit_a PCD（A 世界系 mm）")
	unitB := flag.String("unit-b", "", "unit_b PCD（B 设备系 mm）")
	fused := flag.String("fused", "", "已融合 PCD（跳过精修/融合直接测量）")
	bgPath := flag.String("bg", "", "空工位背景 PCD（必填：背景相减 + 持久地面 + 支撑面）")
	initBToA := flag.String("init-btoa", "", "B→A 初值 16 个逗号分隔(行主序)；-unit-a/-unit-b 模式必填")
	noRefine := flag.Bool("no-refine", false, "跳过点到面精修（对照基线用）")
	flag.Parse()

	if *bgPath == "" {
		fatal("必须提供 -bg 空工位背景")
	}
	bg := loadPCD(*bgPath)

	// 持久地面语义：从背景云拟合一次（与 runner 背景采集持久化同一来源）。
	ground := laser.DetectGround(bg, laser.DefaultGroundParams())
	if !ground.Valid {
		fatal("背景云地面拟合失败")
	}

	out := map[string]any{"ground": ground}
	var cloudFus []float32
	switch {
	case *fused != "":
		cloudFus = loadPCD(*fused)
	case *unitA != "" && *unitB != "":
		a := loadPCD(*unitA)
		b := loadPCD(*unitB)
		init, err := parseMat16(*initBToA)
		if err != nil {
			fatal("解析 -init-btoa 失败: " + err.Error())
		}
		bToA := init
		if !*noRefine {
			var st laser.RefineBToAStats
			bToA, st = laser.RefineBToA(a, b, init, laser.DefaultRefineBToAParams())
			out["b_to_a_refine"] = st
		}
		out["b_to_a"] = bToA
		cloudFus = append(append([]float32(nil), a...), transform(b, bToA)...)
	default:
		fatal("要么 -fused，要么 -unit-a + -unit-b + -init-btoa")
	}

	fg := laser.SubtractBackground(cloudFus, bg, laser.DefaultBackgroundParams())
	out["fg_pts"] = len(fg) / 3

	// 与 runner bg_subtract 路径完全一致的测量参数（M13）。
	mp := laser.GroundMeasureParams([3]float32{ground.NX, ground.NY, ground.NZ}, ground.D, 30, 5000)
	mp.SupportBG = bg
	mp.WidthSupportFrac = 0.15
	mp.WidthBinMM = 1
	mp.SpanTrimPct = 0.5
	dims := laser.Measure(fg, mp)
	out["measure"] = dims

	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", " ")
	_ = enc.Encode(out)
	if !dims.Valid {
		os.Exit(2)
	}
}

func loadPCD(path string) []float32 {
	raw, err := os.ReadFile(path)
	if err != nil {
		fatal(err.Error())
	}
	xyz, err := laser.DecodePCDBinary(raw)
	if err != nil {
		fatal(path + ": " + err.Error())
	}
	return xyz
}

func parseMat16(s string) ([16]float32, error) {
	var m [16]float32
	parts := strings.Split(s, ",")
	if len(parts) != 16 {
		return m, fmt.Errorf("需要 16 个数，得 %d", len(parts))
	}
	for i, p := range parts {
		v, err := strconv.ParseFloat(strings.TrimSpace(p), 32)
		if err != nil {
			return m, err
		}
		m[i] = float32(v)
	}
	return m, nil
}

func transform(xyz []float32, m [16]float32) []float32 {
	out := make([]float32, len(xyz))
	for i := 0; i+2 < len(xyz); i += 3 {
		x, y, z := xyz[i], xyz[i+1], xyz[i+2]
		out[i] = m[0]*x + m[1]*y + m[2]*z + m[3]
		out[i+1] = m[4]*x + m[5]*y + m[6]*z + m[7]
		out[i+2] = m[8]*x + m[9]*y + m[10]*z + m[11]
	}
	return out
}

func fatal(msg string) {
	fmt.Fprintln(os.Stderr, "laserreplay: "+msg)
	os.Exit(1)
}
