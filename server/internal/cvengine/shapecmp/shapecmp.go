// Package shapecmp —— 3D 外廓"元数据级"质量比对（M-S9.x cv-engine 接入）。
//
// 当前阶段不解析 mesh 二进制（.ply / .glb），而是基于已知元数据：
//
//	triangle_count / point_count / bbox 6 维 / coverage / qc_score
//
// 计算 Scan vs Ref 之间的几个稳健比值与 3D bbox IoU，然后按阈值出 verdict。
//
// 真"几何级"比对（chamfer / Hausdorff / 点云覆盖图）将在后续阶段叠加，本包契约不变：
// 调用方传 ScanMetadata + RefMetadata，得 Result。Mesh 解析层补完后只是把 Result 里
// 的 GeoMetrics 字段填上，元数据级指标不动。
package shapecmp

import (
	"math"
)

// Metadata 外廓元数据（Scan / Ref 共用）。
type Metadata struct {
	TriangleCount int64    // 三角面数（≥0；0 表示缺）
	PointCount    int64    // 顶点数（≥0；0 表示缺）
	BBox          *BBox    // 包围盒（nil 表示缺，IoU 不参与计算）
	Coverage      *float32 // 0..1 重建覆盖率（nil 表示缺）
	QCScore       *float32 // 0..1 端侧 QC 分（nil 表示缺）
}

// BBox 3D 包围盒。
type BBox struct {
	MinX, MinY, MinZ float32
	MaxX, MaxY, MaxZ float32
}

// Volume 包围盒体积。退化（min>max）返 0。
func (b *BBox) Volume() float64 {
	dx := float64(b.MaxX - b.MinX)
	dy := float64(b.MaxY - b.MinY)
	dz := float64(b.MaxZ - b.MinZ)
	if dx <= 0 || dy <= 0 || dz <= 0 {
		return 0
	}
	return dx * dy * dz
}

// IoU3D 两个 bbox 的 3D 交并比 (intersection / union)。
//
// 任一退化（Volume=0）→ 返 0。
func IoU3D(a, b *BBox) float64 {
	if a == nil || b == nil {
		return 0
	}
	va, vb := a.Volume(), b.Volume()
	if va == 0 || vb == 0 {
		return 0
	}
	ix := float64(min32(a.MaxX, b.MaxX) - max32(a.MinX, b.MinX))
	iy := float64(min32(a.MaxY, b.MaxY) - max32(a.MinY, b.MinY))
	iz := float64(min32(a.MaxZ, b.MaxZ) - max32(a.MinZ, b.MinZ))
	if ix <= 0 || iy <= 0 || iz <= 0 {
		return 0
	}
	inter := ix * iy * iz
	union := va + vb - inter
	if union <= 0 {
		return 0
	}
	return inter / union
}

func min32(a, b float32) float32 { if a < b { return a }; return b }
func max32(a, b float32) float32 { if a > b { return a }; return b }

// Metrics 元数据级指标。所有比值"以 Ref 为基准"。
//
//	TriRatio / PointRatio  ratio = Scan / Ref（≥0；Ref=0 时返 0 + flag missing）
//	BBoxVolumeRatio        体积比（同上）
//	BBoxIoU                3D IoU（任一缺 → 0；调用方按 reasons 区分"缺数据"和"完全错位"）
//	CoverageDiff           Scan.Coverage - Ref.Coverage（若 Ref 缺则 NaN→ 0）
//	QCScoreDiff            同上
//
// 所有 ratio 指标向 1 趋近为相似；CoverageDiff 取 0~略负 为可接受。
type Metrics struct {
	TriRatio        float64
	PointRatio      float64
	BBoxVolumeRatio float64
	BBoxIoU         float64
	CoverageDiff    float64
	QCScoreDiff     float64

	// 缺数据标记（影响 verdict 决策）
	TriMissing      bool
	PointMissing    bool
	BBoxMissing     bool
	CoverageMissing bool
	QCMissing       bool
}

