#!/usr/bin/env python3
# VIN 字符 OBB 检测 —— 1:1 复刻原厂 com.esp.uvc.main.ONNXDetector（yolo-obb.onnx, YOLO11s-OBB 单类）。
# preprocess: letterbox 640×640 pad=114 / ÷255 / NCHW RGB（无 mean/std）。
# output[1,6,8400] 转置→每行 [cx,cy,w,h,score,angleRad]；score≥0.5；rotatedNms IoU>0.4。
# 每个检测 = 4 角点(sortCorners 排 TL/TR/BR/BL) + angleDeg(computeOrientationDegrees 长轴朝向)。
import math
import numpy as np
import cv2
import onnxruntime as ort

CONF = 0.5
NMS_IOU = 0.4
INP = 640
PAD = 114


class ObbDetector:
    def __init__(self, model_path):
        self.s = ort.InferenceSession(model_path, providers=["CPUExecutionProvider"])
        self.inname = self.s.get_inputs()[0].name

    def _preprocess(self, bgr):
        h, w = bgr.shape[:2]
        ratio = min(INP / w, INP / h)
        nw, nh = max(1, int(w * ratio)), max(1, int(h * ratio))
        padx, pady = (INP - nw) // 2, (INP - nh) // 2
        resized = cv2.resize(bgr, (nw, nh), interpolation=cv2.INTER_LINEAR)
        canvas = np.full((INP, INP, 3), PAD, np.uint8)
        canvas[pady:pady + nh, padx:padx + nw] = resized
        rgb = cv2.cvtColor(canvas, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
        chw = np.transpose(rgb, (2, 0, 1))[None]  # NCHW
        return chw, ratio, padx, pady

    def detect(self, bgr):
        chw, ratio, padx, pady = self._preprocess(bgr)
        out = self.s.run(None, {self.inname: chw})[0]  # [1,6,8400]
        pred = out[0].T  # (8400,6) = [cx,cy,w,h,score,angleRad]
        cands = []
        for row in pred:
            cx, cy, w, h, score, ang = row[0], row[1], row[2], row[3], row[4], row[5]
            if score < CONF or w <= 1 or h <= 1:
                continue
            cx = (cx - padx) / ratio; cy = (cy - pady) / ratio
            w /= ratio; h /= ratio
            angdeg = _norm_angle(math.degrees(ang))
            corners = _rotated_corners(cx, cy, w, h, angdeg)
            cands.append(dict(corners=corners, angle=_orient_deg(corners), score=float(score)))
        return _rotated_nms(cands)


def _norm_angle(a):
    while a >= 90.0:
        a -= 180.0
    while a < -90.0:
        a += 180.0
    return a


def _rotated_corners(cx, cy, w, h, angdeg):
    """原厂 createRotatedCorners：本地角点(±w/2,±h/2) 旋转 angdeg 平移到 (cx,cy)，再 sortCorners。"""
    r = (angdeg / 180.0) * math.pi
    cs, sn = math.cos(r), math.sin(r)
    hw, hh = w / 2, h / 2
    local = [(-hw, -hh), (hw, -hh), (hw, hh), (-hw, hh)]
    pts = []
    for x, y in local:
        pts.append((x * cs + cx - y * sn, x * sn + cy + y * cs))
    return _sort_corners(np.array(pts, np.float32).reshape(-1))


def _sort_corners(c):
    """原厂 sortCorners：按 x+y / x-y 排 TL/TR/BR/BL。c=[x0,y0,...x3,y3]。"""
    pts = c.reshape(4, 2)
    s = pts[:, 0] + pts[:, 1]
    d = pts[:, 0] - pts[:, 1]
    by_sum = np.argsort(s)
    by_diff = np.argsort(d)
    tl = pts[by_sum[0]]; br = pts[by_sum[-1]]
    tr = pts[by_diff[-1]]; bl = pts[by_diff[0]]
    return np.array([tl[0], tl[1], tr[0], tr[1], br[0], br[1], bl[0], bl[1]], np.float32)


def _orient_deg(c):
    """原厂 computeOrientationDegrees：取长轴朝向（水平边 vs 竖直边平均向量取长者）。"""
    x = c[0::2]; y = c[1::2]  # TL,TR,BR,BL
    hx = ((x[1] - x[0]) + (x[2] - x[3])) * 0.5
    hy = ((y[1] - y[0]) + (y[2] - y[3])) * 0.5
    vx = ((x[2] - x[1]) + (x[3] - x[0])) * 0.5
    vy = ((y[2] - y[1]) + (y[3] - y[0])) * 0.5
    if math.hypot(hx, hy) < math.hypot(vx, vy):
        a = math.atan2(vy, vx)
    else:
        a = math.atan2(hy, hx)
    return _norm_angle(math.degrees(a))


def _poly(c):
    return [(c[0], c[1]), (c[2], c[3]), (c[4], c[5]), (c[6], c[7])]


def _rotated_iou(a, b):
    pa = np.array(_poly(a), np.float32)
    pb = np.array(_poly(b), np.float32)
    inter, _ = cv2.intersectConvexConvex(pa, pb)
    if inter <= 0:
        return 0.0
    aa = cv2.contourArea(pa); ab = cv2.contourArea(pb)
    u = aa + ab - inter
    return inter / u if u > 1e-6 else 0.0


def _rotated_nms(cands):
    cands = sorted(cands, key=lambda c: -c["score"])
    keep = []
    for c in cands:
        if all(_rotated_iou(c["corners"], k["corners"]) <= NMS_IOU for k in keep):
            keep.append(c)
    return keep


if __name__ == "__main__":
    import sys, glob, os
    det = ObbDetector(".dev/vin_models/yolo-obb.onnx")
    caps = sorted(glob.glob(sys.argv[1] if len(sys.argv) > 1 else ".dev/vin_captures/cap_*"))
    os.makedirs(".dev/vin_restore", exist_ok=True)
    rows = []
    for c in caps:
        bgr = cv2.imread(os.path.join(c, "rgb1300.jpg"))
        dets = det.detect(bgr)
        viz = bgr.copy()
        for d in dets:
            pts = np.array(_poly(d["corners"]), np.int32)
            cv2.polylines(viz, [pts], True, (0, 0, 255), 1)
        print(os.path.basename(c), "chars=%d" % len(dets),
              "angles=", ["%.0f" % d["angle"] for d in dets][:6])
        rows.append(viz)
    if rows:
        canvas = np.vstack([cv2.copyMakeBorder(r, 0, 4, 0, 0, cv2.BORDER_CONSTANT, value=(40, 40, 40)) for r in rows])
        cv2.imwrite(".dev/vin_restore/overview_obb.png", canvas)
        print("viz → .dev/vin_restore/overview_obb.png")
