#!/usr/bin/env python3
"""向旁路 cvengine 重放真实 VIN RGBD，并保存结构化性能样本。"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import statistics
import time
from datetime import datetime, timezone
from pathlib import Path

import requests


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def request_once(url: str, capture: Path, meta: dict, index: int) -> tuple[dict, bytes]:
    depth = meta["depth"]
    color = meta["color"]
    sync = meta["sync"]
    timestamp_offset = index * 1_000_000
    form = {
        "depth_w": str(depth["w"]),
        "depth_h": str(depth["h"]),
        "fx": str(depth["fx"]),
        "fy": str(depth["fy"]),
        "cx": str(depth["cx"]),
        "cy": str(depth["cy"]),
        "device_id": meta["depthDeviceSerial"],
        "color_device_id": meta["colorDeviceSerial"],
        "color_w": str(color.get("encodedW", color.get("w"))),
        "color_h": str(color.get("encodedH", color.get("h"))),
        "color_timestamp_us": str(sync["colorTimestampUs"] + timestamp_offset),
        "depth_timestamp_us": str(sync["depthTimestampUs"] + timestamp_offset),
        "log_id": f"vin_restore_perf_{time.time_ns()}_{index}",
    }
    started = time.perf_counter()
    with (capture / "rgb1300.jpg").open("rb") as rgb_file, (
        capture / "depth.yuv"
    ).open("rb") as depth_file:
        response = requests.post(
            url,
            data=form,
            files={
                "image_binary_rgb1300": ("rgb1300.jpg", rgb_file, "image/jpeg"),
                "image_binary_depth": (
                    "depth.u16",
                    depth_file,
                    "application/octet-stream",
                ),
            },
            timeout=60,
        )
    received = time.perf_counter()
    payload = response.json()
    parsed = time.perf_counter()
    data = payload.get("data") or {}
    encoded = data.get("result_png_base64") or ""
    png = base64.b64decode(encoded, validate=True) if encoded else b""
    decoded = time.perf_counter()
    result = {
        "index": index,
        "status": response.status_code,
        "code": payload.get("code"),
        "ok": bool(data.get("ok")),
        "reject_reason": data.get("reject_reason", ""),
        "http_ms": round((received - started) * 1000, 3),
        "json_ms": round((parsed - received) * 1000, 3),
        "base64_decode_ms": round((decoded - parsed) * 1000, 3),
        "response_bytes": len(response.content),
        "png_bytes": len(png),
        "png_sha256": sha256_bytes(png) if png else "",
        "width": data.get("width"),
        "height": data.get("height"),
        "log_id": data.get("log_id"),
        "calibration_sha256": data.get("calibration_sha256"),
        "text_anchor": data.get("text_anchor"),
    }
    return result, png


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--count", type=int, default=5)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--save-first-png", type=Path)
    args = parser.parse_args()
    if args.count <= 0:
        parser.error("--count 必须为正数")

    meta = json.loads((args.capture / "meta.json").read_text(encoding="utf-8"))
    reference = args.capture / "restored.png"
    reference_bytes = reference.read_bytes() if reference.exists() else b""
    results = []
    for index in range(args.count):
        result, png = request_once(args.url, args.capture, meta, index)
        results.append(result)
        print(json.dumps(result, ensure_ascii=False))
        if index == 0 and png and args.save_first_png:
            args.save_first_png.parent.mkdir(parents=True, exist_ok=True)
            args.save_first_png.write_bytes(png)

    successful_ms = [float(item["http_ms"]) for item in results if item["ok"]]
    report = {
        "created_at": datetime.now(timezone.utc).isoformat(),
        "capture": str(args.capture.resolve()),
        "url": args.url,
        "cpu_count": os.cpu_count(),
        "load_average": list(os.getloadavg()),
        "reference_png_bytes": len(reference_bytes),
        "reference_png_sha256": sha256_bytes(reference_bytes) if reference_bytes else "",
        "results": results,
        "successful_count": len(successful_ms),
        "http_ms_median": statistics.median(successful_ms) if successful_ms else None,
        "http_ms_max": max(successful_ms) if successful_ms else None,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
