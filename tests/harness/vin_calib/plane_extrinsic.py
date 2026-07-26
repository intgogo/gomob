#!/usr/bin/env python3
# HLSD8↔depth 标定（平面法外参版）。L' 视场比 HLSD8 宽 ~3× → 板在 L' 里太小+IR 散斑无法解码 → 弃角点立体法，
# 改：HLSD8 检 ChArUco 出内参 + 每姿态 solvePnP 板位姿（板平面在 HLSD8 系）；深度 RANSAC 出板平面（depth 系）；
# 多姿态对齐两系板平面 → 旋转 R(法向 Kabsch) + 尺度 s 与平移 t(平面偏移最小二乘，顺带订正 depthScale)。
#
# 1280×256 是 5:1 宽条 → 角点纵向覆盖窄 → 径向畸变难标：默认 FIX_K3（必要时 FIX_K2），避免 k2/k3 过拟合发散。
# 用法: python3 plane_extrinsic.py <calib_dir> [out_json]
import cv2, numpy as np, json, glob, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import calibrate as C

MIN_CORNERS = 6
DEPTH_RANSAC_THRESH_FRAC = 0.012   # 平面内点阈（占中位深度比例）
DEPTH_RANSAC_ITERS = 400


def fit_plane(pts, thresh, iters=150):
    """RANSAC 主平面（向量化采样+点下采样，快）。pts:(N,3)。返回 (n 单位朝相机 nz<0, d, inlier_mask, rms)。"""
    N = len(pts)
    rng = np.random.default_rng(12345)
    # 下采样供拟合（评估内点用全量太慢；6000 足够估平面）
    sub = pts if N <= 6000 else pts[rng.integers(0, N, 6000)]
    tri = rng.integers(0, len(sub), (iters, 3))           # 向量化抽样
    best_cnt, n_best, d_best = -1, None, None
    for k in range(iters):
        p = sub[tri[k]]
        nrm = np.cross(p[1] - p[0], p[2] - p[0])
        ln = np.linalg.norm(nrm)
        if ln < 1e-9:
            continue
        nrm /= ln
        d = nrm @ p[0]
        cnt = int((np.abs(sub @ nrm - d) < thresh).sum())
        if cnt > best_cnt:
            best_cnt, n_best, d_best = cnt, nrm, d
    inl_full = np.abs(pts @ n_best - d_best) < thresh     # 全量内点掩码
    P = pts[inl_full]
    c = P.mean(0)
    _, _, vt = np.linalg.svd(P - c, full_matrices=False)
    n = vt[2]
    if n[2] > 0:
        n = -n
    d = n @ c
    rms = float(np.sqrt(((P @ n - d) ** 2).mean()))
    return n, d, inl_full, rms


def depth_board_plane(capdir, meta):
    """深度反投影(原始单位,不乘 depthScale)→RANSAC 主平面(=板)。返回 n_d, d_raw, inlier_ratio, rms_raw, medZ。"""
    dm = meta["depth"]
    dw, dh = dm["w"], dm["h"]
    fx, fy, cx, cy = dm["fx"], dm["fy"], dm["cx"], dm["cy"]
    a = np.fromfile(os.path.join(capdir, "depth.yuv"), dtype="<u2")
    if a.size < dw * dh:
        return None
    Z = a[:dw * dh].reshape(dh, dw).astype(np.float64)
    vs, us = np.where(Z > 0)
    z = Z[vs, us]
    X = (us - cx) / fx * z
    Y = (vs - cy) / fy * z
    pts = np.stack([X, Y, z], 1)
    if len(pts) < 200:
        return None
    medZ = np.median(z)
    n, d, inl, rms = fit_plane(pts, thresh=DEPTH_RANSAC_THRESH_FRAC * medZ, iters=DEPTH_RANSAC_ITERS)
    return n, d, inl.mean(), rms, medZ


def calib_intrinsics(caps, det, board, flags):
    objs, imgs, sz, used = [], [], None, []
    for c in caps:
        cc, ci, sh = C._detect(det, os.path.join(c, "rgb1300.jpg"))
        if ci is None or len(ci) < MIN_CORNERS:
            used.append(None); continue
        o, i = board.matchImagePoints(cc, ci)
        objs.append(o); imgs.append(i); sz = (sh[1], sh[0]); used.append((o, i))
    rms, K, dist, rvecs, tvecs = cv2.calibrateCamera(objs, imgs, sz, None, None, flags=flags)
    return rms, K, dist, sz, used


def kabsch(A, B):
    """求 R 使 R·A ≈ B（A,B:(N,3) 单位法向集）。"""
    H = A.T @ B
    U, _, Vt = np.linalg.svd(H)
    D = np.diag([1, 1, np.sign(np.linalg.det(Vt.T @ U.T))])
    return Vt.T @ D @ U.T


