#!/usr/bin/env python3
"""device_realtime_interaction/analyze.py — 以日志为主判定设备消息 / 直播交互覆盖。"""

from __future__ import annotations

import glob
import json
import os
import re
import sys
from typing import Any


MESSAGE_SCENARIOS = [
    "D1.register_login",
    "D2.websocket_online",
    "D3.emulator_to_phone_message",
    "D4.phone_to_emulator_message",
    "D5.rest_history_and_read",
    "D6.offline_reconnect_fetch",
]

LIVE_SCENARIOS = [
    "L1.media_room_create_capability",
    "L2.live_session_list_capability",
]

FATAL_LOG_PATTERNS = [
    re.compile(r"FATAL EXCEPTION"),
    re.compile(r"AndroidRuntime"),
    re.compile(r"ANR in io\.gomob"),
    re.compile(r"HTTP FAILED"),
    re.compile(r"UnknownHostException"),
    re.compile(r"ConnectException"),
    re.compile(r"CLEARTEXT communication not permitted"),
    re.compile(r"(/v1/conversations|conversations).*404|404.*(/v1/conversations|conversations)"),
]

SERVER_FATAL_PATTERNS = [
    re.compile(r"\bpanic\b", re.IGNORECASE),
    re.compile(r"异常退出"),
    re.compile(r"服务端内部错误"),
    re.compile(r"\blevel=(error|fatal)\b", re.IGNORECASE),
    re.compile(r'"level"\s*:\s*"(ERROR|FATAL)"'),
]


def load_jsonl(path: str) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if not os.path.exists(path):
        return rows
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def read_capabilities(out_dir: str) -> dict[str, Any]:
    path = os.path.join(out_dir, "capabilities.json")
    if not os.path.exists(path):
        return {}
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def scan_logs(paths: list[str], patterns: list[re.Pattern[str]], limit: int = 8) -> tuple[int, list[str]]:
    hits: list[str] = []
    total = 0
    for path in paths:
        if not os.path.exists(path):
            continue
        try:
            with open(path, encoding="utf-8", errors="replace") as f:
                for no, line in enumerate(f, 1):
                    for pattern in patterns:
                        if pattern.search(line):
                            total += 1
                            if len(hits) < limit:
                                hits.append(f"{os.path.basename(path)}:{no}: {line.strip()[:220]}")
                            break
        except OSError as exc:
            hits.append(f"{os.path.basename(path)}: 读取失败 {exc}")
    return total, hits


def count_lines(path: str) -> int:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return sum(1 for _ in f)
    except OSError:
        return 0


def print_scenario(row: dict[str, Any]) -> None:
    severity = row.get("severity") or ("pass" if row.get("ok") else "fail")
    label = {"pass": "正常", "warn": "警告", "fail": "异常"}.get(severity, severity)
    scenario = row.get("scenario", "")
    latency = row.get("latency_ms", 0)
    note = row.get("note") or ""
    http = row.get("http_code")
    http_text = f" http={http}" if http else ""
    print(f"{label} {scenario:<38} {latency:>5}ms{http_text}  {note}")


def main(out_dir: str) -> int:
    rf = os.path.join(out_dir, "results.jsonl")
    rows = load_jsonl(rf)
    if not rows:
        print(f"异常：缺少或无法读取采样结果 {rf}")
        return 2

    by = {row.get("scenario"): row for row in rows}
    warnings: list[str] = []
    failures: list[str] = []

    print("消息链路：")
    for name in MESSAGE_SCENARIOS:
        row = by.get(name)
        if not row:
            print(f"异常 {name:<38} 缺场景")
            failures.append(f"缺场景 {name}")
            continue
        print_scenario(row)
        if row.get("severity") == "fail" or not row.get("ok"):
            failures.append(f"{name}: {row.get('note')}")

    print("\n直播控制面能力：")
    for name in LIVE_SCENARIOS:
        row = by.get(name)
        if not row:
            print(f"警告 {name:<38} 缺能力探测")
            warnings.append(f"缺直播能力探测 {name}")
            continue
        print_scenario(row)
        severity = row.get("severity")
        if severity == "fail":
            failures.append(f"{name}: {row.get('note')}")
        elif severity == "warn" or not row.get("ok"):
            warnings.append(f"{name}: {row.get('note')}")

    cap = read_capabilities(out_dir)
    devices = cap.get("devices") or load_jsonl(os.path.join(out_dir, "devices.jsonl"))
    emulator_count = cap.get("emulator_count", sum(1 for d in devices if d.get("kind") == "emulator"))
    physical_count = cap.get("physical_count", sum(1 for d in devices if d.get("kind") == "physical"))

    print("\n设备与日志：")
    print(f"设备覆盖：emulator={emulator_count} physical={physical_count} log_dir={out_dir}")
    if emulator_count == 0:
        warnings.append("未连接模拟器；本次只有 host 侧 emulator-sim 覆盖")
    if physical_count == 0:
        warnings.append("未连接真实手机；本次只有 host 侧 phone-sim 覆盖")
    for dev in devices:
        log_file = dev.get("log_file") or ""
        line_count = count_lines(log_file)
        print(
            "设备日志 "
            f"{dev.get('serial', '?')}({dev.get('kind', '?')}) "
            f"reverse={dev.get('reverse_ok')} app_started={dev.get('app_started')} "
            f"lines={line_count} file={os.path.basename(log_file)}"
        )
        if line_count == 0:
            warnings.append(f"{dev.get('serial', '?')} logcat 未采到 gomob/OkHttp 日志")

    adb_logs = glob.glob(os.path.join(out_dir, "adb-*.log"))
    fatal_count, fatal_hits = scan_logs(adb_logs, FATAL_LOG_PATTERNS)
    if fatal_count:
        failures.append(f"ADB 日志出现 {fatal_count} 条致命/网络错误")
        print("\nADB 异常样本：")
        for hit in fatal_hits:
            print(f"  {hit}")

    server_logs = [
        os.path.join(out_dir, name)
        for name in ("auth.log", "api.log", "gateway.log", "signaling.log", "deviceinteractionharness.log")
    ]
    server_fatal_count, server_hits = scan_logs(server_logs, SERVER_FATAL_PATTERNS)
    if server_fatal_count:
        failures.append(f"服务端日志出现 {server_fatal_count} 条异常")
        print("\n服务端异常样本：")
        for hit in server_hits:
            print(f"  {hit}")

    message_lats = sorted(
        int(r.get("latency_ms", 0))
        for r in rows
        if r.get("area") == "message" and r.get("latency_ms")
    )
    if message_lats:
        p95_index = min(len(message_lats) - 1, int(len(message_lats) * 0.95))
        print(
            "\n消息延迟："
            f"p50={message_lats[len(message_lats)//2]}ms "
            f"p95={message_lats[p95_index]}ms "
            f"max={max(message_lats)}ms n={len(message_lats)}"
        )

    if failures:
        print("\n异常：")
        for item in failures:
            print(f"- {item}")
        return 2

    if warnings:
        print("\n警告：")
        for item in warnings:
            print(f"- {item}")
        print("\n警告：消息链路正常；直播/设备覆盖仍有阻塞或缺口，未按真实直播通过计算。")
        return 1

    print("\n正常：模拟器端 / 真机端双向消息、REST 历史、离线补齐和直播控制面探测均通过。")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/device_realtime_interaction"
    sys.exit(main(out))
