#!/usr/bin/env python3
# eys3d-parse-descriptor.py — 解析 lsusb -v 的 UVC VideoStreaming 描述符,自动导出 mode25 的 VS 帧索引。
#
# 用途:device-gated 的 bFrameIndex(1280×256_MJPEG / 640×128)一次 lsusb 即定,喂 Mode25Usb2Plan。
#   真机:  ./scripts/eys3d-parse-descriptor.py            # 自动 lsusb -v -d 3438:0206 解析
#   离线测:./scripts/eys3d-parse-descriptor.py --selftest # 内置 fixture 验证解析逻辑
#
# 输出:每个 VS 接口的 (formatIndex, 格式, frameIndex, 宽×高, maxFrameSize),并定位 mode25 两路索引。
import re
import subprocess
import sys

EYS3D_VID, EYS3D_PID = 0x3438, 0x0206

# UVC VS 描述符 subtype
SUBTYPE_FMT = {4: "UNCOMPRESSED", 6: "MJPEG", 13: "FRAME_BASED"}
SUBTYPE_FRAME = {5: "UNCOMPRESSED", 7: "MJPEG", 12: "FRAME_BASED"}


def _int(tok):
    """lsusb 字段值取首 token 转 int(支持 0x / 十进制)。"""
    try:
        return int(tok, 0)
    except ValueError:
        return None


def parse(text):
    """解析 lsusb -v 文本 → {iface_num: [ {format_index, format, frames:[{frame_index,w,h,max}]} ]}。"""
    ifaces = {}
    cur_iface = None
    cur_format = None
    cur_frame = None
    in_vs = False
    for raw in text.splitlines():
        line = raw.strip()
        m = re.match(r"bInterfaceNumber\s+(\d+)", line)
        if m:
            cur_iface = int(m.group(1))
            cur_format = None
            cur_frame = None
            in_vs = False
            continue
        # VideoStreaming 接口段开始
        if "VideoStreaming Interface Descriptor" in line:
            in_vs = True
            cur_frame = None
            continue
        if not in_vs or cur_iface is None:
            continue
        m = re.match(r"bDescriptorSubtype\s+(\d+)", line)
        if m:
            st = int(m.group(1))
            if st in SUBTYPE_FMT:
                cur_format = {"format": SUBTYPE_FMT[st], "format_index": None, "frames": []}
                ifaces.setdefault(cur_iface, []).append(cur_format)
                cur_frame = None
            elif st in SUBTYPE_FRAME:
                cur_frame = {"frame_index": None, "w": None, "h": None, "max": None}
                if cur_format is not None:
                    cur_format["frames"].append(cur_frame)
            continue
        m = re.match(r"bFormatIndex\s+(\d+)", line)
        if m and cur_format is not None:
            cur_format["format_index"] = int(m.group(1))
            continue
        m = re.match(r"bFrameIndex\s+(\d+)", line)
        if m and cur_frame is not None:
            cur_frame["frame_index"] = int(m.group(1))
            continue
        m = re.match(r"wWidth\s+(\d+)", line)
        if m and cur_frame is not None:
            cur_frame["w"] = int(m.group(1))
            continue
        m = re.match(r"wHeight\s+(\d+)", line)
        if m and cur_frame is not None:
            cur_frame["h"] = int(m.group(1))
            continue
        m = re.match(r"dwMaxVideoFrameBufferSize\s+(\d+)", line)
        if m and cur_frame is not None:
            cur_frame["max"] = int(m.group(1))
            continue
    return ifaces


def find(ifaces, want_fmt, w, h):
    """在所有 VS 接口里找 (格式, 宽, 高) → (iface, format_index, frame_index, max)。无则 None。"""
    for iface, formats in ifaces.items():
        for fmt in formats:
            if fmt["format"] != want_fmt:
                continue
            for fr in fmt["frames"]:
                if fr["w"] == w and fr["h"] == h:
                    return (iface, fmt["format_index"], fr["frame_index"], fr["max"])
    return None


