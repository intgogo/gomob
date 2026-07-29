#!/usr/bin/env python3
# HLSD8↔depth(eYs3D L') 双相机标定。读端侧 vin_calib/calib_*（rgb1300.jpg=HLSD8 + lprime.jpg=L' + meta.json），
# ChArUco 检角 → 各自 calibrateCamera 出内参/畸变 → stereoCalibrate 出外参 R|t（L'/depth → HLSD8）。
# 输出 calibration.json（服务端 render 套用）+ 可判定结论（reproj 误差 + 姿态数闸）。
#
# 用法: python3 calibrate.py <calib_dir> [out_json]
#   calib_dir 默认 .dev/vin_calib_caps，out_json 默认 <calib_dir>/calibration.json
import cv2, numpy as np, json, glob, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = json.load(open(os.path.join(HERE, "board_spec.json")))
SQ_M = SPEC["square_mm"] / 1000.0  # 棋盘格物理边长(米)，决定 obj 点尺度 → T 单位(米，输出转 mm)

MIN_CORNERS = 6      # 单视角入选最少 charuco 角点
MIN_POSES = 6        # 内参标定最少姿态
MIN_STEREO = 4       # 外参标定最少双视角共见姿态
RMS_OK_PX = 1.0      # reproj rms 正常阈(px)


def _board():
    d = cv2.aruco.getPredefinedDictionary(getattr(cv2.aruco, SPEC["dict_name"]))
    return cv2.aruco.CharucoBoard((SPEC["squares_x"], SPEC["squares_y"]),
                                  SQ_M, SPEC["marker_mm"] / 1000.0, d)


def _make_detector(board):
    """放宽 DetectorParameters + 亚像素精修：扛真机眩光/IR 散斑/轻糊。"""
    dp = cv2.aruco.DetectorParameters()
    dp.adaptiveThreshWinSizeMin = 3
    dp.adaptiveThreshWinSizeMax = 63
    dp.adaptiveThreshWinSizeStep = 6
    dp.minMarkerPerimeterRate = 0.01
    dp.maxMarkerPerimeterRate = 4.0
    dp.polygonalApproxAccuracyRate = 0.06
    dp.cornerRefinementMethod = cv2.aruco.CORNER_REFINE_SUBPIX
    return cv2.aruco.CharucoDetector(board, cv2.aruco.CharucoParameters(), dp, cv2.aruco.RefineParameters())


_CLAHE = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 4))


def _detect(detector, path):
    """读图 → 原图/直方均衡/CLAHE 三路各检一遍，取角点最多的一路。返回 (cc,ci,shape) 或 (None,None,shape)。"""
    img = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
    if img is None:
        return None, None, None
    best = (None, None, 0)
    for g in (img, cv2.equalizeHist(img), _CLAHE.apply(img)):
        cc, ci, _, _ = detector.detectBoard(g)
        n = 0 if ci is None else len(ci)
        if n > best[2]:
            best = (cc, ci, n)
    return best[0], best[1], img.shape[:2]  # shape=(h,w)


