#!/usr/bin/env python3
"""vehicle_measure 分析：测量 JSONL vs Result.ini 真值，输出可判定结论（正常/警告/异常）。

判据（M9.2 当前管线达标线，<1% 待 M9.2b 反汇编 bound_box/SOR/carType 偏移）：
  误差 |meas-truth|/truth：所有维度 <=2.5% → 正常；任一 (2.5%,5%] → 警告；任一 >5% → 异常。
1.pcd 对 (Length,Width,Height)，2.pcd 对 (Length2,Width2,Height2)。
"""
import json
import re
import sys

WARN, FAIL = 2.5, 5.0  # 百分比阈值


def parse_result_ini(path):
    vals = {}
    raw = open(path, "rb").read().decode("gbk", "ignore")
    for line in raw.replace("\r", "").splitlines():
        m = re.match(r"\s*([A-Za-z0-9_]+)\s*=\s*(-?\d+(?:\.\d+)?)\s*$", line)
        if m:
            vals[m.group(1)] = float(m.group(2))
    return vals


def main():
    measured = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
    truth = parse_result_ini(sys.argv[2])

    # 文件 → (真值键三元组)
    expect = {
        "1.pcd": ("Length", "Width", "Height"),
        "2.pcd": ("Length2", "Width2", "Height2"),
    }
    dims = ("length", "width", "height")
    labels = ("车长", "车宽", "车高")

    worst = 0.0
    verdict = "正常"
    reasons = []
    print(f"{'文件':<8}{'维度':<6}{'测量':>9}{'真值':>9}{'误差%':>9}  判定")
    print("-" * 52)
    for m in measured:
        f = m["file"]
        if not m.get("valid"):
            verdict = "异常"
            reasons.append(f"{f}: 管线 valid=false")
            continue
        keys = expect.get(f)
        if not keys:
            continue
        for dim, lab, k in zip(dims, labels, keys):
            meas = m[dim]
            tv = truth.get(k)
            if tv is None or tv == 0:
                print(f"{f:<8}{lab:<6}{meas:>9.1f}{'—':>9}{'(无真值)':>9}")
                continue
            err = abs(meas - tv) / abs(tv) * 100.0
            worst = max(worst, err)
            tag = "正常" if err <= WARN else ("警告" if err <= FAIL else "异常")
            if err > FAIL:
                verdict = "异常"
                reasons.append(f"{f} {lab} 误差 {err:.1f}%>{FAIL}%")
            elif err > WARN and verdict == "正常":
                verdict = "警告"
                reasons.append(f"{f} {lab} 误差 {err:.1f}%>{WARN}%")
            print(f"{f:<8}{lab:<6}{meas:>9.1f}{tv:>9.1f}{err:>8.2f}%  {tag}")
        print(f"         body={m['body']}/{m['raw']} ratio={m['ratio']:.2f} angle={m['angle']:.1f}°")

    print("-" * 52)
    print(f"最大误差 {worst:.2f}%  →  结论：【{verdict}】")
    if reasons:
        print("原因：" + "；".join(reasons))
    else:
        print(f"原因：所有维度误差 <= {WARN}%（M9.2 达标；<1% 见 TODO M9.2b）")
    sys.exit(0 if verdict != "异常" else 1)


if __name__ == "__main__":
    main()
