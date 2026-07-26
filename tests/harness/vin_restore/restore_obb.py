#!/usr/bin/env python3
# Pass 2：OBB 锚定正射 —— 深度平面去透视 + VIN OBB 定中心/朝向 + 固定 px 定尺度 → 多张重合。
# 对齐原厂 restoreImageFlow：RANSAC 平面 → GetRotationMatrixForPlane（摆正）→ OBB 角点映射 → PerspectiveTrans。
# 这里用「平面基 + OBB 在平面内的中心/长轴/物理宽」直接定输出窗，等价且更稳。
import os, sys, glob, json, math
import numpy as np
import cv2
from obb import ObbDetector, _poly
from restore import load_capture, backproject_roi, ransac_plane, plane_basis

OUT_W = 1200                      # 输出窗宽度固定，高度按原厂 metric 画布比例动态算
MIN_OUT_H, MAX_OUT_H = 96, 260
MARGIN_X_MM, MARGIN_Y_MM = 10.0, 5.0
MODEL = ".dev/vin_models/yolo-obb.onnx"


MAX_TILT_DEG = 70.0  # 原厂硬门：承印面相对相机倾角 >70° 判废（restoreImageFlow 码 34）


# 去阴影质量闸：归一后墨水占比 > 此值 = 噪声/坏采集（真实 VIN 字带稀疏 ~8-12%）。
SIG_INK_MAX = 0.25


def signature_binarize(bgr):
    """去阴影 + OCR 级二值化（鲁棒版，2026-06-21 真机 21 组实证重写）。

    原厂 GetSignature3G 的 adaptiveThreshold(131,15) 在真机实测两处失效：① **极性假设固定**——刻字在不同
    补光角度下可比底暗也可比底亮(镜面高光灌进刻槽)，固定 BINARY+反相会整片翻转(白字黑底)；② **固定边距怕低对比**
    ——字-底差 < C 时整串丢字成碎片。改：双边降噪保边除微纹理 → **背景除法平照**(g÷大blur×180 拉平光照不均) →
    全局 Otsu → **极性归一**(真实墨水稀疏,前景过半即反相) → 去小斑 → 形态学。返回二值图(前景=黑0)。
    """
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    d = cv2.bilateralFilter(gray, 9, 60, 60)                          # 保边降噪，除钢板微纹理
    bg = cv2.GaussianBlur(d, (0, 0), 21)                              # 估局部光照
    norm = np.clip(d.astype(np.float32) / (bg.astype(np.float32) + 1) * 180, 0, 255).astype(np.uint8)
    _, fg = cv2.threshold(norm, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU)  # fg=白=暗于底(假定刻字)
    if (fg > 0).mean() > 0.5:                                         # 极性归一：墨水稀疏，前景过半=刻字偏亮→反相
        fg = cv2.bitwise_not(fg)
    cnts, _ = cv2.findContours(fg, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    for c in cnts:                                                    # 去小斑：宽高都≤39
        (_, _), (w, h), _ = cv2.minAreaRect(c)
        if int(w) <= 39 and int(h) <= 39:
            cv2.drawContours(fg, [c], -1, 0, -1)
    fg = cv2.morphologyEx(fg, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (2, 2)))
    fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)))
    return cv2.bitwise_not(fg)                                        # 回正：前景=黑


