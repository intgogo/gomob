#!/usr/bin/env python3
# 带标定的 VIN 正射：把深度拟合平面经 R|t 变到 HLSD8 系，用真 K_hlsd8+Brown 畸变投影采样。
# 对比"无标定(2×depth+R=I)"看内凹/视角相关是否压平。用 calibration_2510DRK44C.json。
import cv2, numpy as np, json, os, sys, glob, math
sys.path.insert(0, 'tests/harness/vin_restore')
from obb import ObbDetector, _poly
from restore import load_capture, backproject_roi, ransac_plane, plane_basis

C = json.load(open('tests/harness/vin_calib/calibration_2510DRK44C.json'))
KH = np.array(C['hlsd8']['K']); DH = np.array(C['hlsd8']['dist'], np.float64)
R = np.array(C['extrinsic_hlsd8_from_depth']['R']); T = np.array(C['extrinsic_hlsd8_from_depth']['t_mm'])
MODEL = '.dev/vin_models/yolo-obb.onnx'
PX = 0.20; MX, MY = 10.0, 5.0
DS = float(os.environ.get('VIN_DEPTH_SCALE', '0.1116'))


def frame(capdir, det):
    depth, color, meta = load_capture(capdir)
    depth = depth.astype(np.float64) * DS
    Kd = (meta['depth']['fx'], meta['depth']['fy'], meta['depth']['cx'], meta['depth']['cy'])
    ch, cw = color.shape[:2]
    dets = det.detect(color)
    if not dets:
        return None
    d = max(dets, key=lambda x: x['score'])
    cp = _poly(d['corners'])
    xs = [p[0] for p in cp]; ys = [p[1] for p in cp]
    roi = ((min(xs)+max(xs))*0.5/cw, (min(ys)+max(ys))*0.5/ch,
           max(0.12, (max(xs)-min(xs))/cw*0.95), max(0.12, (max(ys)-min(ys))/ch*1.25))
    pts = backproject_roi(depth, Kd, roi)
    plane = ransac_plane(pts)
    tilt = math.degrees(math.acos(min(1.0, abs(plane['n'][2]))))
    right, up = plane_basis(plane)
    Cd = plane['centroid']
    # 深度系 → HLSD8 系
    Ch = R @ Cd + T; rh = R @ right; uh = R @ up; nh = R @ plane['n']
    # OBB 角点(HLSD8 像素) → 去畸变归一射线 → 交平面(HLSD8 系) → 平面内 ab
    und = cv2.undistortPoints(np.array(cp, np.float64).reshape(-1, 1, 2), KH, DH).reshape(-1, 2)
    ab = []
    for (xn, yn) in und:
        dr = np.array([xn, yn, 1.0])
        s = (nh @ Ch) / (nh @ dr)
        rel = s * dr - Ch
        ab.append([rel @ rh, rel @ uh])
    return dict(Ch=Ch, rh=rh, uh=uh, color=color, ab=np.array(ab, np.float64), tilt=tilt,
               inlier=plane['inlier_ratio'], rms=plane['rms'])


def render(f):
    ab = f['ab']; Ch = f['Ch']; rh = f['rh']; uh = f['uh']; color = f['color']
    tl, tr, br, bl = ab
    cen = (tl + tr + br + bl) * 0.25
    xd = (tr - tl) + (br - bl); xd = xd / (np.linalg.norm(xd) + 1e-9)
    yd = np.array([-xd[1], xd[0]])
    if (bl - tl) @ yd < 0:
        yd = -yd
    wmm = (np.linalg.norm(tr - tl) + np.linalg.norm(br - bl)) * 0.5 + 2 * MX
    hmm = (np.linalg.norm(bl - tl) + np.linalg.norm(br - tr)) * 0.5 + 2 * MY
    ow = max(8, int(round(wmm / PX))); oh = max(8, int(round(hmm / PX)))
    xs, ys = np.meshgrid(np.arange(ow), np.arange(oh))
    dx = (xs + 0.5 - ow / 2.0) * PX; dy = (ys + 0.5 - oh / 2.0) * PX
    a = cen[0] + dx * xd[0] + dy * yd[0]; b = cen[1] + dx * xd[1] + dy * yd[1]
    # 平面点(HLSD8 系) Q = Ch + a·rh + b·uh
    Q = (Ch[None, None, :] + a[..., None] * rh[None, None, :] + b[..., None] * uh[None, None, :]).reshape(-1, 3)
    # 投影(K_hlsd8 + Brown 畸变)；Q 已在 HLSD8 系 → rvec=tvec=0
    uv, _ = cv2.projectPoints(Q.astype(np.float64), np.zeros(3), np.zeros(3), KH, DH)
    uv = uv.reshape(oh, ow, 2).astype(np.float32)
    out = cv2.remap(color, uv[..., 0], uv[..., 1], cv2.INTER_LINEAR, borderValue=(0, 0, 0))
    return out, ow, oh, wmm - 2 * MX


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else '.dev/vin_live'
    out = sys.argv[2] if len(sys.argv) > 2 else '.dev/vin_calib_render'
    os.makedirs(out, exist_ok=True)
    det = ObbDetector(MODEL)
    tiles = []
    print('cap                          tilt  out(px)    VINwidth(mm)  inlier rms')
    for c in sorted(glob.glob(os.path.join(root, 'cap_*'))):
        n = os.path.basename(c)
        f = frame(c, det)
        if f is None:
            print('%-28s no-obb' % n); continue
        img, ow, oh, wmm = render(f)
        cv2.imwrite(os.path.join(out, n[:14] + '_cal.png'), img)
        print('%-28s %4.1f  %4dx%-4d  %6.1f       %.2f %.1f' % (n[:26], f['tilt'], ow, oh, wmm, f['inlier'], f['rms']))
        tiles.append((n[:14], img))
    if tiles:
        W = max(t[1].shape[1] for t in tiles)
        rows = []
        for nm, im in tiles:
            p = cv2.copyMakeBorder(im, 0, 0, 0, W - im.shape[1], cv2.BORDER_CONSTANT, value=(20, 20, 20))
            cv2.putText(p, nm, (4, 16), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 255), 1)
            rows.append(cv2.copyMakeBorder(p, 0, 3, 0, 0, cv2.BORDER_CONSTANT, value=(60, 60, 60)))
        cv2.imwrite(os.path.join(out, 'overview_calib.png'), np.vstack(rows))
        print('\noverview →', os.path.join(out, 'overview_calib.png'))


if __name__ == '__main__':
    main()
