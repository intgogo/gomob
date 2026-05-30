#!/usr/bin/env python3
"""
从 companion bus8 BULK URB capdata 抽 raw 16-bit depth，dump 成 PGM 灰度图。

目的：M1.6.6 candidate C 验证后，确认 firmware 推的是真 depth 数据不是 garbage。
PGM 可用任何图片预览器看（GIMP / preview / display）。

用法：
  python3 scripts/extract_depth_bulk_to_pgm.py \
      .dev/m1.6.2-usb-trace/bus8-companion.pcap \
      .dev/depth_trace_visualization/

输出：
  - depth_raw.bin     原始 16-bit LE bytes
  - depth.pgm         16-bit PGM 灰度图 (width × rows_we_have)
  - stats.json        min/max/mean/zero_percent
"""
import json
import struct
import subprocess
import sys
from pathlib import Path


WIDTH = 640  # P100R3 companion frame index 2 width


def extract_bulk_payload(pcap_path: str) -> bytes:
    """从 pcap 抽第一个有 capdata 的 BULK URB (ep 0x82)，返字节。"""
    cmd = [
        "tshark", "-r", pcap_path,
        "-Y", "usb.endpoint_address == 0x82 && usb.capdata",
        "-T", "fields",
        "-e", "usb.capdata",
    ]
    out = subprocess.check_output(cmd, text=True)
    first_line = out.strip().split("\n")[0]
    hex_str = first_line.replace(":", "")
    return bytes.fromhex(hex_str)


def trim_trailing_zeros(data: bytes) -> bytes:
    """去尾巴填充字节（firmware padding to URB size）。"""
    end = len(data)
    while end > 0 and data[end - 1] == 0:
        end -= 1
    return data[:end]


def compute_stats(values: list[int]) -> dict:
    nonzero = [v for v in values if v != 0]
    if not nonzero:
        return {"all_zero": True}
    return {
        "count_total": len(values),
        "count_nonzero": len(nonzero),
        "zero_percent": round(100 * (len(values) - len(nonzero)) / len(values), 2),
        "min": min(nonzero),
        "max": max(nonzero),
        "mean_nonzero": round(sum(nonzero) / len(nonzero), 1),
    }


def save_pgm16(path: Path, width: int, height: int, values: list[int]):
    """写 binary PGM P5 16-bit。每像素 2 字节大端（PGM 规范）。"""
    with open(path, "wb") as f:
        f.write(f"P5\n{width} {height}\n65535\n".encode())
        for v in values:
            f.write(struct.pack(">H", v))  # big-endian


def main():
    if len(sys.argv) < 3:
        print(__doc__, file=sys.stderr)
        sys.exit(2)
    pcap = sys.argv[1]
    out_dir = Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)

    raw = extract_bulk_payload(pcap)
    valid = trim_trailing_zeros(raw)
    print(f"capdata: {len(raw)} bytes; valid (trailing zeros stripped): {len(valid)} bytes")

    # 16-bit LE depth
    n_pixels = len(valid) // 2
    values = list(struct.unpack(f"<{n_pixels}H", valid[: n_pixels * 2]))

    # 切成 640 宽多行
    height = n_pixels // WIDTH
    print(f"pixels: {n_pixels} → {WIDTH}×{height} (剩余 {n_pixels % WIDTH} 像素截断)")
    values = values[: WIDTH * height]

    # 保存
    (out_dir / "depth_raw.bin").write_bytes(valid)
    save_pgm16(out_dir / "depth.pgm", WIDTH, height, values)

    stats = compute_stats(values)
    stats["width"] = WIDTH
    stats["height"] = height
    stats["raw_bulk_bytes"] = len(raw)
    stats["valid_bytes"] = len(valid)
    stats["first_16_bytes_hex"] = valid[:16].hex()
    stats["last_valid_16_bytes_hex"] = valid[-16:].hex()
    (out_dir / "stats.json").write_text(json.dumps(stats, indent=2, ensure_ascii=False))

    print()
    print("stats:")
    print(json.dumps(stats, indent=2, ensure_ascii=False))
    print(f"\n图: {out_dir / 'depth.pgm'} (打开看看像不像真 depth)")


if __name__ == "__main__":
    main()
