#!/usr/bin/env python3
# VIN 还原参考实现（脱机，cv2+numpy）—— 对齐原厂 libcreator_jni.so::ImageRestorerFunc::restoreImageFlow。
# Pass 1（本文件起步版）：深度 RANSAC 主平面 → 正交基反向正射重采样彩色（假设彩色与深度同光路：
#   color 内参 = 2×depth、外参单位阵）。验证：① 几何摆正对不对 ② "彩色=深度2×" 配准假设成不成立。
# 后续接：YOLO 字符 OBB 锁框（多张重合）、picshadow 去阴影、postProcessV3G 后处理。
#
# 输入：cap 目录（rgb1300.jpg + depth.yuv + meta.json，端侧 VinCaptureViewModel 落盘格式）。
# 输出：rectified.png（正射还原图）+ 调试图。
import os, sys, json, glob
import numpy as np
import cv2


def load_capture(capdir):
    meta = json.load(open(os.path.join(capdir, "meta.json")))
    dw, dh = meta["depth"]["w"], meta["depth"]["h"]
    depth = np.fromfile(os.path.join(capdir, "depth.yuv"), dtype="<u2").reshape(dh, dw)
    color = cv2.imread(os.path.join(capdir, "rgb1300.jpg"), cv2.IMREAD_COLOR)  # BGR
    return depth, color, meta


def backproject_roi(depth, K, roi):
    """中心 ROI 内有效深度像素 → depth 相机系 3D 点（mm）。"""
    fx, fy, cx, cy = K
    dh, dw = depth.shape
    rcx, rcy, rw, rh = roi
    u0, u1 = int((rcx - rw / 2) * dw), int((rcx + rw / 2) * dw)
    v0, v1 = int((rcy - rh / 2) * dh), int((rcy + rh / 2) * dh)
    u0, u1 = max(0, u0), min(dw, u1)
    v0, v1 = max(0, v0), min(dh, v1)
    us, vs = np.meshgrid(np.arange(u0, u1), np.arange(v0, v1))
    z = depth[v0:v1, u0:u1].astype(np.float64)
    m = z > 0
    z = z[m]; us = us[m]; vs = vs[m]
    X = (us - cx) / fx * z
    Y = (vs - cy) / fy * z
    return np.stack([X, Y, z], axis=1)  # (N,3)


def ransac_plane(pts, thr0=3.0, iters=300, seed=12345):
    """RANSAC 主平面 n·P+d=0，自适应阈值 max(thr0, 0.8%×中位深度)，内点 LS 精修，法向朝相机。"""
    rng = np.random.RandomState(seed)
    N = len(pts)
    med_z = np.median(pts[:, 2])
    thr = max(thr0, 0.008 * med_z)
    best_in, best_n, best_d = 0, np.array([0, 0, 1.0]), 0.0
    for _ in range(iters):
        idx = rng.randint(0, N, 3)
        a, b, c = pts[idx]
        n = np.cross(b - a, c - a)
        nn = np.linalg.norm(n)
        if nn < 1e-6:
            continue
        n = n / nn
        d = -n.dot(a)
        dist = np.abs(pts.dot(n) + d)
        cnt = int((dist <= thr).sum())
        if cnt > best_in:
            best_in, best_n, best_d = cnt, n, d
    # LS 精修
    inl = pts[np.abs(pts.dot(best_n) + best_d) <= thr]
    centroid = inl.mean(0)
    q = inl - centroid
    _, _, vt = np.linalg.svd(q, full_matrices=False)
    n = vt[2]
    n = n / np.linalg.norm(n)
    d = -n.dot(centroid)
    if n.dot(centroid) > 0:  # 朝相机（相机在原点）
        n, d = -n, -d
    rms = float(np.sqrt((((inl - centroid).dot(n)) ** 2).mean()))
    return dict(n=n, d=d, centroid=centroid, inlier_ratio=best_in / N, rms=rms, thr=thr, med_z=med_z)


def plane_basis(plane):
    n = plane["n"]
    cam_up = np.array([0.0, -1.0, 0.0])  # 图像 Y 朝下，真实"上"= -Y
    up = cam_up - cam_up.dot(n) * n
    if np.linalg.norm(up) < 1e-4:
        cam_x = np.array([1.0, 0.0, 0.0]); up = cam_x - cam_x.dot(n) * n
    up = up / np.linalg.norm(up)
    right = np.cross(up, n); right = right / np.linalg.norm(right)
    return right, up


