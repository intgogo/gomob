"""heuristic_roi — M3.14 阶段1 启发式前景分割的忠实 Python 复刻(供与 SAM 做 A/B)。

1:1 对照 native/reconstruction/scan_session.cpp 的 BuildForegroundDepth/EstimateForegroundSeedDepth:
  ① 中心 ROI(60%)取有效深度 P25 作种子;不足 500 px 退全区。
  ② 动态深度带 [max(200,seed-80), min(8000, seed+clamp(seed×0.45,280,2000))]。
  ③ 深度带内 4-连通块,中心 ROI(40%)命中数×32 + 面积 打分,取最高分块。
  ④ 最优块面积 ≥500 → 输出该块深度;否则退整带(宁缺勿滤)。
有效深度窗 = IsDepthValid 的 [200,8000]mm。

box 版(传 box)= stage1"用户拉 ROI 框补救"路径:把上述全部限制在框内,seed/中心 ROI 按框缩放
(steelman 启发式:给它和 SAM 同一人工框)。box=None = 忠实原生(按整图分数取 ROI)。

**这是纯深度法**:目标坐在地面上、接触处深度连续时,基座一圈地面会被同一连通块吃进来(裙边毛刺),
这正是 SAM 按外观能切干净、启发式切不掉的根本差异。纯算法供 harness 复用,不是生产代码。
"""
from __future__ import annotations

import numpy as np
from scipy import ndimage

VALID_LO, VALID_HI = 200.0, 8000.0   # IsDepthValid 窗口(mm)


def _region_box(box, H, W):
    if box is None:
        return 0, 0, W - 1, H - 1
    x0, y0, x1, y1 = [int(round(v)) for v in box]
    return max(0, x0), max(0, y0), min(W - 1, x1), min(H - 1, y1)


def _roi_bounds(rx0, ry0, rx1, ry1):
    """返回 (seed_roi, center_roi),各为**半开** [x0,x1)×[y0,y1),逐位复刻 native 的两套整数约定:
      seed ROI  = 各维 [w/5, w - w/5)   (中心~60%,对应 EstimateForegroundSeedDepth 的 width/5、width-width/5)
      center ROI= 各维 [w*3/10, w*7/10) (中心40%,对应 is_center 的 width*3/10、width*7/10,半开 x<cx1)
    全图(rx0=0,rx1=W-1)时与 native 逐位一致;box 版按框宽高同式(box 是本 harness 扩展,无 native 对照)。"""
    rw, rh = rx1 - rx0 + 1, ry1 - ry0 + 1
    seed = (rx0 + rw // 5, ry0 + rh // 5, rx0 + rw - rw // 5, ry0 + rh - rh // 5)
    ctr = (rx0 + rw * 3 // 10, ry0 + rh * 3 // 10, rx0 + rw * 7 // 10, ry0 + rh * 7 // 10)
    return seed, ctr


def _hist_p25(depths: np.ndarray) -> float:
    """bit 级复刻 native PercentileFromHistogram(hist, total, 0.25):1mm-bin 累积首达 ceil(0.25·n) 的 bin
    = 第 ⌈0.25·n⌉ 小的**整数 mm** 深度(深度按 uint16 floor 到整 mm,与端侧一致)。非 np.percentile 线性插值。"""
    di = np.sort(np.floor(depths).astype(np.int64))
    n = di.size
    if n == 0:
        return 0.0
    target = max(1, int(np.ceil(n * 0.25)))
    return float(di[min(target - 1, n - 1)])


def foreground_depth(depth_mm: np.ndarray, box=None) -> np.ndarray:
    """启发式前景深度:返回与输入同形 float32,前景保留原深度、其余置 0。box 见模块头。"""
    d = depth_mm.astype(np.float32)
    H, W = d.shape
    valid = (d >= VALID_LO) & (d <= VALID_HI)
    rx0, ry0, rx1, ry1 = _region_box(box, H, W)
    region = np.zeros((H, W), bool)
    region[ry0:ry1 + 1, rx0:rx1 + 1] = True

    (sx0, sy0, sx1, sy1), (cx0, cy0, cx1, cy1) = _roi_bounds(rx0, ry0, rx1, ry1)

    # ① 种子:中心 60% ROI 的 P25(直方图法,半开 ROI);不足 500 退全区(原生退整图,这里退框/全图)
    seed_sel = np.zeros((H, W), bool)
    seed_sel[sy0:sy1, sx0:sx1] = True
    sd = d[valid & seed_sel]
    if sd.size < 500:
        sd = d[valid & region]
    if sd.size == 0:
        return np.zeros_like(d)
    seed = _hist_p25(sd)
    if seed <= 0:
        return np.zeros_like(d)

    # ② 深度带
    band_after = round(min(max(seed * 0.45, 280.0), 2000.0))
    lower, upper = max(200.0, seed - 80), min(8000.0, seed + band_after)
    band = valid & region & (d >= lower) & (d <= upper)
    if not band.any():
        return np.zeros_like(d)

    # ③ 4-连通块(ndimage 默认 4-邻),中心 40% ROI(半开)命中×32 + 面积 打分
    lab, n = ndimage.label(band)
    center = np.zeros((H, W), bool)
    center[cy0:cy1, cx0:cx1] = True
    best_label, best_area, best_score = 0, 0, -1
    for lb in range(1, n + 1):
        comp = lab == lb
        area = int(comp.sum())
        ch = int((comp & center).sum())
        score = ch * 32 + area if ch > 0 else area
        if score > best_score:
            best_score, best_label, best_area = score, lb, area

    # ④ 输出
    out = np.zeros_like(d)
    if best_area >= 500:
        sel = lab == best_label
    else:
        sel = band                       # 块碎裂 → 退整带
    out[sel] = d[sel]
    return out
