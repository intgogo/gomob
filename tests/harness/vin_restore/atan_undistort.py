#!/usr/bin/env python3
# 原厂 VINCreator (libcreator_jni.so) atan 镜头畸变模型 + 设备标定 blob 解码 —— 参考实现。
#
# === 逆向结论 (确切公式) ===
# 反汇编 libcreator_jni.so 三个函数得到 atan 畸变模型 (per-axis FOV / Devernay 型):
#
#   LMPointCalibrator::applyAtanDistortion(double& rx, double& ry, double r, double a, double b)  @0x3845a0:
#       若 r < 1e-12: 不动 (rx,ry 原样)
#       否则:
#           rx_out = rx * a * atan(r / a) / r
#           ry_out = ry * b * atan(r / b) / r
#     其中 (rx,ry) 是输入归一化坐标, r = sqrt(rx^2+ry^2), a/b = 两个 atan 焦/FOV 参数 = 相机内参 fx,fy。
#
#   targetFun(@0x384630) 内联同一公式: 读 pt=[xn,yn], r=hypot(xn,yn), a=this[0x10], b=this[0x18],
#     算 xd=xn*a*atan(r/a)/r, yd=yn*b*atan(r/b)/r, 再叠一段 r^2 多项式径向 (calcDistortion 系数表)。
#     这是【正向: 理想归一坐标 -> 畸变归一坐标】。
#
#   UndistortionPointByLM(@0x3698e4) = 逆向(畸变像素 -> 理想像素), 用 LM 迭代反解 targetFun:
#       xn0 = (u - cx)/fx ; yn0 = (v - cy)/fy        ; cx=this[0x00] cy=this[0x08] fx=this[0x10] fy=this[0x18]
#       LM 迭代求 [xn,yn] 使 targetFun([xn,yn]) == [xn0,yn0]
#       u' = xn*fx + cx ; v' = yn*fy + cy            (去畸变后像素)
#   get_uv(@0x369b14) 再把 u',v' 减 cx,cy 除 fx,fy 得理想归一坐标。
#
# 即: 同一 (fx,fy,cx,cy) 既做归一化又做 atan 参数。畸变强度由 r/fx 决定。
#
# === 本文件提供 ===
#   - apply_atan_forward(xn, yn, a, b, poly): 正向畸变 (理想->畸变), 端口自 targetFun。
#   - undistort_points(u, v, K, poly): 逆向去畸变 (畸变像素->理想像素), 端口自 UndistortionPointByLM (用向量化牛顿迭代)。
#   - undistort_hlsd8(color_bgr, K, poly): 整图去畸变 (重映射), 用于喂给正射 render。
#   - HLSD8_215 / 等: 从 VIN_BF301215.bin 解出的 gomob HLSD8 内参+畸变 (见 blob 解码注释)。

import numpy as np
import cv2


# ---------- atan 正向畸变 (端口自 targetFun @0x384630) ----------
def apply_atan_forward(xn, yn, a, b, poly=None):
    """正向: 理想归一坐标 (xn,yn) -> 畸变归一坐标 (xd,yd)。
       atan 段端口自 applyAtanDistortion; poly = [k1,k2,...] r^2 多项式径向 (calcDistortion), 可选。
       公式: r=hypot(xn,yn); xd=xn*a*atan(r/a)/r; yd=yn*b*atan(r/b)/r; 再 *(1+k1 r^2+k2 r^4+...)"""
    xn = np.asarray(xn, np.float64); yn = np.asarray(yn, np.float64)
    r = np.hypot(xn, yn)
    safe = r > 1e-12
    rr = np.where(safe, r, 1.0)
    xd = np.where(safe, xn * a * np.arctan(rr / a) / rr, xn)
    yd = np.where(safe, yn * b * np.arctan(rr / b) / rr, yn)
    if poly:
        r2 = xd * xd + yd * yd
        rad = np.ones_like(r2)
        p = r2.copy()
        for k in poly:
            rad = rad + k * p
            p = p * r2
        xd = xd * rad; yd = yd * rad
    return xd, yd


# ---------- 逆向去畸变 (端口自 UndistortionPointByLM @0x3698e4, 向量化牛顿迭代) ----------
def undistort_points(u, v, K, poly=None, iters=20):
    """畸变像素 (u,v) -> 去畸变(理想)像素 (u',v')。K=(fx,fy,cx,cy)。
       反解 apply_atan_forward([xn,yn])==[(u-cx)/fx,(v-cy)/fy]。"""
    fx, fy, cx, cy = K
    u = np.asarray(u, np.float64); v = np.asarray(v, np.float64)
    xd = (u - cx) / fx           # 观测到的畸变归一坐标
    yd = (v - cy) / fy
    xn = xd.copy(); yn = yd.copy()   # 初值
    eps = 1e-7
    for _ in range(iters):
        fxn, fyn = apply_atan_forward(xn, yn, fx, fy, poly)
        ex = fxn - xd; ey = fyn - yd
        # 数值雅可比 (2x2)
        fxx, fyx = apply_atan_forward(xn + eps, yn, fx, fy, poly)
        fxy, fyy = apply_atan_forward(xn, yn + eps, fx, fy, poly)
        j11 = (fxx - fxn) / eps; j21 = (fyx - fyn) / eps
        j12 = (fxy - fxn) / eps; j22 = (fyy - fyn) / eps
        det = j11 * j22 - j12 * j21
        det = np.where(np.abs(det) < 1e-12, 1.0, det)
        dxn = (j22 * ex - j12 * ey) / det
        dyn = (-j21 * ex + j11 * ey) / det
        xn = xn - dxn; yn = yn - dyn
    up = xn * fx + cx
    vp = yn * fy + cy
    return up, vp