// Compute 元数据指标。
func Compute(scan, ref Metadata) Metrics {
	m := Metrics{}
	if ref.TriangleCount > 0 && scan.TriangleCount > 0 {
		m.TriRatio = float64(scan.TriangleCount) / float64(ref.TriangleCount)
	} else {
		m.TriMissing = true
	}
	if ref.PointCount > 0 && scan.PointCount > 0 {
		m.PointRatio = float64(scan.PointCount) / float64(ref.PointCount)
	} else {
		m.PointMissing = true
	}
	if scan.BBox == nil || ref.BBox == nil {
		m.BBoxMissing = true
	} else {
		va, vb := scan.BBox.Volume(), ref.BBox.Volume()
		if vb > 0 && va > 0 {
			m.BBoxVolumeRatio = va / vb
		}
		m.BBoxIoU = IoU3D(scan.BBox, ref.BBox)
	}
	if scan.Coverage == nil || ref.Coverage == nil {
		m.CoverageMissing = true
	} else {
		m.CoverageDiff = float64(*scan.Coverage - *ref.Coverage)
	}
	if scan.QCScore == nil || ref.QCScore == nil {
		m.QCMissing = true
	} else {
		m.QCScoreDiff = float64(*scan.QCScore - *ref.QCScore)
	}
	return m
}

// Score 元数据综合得分（0..1，越大越像）。
//
// 以"接近 1 的 ratio + 高 IoU"为高分。各分量贡献：
//
//	bbox_iou × 0.40   (主导 — 形状是否对齐)
//	tri_score × 0.20  (1 - clamp(|tri_ratio - 1|, 0, 1))
//	point_score × 0.20
//	coverage_score × 0.10  (clamp01(1 + coverage_diff)，scan 更高 → 更好)
//	qc_score × 0.10
//
// 任何"missing"分量按权重权值 0 重新归一化（不强行扣分）。
func (m Metrics) Score() float64 {
	type comp struct {
		val    float64
		weight float64
	}
	parts := []comp{}

	if !m.BBoxMissing && m.BBoxIoU >= 0 {
		parts = append(parts, comp{val: clamp01(m.BBoxIoU), weight: 0.40})
	}
	if !m.TriMissing {
		parts = append(parts, comp{val: ratioScore(m.TriRatio), weight: 0.20})
	}
	if !m.PointMissing {
		parts = append(parts, comp{val: ratioScore(m.PointRatio), weight: 0.20})
	}
	if !m.CoverageMissing {
		// coverage_diff < 0 是 scan 比 ref 差；clamp 到 [0, 1]
		parts = append(parts, comp{val: clamp01(1 + m.CoverageDiff), weight: 0.10})
	}
	if !m.QCMissing {
		parts = append(parts, comp{val: clamp01(1 + m.QCScoreDiff), weight: 0.10})
	}
	if len(parts) == 0 {
		return 0
	}
	num, den := 0.0, 0.0
	for _, p := range parts {
		num += p.val * p.weight
		den += p.weight
	}
	if den <= 0 {
		return 0
	}
	return num / den
}

// ratioScore ratio→[0,1]，1.0 时为 1，偏离 1 单调下降；|delta|≥1 时为 0。
func ratioScore(r float64) float64 {
	if r <= 0 {
		return 0
	}
	d := math.Abs(r - 1)
	if d >= 1 {
		return 0
	}
	return 1 - d
}

func clamp01(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

// Verdict 阈值决策（与 vin_pipeline 共用 pass / warning / fail 三态）。
//
// 默认阈值（与 cv-engine handler 默认 0.85/0.60 一致）：
//
//	pass:    score >= passTh AND bbox_iou (有时) >= 0.7
//	warning: score >= warnTh
//	fail:    其它
//
// reasons 描述决策原因，便于排错。
func Verdict(m Metrics, score, passTh, warnTh float64) (verdict string, reasons []string) {
	switch {
	case score >= passTh && (m.BBoxMissing || m.BBoxIoU >= 0.7):
		verdict = "pass"
	case score >= warnTh:
		verdict = "warning"
		reasons = append(reasons, "below_pass_threshold")
	default:
		verdict = "fail"
		reasons = append(reasons, "below_warn_threshold")
	}

	if !m.BBoxMissing && m.BBoxIoU < 0.5 && verdict == "pass" {
		// 元数据吻合但 bbox IoU 低 — 不让"伪 pass"
		verdict = "warning"
		reasons = append(reasons, "bbox_iou_low")
	}
	if !m.CoverageMissing && m.CoverageDiff < -0.2 {
		reasons = append(reasons, "coverage_significantly_below_ref")
	}
	if !m.TriMissing && (m.TriRatio < 0.5 || m.TriRatio > 2.0) {
		reasons = append(reasons, "tri_count_out_of_band")
	}
	if m.BBoxMissing {
		reasons = append(reasons, "bbox_missing")
	}
	return verdict, reasons
}
