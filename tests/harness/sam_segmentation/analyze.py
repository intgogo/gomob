"""analyze — 读 seg_bench 的 metrics.json,判定 SAM 分割质量(正常/异常)。

硬门:IoU ≥ 0.92(对齐 TODO M3.17「SAM mask 与人工标注 IoU ≥ 0.92」)。
退出码:0=过(正常);1=失败(异常);2=metrics.json 缺失。
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(__file__)
IOU_MIN = 0.92


def main() -> int:
    out_dir = (sys.argv[1] if len(sys.argv) > 1 else
               os.environ.get("OUTPUT_DIR", os.path.join(HERE, "..", "..", "..", ".dev", "sam_segmentation")))
    path = os.path.join(out_dir, "metrics.json")
    if not os.path.isfile(path):
        print(f"[analyze] 缺 metrics.json:{path}(先跑 seg_bench.py)", file=sys.stderr)
        return 2
    with open(path) as fh:
        m = json.load(fh)

    iou = m["iou"]
    ok = iou >= IOU_MIN
    print("=== M3.17 HQ-SAM 分割质量判定 ===")
    print(f"模型 {m.get('model_type')}:IoU={iou} 模型置信={m.get('model_score')} "
          f"pred/gt 面积={m.get('pred_area')}/{m.get('gt_area')}")
    print(f"IoU {iou} ≥ {IOU_MIN}: {'✓' if ok else '✗'}")
    verdict = "正常" if ok else "异常"
    print(f"\n>>> {verdict}:HQ-SAM 高精度分割 "
          f"{'达标(框提示→高保真 mask)' if ok else '未达标,查权重/提示/前景背景对比度'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