# ---------- FOV/atan 等距模型 (像素空间, 实测拟合形态) ----------
# 原厂 applyAtanDistortion 用同一参数同时做归一化和 atan, 数学上退化为近恒等(已验证)。真正起作用的
# 是【等距 FOV 模型】: 在像素空间 rd = a·atan(r/a) (a = 等效焦距/FOV 尺度 px)。这与原厂"r=atan(...)/类
# FOV"同族, 但参数 a 直接是像素焦距。实测对 HLSD8 真机 cap 联合拟合: a≈321px @1280×256 把弯曲压到 <0.02px。
def fov_undistort_map(shape, cx, cy, a):
    """FOV 等距去畸变映射(dst 理想 -> src 畸变, 供 cv2.remap)。
       forward(理想->畸变): r_ideal = sqrt(...); r_dist = a·atan(r_ideal/a)。"""
    h, w = shape[:2]
    xs, ys = np.meshgrid(np.arange(w, dtype=np.float64), np.arange(h, dtype=np.float64))
    x = xs - cx; y = ys - cy
    r = np.hypot(x, y)
    safe = r > 1e-9
    rr = np.where(safe, r, 1.0)
    scale = np.where(safe, a * np.arctan(rr / a) / rr, 1.0)   # 理想->畸变
    return (x * scale + cx).astype(np.float32), (y * scale + cy).astype(np.float32)


def fov_undistort_points(P, cx, cy, a):
    """点集去畸变(畸变像素 -> 理想像素): r_ideal = a·tan(r_dist/a)。"""
    x = P[:, 0] - cx; y = P[:, 1] - cy
    rd = np.hypot(x, y)
    safe = rd > 1e-9
    rr = np.where(safe, rd, 1.0)
    scale = np.where(safe, a * np.tan(rr / a) / rr, 1.0)
    return np.stack([x * scale + cx, y * scale + cy], 1)


def undistort_hlsd8(color_bgr, params, poly=None, iters=12):
    """整图去畸变。params 两种用法:
       - dict(cx,cy,a): 用实测 FOV 等距模型 (推荐, 见 HLSD8_215)。
       - tuple K=(fx,fy,cx,cy): 用原厂 atan+poly 模型 (近恒等, 仅作对照)。"""
    if isinstance(params, dict):
        mu, mv = fov_undistort_map(color_bgr.shape, params["cx"], params["cy"], params["a"])
        return cv2.remap(color_bgr, mu, mv, cv2.INTER_LINEAR,
                         borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    fx, fy, cx, cy = params
    h, w = color_bgr.shape[:2]
    xs, ys = np.meshgrid(np.arange(w), np.arange(h))
    xn = (xs - cx) / fx; yn = (ys - cy) / fy
    xd, yd = apply_atan_forward(xn, yn, fx, fy, poly)
    map_u = (xd * fx + cx).astype(np.float32)
    map_v = (yd * fy + cy).astype(np.float32)
    return cv2.remap(color_bgr, map_u, map_v, cv2.INTER_LINEAR,
                     borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))


# ---------- gomob HLSD8 标定 (实测联合拟合, @1280×256 彩色分辨率) ----------
# 来源: tests/harness 真机 cap_131/132 联合拟合 (.dev/atan_decode/fit_refine.py)。
# atan/FOV 等距模型把【原始彩色图】字符顶边弓高从 -10/-17px 压到 <0.04px (压平 281×/577×)。
# 单一参数集同压两不同角度采集 → 确认是 HLSD8 镜头 atan 畸变 (非 Brown 径向: Brown 最优残差仍 16px)。
#   cx,cy : 畸变中心 (px); a : atan/FOV 畸变尺度参数 (px, 越小弯越强) —— 即原厂 applyAtanDistortion 的 a/b。
#   f_proj: 去畸变后的【投影焦距】(px), 与 a 解耦 (a 管弯曲, f_proj 管整体尺度); 用于正射投影内参。
#           由 VIN 实际宽/深度距离反推 ~1000-1100px, 与 depth_fx*2≈1229 同量级。
# 注: blob 824-860 块(fx=1211.79,cx=629,baseline=49.89mm)是 depth/IR 立体矫正, 非 HLSD8 彩色;
#     HLSD8 彩色畸变参数取本实测拟合值 (blob 内无直接可用的 HLSD8 atan 槽, 详见 .dev/atan_decode/)。
# 两组拟合 (fit_refine.py / fit_multiline.py):
#   - 仅顶边联合: cx=587.5,cy=134.8,a=320.8 → 顶边压到 <0.04px (但属单线过拟合, 底边会反弹)。
#   - 多线鲁棒(顶+底×两图): cx=598.4,cy=163.7,a=410.7 → 四线总弓高 32px→7.4px (4×+);
#     残留 7px 是 cap131 的视角透视 (顶/底不能被纯径向同时压平), 由正射阶段处理。采用此鲁棒解。
HLSD8_215 = dict(cx=598.4, cy=163.7, a=410.7, f_proj=1050.0, model="fov_atan", res=(1280, 256))
HLSD8_215_TOPONLY = dict(cx=587.5, cy=134.8, a=320.8, f_proj=1050.0)  # 仅顶边过拟合解, 留作对照