def picshadow_crop(binimg, pad=5):
    """原厂 picshadow（逆向逐指令对齐）：在已二值图(前景=黑0)上按黑像素行/列投影裁到墨迹紧致框+pad。
       行：含黑行(rowcnt!=0)的最长连续游程定 [top,bottom]；列：带内从两侧累计黑像素 >10 定 left/right。
       内容锚定（不靠抖动的 YOLO 框）→ 多张同 VIN 框一致 → 重合。返回裁剪后二值图。"""
    rows, cols = binimg.shape
    black = (binimg == 0)
    rowcnt = black.sum(1)
    # 最长含黑行游程
    top, bottom, best, rs, re_, prev = 0, -1, -1, 0, 0, False
    for r in range(rows):
        cur = rowcnt[r] != 0
        if cur:
            if not prev:
                rs = r
            re_ = r
        elif prev and (re_ - rs) >= best:
            best, top, bottom = re_ - rs, rs, re_
        prev = cur
    if bottom < top:                       # 内容延伸到末行(原厂不 flush)：harness 兜底用整段
        if prev and (re_ - rs) >= best:
            top, bottom = rs, re_
        else:
            return binimg
    band = black[top:bottom + 1]
    colcnt = band.sum(0)
    acc, left = 0, cols - 1
    for c in range(cols):
        if colcnt[c]:
            acc += colcnt[c]
            if acc > 10:
                left = c; break
    acc, right = 0, 0
    for c in range(cols - 1, -1, -1):
        if colcnt[c]:
            acc += colcnt[c]
            if acc > 10:
                right = c; break
    if right <= left or bottom <= top:
        return binimg
    x0, y0 = max(0, left - pad), max(0, top - pad)
    x1, y1 = min(cols, right + pad + 1), min(rows, bottom + pad + 1)
    return binimg[y0:y1, x0:x1]


