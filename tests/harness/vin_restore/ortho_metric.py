#!/usr/bin/env python3
# 决定性实验：固定度量网格正射（零标定）vs OBB-单应正射 —— 判定残留透视到底是【真视差(需 HLSD8↔depth 标定)】
# 还是【OBB-单应架构假象(换度量网格即修, 零标定)】。
#
# 关键区别：
#   - OBB-单应 render(restore_obb.render)：把 OBB 4 角强行钉成输出矩形 → 角点对齐"看着正"，但**视差被掩盖**。
#   - 度量网格 render(本文件, 端口自 native/vin/ortho_rectify.cpp)：平面上铺**固定 mm/px** 刚性网格、不钉角点 →
#     如实暴露真实几何。零标定(R=I,t=0,fyc=fxc)下：
#       · 同 VIN 不同角度【重合】且【平】  → 彩色≈registered 零视差 → 标定非必需(架构是真因)。
#       · 仍随拍摄角度透视/不重合         → HLSD8↔depth 视差真实 → 需要标定 B。
#
# 用法：python3 ortho_metric.py [captures_dir] [out_dir]
import os, sys, glob, math
import numpy as np
import cv2
from obb import ObbDetector, _poly
from restore import load_capture, backproject_roi, ransac_plane, plane_basis
from restore_obb import ray_plane_inplane, signature_binarize, MAX_TILT_DEG, SIG_INK_MAX
from atan_undistort import undistort_hlsd8, HLSD8_215

# HLSD8 atan 去畸变【默认关】：真机 cap_133-139 复验证明该模型(畸变中心 cy 偏离图心+过校正)会把本来端直的
# 钢牌弯成弧、图角留黑楔 → 还原"完全不对"，已下线。保留 VIN_UNDISTORT=1 仅作对照实验，不进生产路径。
USE_UNDISTORT = os.environ.get("VIN_UNDISTORT", "0") != "0"

MODEL = ".dev/vin_models/yolo-obb.onnx"
PX_MM = 0.20            # 每正射像素物理尺寸(mm) —— 固定 → 输出严格 metric
MARGIN_X_MM, MARGIN_Y_MM = 10.0, 5.0

# 深度尺度订正（经验，待精定）：eYs3D mode25 几何解码 Z=fx×B/(disp/8) 用了全幅 fx=1229.205 配 640宽视差致
# Z 偏大。真机尺子 VIN 宽 120mm 定标。注：harness 用 obb.py 检测框比服务端 onnx 略宽 → 同 120mm 目标下
# harness 标定值(0.092)与服务端(0.1116)不同，各自对各自 OBB 标定。TODO(终态)：注入设备 ZD 表精定，删此经验因子。
DEPTH_SCALE = float(os.environ.get("VIN_DEPTH_SCALE", "0.092"))


def build_frame(capdir, det, fyc_mode="fxc"):
    """检 OBB → OBB 区拟合平面 → 返回 plane/基/内参/OBB 平面内 4 角(ab)。fyc_mode: fxc | 2fyd。"""
    depth, color, meta = load_capture(capdir)
    depth = depth.astype(np.float64) * DEPTH_SCALE   # 深度尺度订正（均匀缩放→不改平面法向/彩色采样，只订正绝对 mm）
    Kd = (meta["depth"]["fx"], meta["depth"]["fy"], meta["depth"]["cx"], meta["depth"]["cy"])
    s = color.shape[1] / depth.shape[1]
    if USE_UNDISTORT:
        color = undistort_hlsd8(color, HLSD8_215)    # FOV 去畸变（消 HLSD8 广角"内凹"弯曲）
        fp = HLSD8_215["f_proj"]
        Kc = (fp, fp, HLSD8_215["cx"], HLSD8_215["cy"])  # 去畸变后真实 HLSD8 投影内参
    else:
        fyc = Kd[0] * s if fyc_mode == "fxc" else Kd[1] * s
        Kc = (Kd[0] * s, fyc, Kd[2] * s, Kd[3] * s)
    ch, cw = color.shape[:2]
    dets = det.detect(color)
    if not dets:
        return None, "no obb"
    d = max(dets, key=lambda x: x["score"])
    corners_px = _poly(d["corners"])
    xs = [p[0] for p in corners_px]; ys = [p[1] for p in corners_px]
    roi = ((min(xs) + max(xs)) * 0.5 / cw, (min(ys) + max(ys)) * 0.5 / ch,
           max(0.12, (max(xs) - min(xs)) / cw * 0.95), max(0.12, (max(ys) - min(ys)) / ch * 1.25))
    pts = backproject_roi(depth, Kd, roi)
    plane = ransac_plane(pts)
    tilt = math.degrees(math.acos(min(1.0, abs(plane["n"][2]))))
    if tilt > MAX_TILT_DEG:
        return None, "tilt>%.0f" % tilt
    right, up = plane_basis(plane)
    ab = np.array([ray_plane_inplane(u, v, Kc, plane, right, up)[0] for (u, v) in corners_px], np.float32)
    return dict(plane=plane, right=right, up=up, Kc=Kc, color=color, ab=ab, tilt=tilt,
                inlier=plane["inlier_ratio"], rms=plane["rms"]), None


