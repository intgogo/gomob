#!/usr/bin/env python3
# laser_background harness：空工位背景相减（路 B，全自动抠车）的阈值(tol)扫参 + 可判定结论。
#
# 物理判据：固定安装下扫描仪不动 → 静态房间(地/墙/天花/固定设备)每次扫到的点位置不变；唯一变的是车。
# 故 live 点在背景云 tol 内有近邻=背景剔除，无近邻=车。tol 须吸收传感器噪声+ICP 残差+小配准漂移：
#   过小→残留背景散点；过大→侵蚀车体边界。本 harness 用合成"房间+车"已知真值闭环，扫 tol 找稳健区间，
#   并对房间叠加噪声/小漂移检验鲁棒性。与 server/internal/laser/background.go 同算法（体素哈希+27邻域）。
#
# 真机闭环（可选）：LIVE_PCD/BG_PCD 给同坐标系的"空工位+车"与"空工位"融合云，直接在真实数据上相减判定。
import os, sys
import numpy as np

DEFAULT_TOL = 40.0  # background.go DefaultBackgroundParams


def load_pcd(path):
    with open(path, "rb") as f:
        data = f.read()
    marker = b"DATA binary\n"
    hi = data.index(marker)
    hdr = data[:hi].decode("ascii", "replace")
    fields, sizes, npts = [], [], 0
    for ln in hdr.splitlines():
        t = ln.split()
        if not t:
            continue
        if t[0] == "FIELDS":
            fields = t[1:]
        elif t[0] == "SIZE":
            sizes = [int(x) for x in t[1:]]
        elif t[0] == "POINTS":
            npts = int(t[1])
    stride = sum(sizes)
    body = data[hi + len(marker):hi + len(marker) + npts * stride]
    arr = np.frombuffer(body, np.uint8).reshape(npts, stride)
    off, o = {}, 0
    for fn, sz in zip(fields, sizes):
        off[fn] = o
        o += sz
    xyz = np.stack([arr[:, off[c]:off[c] + 4].copy().view(np.float32).reshape(-1) for c in ("x", "y", "z")], 1)
    m = np.isfinite(xyz).all(1) & (np.abs(xyz) < 1e5).all(1)
    return xyz[m]


def subtract_background(live, bg, tol):
    """live 中在 bg tol 内有近邻的点剔除，返回前景。体素哈希(leaf=tol)+27 邻域精确距离（镜像 Go）。"""
    if len(bg) == 0 or len(live) == 0:
        return live
    inv = 1.0 / tol
    bgk = np.floor(bg * inv).astype(np.int64)
    grid = {}
    for i, k in enumerate(map(tuple, bgk)):
        grid.setdefault(k, []).append(i)
    tol2 = tol * tol
    keep = np.ones(len(live), bool)
    livek = np.floor(live * inv).astype(np.int64)
    for i in range(len(live)):
        ci, cj, ck = livek[i]
        p = live[i]
        hit = False
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    for j in grid.get((ci + dx, cj + dy, ck + dz), ()):
                        d = bg[j] - p
                        if d @ d <= tol2:
                            hit = True
                            break
                    if hit:
                        break
                if hit:
                    break
            if hit:
                break
        keep[i] = not hit
    return live[keep]


def plane_grid(axis, fixed, a0, a1, b0, b1, step):
    a = np.arange(a0, a1, step)
    b = np.arange(b0, b1, step)
    A, B = np.meshgrid(a, b)
    A, B = A.ravel(), B.ravel()
    F = np.full_like(A, fixed)
    if axis == 0:
        return np.stack([F, A, B], 1)
    if axis == 1:
        return np.stack([A, F, B], 1)
    return np.stack([A, B, F], 1)


def make_room(X, Y, H, step):
    return np.concatenate([
        plane_grid(2, 0, -X, X, -Y, Y, step), plane_grid(2, H, -X, X, -Y, Y, step),
        plane_grid(0, -X, -Y, Y, 0, H, step), plane_grid(0, X, -Y, Y, 0, H, step),
        plane_grid(1, -Y, -X, X, 0, H, step), plane_grid(1, Y, -X, X, 0, H, step),
    ]).astype(np.float32)


def make_vehicle(cx, cy, L, W, Vh, step, ang):
    hl, hw = L / 2, W / 2
    raw = np.concatenate([
        plane_grid(2, Vh, -hl, hl, -hw, hw, step),
        plane_grid(0, -hl, -hw, hw, 0, Vh, step), plane_grid(0, hl, -hw, hw, 0, Vh, step),
        plane_grid(1, -hw, -hl, hl, 0, Vh, step), plane_grid(1, hw, -hl, hl, 0, Vh, step),
    ])
    c, s = np.cos(np.radians(ang)), np.sin(np.radians(ang))
    x, y, z = raw[:, 0], raw[:, 1], raw[:, 2]
    return np.stack([cx + x * c - y * s, cy + x * s + y * c, z], 1).astype(np.float32)


def span(p):
    return p.max(0) - p.min(0) if len(p) else np.zeros(3)


