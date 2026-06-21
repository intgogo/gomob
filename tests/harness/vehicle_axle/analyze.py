#!/usr/bin/env python3
# vehicle_axle harness 分析器：对原厂真值会话 Data/100742 复算 轴距/前后悬，输出可判定结论。
# 物理判据：轮是唯一触地的部件 → 贴地接触带(z<z0+contactH)沿车长轴的密度峰=轴心。
# 车厢侧壁/底盘悬在离地间隙以上，不进接触带，自然被排除（厚带会误纳，见 .dev/vehicle-parts EDA）。
# 真值 Result.ini(carType=2 常规货车模型)：轴距 710/399/261 总1370，前悬261 后悬163，车长1777。
import os, sys, numpy as np
from scipy.signal import find_peaks

# 真值会话目录：优先 JCHY_DATA 环境变量；否则在若干默认根下探测 Data/100742。
# M12.4：禁止硬编码个人机器绝对路径作为唯一真理源 → 环境变量 + 默认探测，缺则 loud-fail。
def _resolve_sess():
    env = os.environ.get("JCHY_DATA")
    if env:
        return env
    # 默认探测根（按优先级），命中含 1.pcd/2.pcd 的目录即用
    candidates = [
        os.path.expanduser("~/WindowsR/JCHY_OFFLINE/Data/100742"),
        "/root/WindowsR/JCHY_OFFLINE/Data/100742",
        os.path.join(os.path.dirname(__file__), "..", "..", "..", ".dev", "vehicle_axle", "truth", "100742"),
    ]
    for c in candidates:
        if os.path.exists(os.path.join(c, "1.pcd")):
            return c
    # 未命中：返回首选默认，由 main() 统一 loud-fail（exit 1）
    return candidates[0]


SESS = _resolve_sess()
# 真值
TG = [710, 399, 261]   # 相邻轴距
FO, RO = 261, 163      # 前悬 / 后悬
# 达标阈（v1，几何-only、单噪声样本）：轴距 6%、前悬 12%、后悬 20%（后悬最弱，受尾端噪声+尾轮挡泥影响）
TOL_GAP, TOL_FO, TOL_RO = 0.06, 0.12, 0.20


def load_pcd(path):
    raw = open(path, "rb").read()
    m = raw.index(b"DATA binary\n") + 12
    hdr = raw[:m].decode("ascii", "replace"); body = raw[m:]
    fields = sizes = None; npts = 0
    for ln in hdr.splitlines():
        f = ln.split()
        if not f:
            continue
        if f[0] == "FIELDS": fields = f[1:]
        elif f[0] == "SIZE": sizes = [int(x) for x in f[1:]]
        elif f[0] == "POINTS": npts = int(f[1])
    step = 0; off = {}
    for i, fn in enumerate(fields):
        if fn in ("x", "y", "z"): off[fn] = step
        step += sizes[i]
    xs = np.frombuffer(body[:npts * step], np.uint8).reshape(npts, step)
    col = lambda o: xs[:, o:o + 4].copy().view(np.float32).ravel()
    P = np.stack([col(off["x"]), col(off["y"]), col(off["z"])], 1)
    return P[np.isfinite(P).all(1)]