def signature_content_rect(binimg):
    """内部签名投影裁切：最长墨迹行带 + 左右累计黑像素定界，返回带彩色上下文边距的 rect。"""
    rows, cols = binimg.shape[:2]
    black = (binimg < 128)
    rowcnt = black.sum(1)
    row_thresh = max(1, cols // 1000)
    best = (-1, 0, -1)
    in_run = False
    rs = 0
    for y in range(rows):
        has = rowcnt[y] >= row_thresh
        if has and not in_run:
            rs = y
            in_run = True
        if (not has or y == rows - 1) and in_run:
            re_ = y if has and y == rows - 1 else y - 1
            if re_ - rs > best[0]:
                best = (re_ - rs, rs, re_)
            in_run = False
    _, top, bottom = best
    if bottom < top:
        return None
    band = black[top:bottom + 1]
    colcnt = band.sum(0)
    if int(colcnt.sum()) < 80:
        return None
    acc, left = 0, cols - 1
    for x in range(cols):
        if colcnt[x]:
            acc += int(colcnt[x])
            if acc > 10:
                left = x
                break
    acc, right = 0, 0
    for x in range(cols - 1, -1, -1):
        if colcnt[x]:
            acc += int(colcnt[x])
            if acc > 10:
                right = x
                break
    if right <= left:
        return None
    cw, ch = right - left + 1, bottom - top + 1
    pad_x = max(16, int(round(cw * 0.06)))
    pad_y = max(8, int(round(ch * 0.55)))
    x0, x1 = max(0, left - pad_x), min(cols, right + pad_x + 1)
    y0, y1 = max(0, top - pad_y), min(rows, bottom + pad_y + 1)
    if x1 <= x0 or y1 <= y0:
        return None
    return x0, y0, x1, y1


def signature_skew_deg(binimg, rect):
    """用黑像素 PCA 估字符主轴角，角度为图像坐标系下相对水平的残余倾斜。"""
    x0, y0, x1, y1 = rect
    ys, xs = np.where(binimg[y0:y1, x0:x1] < 128)
    if len(xs) < 80:
        return None
    xs = xs.astype(np.float64) + x0
    ys = ys.astype(np.float64) + y0
    xs -= xs.mean()
    ys -= ys.mean()
    cxx = float((xs * xs).sum())
    cyy = float((ys * ys).sum())
    cxy = float((xs * ys).sum())
    if cxx + cyy < 1e-6:
        return None
    angle = 0.5 * math.degrees(math.atan2(2 * cxy, cxx - cyy))
    while angle >= 45:
        angle -= 90
    while angle < -45:
        angle += 90
    return angle


def align_color_by_signature(bgr, sig):
    """黑白签名只作内部校正信号；返回旋正、裁切、统一宽度后的彩色正射图。"""
    rect = signature_content_rect(sig)
    if rect is None:
        return bgr
    angle = signature_skew_deg(sig, rect)
    if angle is None or abs(angle) > 25:
        return bgr
    h, w = sig.shape[:2]
    rot_bgr, rot_sig = bgr, sig
    if abs(angle) >= 0.25:
        M = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
        rot_bgr = cv2.warpAffine(bgr, M, (w, h), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
        rot_sig = cv2.warpAffine(sig, M, (w, h), flags=cv2.INTER_NEAREST, borderMode=cv2.BORDER_CONSTANT, borderValue=255)
    rect = signature_content_rect(rot_sig)
    if rect is None:
        return bgr
    x0, y0, x1, y1 = rect
    if x1 - x0 < w // 3 or y1 - y0 < 6:
        return bgr
    crop = rot_bgr[y0:y1, x0:x1]
    out_h = int(round((y1 - y0) * OUT_W / max(1, (x1 - x0))))
    out_h = max(MIN_OUT_H, min(MAX_OUT_H, out_h))
    return cv2.resize(crop, (OUT_W, out_h), interpolation=cv2.INTER_LINEAR)


def ray_plane_inplane(u, v, K_color, plane, right, up):
    """彩色像素 (u,v) → 过相机原点射线 ∩ 平面 → depth 系 3D → 平面内 2D (a,b)（相对 centroid）。"""
    fx, fy, cx, cy = K_color
    d = np.array([(u - cx) / fx, (v - cy) / fy, 1.0])  # 视线方向（Z=1）
    n = plane["n"]; D = plane["d"]; C = plane["centroid"]
    t = -(n.dot(np.zeros(3)) + D) / n.dot(d)            # 原点 + t·d 落平面
    P = t * d
    rel = P - C
    return np.array([rel.dot(right), rel.dot(up)]), P


def restore_obb(capdir, det, px=None):
    depth, color, meta = load_capture(capdir)
    Kd = (meta["depth"]["fx"], meta["depth"]["fy"], meta["depth"]["cx"], meta["depth"]["cy"])
    s = color.shape[1] / depth.shape[1]
    # 彩色(HLSD8)内参：水平与深度 2× registered → fxc=2·fxd、cx/cy=2×；但 **fyc≠2·fyd**！
    # 深度被重度竖直 binning(640×128, fy≈164 anamorphic)，彩色不是——实测彩色近方形像素 fyc≈fxc，
    # 用 2·fyd 会留残余竖直透视→字符随拍摄倾角左右渐斜。真机 21 组实证 fyc=fxc 时倾角全≤2°。详见 finding 2026-06-22。
    Kc = (Kd[0] * s, Kd[0] * s, Kd[2] * s, Kd[3] * s)  # fyc = fxc（方形彩色像素）
    ch, cw = color.shape[:2]

    # ① 先检 OBB（定位钢牌上的 VIN 文字区）—— 用它把平面拟合限到承印面，排除 strip 上下背景。
    dets = det.detect(color)
    if not dets:
        return None, dict(err="no obb")
    d = max(dets, key=lambda x: x["score"])
    corners_px = _poly(d["corners"])  # TL,TR,BR,BL（color px）

    # ② 只在 OBB 区(=钢牌承印面)拟合平面。旧版取中心 90% 把 strip 上下背景纳入 → 法向被污染 → 去透视不彻底→字斜。
    #    用 OBB 轴对齐 bbox 换算成 depth 分数 ROI（÷ 颜色分辨率，分数与分辨率无关）；高度补一点(×1.25)多覆盖些钢牌面、夹钳定法向。
    xs = [p[0] for p in corners_px]
    ys = [p[1] for p in corners_px]
    rcx = (min(xs) + max(xs)) * 0.5 / cw
    rcy = (min(ys) + max(ys)) * 0.5 / ch
    rw = max(0.12, (max(xs) - min(xs)) / cw * 0.95)
    rh = max(0.12, (max(ys) - min(ys)) / ch * 1.25)
    pts = backproject_roi(depth, Kd, (rcx, rcy, rw, rh))
    plane = ransac_plane(pts)
    tilt = math.degrees(math.acos(min(1.0, abs(plane["n"][2]))))  # 原厂 tilt=acos(|nz|)
    if tilt > MAX_TILT_DEG:
        return None, dict(err="tilt>%g (=%.1f)" % (MAX_TILT_DEG, tilt))
    right, up = plane_basis(plane)

    # ③ OBB 4 角 → 平面内 2D（相对 centroid）
    ab = np.array([ray_plane_inplane(u, v, Kc, plane, right, up)[0] for (u, v) in corners_px], np.float32)
    tl, tr, br, bl = ab
    width_mm = (np.linalg.norm(tr - tl) + np.linalg.norm(br - bl)) * 0.5
    height_mm = (np.linalg.norm(bl - tl) + np.linalg.norm(br - tr)) * 0.5
    theta = math.degrees(math.atan2(((tr - tl) + (br - bl))[1], ((tr - tl) + (br - bl))[0]))
    return dict(plane=plane, right=right, up=up, Kc=Kc, color=color, ab=ab,
                width_mm=width_mm, height_mm=height_mm, theta=theta,
                inlier=plane["inlier_ratio"], rms=plane["rms"], medz=plane["med_z"]), None


def output_size(frame, out_w=OUT_W):
    """对齐原厂 metric 画布：四角外扩左右 10mm、上下 5mm；宽度归一到 out_w。"""
    w = max(float(frame["width_mm"]), 1.0)
    h = max(float(frame["height_mm"]), 1.0)
    scale = out_w / (w + 2 * MARGIN_X_MM)
    out_h = int(round((h + 2 * MARGIN_Y_MM) * scale))
    out_h = max(MIN_OUT_H, min(MAX_OUT_H, out_h))
    return out_w, out_h, scale


def render(frame, out_w=OUT_W):
    """原厂式 PerspectiveTrans：OBB 四角(平面内) → 动态高度输出矩形单应，钉死四角 →
       吸收残余旋转/尺度/keystone，多张直接重合。输出像素 → 单应 → 平面内(a,b) → 3D → 彩色采样。"""
    plane = frame["plane"]; right = frame["right"]; up = frame["up"]; Kc = frame["Kc"]
    C = plane["centroid"]; ab = frame["ab"]
    fxc, fyc, cxc, cyc = Kc
    out_w, out_h, scale = output_size(frame, out_w)
    px0, py0 = MARGIN_X_MM * scale, MARGIN_Y_MM * scale
    px1, py1 = out_w - px0, out_h - py0
    out_corners = np.array([[px0, py0], [px1, py0], [px1, py1], [px0, py1]], np.float32)  # TL,TR,BR,BL
    # 把 OBB 在平面内的 4 角(透视梯形/平行四边形)重建成**真矩形**(垂直边)：用平均长轴定向 + 垂直轴，
    # 这样输出↔平面是保角相似(旋转+各向异性缩放)而非含 shear 的一般单应 → 字符正(不再斜体状)。
    tl, tr, br, bl = ab
    c = (tl + tr + br + bl) * 0.25
    xdir = (tr - tl) + (br - bl)
    xdir = xdir / (np.linalg.norm(xdir) + 1e-9)        # 平面内文字水平方向(长轴)
    ydir = np.array([-xdir[1], xdir[0]], np.float64)   # 平面内垂直 x 轴
    if np.dot(bl - tl, ydir) < 0:
        ydir = -ydir                                    # 指向"下"(与 tl→bl 一致)
    hw = (np.linalg.norm(tr - tl) + np.linalg.norm(br - bl)) * 0.25
    hh = (np.linalg.norm(bl - tl) + np.linalg.norm(br - tr)) * 0.25
    rect = np.array([c - hw * xdir - hh * ydir, c + hw * xdir - hh * ydir,
                     c + hw * xdir + hh * ydir, c - hw * xdir + hh * ydir], np.float32)  # TL,TR,BR,BL 真矩形
    H = cv2.getPerspectiveTransform(out_corners, rect)  # 输出像素 → 平面内 (a,b)，保角无 shear
    xs, ys = np.meshgrid(np.arange(out_w), np.arange(out_h))  # 列=x，行=y
    g = np.stack([xs.ravel(), ys.ravel(), np.ones(xs.size)], 0)
    m = H @ g
    a = (m[0] / m[2]).reshape(out_h, out_w)
    b = (m[1] / m[2]).reshape(out_h, out_w)
    Q = (C[None, None, :] + a[..., None] * right[None, None, :] + b[..., None] * up[None, None, :])
    Z = Q[..., 2]; valid = Z > 1e-3
    u = fxc * Q[..., 0] / np.where(valid, Z, 1) + cxc
    v = fyc * Q[..., 1] / np.where(valid, Z, 1) + cyc
    out = cv2.remap(frame["color"], u.astype(np.float32), v.astype(np.float32),
                    cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=(0, 0, 0))
    out[~valid] = 0
    return out


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else ".dev/vin_captures"
    # 产物落点：优先 argv[2]，否则环境变量 OUTPUT_DIR，最后默认 .dev/vin_restore（与 run.sh 一致）
    outdir = sys.argv[2] if len(sys.argv) > 2 else os.environ.get("OUTPUT_DIR", ".dev/vin_restore")
    os.makedirs(outdir, exist_ok=True)
    det = ObbDetector(MODEL)
    frames = []
    for c in sorted(glob.glob(os.path.join(root, "cap_*"))):
        f, err = restore_obb(c, det)
        name = os.path.basename(c)
        if f is None:
            print(name, "FAIL", err); continue
        print(name, "obb width=%.0fmm h=%.0fmm theta=%.1f inlier=%.2f rms=%.1f medz=%.0f"
              % (f["width_mm"], f["height_mm"], f["theta"], f["inlier"], f["rms"], f["medz"]))
        frames.append((name, f))
    # 逐张按 OBB 宽归一化：深度非严格 metric（物理宽随距离变）→ 每张 VIN 渲染到同输出宽，
    # 深度只负责去透视。这样直接可比，重合质量 = 框内字符是否对齐。
    rows = []
    for name, f in frames:
        # 主锚定 = render 的 OBB 单应框（YOLO "number" 区→固定矩形，已排除首尾星号）→ 去阴影二值化（OCR级+去反光）。
        # 不再 picshadow 重裁：picshadow 按墨迹裁会把含/不含星号的差异引回来 → 框宽抖动。picshadow_crop 仅留作 Go 端口参照
        # （原厂在 number 框内裁，星号本就在框外）。
        r0 = render(f)
        sig0 = signature_binarize(r0)
        r = align_color_by_signature(r0, sig0)
        sig = signature_binarize(r)
        cv2.imwrite(os.path.join(outdir, name + "_obb.png"), r)
        # 质量闸：归一后墨水占比过高 = 噪声/坏采集（框偏/糊/低对比无法提字），判废不进重合分析。
        # _obb 仍写盘便于排查；_sig 只对通过质量闸的写（analyze 只看 _sig）。
        ink = float((sig == 0).mean())
        if ink > SIG_INK_MAX:
            print("  ↳ %s 判废：墨水占比 %.0f%% > %.0f%%（噪声/坏采集，对准钢牌重拍）"
                  % (name, ink * 100, SIG_INK_MAX * 100))
            continue
        cv2.imwrite(os.path.join(outdir, name + "_sig.png"), sig)
        rows.append((name, cv2.cvtColor(sig, cv2.COLOR_GRAY2BGR)))
    if rows:
        canvas = np.vstack([cv2.copyMakeBorder(r, 0, 3, 0, 0, cv2.BORDER_CONSTANT, value=(40, 40, 40))
                            for _, r in rows])
        cv2.imwrite(os.path.join(outdir, "overview_obb_rect.png"), canvas)
        print("overview →", os.path.join(outdir, "overview_obb_rect.png"))


if __name__ == "__main__":
    main()
