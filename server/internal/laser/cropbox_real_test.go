package laser

import (
	"os"
	"testing"
)

// 真机数据验证：用 scan24 融合云(594k点，含办公室背景)验证"框裁剪+框内测量"管线在真实点分布上
// 不退化——给定车位框，裁剪是严格子集(深度隔离背景)、主簇测量产出有效正尺寸。
// 不在 CI 默认跑：依赖 .dev 真机资产；缺文件即 skip。资产由 docs 记录的 scan24 下载流程产出：
//
//	curl .../v1/scans/laser/24/cloud/fused -o .dev/measure-roi/scan24/fused.pcd
//
// 车的确切尺寸真值留给端到端(用户在 UI 画框、已知车型)；此处只锁管线机制。
func TestCropBoxMeasureRealData(t *testing.T) {
	path := os.Getenv("LASER_REAL_FUSED")
	if path == "" {
		path = "../../../.dev/measure-roi/scan24/fused.pcd"
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Skipf("无真机融合云(%s)，跳过：%v", path, err)
	}
	xyz, err := DecodePCDBinary(raw)
	if err != nil {
		t.Fatalf("解码 fused.pcd 失败: %v", err)
	}
	total := len(xyz) / 3
	if total < 100000 {
		t.Fatalf("融合云点数异常少(%d)，资产可能损坏", total)
	}

	// scan24 自动地面法向(stats.ground)作 up：朝上但拟到了天花，仍是该云里最接近"竖直"的方向。
	// 框心取云质心、车位尺度半尺(右2.5m/前3m/上2m)——代表"两单元间一块车位体"。
	box := CropBox{
		Center: centroid(xyz),
		Up:     [3]float32{-0.015598887, 0.08109407, 0.99658436},
		YawDeg: 0,
		Half:   [3]float32{2500, 3000, 2000},
	}
	// 裁剪必须是严格子集(框比整个办公室小很多)——证明按深度隔离了背景。
	in := CropToBox(xyz, box)
	inN := len(in) / 3
	if inN == 0 || inN >= total {
		t.Fatalf("框裁剪应为严格子集，得 框内=%d 全云=%d", inN, total)
	}
	t.Logf("框裁剪: 全云 %d → 框内 %d (%.0f%%)", total, inN, 100*float64(inN)/float64(total))

	// 框内测量：主簇→ROR→OBB 不退化，产出有效正尺寸。
	d := Measure(xyz, CropBoxMeasureParams(box))
	if !d.Valid {
		t.Fatalf("框内测量应有效，得 %+v", d)
	}
	if d.LengthMM <= 0 || d.WidthMM <= 0 || d.HeightMM <= 0 {
		t.Errorf("尺寸应为正，得 L=%.0f W=%.0f H=%.0f", d.LengthMM, d.WidthMM, d.HeightMM)
	}
	// 尺寸应被框约束(不超框对角)——证明测的是框内物体而非整场。
	if d.LengthMM > 2*box.Half[1]*1.05 || d.WidthMM > 2*box.Half[0]*1.05 || d.HeightMM > 2*box.Half[2]*1.05 {
		t.Errorf("尺寸超出框边界，框裁剪未生效: L=%.0f W=%.0f H=%.0f 框半尺=%v", d.LengthMM, d.WidthMM, d.HeightMM, box.Half)
	}
	// 主簇应是框内主体(连通簇剥离散点后仍占可观比例)。
	if d.BodyRatio < 0.05 {
		t.Errorf("主簇占比过低(%.3f)，框内全是散噪？", d.BodyRatio)
	}
	t.Logf("框内测量: L=%.0f W=%.0f H=%.0f mm  ROI点=%d 主簇=%d(%.0f%%) OBB角=%.1f°",
		d.LengthMM, d.WidthMM, d.HeightMM, d.ROIPts, d.BodyPts, 100*d.BodyRatio, d.OBBAngleDeg)
}

func centroid(xyz []float32) [3]float32 {
	n := len(xyz) / 3
	var sx, sy, sz float64
	for i := 0; i < n; i++ {
		sx += float64(xyz[3*i])
		sy += float64(xyz[3*i+1])
		sz += float64(xyz[3*i+2])
	}
	f := float32(n)
	return [3]float32{float32(sx) / f, float32(sy) / f, float32(sz) / f}
}
