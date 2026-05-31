"""analyze — 读 quality_bench 的 metrics.json,输出可判定结论(正常 / 警告 / 异常)。

硬门(决定 exit code)= 重建"扫描真实化"的本质:精度 + 完整度。
  ① mesh chamfer ≤ 5.0mm           (实测 ~1.76mm 稳定;配准+PGO+TSDF 几何正确)
  ② coverage@5mm ≥ 88%             (实测 ~96%;voxel 尺度完整度,留 ~8pp 余量)
  ③ coverage@10mm ≥ 94%            (实测 ~99.8%;观测面无大空洞)

软报告(只警告、不判异常):
  - UV atlas 利用率:实测 iso-charts 与 xatlas 在 Bunny 这类 marching-cubes 有机网格上都只 ~62–70%
    (小 chart 多、曲边界难密铺单位方),TODO 原定 ≥70% 不可达 → 软监测:跌破 UV_SOFT_FLOOR(60%)
    才警告(正常工作区间在其之上;< 60% 提示 chart 爆炸 / 展开退化)。详见 README「UV 利用率为何软门」。
  - 精度/完整度分量(accuracy/completeness mm):对称 chamfer 会把两个方向平均掉,单列出来便于定位
    是飞点/伪几何(精度高)还是漏洞/翻转(完整度高)。
  - 配准退化预警:chamfer 虽 ≤5mm 但 > 4mm 时提示(旧 12mm/30mm 误配翻转会跳到 ~7.5mm,>4mm 是先兆区)。

真实卡车数据:status=ok 报告统计;skipped 非失败(数据未就绪);error 警告。

退出码:0 = 硬门全过(正常);1 = 硬门失败(异常);2 = metrics.json 缺失/损坏(quality_bench 未产出)。
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(__file__)

CHAMFER_MAX_MM = 5.0
CHAMFER_WARN_MM = 4.0       # ≤5mm 过门但 >4mm:配准退化先兆(误配翻转跳到 ~7.5mm)
COV5_MIN_PCT = 88.0
COV10_MIN_PCT = 94.0
UV_SOFT_FLOOR_PCT = 60.0   # UV 利用率正常区间 ~62–70%;跌破此值才警告(展开退化/chart 爆炸)


def main() -> int:
    # `./dev.sh harness` 用系统 python3 调本脚本并把 out_dir 作 argv[1] 传入;兼容 OUTPUT_DIR 环境变量。
    out_dir = (sys.argv[1] if len(sys.argv) > 1 else
               os.environ.get("OUTPUT_DIR", os.path.join(HERE, "..", "..", "..", ".dev", "scan_multiview_quality")))
    path = os.path.join(out_dir, "metrics.json")
    if not os.path.isfile(path):
        print(f"[analyze] 缺 metrics.json:{path}(先跑 quality_bench.py)", file=sys.stderr)
        return 2
    with open(path) as fh:
        m = json.load(fh)

    s = m["synthetic"]
    chamfer = s["chamfer_mm"]
    accuracy = s.get("accuracy_mm")
    completeness = s.get("completeness_mm")
    cov5 = s["coverage_pct"]["5.0mm"]
    cov10 = s["coverage_pct"]["10.0mm"]
    uv = s["uv_utilization_pct"]

    ok_cham = chamfer <= CHAMFER_MAX_MM
    ok_cov5 = cov5 >= COV5_MIN_PCT
    ok_cov10 = cov10 >= COV10_MIN_PCT
    uv_warn = uv < UV_SOFT_FLOOR_PCT
    cham_warn = ok_cham and chamfer > CHAMFER_WARN_MM

    print("=== M3.16 多视角重建质量判定 ===")
    print(f"合成 Bunny:{s['n_views']} 视角 voxel{s['voxel_mm']}mm → fused "
          f"{s['fused']['vertices']} 顶点 / {s['fused']['triangles']} 面 / {s['fused']['fusion_ms']}ms")
    cham_line = f"① chamfer {chamfer}mm ≤ {CHAMFER_MAX_MM}mm: {'✓' if ok_cham else '✗'}"
    if accuracy is not None and completeness is not None:
        cham_line += f"  (精度 {accuracy}mm / 完整 {completeness}mm)"
    print(cham_line)
    if cham_warn:
        print(f"   ⚠ chamfer > {CHAMFER_WARN_MM}mm:配准退化先兆,查 reg_voxel/reg_corr 是否被改回粗固定值")
    print(f"② coverage@5mm {cov5}% ≥ {COV5_MIN_PCT}%: {'✓' if ok_cov5 else '✗'}")
    print(f"③ coverage@10mm {cov10}% ≥ {COV10_MIN_PCT}%: {'✓' if ok_cov10 else '✗'}")
    print(f"   coverage 曲线: {s['coverage_pct']}")
    print(f"[软] UV atlas 利用率 {uv}%({s['uv_method']}):"
          f"{'⚠ < ' + str(UV_SOFT_FLOOR_PCT) + '% 地板,查 UV 展开退化/chart 爆炸' if uv_warn else '正常区间(有机 MC 网格 ~62–70%,见 README)'}")

    truck = m.get("truck", {})
    st = truck.get("status")
    if st == "ok":
        print(f"真实卡车:✓ frames={truck['frame_count']} fused {truck['fused']['vertices']} 顶点 / UV {truck.get('uv_utilization_pct')}%(无 GT,仅统计)")
    elif st == "error":
        print(f"真实卡车:⚠ 重建出错 {truck.get('error')}")
    else:
        print(f"真实卡车:— 跳过({truck.get('reason', '未提供')})")

    hard_ok = ok_cham and ok_cov5 and ok_cov10
    verdict = "正常" if hard_ok else "异常"
    extra = " (UV 软报告偏低)" if (hard_ok and uv_warn) else ""
    print(f"\n>>> {verdict}{extra}:多视角重建 "
          f"{'精度+完整度达标' if hard_ok else '精度/完整度未达标,查配准/PGO/voxel'}")
    return 0 if hard_ok else 1


if __name__ == "__main__":
    sys.exit(main())
