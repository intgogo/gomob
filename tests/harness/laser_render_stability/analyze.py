#!/usr/bin/env python3
"""判定车辆外廓三路 Filament 渲染在模拟器上的稳定性与结果完整性。"""

from __future__ import annotations

import csv
import json
import re
import sys
from collections import Counter
from pathlib import Path
from xml.etree import ElementTree as ET

try:
    from ui_query import (
        EXPECTED_MEASUREMENTS,
        EXPECTED_STATUS,
        summary as summarize_ui,
    )
except ModuleNotFoundError:
    from .ui_query import (
        EXPECTED_MEASUREMENTS,
        EXPECTED_STATUS,
        summary as summarize_ui,
    )


EXPECTED_FUSED_SOURCE = 2_050_753
EXPECTED_FUSED_RENDER = 262_144
EXPECTED_UNIT_RENDER = 65_536
EXPECTED_DIMENSION_LABELS = 0
EXPECTED_DIMENSION_BADGES = 0
EXPECTED_WIREFRAMES_ON = 1


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def read_log(out: Path) -> tuple[str, str]:
    streamed = out / "logcat.txt"
    final = out / "logcat-final.txt"
    streamed_text = streamed.read_text(encoding="utf-8", errors="replace") if streamed.exists() else ""
    final_text = final.read_text(encoding="utf-8", errors="replace") if final.exists() else ""
    if len(streamed_text.splitlines()) >= 10:
        return streamed_text, "logcat.txt"
    return final_text, "logcat-final.txt"