def main(calib_dir, out_json):
    board = C._board(); det = C._make_detector(board)
    caps = sorted(glob.glob(os.path.join(calib_dir, "calib_*")))
    if not caps:
        print("无标定采集", calib_dir); return 2

    # 内参：1280×256 宽条 k3/切向易发散 → FIX_K3（标 k1+k2+切向）。注：姿态变化不足时 fx/cy 仍不稳，需多样姿态。
    flags = cv2.CALIB_FIX_K3
    rms, K, dist, sz, used = calib_intrinsics(caps, det, board, flags)
    print("HLSD8 内参(FIX_K3) rms=%.3fpx fx=%.1f fy=%.1f cx=%.1f cy=%.1f dist=%s"
          % (rms, K[0, 0], K[1, 1], K[0, 2], K[1, 2], np.round(dist.flatten(), 4).tolist()))

    # 每姿态：HLSD8 板平面(solvePnP) + 深度板平面(RANSAC)
    nh, dh_, nd, dd, info = [], [], [], [], []
    for c, u in zip(caps, used):
        if u is None:
            continue
        o, i = u
        ok, rvec, tvec = cv2.solvePnP(o, i, K, dist, flags=cv2.SOLVEPNP_ITERATIVE)
        if not ok:
            continue
        R, _ = cv2.Rodrigues(rvec)
        n_h = R[:, 2].copy()
        if n_h[2] > 0:
            n_h = -n_h
        d_h = float(n_h @ tvec.flatten())
        dep = depth_board_plane(c, json.load(open(os.path.join(c, "meta.json"))))
        if dep is None:
            continue
        n_d, d_raw, inlr, drms, medZ = dep
        nh.append(n_h); dh_.append(d_h); nd.append(n_d); dd.append(d_raw)
        info.append((os.path.basename(c)[6:9], inlr, drms, medZ))
    nh = np.array(nh); nd = np.array(nd); dh_ = np.array(dh_); dd = np.array(dd)
    print("\n可用姿态 %d；每姿态 [深度板平面内点率 / rms(raw) / medZ(raw)]：" % len(nh))
    for (nm, inlr, drms, medZ) in info:
        print("  %s inlier=%.0f%% rms=%.1f medZ=%.0f" % (nm, inlr * 100, drms, medZ))

    # 旋转 R: 对齐 n_d → n_h
    R = kabsch(nd, nh)
    res_deg = [np.degrees(np.arccos(np.clip((R @ nd[i]) @ nh[i], -1, 1))) for i in range(len(nh))]
    # 尺度 s + 平移 t: d_h = s·d_raw + n_h·t  →  [d_raw, n_h] · [s, t] = d_h
    A = np.concatenate([dd[:, None], nh], 1)
    sol, *_ = np.linalg.lstsq(A, dh_, rcond=None)
    s, t = float(sol[0]), sol[1:]
    pred = A @ sol
    off_res_mm = np.abs(pred - dh_)  # mm (HLSD8 系，board 用真实 square_mm)
    maxpair = max((np.degrees(np.arccos(np.clip(abs(nh[i] @ nh[j]), 0, 1)))
                   for i in range(len(nh)) for j in range(i + 1, len(nh))), default=0)

    print("\n外参 depth→HLSD8：")
    print("  法向对齐残差(deg): med %.2f max %.2f   (姿态法向最大夹角 %.1f°, 需>~20 才稳)"
          % (np.median(res_deg), np.max(res_deg), maxpair))
    print("  depthScale 订正 s=%.4f（旧经验 0.1116；板平面偏移最小二乘解出）" % s)
    print("  t(mm)=%s  |t|=%.1fmm   平面偏移残差(mm): med %.2f max %.2f"
          % (np.round(t, 2).tolist(), float(np.linalg.norm(t)), np.median(off_res_mm), np.max(off_res_mm)))

    out = {
        "method": "plane_based", "board": C.SPEC, "n_poses": len(nh),
        "hlsd8": {"w": sz[0], "h": sz[1], "K": K.tolist(), "dist": dist.flatten().tolist(), "reproj_rms_px": rms},
        "extrinsic_hlsd8_from_depth": {"R": R.tolist(), "t_mm": t.tolist()},
        "depth_scale": s,
        "diag": {"normal_align_res_deg_med": float(np.median(res_deg)),
                 "normal_align_res_deg_max": float(np.max(res_deg)),
                 "max_pairwise_normal_deg": float(maxpair),
                 "plane_offset_res_mm_med": float(np.median(off_res_mm)),
                 "plane_offset_res_mm_max": float(np.max(off_res_mm))},
    }
    json.dump(out, open(out_json, "w"), indent=2)
    print("\n→", out_json)
    ok = maxpair > 18 and np.median(res_deg) < 2.0 and np.median(off_res_mm) < 5
    print("结论：" + ("正常 — 外参可用，接服务端 render 试。" if ok else
          "⚠ 倾角多样性/残差不足 → 外参不稳。需重拍：板大幅变倾角(俯仰/左右倾各 ±25°以上)、多覆盖。"))
    return 0


if __name__ == "__main__":
    cd = sys.argv[1] if len(sys.argv) > 1 else ".dev/vin_calib_caps"
    oj = sys.argv[2] if len(sys.argv) > 2 else os.path.join(cd, "calibration.json")
    sys.exit(main(cd, oj))
