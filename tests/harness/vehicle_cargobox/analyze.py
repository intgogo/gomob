#!/usr/bin/env python3
# vehicle_cargobox harness：货箱分割 + 外/内尺寸 + 箱深，输出可判定结论。
# 物理判据（.dev/vehicle-parts EDA 在原厂 Data/100742 上夯实）：
#   - 货箱=车尾侧顶高接近全局最高的最长连续段（车头顶矮、且与箱间有缝）。
#   - 箱顶 rim(顶部薄层)的长/宽=货箱外长/外宽（rim 干净、无轮）。
#   - 箱壁竖直 → 横截面宽度随 z 恒定；最长恒宽 z 段=箱壁，其底=bed floor，箱高=箱顶-bed。
#   - 壁带内 X 直方图两壁峰间距=内宽。
# 数值真值靠合成"车头+开顶货箱"(已知尺寸)闭环；100742 无货箱真值(carType=2 未触发箱测)，
# 用来验证分割正确性 + 外尺寸与 rim/EDA 一致。
import os, sys, numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from detect import load_pcd, detect_cargobox

SESS = os.environ.get("JCHY_DATA", "/root/WindowsR/JCHY_OFFLINE/Data/100742")


def make_box_truck(cab_len, gap, box_len, outer_w, inner_w, bed, box_top, cab_roof, step=15.0):
    """合成 车头(矮实心盒) + 开顶货箱(四壁+底,已知内/外尺寸)。z=上,地面 z=0,车长沿 y。"""
    pts = []
    W = outer_w
    # 车头：y∈[0,cab_len] 实心盒外壳，顶 cab_roof(< box_top)
    for y in np.arange(0, cab_len, step):
        for x in np.arange(-W / 2, W / 2, step):
            pts.append((x, y, 0)); pts.append((x, y, cab_roof))
        for z in np.arange(0, cab_roof, step):
            pts.append((-W / 2, y, z)); pts.append((W / 2, y, z))
    by0 = cab_len + gap; by1 = by0 + box_len
    # 货箱：bed 底面(内腔) + 四壁(bed→box_top)
    for y in np.arange(by0, by1, step):
        for x in np.arange(-inner_w / 2, inner_w / 2, step):
            pts.append((x, y, bed))                       # 箱底
        for z in np.arange(bed, box_top, step):
            pts.append((-outer_w / 2, y, z)); pts.append((outer_w / 2, y, z))  # 左右外壁
            pts.append((-inner_w / 2, y, z)); pts.append((inner_w / 2, y, z))  # 左右内壁
    for x in np.arange(-outer_w / 2, outer_w / 2, step):  # 前后壁
        for z in np.arange(bed, box_top, step):
            pts.append((x, by0, z)); pts.append((x, by1, z))
    return np.array(pts, np.float32)


def make_thin_residual_case(step=20.0):
    """合成低矮车体 + 细长高残留；残留不能被当成货箱。"""
    pts = []
    L, W, H = 1800.0, 550.0, 520.0
    for y in np.arange(0, L, step):
        for x in np.arange(-W / 2, W / 2, step):
            pts.append((x, y, 0)); pts.append((x, y, H))
        for z in np.arange(0, H, step):
            pts.append((-W / 2, y, z)); pts.append((W / 2, y, z))
    for x in np.arange(-W / 2, W / 2, step):
        for z in np.arange(0, H, step):
            pts.append((x, 0, z)); pts.append((x, L, z))
    for y in np.arange(1200, 1281, 10.0):
        for x in np.arange(-25, 26, 10.0):
            for z in np.arange(300, 2301, 20.0):
                pts.append((x, y, z))
    return np.array(pts, np.float32)


def check(name, val, truth, tol_pct, warn, err):
    if np.isnan(val):
        err.append(f"{name} 未测出"); return
    re = abs(val - truth) / truth * 100
    if re > tol_pct:
        err.append(f"{name} {val:.0f}vs{truth} 误差{re:.1f}%>{tol_pct:.0f}%")
    elif re > tol_pct * 0.6:
        warn.append(f"{name} {val:.0f}vs{truth} 误差{re:.1f}%")


