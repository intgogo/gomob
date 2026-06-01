"""analyze — 读 mask_fusion_bench 的 metrics.json,输出可判定结论(正常 / 警告 / 异常)。

硬门(决定 exit code)= M3.17 ① 的本质:SAM 分割准 + mask 引导只重建目标、不漏背景。
  ① sam_iou_mean ≥ 0.95            各视角 SAM mask vs GT 目标的 IoU 均值(实测 ~0.994)
  ② masked.chamfer ≤ 5.0mm         mask 引导重建 vs 目标观测面(实测 ~1.7,贴齐 GT-mask 上界)
  ③ masked.coverage@5mm ≥ 88%      目标被重建覆盖(实测 ~95%)
  ④ masked.contamination ≤ 2.0%    重建点离目标 >τ 的比例(背景污染;实测 ~0.01%)
  ⑤ baseline.contamination ≥ 30%   **价值证明门**:不带 mask 时杂物确实大量污染(实测 ~87%)。
                                     否则场景无可去杂物 → "masked 干净"不成立,harness 退化为空操作。
  ⑥ contrast:masked.contam ≤ baseline.contam / 5   mask 须把污染砍 ≥5×(实测 ~8000×)

软报告(只警告、不判异常):
  - GT-mask 上界对照:masked 污染比完美 mask 高 >3pp → SAM 边界质量先兆(考虑开 mask_erode_px)。
  - accuracy/completeness 分量:定位是飞点/伪几何(精度)还是漏洞/翻转(完整度)。
  - 最差视角 IoU:某视角 < 0.90 提示该视角框/分割偏差。

退出码:0 = 硬门全过;1 = 硬门失败;2 = metrics.json 缺失/损坏。
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(__file__)

SAM_IOU_MIN = 0.95
CHAMFER_MAX_MM = 5.0
COV5_MIN_PCT = 88.0
CONTAM_MAX_PCT = 2.0           # mask 引导后允许的残留背景污染上限
BASELINE_CONTAM_MIN_PCT = 30.0  # 价值证明门:baseline 必须被杂物显著污染,否则场景无效
CONTRAST_MIN_RATIO = 5.0        # mask 须把污染砍 ≥5×
GT_GAP_WARN_PCT = 3.0           # masked 比 GT-mask 上界多 >此 pp 污染 → SAM 边界先兆
VIEW_IOU_WARN = 0.90


def main() -> int:
    out_dir = (sys.argv[1] if len(sys.argv) > 1 else
               os.environ.get("OUTPUT_DIR", os.path.join(HERE, "..", "..", "..", ".dev", "scan_mask_fusion")))
    path = os.path.join(out_dir, "metrics.json")
    if not os.path.isfile(path):
        print(f"[analyze] 缺 metrics.json:{path}(先跑 mask_fusion_bench.py)", file=sys.stderr)
        return 2
    with open(path) as fh:
        m = json.load(fh)

    iou_mean = m["sam_iou_mean"]
    iou_views = m.get("sam_iou_per_view", [])
    masked, gtm, base = m["masked"], m.get("gt_masked", {}), m["baseline"]
    mc = masked["contamination_pct"]
    bc = base["contamination_pct"]
    cov5 = masked["coverage_pct"]["5.0mm"]

    ok_iou = iou_mean >= SAM_IOU_MIN
    ok_cham = masked["chamfer_mm"] <= CHAMFER_MAX_MM
    ok_cov5 = cov5 >= COV5_MIN_PCT
    ok_contam = mc <= CONTAM_MAX_PCT
    ok_baseline = bc >= BASELINE_CONTAM_MIN_PCT
    ok_contrast = (mc <= bc / CONTRAST_MIN_RATIO) if bc > 0 else False

    gt_gap = (mc - gtm["contamination_pct"]) if "contamination_pct" in gtm else 0.0
    gt_warn = gt_gap > GT_GAP_WARN_PCT
    worst_iou = min(iou_views) if iou_views else iou_mean
    view_warn = worst_iou < VIEW_IOU_WARN

    print("=== M3.17 ① SAM mask 引导融合判定 ===")
    print(f"场景:目标+地面+干扰,{m['n_views']} 视角 voxel{m['voxel_mm']}mm,τ污染={m['tau_contam_mm']}mm")
    print(f"① SAM IoU 均值 {iou_mean} ≥ {SAM_IOU_MIN}: {'✓' if ok_iou else '✗'}  "
          f"(最差视角 {round(worst_iou, 3)})")
    print(f"② mask 引导 chamfer {masked['chamfer_mm']}mm ≤ {CHAMFER_MAX_MM}mm: {'✓' if ok_cham else '✗'}"
          f"  (精度 {masked.get('accuracy_mm')}mm / 完整 {masked.get('completeness_mm')}mm)")
    print(f"③ mask 引导 coverage@5mm {cov5}% ≥ {COV5_MIN_PCT}%: {'✓' if ok_cov5 else '✗'}")
    print(f"④ mask 引导 污染 {mc}% ≤ {CONTAM_MAX_PCT}%: {'✓' if ok_contam else '✗'}  "
          f"(顶点 {masked['vertices']})")
    print(f"⑤ baseline 污染 {bc}% ≥ {BASELINE_CONTAM_MIN_PCT}%(价值证明:无 mask 确被杂物污染): "
          f"{'✓' if ok_baseline else '✗'}  (顶点 {base['vertices']}, chamfer {base['chamfer_mm']}mm)")
    ratio = round(bc / mc, 1) if mc > 0 else float("inf")
    print(f"⑥ 污染对照 masked≤baseline/{CONTRAST_MIN_RATIO}: {'✓' if ok_contrast else '✗'}  "
          f"(实际砍 {ratio}×)")
    if "contamination_pct" in gtm:
        print(f"[软] GT-mask 上界:chamfer {gtm['chamfer_mm']}mm / 污染 {gtm['contamination_pct']}%"
              f"{'  ⚠ SAM 比上界多 ' + str(round(gt_gap, 2)) + 'pp 污染(边界先兆,可评估 mask_erode_px)' if gt_warn else '(SAM 贴齐上界)'}")
    if view_warn:
        print(f"[软] ⚠ 最差视角 IoU {round(worst_iou, 3)} < {VIEW_IOU_WARN}:查该视角框/分割偏差")

    hard_ok = ok_iou and ok_cham and ok_cov5 and ok_contam and ok_baseline and ok_contrast
    verdict = "正常" if hard_ok else "异常"
    print(f"\n>>> {verdict}:SAM mask "
          f"{'引导融合只重建目标、剔除背景,价值成立' if hard_ok else '引导融合未达标,查 SAM 分割/mask 预掩/配准'}")
    return 0 if hard_ok else 1


if __name__ == "__main__":
    sys.exit(main())