def run_case(bg, live, veh_truth, tol, label, rows):
    fg = subtract_background(live, bg, tol)
    ratio = len(fg) / max(1, len(veh_truth))
    sx, sy, sz = span(fg)
    # 残留背景=前景里离车真值远的点（粗判：z 跨度异常或点数远超车）
    rows.append((label, tol, len(fg), ratio, sx, sy, sz))
    return fg, ratio, (sx, sy, sz)


def main():
    warn, err = [], []
    rows = []
    print("=== 合成『房间+车』背景相减阈值扫参 ===")
    step = 30.0
    room = make_room(3000, 3000, 3000, step)
    veh = make_vehicle(200, -300, 4000, 1800, 1500, step, 18)
    rng = np.random.default_rng(7)

    # ① 干净背景（live 房间无噪声）：各 tol 下前景/车真值比 + 前景跨度
    print("\n-- 干净房间，扫 tol --")
    for tol in (10, 20, 30, 40, 60, 80):
        live = np.concatenate([room, veh])
        _, ratio, (sx, sy, sz) = run_case(room, live, veh, tol, "clean", rows)
        flag = "ok" if 0.9 <= ratio <= 1.1 and sz < 1700 else "BAD"
        print(f"  tol={tol:3.0f}  前景={ratio*len(veh):.0f}  前景/车={ratio:.3f}  跨度XYZ=({sx:.0f},{sy:.0f},{sz:.0f}) {flag}")

    # ② 带噪声背景（live 房间叠加 ±15mm 抖动 + 5mm 整体漂移，模拟传感器噪声/配准残差）
    print("\n-- 房间叠加 ±15mm 噪声 + 5mm 漂移，扫 tol（检验鲁棒性）--")
    for tol in (10, 20, 30, 40, 60, 80):
        noisy_room = room + rng.uniform(-15, 15, room.shape).astype(np.float32) + np.float32([5, -3, 4])
        live = np.concatenate([noisy_room, veh])
        fg, ratio, (sx, sy, sz) = run_case(room, live, veh, tol, "noisy", rows)
        # 残留房间：前景里 z>1700 的点（车高 1500，超过即残留墙/天花）
        residual = int((fg[:, 2] > 1700).sum()) if len(fg) else 0
        flag = "ok" if 0.9 <= ratio <= 1.12 and residual < len(veh) * 0.05 else "BAD"
        print(f"  tol={tol:3.0f}  前景/车={ratio:.3f}  残留(z>1700)={residual}  跨度Z={sz:.0f} {flag}")
        if tol == DEFAULT_TOL:
            if not (0.9 <= ratio <= 1.12):
                err.append(f"默认 tol={DEFAULT_TOL} 噪声下前景/车={ratio:.3f} 偏离[0.90,1.12]")
            elif residual > len(veh) * 0.05:
                err.append(f"默认 tol={DEFAULT_TOL} 噪声下残留背景 {residual} 点(>5%车)")
            elif ratio < 0.94:
                warn.append(f"默认 tol={DEFAULT_TOL} 噪声下车体侵蚀偏多(前景/车={ratio:.3f})")

    # ③ 真机闭环（可选）：给同坐标系的"空工位+车"与"空工位"融合云
    live_pcd, bg_pcd = os.environ.get("LIVE_PCD"), os.environ.get("BG_PCD")
    if live_pcd and bg_pcd and os.path.exists(live_pcd) and os.path.exists(bg_pcd):
        print(f"\n-- 真机闭环 LIVE={live_pcd} BG={bg_pcd} --")
        L, B = load_pcd(live_pcd), load_pcd(bg_pcd)
        fg = subtract_background(L, B, DEFAULT_TOL)
        sx, sy, sz = span(fg)
        frac = len(fg) / max(1, len(L))
        print(f"  live={len(L)} bg={len(B)} 前景={len(fg)} ({frac*100:.1f}%) 跨度XYZ=({sx:.0f},{sy:.0f},{sz:.0f})")
        if frac > 0.6:
            warn.append(f"真机前景占比 {frac*100:.0f}%>60%，背景可能未覆盖或漂移过大")
        if not (1000 < sx < 18000 and 1000 < sy < 18000 and 300 < sz < 4500):
            warn.append(f"真机前景跨度 ({sx:.0f},{sy:.0f},{sz:.0f}) 不像单车，复核背景/坐标系")
    else:
        print("\n-- 真机闭环跳过（设 LIVE_PCD/BG_PCD 为同坐标系的车场景/空工位融合云即可启用）--")

    print("\n=== 结论 ===")
    if err:
        print("❌ 异常:")
        for e in err:
            print("  -", e)
        sys.exit(1)
    if warn:
        print("⚠ 警告:")
        for w in warn:
            print("  -", w)
        sys.exit(0)
    print(f"✅ 正常：默认 tol={DEFAULT_TOL}mm 在噪声/漂移下稳健抠出车（前景≈车、残留背景<5%）。")


if __name__ == "__main__":
    main()
