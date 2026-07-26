#!/usr/bin/env python3
# 生成 ChArUco 标定板 PNG（HLSD8↔depth 双相机标定用）。新 cv2.aruco API(4.7+)。
# 打印须 100%/实际大小（不缩放铺页），打印后用尺量一个白格确认边长，把实测填进 board_spec.json 的 square_mm。
import cv2, json, os
import numpy as np

# 板规格（真理源；harness 与端侧 meta 都引这里）。
# 14×8 格 @ 12mm + DICT_4X4：① 小格→1280×256 这种 5:1 宽条里能同时进多行(纵向覆盖、修内凹靠它)；
# ② 4×4 字典(比 5×5 少位)→低分辨率/轻糊也能解码；③ 8 行→局部视图上下滑动覆盖整个纵向 FOV。
# A4 横向打印(板 168×96mm)。HLSD8 全分辨率抓帧后 12mm 标记在 4160 宽里 ~280px，解码无压力。
SPEC = dict(squares_x=14, squares_y=8, square_mm=12.0, marker_mm=9.0,
            dict_name="DICT_4X4_100", dpi=300)

DICTS = {"DICT_5X5_100": cv2.aruco.DICT_5X5_100, "DICT_5X5_1000": cv2.aruco.DICT_5X5_1000,
         "DICT_4X4_100": cv2.aruco.DICT_4X4_100, "DICT_4X4_250": cv2.aruco.DICT_4X4_250}

def main():
    d = cv2.aruco.getPredefinedDictionary(DICTS[SPEC["dict_name"]])
    board = cv2.aruco.CharucoBoard((SPEC["squares_x"], SPEC["squares_y"]),
                                   SPEC["square_mm"]/1000.0, SPEC["marker_mm"]/1000.0, d)
    # 板物理尺寸 → 像素(@dpi)，保证 100% 打印时格子=square_mm。
    bw_mm = SPEC["squares_x"]*SPEC["square_mm"]; bh_mm = SPEC["squares_y"]*SPEC["square_mm"]
    px = lambda mm: int(round(mm*SPEC["dpi"]/25.4))
    img = board.generateImage((px(bw_mm), px(bh_mm)), marginSize=0, borderBits=1)
    # 加白边 + 角点信息条
    m = px(12)
    canvas = np.full((img.shape[0]+2*m+px(10), img.shape[1]+2*m, 1), 255, np.uint8)
    canvas[m:m+img.shape[0], m:m+img.shape[1], 0] = img
    txt = "ChArUco %dx%d sq=%.0fmm mk=%.0fmm %s  PRINT 100%% then MEASURE a square" % (
        SPEC["squares_x"], SPEC["squares_y"], SPEC["square_mm"], SPEC["marker_mm"], SPEC["dict_name"])
    cv2.putText(canvas, txt, (m, img.shape[0]+2*m+px(7)), cv2.FONT_HERSHEY_SIMPLEX, 0.9, 0, 2, cv2.LINE_AA)
    out = ".dev/vin_calib/charuco_%dx%d_%s_%.0fmm.png" % (
        SPEC["squares_x"], SPEC["squares_y"], SPEC["dict_name"], SPEC["square_mm"])
    cv2.imwrite(out, canvas)
    spec_path = "tests/harness/vin_calib/board_spec.json"
    json.dump(SPEC, open(spec_path, "w"), indent=2)
    print("board  ->", out, canvas.shape)
    print("spec   ->", spec_path, SPEC)
    print("内角点(charuco corners)数 =", (SPEC["squares_x"]-1)*(SPEC["squares_y"]-1))

if __name__ == "__main__":
    main()