def metric_render(frame, px=PX_MM, rt=None):
    """固定度量网格正射：平面上以 OBB 中心为原点、OBB 长轴为 x、固定 mm/px 铺刚性网格，逐点投影采样彩色。
       rt=None → 零标定(R=I,t=0)；否则 rt=(R3x3, t3) 用真外参。**不钉角点** → 如实反映几何。"""
    plane = frame["plane"]; right = frame["right"]; up = frame["up"]; Kc = frame["Kc"]
    C = plane["centroid"]; ab = frame["ab"]; color = frame["color"]
    fxc, fyc, cxc, cyc = Kc
    tl, tr, br, bl = ab
    center = (tl + tr + br + bl) * 0.25
    xdir = (tr - tl) + (br - bl); xdir = xdir / (np.linalg.norm(xdir) + 1e-9)
    ydir = np.array([-xdir[1], xdir[0]], np.float64)
    if np.dot(bl - tl, ydir) < 0:
        ydir = -ydir
    w_mm = (np.linalg.norm(tr - tl) + np.linalg.norm(br - bl)) * 0.5 + 2 * MARGIN_X_MM
    h_mm = (np.linalg.norm(bl - tl) + np.linalg.norm(br - tr)) * 0.5 + 2 * MARGIN_Y_MM
    out_w = max(8, int(round(w_mm / px))); out_h = max(8, int(round(h_mm / px)))
    xs, ys = np.meshgrid(np.arange(out_w), np.arange(out_h))
    dx = (xs + 0.5 - out_w / 2.0) * px
    dy = (ys + 0.5 - out_h / 2.0) * px
    ab_pt = center[None, None, :] + dx[..., None] * xdir[None, None, :] + dy[..., None] * ydir[None, None, :]
    a = ab_pt[..., 0]; b = ab_pt[..., 1]
    Q = C[None, None, :] + a[..., None] * right[None, None, :] + b[..., None] * up[None, None, :]
    if rt is not None:
        R, t = rt
        Q = Q @ R.T + t[None, None, :]
    Z = Q[..., 2]; valid = Z > 1e-3
    u = fxc * Q[..., 0] / np.where(valid, Z, 1) + cxc
    v = fyc * Q[..., 1] / np.where(valid, Z, 1) + cyc
    out = cv2.remap(color, u.astype(np.float32), v.astype(np.float32),
                    cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    out[~valid] = 0
    return out, out_w, out_h


def draw_ruler(img, px_mm=PX_MM):
    """沿正射图**左边+底边**直接画 metric 刻度尺，精到**毫米级**（与服务端 render.go::drawRuler 对齐）。
       1mm 次/5mm 中/10mm 主+标数；原点左下；描边线(黑粗+白细)落空白边距，任意底色可读、不铺暗带不挡字符。"""
    h, w = img.shape[:2]
    cv = img.copy()
    dark, light = (12, 12, 12), (250, 250, 250)
    def tick(p0, p1):
        cv2.line(cv, p0, p1, dark, 2, cv2.LINE_AA); cv2.line(cv, p0, p1, light, 1, cv2.LINE_AA)
    def lab(s, at):
        cv2.putText(cv, s, at, cv2.FONT_HERSHEY_SIMPLEX, 0.3, dark, 3, cv2.LINE_AA)
        cv2.putText(cv, s, at, cv2.FONT_HERSHEY_SIMPLEX, 0.3, light, 1, cv2.LINE_AA)
    by = h - 1
    # 数字只标首尾两个：刻度线全留，数字仅最左(0)与最右/最顶主刻度。
    last_major_x = int((w - 1) * px_mm) // 10 * 10
    last_major_y = int(by * px_mm) // 10 * 10
    mm = 0
    while True:
        x = int(round(mm / px_mm))
        if x >= w: break
        if mm % 10 == 0:
            tick((x, by), (x, by - 9))
            if mm == 0 or mm == last_major_x:
                s = str(mm); tx = min(x + 2, w - len(s) * 7 - 1); lab(s, (tx, by - 11))
        elif mm % 5 == 0:
            tick((x, by), (x, by - 5))
        else:
            tick((x, by), (x, by - 3))
        mm += 1
    mm = 0
    while True:
        y = by - int(round(mm / px_mm))
        if y < 0: break
        if mm % 10 == 0:
            tick((0, y), (9, y))
            if mm == last_major_y and mm > 0: lab(str(mm), (11, max(y + 4, 10)))
        elif mm % 5 == 0:
            tick((0, y), (5, y))
        else:
            tick((0, y), (3, y))
        mm += 1
    return cv


def lean_gradient(bgr):
    """残留透视量化：把图三等分(左/中/右)，各段二值后估竖笔倾角(PCA)，返回(左-右)倾角差(度)。
       真度量正射应≈0(各处倾角一致)；有视差→左右倾角随位置渐变 → 差值显著。"""
    sig = signature_binarize(bgr)
    h, w = sig.shape
    def seg_angle(x0, x1):
        ys, xs = np.where(sig[:, x0:x1] < 128)
        if len(xs) < 60:
            return None
        xs = xs.astype(np.float64); ys = ys.astype(np.float64)
        xs -= xs.mean(); ys -= ys.mean()
        cxx = (xs * xs).sum(); cyy = (ys * ys).sum(); cxy = (xs * ys).sum()
        ang = 0.5 * math.degrees(math.atan2(2 * cxy, cxx - cyy))
        while ang >= 45: ang -= 90
        while ang < -45: ang += 90
        return ang
    al = seg_angle(0, w // 3); ar = seg_angle(2 * w // 3, w)
    if al is None or ar is None:
        return None
    return al - ar


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else ".dev/vin_captures"
    outdir = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("OUTPUT_DIR", ".dev/vin_ortho_metric")
    os.makedirs(outdir, exist_ok=True)
    det = ObbDetector(MODEL)
    tiles = []
    print("cap                       tilt  out(px)     lean_grad(L-R°)  inlier rms")
    for c in sorted(glob.glob(os.path.join(root, "cap_*"))):
        name = os.path.basename(c)
        f, err = build_frame(c, det, fyc_mode="fxc")
        if f is None:
            print("%-26s FAIL %s" % (name, err)); continue
        img, ow, oh = metric_render(f, rt=None)            # 度量网格(零标定 R=I,t=0)
        cv2.imwrite(os.path.join(outdir, name + "_metric.png"), img)
        cv2.imwrite(os.path.join(outdir, name + "_ruled.png"), draw_ruler(img))  # 叠左/底毫米级刻度尺(用户可见图)
        # 旧的「去阴影二值墨水占比」质量闸已删除（与服务端对齐）：真机 21 组实测它把锐利、无高光、对比
        # 良好的真实采集（板面有污渍/阴影、或网格越板缘暗带）当成「墨水」误判废 43%，鋭度/饱和/对比都不判别
        # 坏采集 = 无有效信号的调参式兜底，违背项目魂。signature_binarize 仅留作 _sig 重合分析，不再判废。
        sig = signature_binarize(img)
        ink = float((sig == 0).mean())
        lg = lean_gradient(img)
        print("%-26s %4.1f  %4dx%-4d (%.0fmm)  %s  ink=%2.0f%% %-3s i%.2f r%.1f"
              % (name, f["tilt"], ow, oh, ow * PX_MM, ("%+5.1f" % lg) if lg is not None else " n/a",
                 ink * 100, "ok", f["inlier"], f["rms"]))
        cv2.imwrite(os.path.join(outdir, name + "_sig.png"), sig)  # 供 analyze.py 重合分析
        tiles.append((name, f["tilt"], img))
    # 视角无关性总览：固定 px → 同 VIN 不同角度应同尺寸+同内容。统一画布宽(取最大)左对齐叠放。
    if tiles:
        W = max(t[2].shape[1] for t in tiles)
        rows = []
        for name, tilt, img in tiles:
            pad = cv2.copyMakeBorder(img, 0, 0, 0, W - img.shape[1], cv2.BORDER_CONSTANT, value=(20, 20, 20))
            cv2.putText(pad, "%s t=%.0f" % (name[:14], tilt), (4, 16),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1, cv2.LINE_AA)
            rows.append(cv2.copyMakeBorder(pad, 0, 3, 0, 0, cv2.BORDER_CONSTANT, value=(60, 60, 60)))
        cv2.imwrite(os.path.join(outdir, "overview_metric.png"), np.vstack(rows))
        print("\noverview →", os.path.join(outdir, "overview_metric.png"))
        print("判读：lean_grad 各组若都≈0 且随 tilt 不增 → 无视差(架构是真因,零标定即可)；"
              "若 |lean_grad| 随 tilt 增大 → HLSD8↔depth 视差真实(需标定 B)。")


if __name__ == "__main__":
    main()