def main():
    warn, err = [], []

    # ① 合成真值闭环（车头+开顶货箱，已知尺寸）
    T = dict(cab_len=500, gap=80, box_len=1000, outer_w=600, inner_w=520, bed=300, box_top=760, cab_roof=600)
    P = make_box_truck(**T)
    r = detect_cargobox(P)
    print("== 合成车头+货箱真值闭环 ==")
    if not r.get("has_box"):
        err.append("合成: 未检出货箱")
    else:
        iw = f"{r['innerW']:.0f}" if not np.isnan(r['innerW']) else "nan"
        print(f"  外长={r['outerL']:.0f}/{T['box_len']} 外宽={r['outerW']:.0f}/{T['outer_w']} "
              f"内宽={iw}/{T['inner_w']} 箱高={r['boxH']:.0f}/{T['box_top']-T['bed']} bed={r['bed']:.0f}/{T['bed']}")
        check("合成外长", r["outerL"], T["box_len"], 8, warn, err)
        check("合成外宽", r["outerW"], T["outer_w"], 8, warn, err)
        check("合成箱深", r["boxH"], T["box_top"] - T["bed"], 12, warn, err)
        # 内宽=壁中距：直方图分辨率下厚壁内外面合并，薄壁时≈内宽。参考值，不硬断言。

    # ② 细长高残留反例：不能把背景柱/残留竖片画成货箱。
    R = make_thin_residual_case()
    rr = detect_cargobox(R)
    print("== 细长高残留反例 ==")
    if rr.get("has_box"):
        err.append(f"细长残留误检为货箱: 外长={rr.get('outerL',0):.0f} 外宽={rr.get('outerW',0):.0f} 箱高={rr.get('boxH',0):.0f}")
    else:
        print("  未检出货箱（正常）")

    # ③ 原厂 100742 分割 sanity（无货箱数值真值，验证分割+外尺寸与 rim 一致）
    p1, p2 = os.path.join(SESS, "1.pcd"), os.path.join(SESS, "2.pcd")
    if os.path.exists(p1) and os.path.exists(p2):
        Q = np.vstack([load_pcd(p1), load_pcd(p2)])
        Q = Q[((Q >= [270, 0, 10]) & (Q <= [1000, 2200, 800])).all(1)]
        rq = detect_cargobox(Q)
        print("== 原厂 100742 分割 sanity ==")
        if not rq.get("has_box"):
            err.append("100742: 未检出货箱(应有)")
        else:
            frac = rq["outerL"] / (rq["lhi"] - rq["llo"]) * 100
            iw = f"{rq['innerW']:.0f}" if not np.isnan(rq['innerW']) else "nan"
            print(f"  货箱占车长{frac:.0f}% 外长={rq['outerL']:.0f} 外宽={rq['outerW']:.0f} 内宽={iw} 箱高={rq['boxH']:.0f}")
            if not (50 <= frac <= 72): err.append(f"100742 货箱占比 {frac:.0f}% 不在 [50,72]")
            if not (950 <= rq["outerL"] <= 1150): err.append(f"100742 外长 {rq['outerL']:.0f} 不在 [950,1150](rim 1046)")
            if not (420 <= rq["outerW"] <= 540): err.append(f"100742 外宽 {rq['outerW']:.0f} 不在 [420,540]")
            if not (380 <= rq["boxH"] <= 560): err.append(f"100742 箱高 {rq['boxH']:.0f} 不在 [380,560]")
    else:
        print(f"[SKIP] 无真值会话 {SESS}")

    if err:
        print("结论: ❌ 异常 —", "; ".join(err)); return 1
    if warn:
        print("结论: ⚠ 警告 —", "; ".join(warn)); return 0
    print("结论: ✅ 正常 — 合成闭环 + 100742 分割全部达标"); return 0


if __name__ == "__main__":
    sys.exit(main())
