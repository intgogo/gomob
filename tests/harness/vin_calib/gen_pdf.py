#!/usr/bin/env python3
# 把 ChArUco 板排到 A4 横向 PDF，板区精确 = squares×square_mm（mm 单位 extent）。
# Windows 用 Adobe Reader/Edge 打开 → 打印 → **实际大小 / 100% / 不缩放** → 真尺寸。
# 附 100mm 标尺条：打印后量这条=100mm 即真尺寸对了（量准了 board_spec 就准）。
import cv2, json, os
import numpy as np
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = json.load(open(os.path.join(HERE, "board_spec.json")))

def main():
    d = cv2.aruco.getPredefinedDictionary(getattr(cv2.aruco, SPEC["dict_name"]))
    board = cv2.aruco.CharucoBoard((SPEC["squares_x"], SPEC["squares_y"]),
                                   SPEC["square_mm"]/1000, SPEC["marker_mm"]/1000, d)
    bw = SPEC["squares_x"]*SPEC["square_mm"]; bh = SPEC["squares_y"]*SPEC["square_mm"]  # 板物理 mm
    img = board.generateImage((int(bw*12), int(bh*12)), marginSize=0, borderBits=1)  # 高清

    PW, PH = 297.0, 210.0  # A4 横向 mm
    x0, y0 = (PW-bw)/2, (PH-bh)/2 + 8  # 居中略上移给标尺让位
    fig = plt.figure(figsize=(PW/25.4, PH/25.4))
    ax = fig.add_axes([0, 0, 1, 1]); ax.set_xlim(0, PW); ax.set_ylim(0, PH)
    ax.axis("off"); ax.set_aspect("auto")
    ax.imshow(img, cmap="gray", extent=[x0, x0+bw, y0, y0+bh], origin="upper",
              aspect="auto", interpolation="nearest", zorder=1)
    ax.add_patch(Rectangle((x0, y0), bw, bh, fill=False, ec="red", lw=0.4, zorder=2))
    # 100mm 校验标尺
    sy = y0 - 12; sx = x0
    ax.plot([sx, sx+100], [sy, sy], "k-", lw=1.2, zorder=3)
    for xx in (sx, sx+100):
        ax.plot([xx, xx], [sy-2.5, sy+2.5], "k-", lw=1.2, zorder=3)
    ax.text(sx+50, sy-6, "100 mm  (print Actual Size / 100%%, then measure this bar)",
            ha="center", va="top", fontsize=7, zorder=3)
    ax.text(x0, y0+bh+5, "ChArUco %dx%d  square=%.1fmm  marker=%.1fmm  %s" %
            (SPEC["squares_x"], SPEC["squares_y"], SPEC["square_mm"], SPEC["marker_mm"], SPEC["dict_name"]),
            ha="left", va="bottom", fontsize=7, zorder=3)
    out = ".dev/vin_calib/charuco_A4_truesize_%.0fmm.pdf" % SPEC["square_mm"]
    fig.savefig(out); plt.close(fig)
    print("PDF ->", out, "| 板区 %.0fx%.0fmm, 每格 %.1fmm" % (bw, bh, SPEC["square_mm"]))

if __name__ == "__main__":
    main()
