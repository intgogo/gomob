#!/usr/bin/env python3
"""
depth_singlestream/analyze.py — 解析 dual stream 测试输出，产出可判定 result.json。

输入:
  --page-log    SonixDebugScreen 页面日志 (grep 'text="[...]"' 行)
  --logcat      gomob_native logcat dump
  --out         JSON 输出路径

输出 schema:
{
  "status": "OK" | "WARN" | "FAIL",
  "duration_ms": <数据流持续时间>,
  "data_reads": <成功读取次数>,
  "total_bytes": <累计字节>,
  "first_error_code": <首个 libusb 错误 -1000+e>,
  "keepalive_ka_count": <master XU 5 keepalive 发送次数>,
  "verdict": "<原因解释>"
}

判定:
  OK   ≥10 reads && total_bytes ≥ 320KB && first_error in {0, -1007 timeout}
  WARN ≥3 reads but < 10 OR first_error 是 -1004 NO_DEVICE (短暂跑通)
  FAIL 0 reads OR master 缺失 OR companion init/probe/commit 失败
"""
import argparse
import json
import re
import sys
from pathlib import Path


SUMMARY_RE = re.compile(
    r"summary: reads=(\d+) dataReads=(\d+) totalBytes=(\d+) firstErr=(-?\d+)"
)
# NativeStack 路径输出：frames=N  in=XB frames=N dropped=N splits=N queue=N
NATIVESTACK_SUMMARY_RE = re.compile(
    r"NativeStack summary: frames=(\d+)\s+in=(\d+)B"
)
KA_EXIT_RE = re.compile(r"ka thread exit n=(\d+)|keepalive exit n=(\d+)")
TEST_START_RE = re.compile(r"\[(\d{2}:\d{2}:\d{2}\.\d{3})\] === (?:DUAL STREAM TEST|NativeStack frame test) 开始")
TEST_END_RE = re.compile(r"\[(\d{2}:\d{2}:\d{2}\.\d{3})\] === (?:DUAL STREAM TEST|NativeStack frame test) 结束")


def parse_time_hms(s: str) -> float:
    h, m, rest = s.split(":")
    return int(h) * 3600 + int(m) * 60 + float(rest)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--page-log", required=True)
    ap.add_argument("--logcat", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    page_text = Path(args.page_log).read_text(errors="ignore") if Path(args.page_log).exists() else ""
    log_text = Path(args.logcat).read_text(errors="ignore") if Path(args.logcat).exists() else ""

    result = {
        "status": "FAIL",
        "duration_ms": 0,
        "data_reads": 0,
        "total_bytes": 0,
        "frames_assembled": 0,
        "first_error_code": None,
        "keepalive_ka_count": 0,
        "verdict": "no data parsed",
        "master_seen": False,
        "companion_init_ok": False,
        "uvc_commit_ok": False,
    }

    # 解析 page log
    if "master XU 5 init done" in page_text or "✅ stack.start OK" in page_text:
        result["master_seen"] = True
    if "companion init#6" in page_text or "✅ stack.start OK" in page_text:
        result["companion_init_ok"] = True
    if "companion UVC probe SET=true GET=true commit=true" in page_text or "✅ stack.start OK" in page_text:
        result["uvc_commit_ok"] = True

    m = SUMMARY_RE.search(page_text)
    if m:
        result["data_reads"] = int(m.group(2))
        result["total_bytes"] = int(m.group(3))
        first_err = int(m.group(4))
        result["first_error_code"] = first_err
    else:
        # 可能在 logcat 里
        m = SUMMARY_RE.search(log_text)
        if m:
            result["data_reads"] = int(m.group(2))
            result["total_bytes"] = int(m.group(3))
            result["first_error_code"] = int(m.group(4))

    # NativeStack 模式：frames + total bytes in
    m = NATIVESTACK_SUMMARY_RE.search(page_text)
    if m:
        result["frames_assembled"] = int(m.group(1))
        result["total_bytes"] = int(m.group(2))
        # NativeStack 不 surface firstErr 到 page log，但流真的死了 frames=0 + total_bytes 小是 NO_DEVICE 信号
        if result["frames_assembled"] == 0 and result["total_bytes"] == 0:
            result["first_error_code"] = -1004  # 推断 NO_DEVICE
    # ka count from logcat
    m = KA_EXIT_RE.search(log_text)
    if m:
        result["keepalive_ka_count"] = int(m.group(1) or m.group(2) or 0)

    # duration_ms from page log timestamps
    m_start = TEST_START_RE.search(page_text)
    m_end = TEST_END_RE.search(page_text)
    if m_start and m_end:
        dur = parse_time_hms(m_end.group(1)) - parse_time_hms(m_start.group(1))
        result["duration_ms"] = int(dur * 1000)

    # 判定
    reads = result["data_reads"]
    frames = result["frames_assembled"]
    bytes_ = result["total_bytes"]
    err = result["first_error_code"]
    # NativeStack 优先看 frames，旧路径看 reads
    primary_signal = frames if frames > 0 else reads
    if primary_signal >= 10 and bytes_ >= 320 * 1024 and err in (0, None, -1007):
        result["status"] = "OK"
        result["verdict"] = f"持续流 OK, frames={frames} reads={reads} {bytes_} bytes"
    elif primary_signal >= 3 or bytes_ >= 100 * 1024:
        result["status"] = "WARN"
        result["verdict"] = f"短暂跑通, frames={frames} reads={reads} bytes={bytes_} firstErr={err}"
    else:
        result["status"] = "FAIL"
        if result["master_seen"] is False and result["companion_init_ok"] is False:
            result["verdict"] = "test 未启动 / master 未发现"
        elif not result["uvc_commit_ok"]:
            result["verdict"] = "UVC probe/commit 失败"
        else:
            result["verdict"] = f"无 BULK 数据, firstErr={err}"

    Path(args.out).write_text(json.dumps(result, indent=2, ensure_ascii=False))
    print(json.dumps(result, indent=2, ensure_ascii=False))
    sys.exit(0 if result["status"] == "OK" else (2 if result["status"] == "FAIL" else 1))


if __name__ == "__main__":
    main()
