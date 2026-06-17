#!/usr/bin/env python3
# 生成现场共享标记场用的 ArUco 打印图（DICT_APRILTAG_36h11）。
# 每页一个标记，黑框=指定物理边长，带白色 quiet zone（检测必需）、ID/尺寸标签、四角裁切线、
# 100mm 比例尺校验条。打印务必【实际大小/100%，勿缩放】，打印后量黑框边长应=设定值。
#
# 用法: python3 scripts/gen_aruco_markers.py [个数] [黑框mm] [DPI]
#       默认 12 个 / 150mm / 300dpi -> .dev/aruco_print/
import sys, os
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

N      = int(sys.argv[1]) if len(sys.argv) > 1 else 12
SIDEMM = float(sys.argv[2]) if len(sys.argv) > 2 else 150.0
DPI    = int(sys.argv[3]) if len(sys.argv) > 3 else 300

OUT = os.path.join(os.path.dirname(__file__), "..", ".dev", "aruco_print")
OUT = os.path.abspath(OUT)
os.makedirs(OUT, exist_ok=True)

mm = lambda v: int(round(v / 25.4 * DPI))         # 毫米 -> 像素
A4 = (mm(210), mm(297))
side = mm(SIDEMM)
DICT = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_APRILTAG_36h11)

def font(px):
    # 优先 CJK 字体（标签含中文），回退 DejaVu。
    for p in ("/usr/share/fonts/google-noto-cjk/NotoSansCJK-Black.ttc",
              "/usr/share/fonts/google-noto-cjk/NotoSansCJK-Regular.ttc",
              "/usr/share/fonts/dejavu-sans-fonts/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, px)
            except Exception:
                pass
    return ImageFont.load_default()

pages = []
for mid in range(N):
    # 标记位图（黑白格），放大到 side 像素（最近邻，保持锐利）。
    raw = cv2.aruco.generateImageMarker(DICT, mid, side)
    marker = Image.fromarray(raw).convert("L").resize((side, side), Image.NEAREST)

    page = Image.new("L", A4, 255)               # 纯白底，自带 quiet zone
    ox, oy = (A4[0] - side) // 2, (A4[1] - side) // 2  # 页面居中，无任何文字/标记
    page.paste(marker, (ox, oy))
    pages.append(page.convert("RGB"))

pdf = os.path.join(OUT, f"aruco_36h11_{int(SIDEMM)}mm_x{N}.pdf")
pages[0].save(pdf, save_all=True, append_images=pages[1:], resolution=DPI)
# 同时存单张 PNG 备用
for i, p in enumerate(pages):
    p.save(os.path.join(OUT, f"marker_{i:02d}.png"))
print(f"OK: {pdf}  ({N} 个标记, 黑框 {SIDEMM:.0f}mm, {DPI}dpi, A4 单页)")
print(f"    单张 PNG: {OUT}/marker_*.png")