def inspect_ui(
    out: Path,
    name: str,
    failures: list[str],
    *,
    measurements_visible: bool,
    wireframe_visible: bool,
    storyboard: bool = False,
) -> dict[str, object]:
    path = out / "ui" / name
    if not path.exists():
        failures.append(f"缺少 UI 证据：ui/{name}")
        return {
            "labels": -1,
            "label_set": [],
            "badges": -1,
            "badge_set": [],
            "measurements": [],
            "wireframes": -1,
            "wireframe_nodes": [],
            "wireframe_line_counts": [],
            "storyboard_counts": [],
            "measurement_bounds_valid": False,
            "wireframe_bounds_valid": False,
        }
    try:
        ui = summarize_ui(path)
    except (ET.ParseError, OSError) as exc:
        failures.append(f"UI 证据损坏：ui/{name}: {exc}")
        return {
            "labels": -1,
            "label_set": [],
            "badges": -1,
            "badge_set": [],
            "measurements": [],
            "wireframes": -1,
            "wireframe_nodes": [],
            "wireframe_line_counts": [],
            "storyboard_counts": [],
            "measurement_bounds_valid": False,
            "wireframe_bounds_valid": False,
        }
    texts = list(ui["texts"])
    labels = list(ui["dimension_labels"])
    badges = list(ui["dimension_badges"])
    measurements = list(ui["measurement_results"])
    wireframe_nodes = list(ui["wireframe_nodes"])
    wireframe_line_counts = list(ui["wireframe_line_counts"])
    expected_wireframes = EXPECTED_WIREFRAMES_ON if wireframe_visible else 0
    if len(labels) != EXPECTED_DIMENSION_LABELS:
        failures.append(
            f"ui/{name} 点云尺寸文字={len(labels)}，期望 {EXPECTED_DIMENSION_LABELS}"
        )
    if len(set(labels)) != len(labels):
        failures.append(f"ui/{name} 存在重复点云尺寸文字")
    if len(badges) != EXPECTED_DIMENSION_BADGES:
        failures.append(
            f"ui/{name} 顶部长宽高徽章={len(badges)}，期望 {EXPECTED_DIMENSION_BADGES}："
            f"{sorted(badges)}"
        )
    if len(wireframe_nodes) != expected_wireframes:
        failures.append(
            f"ui/{name} 车辆外廓尺寸线框语义节点={len(wireframe_nodes)}，"
            f"期望 {expected_wireframes}"
        )
    if EXPECTED_STATUS not in texts:
        failures.append(f"ui/{name} 缺少指定融合源 {EXPECTED_FUSED_SOURCE:,}")
    has_measurements = bool(ui["measurement_results_exact"])
    if has_measurements != measurements_visible:
        failures.append(
            f"ui/{name} 13 条测量列表精确可见={has_measurements}，"
            f"期望 {measurements_visible}"
        )
    if measurements_visible and not has_measurements:
        failures.append(
            f"ui/{name} 测量列表不一致：实际={sorted(measurements)} 期望={sorted(EXPECTED_MEASUREMENTS)}"
        )
    if not measurements_visible and measurements:
        failures.append(f"ui/{name} 不应出现测量列表：{sorted(measurements)}")
    if measurements_visible:
        screen_bounds = ui["screen_bounds"]
        invalid_measurements = list(ui["invalid_measurement_bounds"])
        outside_measurements = list(ui["outside_measurement_bounds"])
        if screen_bounds is None:
            failures.append(f"ui/{name} 无法解析屏幕 bounds")
        if invalid_measurements:
            failures.append(
                f"ui/{name} 测量结果 bounds 为空或无效：{invalid_measurements}"
            )
        if outside_measurements:
            failures.append(
                f"ui/{name} 测量结果超出屏幕 {screen_bounds}：{outside_measurements}"
            )
    if wireframe_visible:
        if not ui["wireframe_descriptions_valid"]:
            failures.append(
                f"ui/{name} 线框语义必须为“车辆外廓尺寸线框 <N> 条”且 N>0："
                f"{wireframe_nodes}"
            )
        invalid_wireframes = list(ui["invalid_wireframe_bounds"])
        outside_wireframes = list(ui["outside_wireframe_bounds"])
        if invalid_wireframes:
            failures.append(f"ui/{name} 线框语义 bounds 为空或无效：{invalid_wireframes}")
        if outside_wireframes:
            failures.append(
                f"ui/{name} 线框语义超出屏幕 {ui['screen_bounds']}：{outside_wireframes}"
            )
    storyboard_counts = [
        value for value in texts if re.fullmatch(r"源 [\d,]+ · 显示 65,536", value)
    ]
    if storyboard:
        if len(storyboard_counts) != 2:
            failures.append(
                f"ui/{name} A/B 显示 65,536 的证据={len(storyboard_counts)} 条，期望 2"
            )
        for label in ("镜头 A", "镜头 B", "镜头 C · 未接入", "镜头 D · 未接入"):
            if label not in texts:
                failures.append(f"ui/{name} 缺少 {label}")
    return {
        "labels": len(labels),
        "label_set": sorted(labels),
        "badges": len(badges),
        "badge_set": sorted(badges),
        "measurements": sorted(measurements),
        "wireframes": len(wireframe_nodes),
        "wireframe_nodes": wireframe_nodes,
        "wireframe_line_counts": wireframe_line_counts,
        "wireframe_descriptions_valid": ui["wireframe_descriptions_valid"],
        "wireframe_bounds_valid": ui["wireframe_bounds_valid"],
        "invalid_wireframe_bounds": ui["invalid_wireframe_bounds"],
        "outside_wireframe_bounds": ui["outside_wireframe_bounds"],
        "storyboard_counts": storyboard_counts,
        "screen_bounds": ui["screen_bounds"],
        "measurement_nodes": ui["measurement_nodes"],
        "measurement_results_exact": ui["measurement_results_exact"],
        "measurement_bounds_valid": ui["measurement_bounds_valid"],
        "invalid_measurement_bounds": ui["invalid_measurement_bounds"],
        "outside_measurement_bounds": ui["outside_measurement_bounds"],
        "dimension_nodes": ui["dimension_nodes"],
    }


def parse_framestats(path: Path) -> list[float]:
    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    durations: list[float] = []
    index = 0
    while index < len(lines):
        header = lines[index].strip()
        if not header.startswith("Flags,") or "IntendedVsync" not in header:
            index += 1
            continue
        fields = next(csv.reader([header]))
        try:
            intended_index = fields.index("IntendedVsync")
            completed_index = fields.index("FrameCompleted")
        except ValueError:
            index += 1
            continue
        index += 1
        while index < len(lines):
            row_text = lines[index].strip()
            if not row_text or row_text.startswith("---PROFILEDATA---"):
                break
            row = next(csv.reader([row_text]))
            if len(row) > max(intended_index, completed_index):
                try:
                    intended = int(row[intended_index])
                    completed = int(row[completed_index])
                except ValueError:
                    index += 1
                    continue
                if intended > 0 and completed >= intended:
                    durations.append((completed - intended) / 1_000_000.0)
            index += 1
        index += 1
    return durations


def parse_threads(path: Path) -> dict[str, int]:
    text = path.read_text(encoding="utf-8", errors="replace")
    result: dict[str, int] = {}
    for key in ("pid", "task_count", "fengine_loop_count"):
        match = re.search(rf"^{key}=(\d*)$", text, re.MULTILINE)
        result[key] = int(match.group(1)) if match and match.group(1) else 0
    return result