def ortho_rectify(color, K_color, plane, pts, px=None, out_w=1400, out_h=420, margin=0.06):
    """正交基反向正射：每个输出像素 → 平面 3D 点 → 投影到彩色双线性采样。
       自动按平面内点在平面系的展布定框（px=None 时自适配满输出），保证整块板入图。
       K_color: (fx,fy,cx,cy) 彩色内参（假设与 depth 同光路 → =2×depth）。外参单位阵。"""
    fxc, fyc, cxc, cyc = K_color
    centroid = plane["centroid"]
    right, up = plane_basis(plane)
    # 平面内点投到 (right,up) 2D，取展布
    rel = pts - centroid
    a = rel.dot(right); b = rel.dot(up)
    amin, amax = np.percentile(a, [1, 99]); bmin, bmax = np.percentile(b, [1, 99])
    aw = (amax - amin) * (1 + margin); bh = (bmax - bmin) * (1 + margin)
    ac = (amax + amin) / 2; bc = (bmax + bmin) / 2
    if px is None:
        px = max(aw / out_w, bh / out_h)  # 等比适配填满输出
    js, is_ = np.meshgrid(np.arange(out_h), np.arange(out_w), indexing="ij")
    da = ac + (is_ + 0.5 - out_w / 2) * px
    db = bc - (js + 0.5 - out_h / 2) * px  # 行 0 = 顶部(+up)
    Q = (centroid[None, None, :]
         + da[..., None] * right[None, None, :]
         + db[..., None] * up[None, None, :])  # (H,W,3) depth 系
    Z = Q[..., 2]
    valid = Z > 1e-3
    u = fxc * Q[..., 0] / np.where(valid, Z, 1) + cxc
    v = fyc * Q[..., 1] / np.where(valid, Z, 1) + cyc
    out = cv2.remap(color, u.astype(np.float32), v.astype(np.float32),
                    interpolation=cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    out[~valid] = 0
    return out, dict(up=up, right=right, px=px, extent_mm=(aw, bh))


def restore_one(capdir, px=None, out_w=1400, out_h=600, roi=(0.5, 0.5, 0.9, 0.9)):
    depth, color, meta = load_capture(capdir)
    Kd = (meta["depth"]["fx"], meta["depth"]["fy"], meta["depth"]["cx"], meta["depth"]["cy"])
    # 假设配准：彩色内参 = depth × (彩色宽/深度宽)，外参单位阵。
    s = color.shape[1] / depth.shape[1]
    Kc = (Kd[0] * s, Kd[1] * s, Kd[2] * s, Kd[3] * s)
    pts = backproject_roi(depth, Kd, roi)
    if len(pts) < 100:
        return None, dict(err="too few depth points", npts=len(pts))
    plane = ransac_plane(pts)
    inl = pts[np.abs(pts.dot(plane["n"]) + plane["d"]) <= plane["thr"]]
    rect, basis = ortho_rectify(color, Kc, plane, inl, px, out_w, out_h)
    info = dict(npts=len(pts), extent_mm=basis["extent_mm"], px=basis["px"],
                **{k: plane[k] for k in ("inlier_ratio", "rms", "thr", "med_z")})
    return rect, info


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else ".dev/vin_captures"
    outdir = sys.argv[2] if len(sys.argv) > 2 else ".dev/vin_restore"
    os.makedirs(outdir, exist_ok=True)
    caps = sorted(glob.glob(os.path.join(root, "cap_*")))
    rows = []
    for c in caps:
        name = os.path.basename(c)
        rect, info = restore_one(c)
        if rect is None:
            print(name, "FAIL", info); continue
        cv2.imwrite(os.path.join(outdir, name + "_rect.png"), rect)
        print(name, "inlier=%.2f rms=%.2fmm medz=%.0f extent=%.0fx%.0fmm px=%.3f"
              % (info["inlier_ratio"], info["rms"], info["med_z"],
                 info["extent_mm"][0], info["extent_mm"][1], info["px"]))
        rows.append((name, rect))
    # 总览：每张还原图竖排
    if rows:
        h = rows[0][1].shape[0]; w = rows[0][1].shape[1]
        canvas = np.full((h * len(rows), w, 3), 30, np.uint8)
        for i, (name, r) in enumerate(rows):
            canvas[i * h:(i + 1) * h, :r.shape[1]] = r
            cv2.putText(canvas, name, (10, i * h + 24), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)
        cv2.imwrite(os.path.join(outdir, "overview_rect.png"), canvas)
        print("overview →", os.path.join(outdir, "overview_rect.png"))


if __name__ == "__main__":
    main()
