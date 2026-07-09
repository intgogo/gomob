#!/usr/bin/env python3
"""laser_repeatability/analyze.py — 外廓测量重复性/准确度判定（M13）。

输入: run.sh 拉的 stats.jsonl（每行一个 job 的测量 stats 摘要，倒序）。
判定（按 measure_mode 分组，取最近的同模式连续段）:
  重复性: 组内 ≥3 次有效测量，L/W/H 的 σ ≤ 5mm 且 极差 ≤ 15mm → 正常；超一档 → 警告；再超 → 异常。
  准确度(可选): GOMOB_LASER_TRUTH_LWH="L,W,H" 时，|均值−真值| ≤1% 正常 / ≤2% 警告 / >2% 异常。
  卫生检查: ground_source 应为 persisted（否则警告"地面未持久化，方差会回来"）；
            b_to_a_refine.applied 应为 true；refine_dt 持续 >100mm → 警告"site 标记外参偏差大，建议现场重标"。

深挖单次扫描（对落盘 PCD 用生产管线复算）:
  server/cmd/laserreplay -unit-a a.pcd -unit-b b.pcd -bg bg.pcd -init-btoa "16 逗号分隔"
  （PCD 从 MinIO gomob-assets/laser-scans/ 取出后需剥 MinIO streaming-bitrot 头:
   每 [32B hash][≤1MiB data] 块去掉前 32B。）
"""
import json
import math
import os
import sys


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else ".dev/laser_repeatability/stats.jsonl"
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    if not rows:
        print("异常: 无采样数据（该工位还没有 done 扫描）")
        return 1

    # 取最近的同 measure_mode 连续段（跨模式混算没有意义）
    mode = rows[0]["mode"]
    seg = []
    for r in rows:
        if r["mode"] != mode:
            break
        if r.get("valid"):
            seg.append(r)
    print(f"最近同模式段: mode={mode} 有效测量 {len(seg)} 次 (job {seg[-1]['id']}..{seg[0]['id']})" if seg
          else f"最近同模式段: mode={mode} 无有效测量")

    verdict = "正常"
    reasons = []

    if len(seg) < 3:
        print(f"警告: 有效测量不足 3 次({len(seg)})，无法判重复性——先连扫 3 次同一物体")
        verdict = "警告"
        reasons.append("样本不足")
    else:
        for key, name in (("l", "车长"), ("w", "车宽"), ("h", "车高")):
            vals = [r[key] for r in seg]
            mean = sum(vals) / len(vals)
            sd = math.sqrt(sum((v - mean) ** 2 for v in vals) / len(vals))
            rng = max(vals) - min(vals)
            level = "正常" if (sd <= 5 and rng <= 15) else ("警告" if (sd <= 10 and rng <= 30) else "异常")
            print(f"  {name}: 均值 {mean:.1f}mm σ={sd:.1f}mm 极差={rng:.1f}mm → {level}")
            if level != "正常":
                reasons.append(f"{name}重复性{level}(σ={sd:.1f}/极差={rng:.1f})")
                verdict = worst(verdict, level)

        truth = os.environ.get("GOMOB_LASER_TRUTH_LWH", "")
        if truth:
            t = [float(x) for x in truth.split(",")]
            for key, name, tv in (("l", "车长", t[0]), ("w", "车宽", t[1]), ("h", "车高", t[2])):
                vals = [r[key] for r in seg]
                mean = sum(vals) / len(vals)
                errpct = abs(mean - tv) / tv * 100
                level = "正常" if errpct <= 1 else ("警告" if errpct <= 2 else "异常")
                print(f"  {name} vs 真值{tv:.0f}: 偏差 {mean - tv:+.1f}mm ({errpct:.2f}%) → {level}")
                if level != "正常":
                    reasons.append(f"{name}准确度{level}({errpct:.2f}%)")
                    verdict = worst(verdict, level)
        else:
            print("  (未设 GOMOB_LASER_TRUTH_LWH，跳过准确度判定)")

    # 卫生检查
    if seg:
        if any(r.get("ground_source") not in ("persisted",) for r in seg):
            print("警告: 存在未用持久化地面的扫描(ground_source≠persisted)——重采一次空工位背景以入库地面")
            verdict = worst(verdict, "警告")
            reasons.append("地面未持久化")
        if any(r.get("refine_applied") is not True for r in seg):
            print("警告: 存在 B→A 精修未采纳的扫描(沿用 native 外参，对立面偏置未修)")
            verdict = worst(verdict, "警告")
            reasons.append("精修未采纳")
        dts = [r["refine_dt"] for r in seg if r.get("refine_dt") is not None]
        if dts and min(dts) > 100:
            print(f"警告: B→A 精修量持续 >100mm(最小 {min(dts):.0f}mm)——site 标记外参偏差大，建议现场重标(4 角点版)")
            verdict = worst(verdict, "警告")
            reasons.append("site 外参偏差大")

    print(f"\n结论: {verdict}" + (f"（{'；'.join(reasons)}）" if reasons else "（重复性达标" +
          ("+准确度达标）" if os.environ.get("GOMOB_LASER_TRUTH_LWH") else "）")))
    return {"正常": 0, "警告": 0, "异常": 1}[verdict]


def worst(a: str, b: str) -> str:
    order = {"正常": 0, "警告": 1, "异常": 2}
    return a if order[a] >= order[b] else b


if __name__ == "__main__":
    sys.exit(main())