def calibrate(calib_dir, out_json):
    board = _board()
    objp_all = board.getChessboardCorners()  # (N,3) 板系全内角点(米)
    detector = _make_detector(board)

    caps = sorted(glob.glob(os.path.join(calib_dir, "calib_*")))
    if not caps:
        print("无标定采集 %s（先端侧「标定采集」对准 ChArUco 板多姿态拍 ≥%d 张, adb pull）" % (calib_dir, MIN_POSES))
        return 2

    h_obj, h_img, l_obj, l_img = [], [], [], []   # 各自内参用
    s_obj, s_h, s_l = [], [], []                   # 外参 stereo 用(共见角点)
    h_size = l_size = None
    used = 0
    print("%-26s HLSD8角点 L'角点 共见" % "cap")
    for c in caps:
        name = os.path.basename(c)
        cch, cih, hs = _detect(detector, os.path.join(c, "rgb1300.jpg"))
        ccl, cil, ls = _detect(detector, os.path.join(c, "lprime.jpg"))
        nh = 0 if cih is None else len(cih)
        nl = 0 if cil is None else len(cil)
        if hs: h_size = (hs[1], hs[0])
        if ls: l_size = (ls[1], ls[0])
        ncommon = 0
        if nh >= MIN_CORNERS:
            o, i = board.matchImagePoints(cch, cih)
            h_obj.append(o); h_img.append(i)
        if nl >= MIN_CORNERS:
            o, i = board.matchImagePoints(ccl, cil)
            l_obj.append(o); l_img.append(i)
        if nh >= MIN_CORNERS and nl >= MIN_CORNERS:
            ids_h = cih.flatten(); ids_l = cil.flatten()
            common = np.intersect1d(ids_h, ids_l)
            if len(common) >= MIN_CORNERS:
                mh = {int(v): cch[k, 0] for k, v in enumerate(ids_h)}
                ml = {int(v): ccl[k, 0] for k, v in enumerate(ids_l)}
                o = np.array([objp_all[v] for v in common], np.float32).reshape(-1, 1, 3)
                s_obj.append(o)
                s_h.append(np.array([mh[v] for v in common], np.float32).reshape(-1, 1, 2))
                s_l.append(np.array([ml[v] for v in common], np.float32).reshape(-1, 1, 2))
                ncommon = len(common)
                used += 1
        print("%-26s %6d %6d %5d" % (name, nh, nl, ncommon))

    if len(h_obj) < MIN_POSES or len(l_obj) < MIN_POSES:
        print("✗ 异常：可用姿态不足（HLSD8 %d / L' %d，各需 ≥%d）。多拍不同姿态/倾角，保证板清晰充满画面。"
              % (len(h_obj), len(l_obj), MIN_POSES))
        return 1

    # 内参（各相机独立多视角标定）。宽条画面无法稳定识别 k2/切向，故整条链统一用 Brown k1-only。
    # 不得再“全 Brown 求 K/R/t，运行时却丢 k2/p1/p2”，那会让内参畸变与外参数学不自洽。
    intr_flags = cv2.CALIB_ZERO_TANGENT_DIST | cv2.CALIB_FIX_K2 | cv2.CALIB_FIX_K3
    rms_h, K_h, dist_h, _, _ = cv2.calibrateCamera(h_obj, h_img, h_size, None, None, flags=intr_flags)
    rms_l, K_l, dist_l, _, _ = cv2.calibrateCamera(l_obj, l_img, l_size, None, None, flags=intr_flags)

    if used < MIN_STEREO:
        print("✗ 异常：双视角共见姿态不足（%d，需 ≥%d）。标定板要同时清晰出现在 HLSD8 和 L' 里。" % (used, MIN_STEREO))
        return 1

    # 外参：cam1=L'(=depth 系), cam2=HLSD8 → R,T 使 X_hlsd8 = R·X_lp + T（正是 depth→HLSD8）。固定内参。
    rms_s, _, _, _, _, R, T, E, F = cv2.stereoCalibrate(
        s_obj, s_l, s_h, K_l, dist_l, K_h, dist_h, l_size,
        flags=cv2.CALIB_FIX_INTRINSIC,
        criteria=(cv2.TERM_CRITERIA_EPS + cv2.TERM_CRITERIA_MAX_ITER, 200, 1e-6))
    T_mm = (T.flatten() * 1000.0).tolist()  # 米 → mm（depth Z 是 mm）

    out = {
        "board": SPEC,
        "n_poses_hlsd8": len(h_obj), "n_poses_lprime": len(l_obj), "n_poses_stereo": used,
        "distortion_model": "brown_k1_only",
        "hlsd8": {"w": h_size[0], "h": h_size[1], "K": K_h.tolist(), "dist": dist_h.flatten().tolist(),
                  "reproj_rms_px": rms_h},
        "lprime": {"w": l_size[0], "h": l_size[1], "K": K_l.tolist(), "dist": dist_l.flatten().tolist(),
                   "reproj_rms_px": rms_l},
        "extrinsic_hlsd8_from_depth": {"R": R.tolist(), "t_mm": T_mm, "stereo_rms_px": rms_s},
    }
    json.dump(out, open(out_json, "w"), indent=2)

    fx_h, fy_h = K_h[0, 0], K_h[1, 1]
    cx_h, cy_h = K_h[0, 2], K_h[1, 2]
    base = float(np.linalg.norm(T_mm))
    print("\n== 标定结果 ==")
    print("HLSD8 内参 fx=%.1f fy=%.1f cx=%.1f cy=%.1f  畸变=%s  rms=%.3fpx"
          % (fx_h, fy_h, cx_h, cy_h, np.round(dist_h.flatten(), 4).tolist(), rms_h))
    print("L'    内参 fx=%.1f fy=%.1f cx=%.1f cy=%.1f  rms=%.3fpx"
          % (K_l[0, 0], K_l[1, 1], K_l[0, 2], K_l[1, 2], rms_l))
    print("外参 depth→HLSD8 基线|t|=%.1fmm  t=%s  stereo_rms=%.3fpx" % (base, np.round(T_mm, 2).tolist(), rms_s))
    print("→", out_json)

    worst = max(rms_h, rms_l, rms_s)
    if worst <= RMS_OK_PX:
        print("\n结论：正常 — reproj 最差 %.3fpx ≤ %.1fpx，姿态 %d/%d/%d(H/L/stereo)。可接入服务端 render。"
              % (worst, RMS_OK_PX, len(h_obj), len(l_obj), used))
        return 0
    print("\n结论：✗ 异常 — 训练集 reproj 最差 %.3fpx > %.1fpx。多拍清晰姿态/确认 square_mm 实测准/板别反光糊。"
          % (worst, RMS_OK_PX))
    return 2


if __name__ == "__main__":
    cdir = sys.argv[1] if len(sys.argv) > 1 else ".dev/vin_calib_caps"
    outj = sys.argv[2] if len(sys.argv) > 2 else os.path.join(cdir, "calibration.json")
    sys.exit(calibrate(cdir, outj))
