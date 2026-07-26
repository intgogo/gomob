#!/usr/bin/env python3
"""判定真实激光预览是否仍有无界 Java/Direct/GPU 资源增长。"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def parse_limit_kb(raw: str) -> int:
    match = re.fullmatch(r"\s*(\d+)\s*([kKmMgG]?)\s*", raw or "")
    if not match:
        return 192 * 1024
    value = int(match.group(1))
    unit = match.group(2).lower()
    return value * ({"": 1, "k": 1, "m": 1024, "g": 1024 * 1024}[unit])


def median(values: list[int]) -> float:
    ordered = sorted(values)
    if not ordered:
        return 0.0
    mid = len(ordered) // 2
    if len(ordered) % 2:
        return float(ordered[mid])
    return (ordered[mid - 1] + ordered[mid]) / 2.0


def evaluate_phase_growth(
    label: str,
    values: list[int],
    heap_limit: int,
    failures: list[str],
    warnings: list[str],
    *,
    use_tail_half: bool,
) -> None:
    phase = values[len(values) // 2 :] if use_tail_half else values
    window = min(3, max(2, len(phase) // 3))
    head_med = median(phase[:window])
    tail_med = median(phase[-window:])
    growth = tail_med - head_med
    if growth > heap_limit * 0.10:
        failures.append(f"{label} Heap 仍增长 {growth / 1024:.1f}MiB")
    elif growth > heap_limit * 0.05:
        warnings.append(f"{label} Heap 增长 {growth / 1024:.1f}MiB")


def evaluate_rss_growth(
    label: str,
    values: list[int],
    failures: list[str],
    warnings: list[str],
    *,
    use_tail_half: bool,
) -> None:
    phase = values[len(values) // 2 :] if use_tail_half else values
    window = min(3, max(2, len(phase) // 3))
    growth = median(phase[-window:]) - median(phase[:window])
    if growth > 128 * 1024:
        failures.append(f"{label} VmRSS 仍增长 {growth / 1024:.1f}MiB")
    elif growth > 64 * 1024:
        warnings.append(f"{label} VmRSS 增长 {growth / 1024:.1f}MiB")


def phase_samples(samples: list[dict], phase: str) -> list[dict]:
    def effective_phase(sample: dict) -> str:
        current = sample.get("phase", "running")
        if current == "running" and any("扫描完成" in text for text in sample.get("ui_texts", [])):
            return "final_loading"
        return current

    return [sample for sample in samples if effective_phase(sample) == phase]


def analyze(out: Path) -> int:
    meta = json.loads((out / "meta.json").read_text(encoding="utf-8"))
    cleanup = json.loads((out / "cleanup.json").read_text(encoding="utf-8"))
    samples = []
    for line_no, line in enumerate((out / "samples.jsonl").read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            samples.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise ValueError(f"samples.jsonl 第 {line_no} 行损坏: {exc}") from exc
    if not samples:
        detail = cleanup.get("run_error") or "samples.jsonl 没有有效样本"
        raise ValueError(f"{detail}（sampler_exit_code={cleanup.get('sampler_exit_code')}）")
    logcat = (out / "logcat.txt").read_text(encoding="utf-8", errors="ignore")
    worker = (out / "laserworker.log").read_text(encoding="utf-8", errors="ignore") if (out / "laserworker.log").exists() else ""

    failures: list[str] = []
    warnings: list[str] = []
    budget = int(meta["preview_budget_per_unit"])
    heap_limit = parse_limit_kb(meta.get("heap_growth_limit", "192m"))

    fatal_patterns = {
        "Java OOM": r"OutOfMemoryError",
        "FATAL EXCEPTION": r"FATAL EXCEPTION",
        "native 崩溃": r"Fatal signal|SIGABRT|SIGSEGV|tombstone.*io\.gomob\.scan\.debug",
        "ANR": r"ANR in io\.gomob\.scan\.debug|am_anr.*io\.gomob\.scan\.debug|Input dispatching timed out",
        "Vulkan 分配失败": r"vkAllocateMemory|VK_ERROR_OUT_OF_(?:HOST|DEVICE)_MEMORY|gfxstream.*out of memory",
    }
    for label, pattern in fatal_patterns.items():
        if re.search(pattern, logcat, re.I):
            failures.append(f"出现 {label}")

    seqs = [s.get("seq") for s in samples]
    if seqs != list(range(len(samples))):
        failures.append(f"采样 seq 不连续: {seqs}")
    elapsed_values = [int(s.get("elapsed_sec", -1)) for s in samples]
    if any(b < a for a, b in zip(elapsed_values, elapsed_values[1:])):
        failures.append("elapsed_sec 非单调")
    wall_times = [float(s.get("wall_time", 0)) for s in samples]
    if any(value <= 0 for value in wall_times) or any(b < a for a, b in zip(wall_times, wall_times[1:])):
        failures.append("wall_time 缺失或非单调")

    pids = [s.get("pid") for s in samples if s.get("pid")]
    if not pids:
        failures.append("采样期 App 没有存活 PID")
    elif len(set(pids)) != 1:
        failures.append(f"App PID 发生变化: {sorted(set(pids))}")
    if any(s.get("pid") is None for s in samples):
        failures.append("至少一个采样点 App PID 消失")
    if samples and samples[-1]["elapsed_sec"] < min(50, int(meta["duration_sec"])):
        failures.append(f"有效存活时长仅 {samples[-1]['elapsed_sec']}s")

    server_a = [int(s.get("server", {}).get("live_points_a", 0) or 0) for s in samples]
    server_b = [int(s.get("server", {}).get("live_points_b", 0) or 0) for s in samples]
    if max(server_a or [0]) < 500_000:
        warnings.append(f"A 路服务端仅增长到 {max(server_a or [0]):,} 点，压力覆盖不足")
    if max(server_b or [0]) < 500_000:
        warnings.append(f"B 路服务端仅增长到 {max(server_b or [0]):,} 点，压力覆盖不足")
    if max(server_a or [0]) <= budget or max(server_b or [0]) <= budget:
        failures.append("服务端源点数未跨过端侧预览预算，无法证明有界行为")

    preview_rows: dict[str, list[tuple[int, int, int]]] = {"A": [], "B": []}
    for unit, source, render, voxel in re.findall(
        r"preview unit=([AB]) source=(\d+) render=(\d+) voxel_mm=(\d+)", logcat
    ):
        preview_rows[unit].append((int(source), int(render), int(voxel)))
    for unit in ("A", "B"):
        rows = preview_rows[unit]
        if not rows:
            failures.append(f"缺少 {unit} 路有界预览统计日志")
            continue
        peak = max(row[1] for row in rows)
        if peak <= 0:
            failures.append(f"{unit} 路实时预览驻留点为 0")
        if peak > budget:
            failures.append(f"{unit} 路驻留点 {peak:,} 超预算 {budget:,}")
        if max(row[0] for row in rows) <= budget:
            failures.append(f"{unit} 路源点未超过预算")

    heap_alloc = [int(s["dalvik_heap_alloc_kb"]) for s in samples if s.get("dalvik_heap_alloc_kb") is not None]
    phase_rows = {
        phase: phase_samples(samples, phase)
        for phase in ("scanning", "fusing", "final_loading", "completed", "running")
    }
    if phase_rows["running"]:
        warnings.append(f"存在 {len(phase_rows['running'])} 个无法判定阶段的样本")
    completed_heap_alloc = [
        int(s["dalvik_heap_alloc_kb"]) for s in phase_rows["completed"] if s.get("dalvik_heap_alloc_kb") is not None
    ]
    if not heap_alloc:
        failures.append("未解析到 Dalvik Heap Alloc")
    else:
        peak_heap = max(heap_alloc)
        ratio = peak_heap / heap_limit
        if ratio >= 0.80:
            failures.append(f"Dalvik Heap 峰值 {peak_heap / 1024:.1f}MiB 达 growth limit 的 {ratio:.0%}")
        elif ratio >= 0.65:
            warnings.append(f"Dalvik Heap 峰值达 growth limit 的 {ratio:.0%}")
        for phase, label, use_tail_half in (
            ("scanning", "采集阶段", True),
            ("fusing", "融合等待阶段", False),
        ):
            values = [
                int(s["dalvik_heap_alloc_kb"])
                for s in phase_rows[phase]
                if s.get("dalvik_heap_alloc_kb") is not None
            ]
            stable_values = values[len(values) // 2 :] if use_tail_half else values
            if values and len(stable_values) < 4:
                warnings.append(f"{label} Heap 稳定性采样不足（{len(stable_values)} 点）")
            elif values:
                evaluate_phase_growth(
                    label, values, heap_limit, failures, warnings, use_tail_half=use_tail_half
                )

    vmrss = [int(s["vmrss_kb"]) for s in samples if s.get("vmrss_kb") is not None]
    for phase, label, use_tail_half in (
        ("scanning", "采集阶段", True),
        ("fusing", "融合等待阶段", False),
    ):
        values = [int(s["vmrss_kb"]) for s in phase_rows[phase] if s.get("vmrss_kb") is not None]
        stable_values = values[len(values) // 2 :] if use_tail_half else values
        if len(stable_values) >= 4:
            evaluate_rss_growth(label, values, failures, warnings, use_tail_half=use_tail_half)

    if re.search(r"Blocking GC Alloc|Clamp target GC heap", logcat):
        warnings.append("出现阻塞分配 GC，需关注余量")
    if re.search(r"fatal|panic", worker, re.I):
        failures.append("laserworker 日志出现 fatal/panic")
    if not cleanup.get("ok"):
        failures.append(f"扫描清理未确认终态，mode={cleanup.get('mode')}")
    if cleanup.get("mode") == "rest_fallback":
        failures.append("UI 停止失败，使用了 REST 兜底")
    if cleanup.get("run_error"):
        failures.append(f"采样器错误: {cleanup['run_error']}")
    if int(cleanup.get("sampler_exit_code", 0) or 0) != 0:
        failures.append(f"采样器非零退出: {cleanup.get('sampler_exit_code')}")
    if cleanup.get("signal"):
        failures.append(f"采样被信号中断: {cleanup['signal']}")
    if not worker.strip():
        warnings.append("laserworker 增量日志为空，服务端异常覆盖不足")

    final_clouds: dict[str, tuple[int, int]] = {}
    if meta.get("require_completed"):
        if not completed_heap_alloc:
            failures.append("soak 未从 UI 确认 App 完成态")
        else:
            expected_sec = int(meta.get("post_completion_sec", 0) or 0)
            completed_span = (
                float(phase_rows["completed"][-1]["wall_time"])
                - float(phase_rows["completed"][0]["wall_time"])
            )
            if len(completed_heap_alloc) < 4:
                failures.append(f"完成态 Heap 稳定性采样不足（{len(completed_heap_alloc)} 点）")
            elif completed_span < expected_sec * 0.9:
                failures.append(f"完成态留观仅 {completed_span:.1f}s，要求约 {expected_sec}s")
            else:
                evaluate_phase_growth(
                    "完成态", completed_heap_alloc, heap_limit, failures, warnings, use_tail_half=False
                )
                completed_rss = [
                    int(s["vmrss_kb"])
                    for s in phase_rows["completed"]
                    if s.get("vmrss_kb") is not None
                ]
                if len(completed_rss) >= 4:
                    evaluate_rss_growth(
                        "完成态", completed_rss, failures, warnings, use_tail_half=False
                    )
        final_clouds = {
            name: (int(source), int(render))
            for name, source, render in re.findall(
                r"final cloud=(fused|unit_a|unit_b) source=(\d+) render=(\d+)", logcat
            )
        }
        final_limits = [("unit_a", 65_536), ("unit_b", 65_536)]
        if meta.get("require_fused") or "fused" in final_clouds:
            final_limits.append(("fused", 262_144))
        for name, limit in final_limits:
            if name not in final_clouds:
                failures.append(f"soak 缺少最终 {name} 渲染 PCD")
                continue
            source, render = final_clouds[name]
            expected_render = min(source, limit)
            if source <= 0 or render <= 0:
                failures.append(f"最终 {name} 源点或驻留点为 0")
            elif render != expected_render:
                failures.append(
                    f"最终 {name} 驻留点 {render:,}，期望 min({source:,},{limit:,})={expected_render:,}"
                )
        if meta.get("require_fused") and "fused" not in final_clouds:
            failures.append("soak 要求融合完成，但缺少最终 fused 渲染 PCD")

    status = "FAIL" if failures else "WARN" if warnings else "PASS"
    report = {
        "status": status,
        "failures": failures,
        "warnings": warnings,
        "samples": len(samples),
        "max_server_points_a": max(server_a or [0]),
        "max_server_points_b": max(server_b or [0]),
        "peak_dalvik_heap_alloc_kb": max(heap_alloc or [0]),
        "heap_growth_limit_kb": heap_limit,
        "phase_samples": {phase: len(rows) for phase, rows in phase_rows.items()},
        "completed_heap_samples": len(completed_heap_alloc),
        "peak_vmrss_kb": max(vmrss or [0]),
        "final_clouds": final_clouds,
        "cleanup": cleanup,
    }
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"{status} — 激光实时预览内存闭环")
    for item in failures:
        print(f"  异常: {item}")
    for item in warnings:
        print(f"  警告: {item}")
    print(
        f"  服务端峰值 A/B={report['max_server_points_a']:,}/{report['max_server_points_b']:,}，"
        f"Dalvik 峰值={report['peak_dalvik_heap_alloc_kb'] / 1024:.1f}MiB，清理={cleanup.get('mode')}"
    )
    return {"PASS": 0, "WARN": 1, "FAIL": 2}[status]


def main() -> int:
    if len(sys.argv) != 2:
        print("FAIL — 激光实时预览内存闭环")
        print("  异常: 用法 analyze.py <输出目录>")
        return 2
    out = Path(sys.argv[1])
    try:
        return analyze(out)
    except Exception as exc:
        failure = f"分析输入无效: {exc}"
        report = {"status": "FAIL", "failures": [failure], "warnings": []}
        try:
            out.mkdir(parents=True, exist_ok=True)
            (out / "report.json").write_text(
                json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8"
            )
        except Exception:
            pass
        print("FAIL — 激光实时预览内存闭环")
        print(f"  异常: {failure}")
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