def report(ifaces):
    for iface in sorted(ifaces):
        print(f"=== VS Interface {iface} ===")
        for fmt in ifaces[iface]:
            print(f"  Format[{fmt['format_index']}] {fmt['format']}")
            for fr in fmt["frames"]:
                print(f"    frame[{fr['frame_index']}] {fr['w']}x{fr['h']}  max={fr['max']}")
    print()
    # mode25 两路定位
    color = find(ifaces, "MJPEG", 1280, 256)
    depth = find(ifaces, "UNCOMPRESSED", 640, 128)
    print("---- mode25 帧索引(喂 Mode25Usb2Plan)----")
    if color:
        print(f"  color IF{color[0]}: 1280x256 MJPEG  fmt={color[1]} frame={color[2]} max={color[3]}")
    else:
        print("  color 1280x256 MJPEG: 未找到(检查描述符)")
    if depth:
        print(f"  depth IF{depth[0]}: 640x128 UNCOMPRESSED  fmt={depth[1]} frame={depth[2]} max={depth[3]}")
        status_rows = (depth[3] // (640 * 2) - 128) if depth[3] else 0
        print(f"  depth 状态行估计: {status_rows} 行(maxFrameSize {depth[3]} vs 640x128x2=163840)")
    else:
        print("  depth 640x128: 未找到(检查描述符)")
    if color and depth:
        print(f"\n  ⇒ Mode25Usb2Plan(color_frame_index={color[2]}, depth_frame_index={depth[2]}"
              + (f", depth_status_rows={max(0, depth[3]//(640*2)-128)}" if depth[3] else "") + ")")
    return color, depth


# 内置 fixture(eYs3D RS-D550 描述符形态,据 doc13 §1;含 mode25 两路 + 干扰档)。
SELFTEST_FIXTURE = """
    Interface Descriptor:
      bInterfaceNumber        1
      bInterfaceClass        14 Video
      bInterfaceSubClass      2 Video Streaming
      VideoStreaming Interface Descriptor:
        bDescriptorSubtype      1 (INPUT_HEADER)
      VideoStreaming Interface Descriptor:
        bDescriptorSubtype      4 (FORMAT_UNCOMPRESSED)
        bFormatIndex            1
      VideoStreaming Interface Descriptor:
        bDescriptorSubtype      5 (FRAME_UNCOMPRESSED)
        bFrameIndex             1
        wWidth               2560
        wHeight               960
        dwMaxVideoFrameBufferSize  4915200
      VideoStreaming Interface Descriptor:
        bDescriptorSubtype      5 (FRAME_UNCOMPRESSED)
        bFrameIndex             2
        wWidth               1280
        wHeight               480
        dwMaxVideoFrameBufferSize  1228800
      VideoStreaming Interface Descriptor:
        bDescriptorSubtype      6 (FORMAT_MJPEG)
        bFormatIndex            2
      VideoStreaming Interface Descriptor:
        bDescriptorSubtype      7 (FRAME_MJPEG)
        bFrameIndex             1
        wWidth               1280
        wHeight               960
        dwMaxVideoFrameBufferSize  2457600
      VideoStreaming Interface Descriptor:
        bDescriptorSubtype      7 (FRAME_MJPEG)
        bFrameIndex             2
        wWidth               1280
        wHeight               256
        dwMaxVideoFrameBufferSize  655360
    Interface Descriptor:
      bInterfaceNumber        2
      bInterfaceClass        14 Video
      bInterfaceSubClass      2 Video Streaming
      VideoStreaming Interface Descriptor:
        bDescriptorSubtype      4 (FORMAT_UNCOMPRESSED)
        bFormatIndex            1
      VideoStreaming Interface Descriptor:
        bDescriptorSubtype      5 (FRAME_UNCOMPRESSED)
        bFrameIndex             1
        wWidth               1280
        wHeight               960
        dwMaxVideoFrameBufferSize  2457600
      VideoStreaming Interface Descriptor:
        bDescriptorSubtype      5 (FRAME_UNCOMPRESSED)
        bFrameIndex             4
        wWidth                640
        wHeight               128
        dwMaxVideoFrameBufferSize  163840
"""


def selftest():
    ifaces = parse(SELFTEST_FIXTURE)
    color, depth = report(ifaces)
    ok = True
    # 期望:color IF1 MJPEG 1280x256 = fmt2/frame2;depth IF2 UNCOMP 640x128 = fmt1/frame4,无状态行。
    if not (color and color[0] == 1 and color[1] == 2 and color[2] == 2):
        print("FAIL: color 索引不符,期望 IF1 fmt2 frame2"); ok = False
    if not (depth and depth[0] == 2 and depth[1] == 1 and depth[2] == 4 and depth[3] == 163840):
        print("FAIL: depth 索引不符,期望 IF2 fmt1 frame4 max163840"); ok = False
    print("\nselftest:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def main():
    if "--selftest" in sys.argv:
        return selftest()
    try:
        out = subprocess.run(["lsusb", "-v", "-d", f"{EYS3D_VID:04x}:{EYS3D_PID:04x}"],
                             capture_output=True, text=True, timeout=15)
        text = out.stdout
    except Exception as e:
        print(f"lsusb 失败({e});设备在线? 或用 --selftest 验证解析逻辑")
        return 2
    if "VideoStreaming" not in text:
        print("未拿到 VS 描述符(设备离线/权限?)。可 sudo lsusb -v -d 3438:0206 手动抓再管道喂本脚本。")
        return 2
    report(parse(text))
    return 0


if __name__ == "__main__":
    sys.exit(main())