def nearby_package(log_lines: list[str], index: int, package: str, radius: int = 10) -> bool:
    start = max(0, index - radius)
    end = min(len(log_lines), index + radius + 1)
    return any(package in line for line in log_lines[start:end])


def analyze(out: Path) -> int:
    failures: list[str] = []
    warnings: list[str] = []
    run_path = out / "run.json"
    if not run_path.exists():
        raise ValueError("缺少 run.json，采样没有完成清理")
    run = read_json(run_path)
    package = str(run.get("package") or "io.gomob.scan.debug")
    if not run.get("complete"):
        failures.append(f"采样未完成：phase={run.get('phase')} error={run.get('error') or '未知'}")
    expected_cycles = int(run.get("expected_cycles", 20))
    completed_cycles = int(run.get("completed_cycles", 0))
    if expected_cycles < 20:
        failures.append(f"配置只要求 {expected_cycles} 轮，验收下限为 20 轮")
    if completed_cycles != expected_cycles:
        failures.append(f"切换只完成 {completed_cycles}/{expected_cycles} 轮")
    if int(run.get("drag_sec", 0)) < 30:
        failures.append(f"连续拖动仅 {run.get('drag_sec')}s，要求至少 30s")
    if int(run.get("idle_sec", 0)) < 10:
        failures.append(f"静置仅 {run.get('idle_sec')}s，要求至少 10s")
    if not run.get("backend_health"):
        warnings.append("host 18808 健康检查失败，但 App 若完成真实恢复仍保留结果")

    exit_race_path = out / "exit-race.json"
    exit_race: dict[str, object] = {}
    if not exit_race_path.exists():
        failures.append("缺少 A/B Engine 初始化立即退出证据：exit-race.json")
    else:
        exit_race = read_json(exit_race_path)
        race_roundtrip_ms = int(exit_race.get("roundtrip_ms", 0))
        if not exit_race.get("responded"):
            failures.append("A/B Engine 初始化时立即返回未恢复到 3D 根页")
        if race_roundtrip_ms <= 0:
            failures.append("A/B Engine 初始化立即返回耗时无效")

    exit_log_path = out / "exit-race-logcat.txt"
    exit_log = exit_log_path.read_text(encoding="utf-8", errors="replace") \
        if exit_log_path.exists() else ""
    if not exit_log:
        failures.append("A/B Engine 初始化立即退出 logcat 为空")
    else:
        for label, pattern in {
            "ANR": rf"ANR in {re.escape(package)}|am_anr.*{re.escape(package)}|"
                rf"{re.escape(package)}.*Input dispatching timed out",
            "EGL_BAD_SURFACE": r"EGL_BAD_SURFACE",
            "abandoned BufferQueue": r"BufferQueue[^\n]*(?:has been |was )?abandoned|"
                r"abandoned BufferQueue|BLASTBufferQueue[^\n]*abandon",
            "Filament wrong thread": r"wrong thread|Engine::shutdown\(\) called from the wrong thread",
        }.items():
            if re.search(pattern, exit_log, re.IGNORECASE):
                failures.append(f"A/B Engine 初始化立即退出日志出现 {label}")

        exit_lines = exit_log.splitlines()
        for index, line in enumerate(exit_lines):
            if "FATAL EXCEPTION" in line and nearby_package(exit_lines, index, package):
                failures.append("A/B Engine 初始化立即退出日志出现 App FATAL EXCEPTION")
                break

        created = Counter(int(value) for value in re.findall(r"Engine 创建 budget=(\d+)", exit_log))
        created_on_owner = Counter(
            int(value) for value in re.findall(
                r"Engine 创建 budget=(\d+) owner=PointCloudFilamentOwner main=false",
                exit_log,
            )
        )
        destroyed = Counter(int(value) for value in re.findall(r"Engine 销毁完成 budget=(\d+)", exit_log))
        destroyed_on_owner = Counter(
            int(value) for value in re.findall(
                r"Engine 销毁完成 budget=(\d+) owner=PointCloudFilamentOwner main=false",
                exit_log,
            )
        )
        if created.get(262_144, 0) != 1:
            failures.append(f"退出竞态融合 Engine 创建数={created.get(262_144, 0)}，期望 1")
        if created != created_on_owner:
            failures.append("退出竞态存在不在 PointCloudFilamentOwner 创建的 Engine")
        if destroyed != destroyed_on_owner:
            failures.append("退出竞态存在不在 PointCloudFilamentOwner 销毁的 Engine")
        if destroyed != created:
            failures.append(f"退出竞态 Engine 创建/销毁不守恒：创建={dict(created)} 销毁={dict(destroyed)}")

        exit_race["engines_created"] = dict(created)
        exit_race["engines_destroyed"] = dict(destroyed)

    exit_gfx_path = out / "exit-race-gfxinfo.txt"
    exit_durations = parse_framestats(exit_gfx_path) if exit_gfx_path.exists() else []
    exit_max_frame_ms = max(exit_durations, default=0.0)
    if not exit_durations:
        failures.append("未解析到 A/B Engine 初始化立即退出的 gfxinfo 证据")
    elif exit_max_frame_ms >= 5_000.0:
        failures.append(f"A/B Engine 初始化立即退出主线程帧达到 {exit_max_frame_ms:.1f}ms")
    exit_race["max_frame_ms"] = exit_max_frame_ms
    exit_davey = [int(value) for value in re.findall(r"Davey! duration=(\d+)ms", exit_log)]
    exit_max_davey_ms = max(exit_davey, default=0)
    if exit_max_davey_ms >= 5_000:
        failures.append(f"A/B Engine 初始化立即退出 HWUI Davey 达到 {exit_max_davey_ms}ms")
    exit_race["max_davey_ms"] = exit_max_davey_ms

    log, log_source = read_log(out)
    if not log.strip():
        failures.append("logcat 为空")
    log_lines = log.splitlines()

    strict_log_patterns = {
        "ANR": rf"ANR in {re.escape(package)}|am_anr.*{re.escape(package)}|{re.escape(package)}.*Input dispatching timed out",
        "EGL_BAD_SURFACE": r"EGL_BAD_SURFACE",
        "abandoned BufferQueue": r"BufferQueue[^\n]*(?:has been |was )?abandoned|abandoned BufferQueue|BLASTBufferQueue[^\n]*abandon",
    }
    for label, pattern in strict_log_patterns.items():
        if re.search(pattern, log, re.IGNORECASE):
            failures.append(f"日志出现 {label}")

    for index, line in enumerate(log_lines):
        if "FATAL EXCEPTION" in line and nearby_package(log_lines, index, package):
            failures.append("日志出现 App FATAL EXCEPTION")
            break
    for index, line in enumerate(log_lines):
        if re.search(
            r"OutOfMemoryError|\bout of memory\b|Failed to allocate|"
            r"VK_ERROR_OUT_OF_(?:HOST|DEVICE)_MEMORY",
            line,
            re.I,
        ):
            if nearby_package(log_lines, index, package) or "PointCloud" in line or "Filament" in line:
                failures.append("日志出现 App/GPU OOM")
                break
    if re.search(r"Fatal signal.*(?:SIGABRT|SIGSEGV)", log) and re.search(
        r"PointCloud3dView|FEngine|filament|io\.gomob", log, re.I
    ):
        failures.append("日志出现 native FATAL signal")

    if re.search(r"--> POST https?://[^\s]+/v1/scans/laser(?:\s|\?|$)", log):
        failures.append("harness 期间发起了新激光扫描 POST")

    engine_budgets = [
        int(value) for value in re.findall(r"Engine 创建 budget=(\d+)", log)
    ]
    expected_engines = Counter({262_144: 1, 131_072: 2})
    if Counter(engine_budgets) != expected_engines:
        failures.append(
            f"Engine 创建={dict(Counter(engine_budgets))}，期望 {dict(expected_engines)}"
        )
    destroyed_engines = len(re.findall(r"Engine 销毁完成 budget=", log))
    if destroyed_engines:
        failures.append(f"页面驻留期间销毁了 {destroyed_engines} 个 Engine")

    uploads = [
        (int(total), int(uploaded), color == "true")
        for total, uploaded, color in re.findall(
            r"setPoints total=(\d+) uploaded=(\d+) color=(true|false)", log
        )
    ]
    expected_uploads = Counter(
        {
            (262_144, 262_144, True): 1,
            (65_536, 65_536, True): 2,
        }
    )
    if Counter(uploads) != expected_uploads:
        failures.append(
            "setPoints 不是三路各一次："
            f"实际={dict(Counter(uploads))} 期望={dict(expected_uploads)}"
        )
    if "setPoints 跳过" in log or "setPoints 后台命令失败" in log:
        failures.append("点云上传出现丢帧或后台命令失败")

    cloud_rows: dict[str, list[tuple[int, int, bool]]] = {"fused": [], "unit_a": [], "unit_b": []}
    for name, source, render, color in re.findall(
        r"final cloud=(fused|unit_a|unit_b) source=(\d+) render=(\d+) color=(true|false)", log
    ):
        cloud_rows[name].append((int(source), int(render), color == "true"))
    expected_clouds = {
        "fused": (EXPECTED_FUSED_SOURCE, EXPECTED_FUSED_RENDER, True),
        "unit_a": (None, EXPECTED_UNIT_RENDER, True),
        "unit_b": (None, EXPECTED_UNIT_RENDER, True),
    }
    final_clouds: dict[str, tuple[int, int, bool]] = {}
    for name, expected in expected_clouds.items():
        rows = cloud_rows[name]
        if not rows:
            failures.append(f"缺少 final cloud={name}")
            continue
        final = rows[-1]
        final_clouds[name] = final
        expected_source, expected_render, expected_color = expected
        if expected_source is not None and final[0] != expected_source:
            failures.append(f"{name} source={final[0]:,}，期望 {expected_source:,}")
        if final[1] != expected_render:
            failures.append(f"{name} render={final[1]:,}，期望 {expected_render:,}")
        if final[2] != expected_color:
            failures.append(f"{name} color={final[2]}，期望 {expected_color}")
        if name != "fused" and final[0] <= final[1]:
            failures.append(f"{name} 源点 {final[0]:,} 未高于显示预算 {final[1]:,}")
        if len(rows) > 1:
            warnings.append(f"final cloud={name} 日志出现 {len(rows)} 次，可能发生下载重试")

    ui_results = {
        "restore_fused.xml": inspect_ui(
            out,
            "restore_fused.xml",
            failures,
            measurements_visible=True,
            wireframe_visible=True,
        ),
        "overlay_off.xml": inspect_ui(
            out,
            "overlay_off.xml",
            failures,
            measurements_visible=False,
            wireframe_visible=False,
        ),
        "overlay_on.xml": inspect_ui(
            out,
            "overlay_on.xml",
            failures,
            measurements_visible=True,
            wireframe_visible=True,
        ),
        "storyboard_first.xml": inspect_ui(
            out,
            "storyboard_first.xml",
            failures,
            measurements_visible=False,
            wireframe_visible=False,
            storyboard=True,
        ),
        "after_cycles.xml": inspect_ui(
            out,
            "after_cycles.xml",
            failures,
            measurements_visible=True,
            wireframe_visible=True,
        ),
        "after_drag.xml": inspect_ui(
            out,
            "after_drag.xml",
            failures,
            measurements_visible=True,
            wireframe_visible=True,
        ),
        "after_idle.xml": inspect_ui(
            out,
            "after_idle.xml",
            failures,
            measurements_visible=True,
            wireframe_visible=True,
        ),
    }
    baseline_labels = ui_results["restore_fused.xml"]["label_set"]
    baseline_measurements = ui_results["restore_fused.xml"]["measurements"]
    for name in ("overlay_on.xml", "after_cycles.xml", "after_drag.xml", "after_idle.xml"):
        if ui_results[name]["label_set"] != baseline_labels:
            failures.append(f"ui/{name} 的点云尺寸文字集合与首次恢复不一致")
        if ui_results[name]["measurements"] != baseline_measurements:
            failures.append(f"ui/{name} 的完整测量列表与首次恢复不一致")

    cycle_path = out / "cycles.jsonl"
    cycle_rows: list[dict] = []
    if cycle_path.exists():
        for line_no, line in enumerate(cycle_path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip():
                continue
            try:
                cycle_rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                failures.append(f"cycles.jsonl 第 {line_no} 行损坏：{exc}")
    if len(cycle_rows) != expected_cycles:
        failures.append(f"cycles.jsonl 只有 {len(cycle_rows)}/{expected_cycles} 条")
    if [row.get("cycle") for row in cycle_rows] != list(range(1, expected_cycles + 1)):
        failures.append("切换轮次不连续")
    if any(not row.get("ok") for row in cycle_rows):
        failures.append("至少一轮切换未成功")

    gfx_metrics: dict[str, dict[str, float | int]] = {}
    all_durations: list[float] = []
    gfx_dir = out / "gfxinfo"
    for path in sorted(gfx_dir.glob("*.txt")) if gfx_dir.exists() else []:
        durations = parse_framestats(path)
        all_durations.extend(durations)
        gfx_metrics[path.name] = {
            "frames": len(durations),
            "max_frame_ms": max(durations, default=0.0),
        }
    if not all_durations:
        failures.append("未解析到任何 gfxinfo 主线程帧证据")
    max_frame_ms = max(all_durations, default=0.0)
    if max_frame_ms >= 5_000.0:
        failures.append(f"主线程单帧达到 {max_frame_ms:.1f}ms（阈值 5000ms）")
    davey = [int(value) for value in re.findall(r"Davey! duration=(\d+)ms", log)]
    if max(davey, default=0) >= 5_000:
        failures.append(f"HWUI Davey 单帧达到 {max(davey)}ms")
    idle_quiet = gfx_metrics.get("idle_quiet.txt")
    if idle_quiet and int(idle_quiet["frames"]) > 2:
        warnings.append(f"静置期仍产生 {idle_quiet['frames']} 个 HWUI 帧")

    thread_metrics: dict[str, dict[str, int]] = {}
    thread_dir = out / "threads"
    for path in sorted(thread_dir.glob("*.txt")) if thread_dir.exists() else []:
        thread_metrics[path.stem] = parse_threads(path)
    required_thread_samples = ("restore_fused", "storyboard_first", "after_cycles", "after_drag", "after_idle")
    for name in required_thread_samples:
        if name not in thread_metrics:
            failures.append(f"缺少线程样本：{name}")
    pids = {metric["pid"] for metric in thread_metrics.values() if metric["pid"] > 0}
    if len(pids) != 1:
        failures.append(f"App PID 不稳定：{sorted(pids)}")
    stable_thread_names = ("storyboard_first", "after_cycles", "after_drag", "after_idle")
    fengine_counts = [
        thread_metrics[name]["fengine_loop_count"]
        for name in stable_thread_names
        if name in thread_metrics
    ]
    if fengine_counts and any(value != fengine_counts[0] for value in fengine_counts):
        warnings.append(f"FEngine::loop 线程数有波动：{fengine_counts}")
    if fengine_counts and fengine_counts[-1] != 6:
        warnings.append(f"最终 FEngine::loop={fengine_counts[-1]}，模拟器预期约 6")
    task_counts = [
        thread_metrics[name]["task_count"]
        for name in stable_thread_names
        if name in thread_metrics
    ]
    if len(task_counts) >= 2 and task_counts[-1] - task_counts[0] > 8:
        warnings.append(f"总线程数从 {task_counts[0]} 增至 {task_counts[-1]}")

    status = "FAIL" if failures else "WARN" if warnings else "PASS"
    report = {
        "status": status,
        "failures": failures,
        "warnings": warnings,
        "run": run,
        "exit_race": exit_race,
        "log_source": log_source,
        "engine_budgets": engine_budgets,
        "destroyed_engines": destroyed_engines,
        "uploads": uploads,
        "final_clouds": final_clouds,
        "ui": ui_results,
        "cycles": {
            "count": len(cycle_rows),
            "max_total_ms": max((int(row.get("total_ms", 0)) for row in cycle_rows), default=0),
        },
        "gfxinfo": gfx_metrics,
        "max_frame_ms": max_frame_ms,
        "threads": thread_metrics,
    }
    (out / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(f"{status} — 车辆外廓模拟器渲染稳定性")
    for item in failures:
        print(f"  异常: {item}")
    for item in warnings:
        print(f"  警告: {item}")
    print(
        "  证据: "
        f"Engine={engine_budgets}，上传={uploads}，"
        f"循环={len(cycle_rows)}/{expected_cycles}，最大主线程帧={max_frame_ms:.1f}ms"
    )
    return {"PASS": 0, "WARN": 1, "FAIL": 2}[status]


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("FAIL — 车辆外廓模拟器渲染稳定性")
        print("  异常: 用法 analyze.py <输出目录>")
        return 2
    out = Path(argv[1])
    try:
        return analyze(out)
    except Exception as exc:
        failure = f"分析输入无效：{exc}"
        out.mkdir(parents=True, exist_ok=True)
        (out / "report.json").write_text(
            json.dumps(
                {"status": "FAIL", "failures": [failure], "warnings": []},
                ensure_ascii=False,
                indent=2,
            ),
            encoding="utf-8",
        )
        print("FAIL — 车辆外廓模拟器渲染稳定性")
        print(f"  异常: {failure}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