def detect_axles(P, contactH=60.0, binw=10.0, smooth=5):
    # 此云车长轴≈Y（设备系）。Go 版先按 OBB 角把车长轴转到坐标轴再扫，逻辑一致。
    Y = P[:, 1]
    h, e = np.histogram(Y, bins=int((Y.max() - Y.min()) / 10), range=(Y.min(), Y.max()))
    g = np.where(h > h.max() * 0.05)[0]          # 端噪剔除：丢稀疏端 bin
    ylo, yhi = e[g[0]], e[g[-1] + 1]
    Q = P[(P[:, 1] >= ylo) & (P[:, 1] <= yhi)]
    Yq, Zq = Q[:, 1], Q[:, 2]
    z0 = np.percentile(Zq, 0.3)
    contact = Yq[Zq < z0 + contactH]             # 贴地接触带：只有轮触地
    nb = int((yhi - ylo) / binw) + 1
    edg = np.linspace(ylo, yhi, nb + 1); ctr = (edg[:-1] + edg[1:]) / 2
    dens, _ = np.histogram(contact, bins=edg)
    hs = np.convolve(dens, np.ones(smooth) / smooth, "same")
    pk, _ = find_peaks(hs, height=hs.max() * 0.25,
                       distance=int(150 / binw), prominence=hs.max() * 0.2)
    cen = []
    for p in pk:                                 # 接触密度加权细化轴心
        lo, hi = max(0, p - 8), min(nb, p + 9)
        w = hs[lo:hi]; cen.append(np.sum(ctr[lo:hi] * w) / np.sum(w))
    return ylo, yhi, np.sort(np.array(cen))


def main():
    p1 = os.path.join(SESS, "1.pcd"); p2 = os.path.join(SESS, "2.pcd")
    if not (os.path.exists(p1) and os.path.exists(p2)):
        # M12.1：缺真值不可静默放过（假过）。无 1.pcd/2.pcd → 无法判定 → exit 1。
        print(f"❌ 无法判定：真值会话缺失 {SESS}（需 1.pcd + 2.pcd）", file=sys.stderr)
        print("   提供真值后重跑：JCHY_DATA=/path/to/Data/100742 跑本 harness；", file=sys.stderr)
        print("   或把会话放到 .dev/vehicle_axle/truth/100742/。", file=sys.stderr)
        return 1
    P = np.vstack([load_pcd(p1), load_pcd(p2)])
    P = P[((P >= [270, 0, 10]) & (P <= [1000, 2200, 800])).all(1)]
    ylo, yhi, cen = detect_axles(P)
    gaps = np.diff(cen)
    fo = cen.min() - ylo; ro = yhi - cen.max()

    print(f"检出 {len(cen)} 轴 @L(mm)={np.round(cen).astype(int)}")
    print(f"  轴距={np.round(gaps).astype(int).tolist()}  前悬={fo:.0f}  后悬={ro:.0f}")
    print(f"  真值  轴距={TG}  前悬={FO}  后悬={RO}")

    warn = []; err = []
    if len(gaps) != len(TG):
        err.append(f"轴数不符: 检出{len(gaps)+1} 真值{len(TG)+1}")
    else:
        for i, (gp, tg) in enumerate(zip(gaps, TG)):
            re = abs(gp - tg) / tg
            if re > TOL_GAP:
                err.append(f"轴距{i+1} {gp:.0f}vs{tg} 误差{re*100:.1f}%>{TOL_GAP*100:.0f}%")
            elif re > TOL_GAP * 0.6:
                warn.append(f"轴距{i+1} {gp:.0f}vs{tg} 误差{re*100:.1f}%")
    if abs(fo - FO) / FO > TOL_FO:
        err.append(f"前悬 {fo:.0f}vs{FO} 误差{abs(fo-FO)/FO*100:.1f}%>{TOL_FO*100:.0f}%")
    if abs(ro - RO) / RO > TOL_RO:
        err.append(f"后悬 {ro:.0f}vs{RO} 误差{abs(ro-RO)/RO*100:.1f}%>{TOL_RO*100:.0f}%")

    if err:
        print("结论: ❌ 异常 —", "; ".join(err)); return 1
    if warn:
        # M12.1 三态：WARN 不吞退码但必须醒目，便于 CI/人眼立刻看到偏差。
        print("=" * 60)
        print("结论: ⚠ 警告（未达异常阈但已偏离真值，需关注）—", "; ".join(warn))
        print("=" * 60)
        return 0
    print("结论: ✅ 正常 — 轴距/前后悬全部达标"); return 0


if __name__ == "__main__":
    sys.exit(main())
