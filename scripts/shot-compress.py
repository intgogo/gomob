#!/usr/bin/env python3
"""压缩截图分辨率以便 LLM 分析。

输入：原始截图（PNG），通常 1080x2400 等高分辨率。
输出：等比缩放到最大边 = 目标值（默认 1024）的 PNG，覆盖原文件或写到 -o 指定路径。

用法：
    python3 scripts/shot-compress.py .dev/screenshots/foo.png            # 覆盖原文件
    python3 scripts/shot-compress.py .dev/screenshots/foo.png -o foo_small.png
    python3 scripts/shot-compress.py .dev/screenshots/foo.png -m 800     # 改最大边
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from PIL import Image


def compress(src: Path, dst: Path, max_side: int):
    img = Image.open(src)
    w0, h0 = img.size
    scale = max_side / max(w0, h0)
    if scale >= 1.0:
        if src == dst:
            return (w0, h0), (w0, h0), src.stat().st_size, src.stat().st_size
        img.save(dst, format="PNG", optimize=True)
        return (w0, h0), (w0, h0), src.stat().st_size, dst.stat().st_size
    w1, h1 = int(round(w0 * scale)), int(round(h0 * scale))
    img = img.convert("RGB") if img.mode in ("RGBA", "P") else img
    img = img.resize((w1, h1), Image.LANCZOS)
    img.save(dst, format="PNG", optimize=True)
    return (w0, h0), (w1, h1), src.stat().st_size, dst.stat().st_size


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("src", type=Path)
    ap.add_argument("-o", "--out", type=Path, default=None, help="输出路径，缺省覆盖 src")
    ap.add_argument("-m", "--max-side", type=int, default=1024, help="缩放后最大边长 px")
    a = ap.parse_args()
    if not a.src.exists():
        print(f"src 不存在: {a.src}", file=sys.stderr)
        return 2
    dst = a.out or a.src
    s0, s1, b0, b1 = compress(a.src, dst, a.max_side)
    print(f"{a.src} {s0[0]}x{s0[1]} ({b0/1024:.0f}KB) → {dst} {s1[0]}x{s1[1]} ({b1/1024:.0f}KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
