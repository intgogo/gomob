#!/usr/bin/env python3
"""把 VIN 预览投影探针收敛为正常/警告/异常。"""

from __future__ import annotations

import json
import sys
from pathlib import Path

REFERENCE_WIDTHS_DP = (360.0, 411.0, 432.0)


def metrics_by_width(sample: dict[str, object]) -> dict[float, dict[str, object]]:
    return {
        float(metric["viewport_width_dp"]): metric
        for metric in sample.get("roi_metrics", [])
    }


def sample_name(sample: dict[str, object]) -> str:
    return Path(str(sample.get("capture", ""))).name


def find_sample(samples: list[dict[str, object]], prefix: str) -> dict[str, object] | None:
    return next((sample for sample in samples if sample_name(sample).startswith(prefix)), None)


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else ".dev/vin_preview_alignment/result.json")
    if path.is_dir():
        path = path / "result.json"
    try:
        result = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"❌ 异常：无法读取对齐探针：{exc}")
        return 2
    kotlin_path = path.with_name("kotlin_result.json")
    try:
        kotlin_result = json.loads(kotlin_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"❌ 异常：无法读取 Kotlin 生产投影结果：{exc}")
        return 2

    failures: list[str] = []
    warnings: list[str] = []
    max_oracle_error = max((float(item["error_px"]) for item in result.get("oracle", [])), default=float("inf"))
    if max_oracle_error > 1e-5:
        failures.append(f"跨端固定向量最大误差 {max_oracle_error:.6g}px")
    kotlin_oracle_error = float(kotlin_result.get("max_oracle_error_px", float("inf")))
    if kotlin_oracle_error > 1e-5:
        failures.append(f"Kotlin 固定向量最大误差 {kotlin_oracle_error:.6g}px")
    if int(result.get("valid_depth_points", 0)) < 10_000:
        failures.append("真帧有效深度点不足 10000")
    if float(result.get("in_color_view_ratio", 0.0)) < 0.5:
        failures.append("落入彩色视场的有效点不足 50%")
    coverage = float(result.get("splat_3x3_coverage_ratio", 0.0))
    if coverage < 0.90:
        failures.append(f"3×3 投影覆盖率仅 {coverage:.1%}")
    elif coverage < 0.94:
        warnings.append(f"3×3 投影覆盖率 {coverage:.1%}，低于参考 94%")
    kotlin_coverage = float(kotlin_result.get("coverage_ratio", 0.0))
    if kotlin_coverage < 0.90:
        failures.append(f"Kotlin 3×3 投影覆盖率仅 {kotlin_coverage:.1%}")
    if int(kotlin_result.get("valid_depth_points", 0)) != int(result.get("valid_depth_points", -1)):
        failures.append("Kotlin 与参考实现的有效深度点数不一致")
    kotlin_p95_ms = float(kotlin_result.get("p95_ms", float("inf")))
    if kotlin_p95_ms > 200.0:
        failures.append(f"Kotlin 投影 P95 {kotlin_p95_ms:.1f}ms，无法维持 5fps")
    elif kotlin_p95_ms > 120.0:
        warnings.append(f"Kotlin 投影 P95 {kotlin_p95_ms:.1f}ms，真机需重点复核")
    fixed_p95 = float(result.get("fixed_depth_error_p95_px", 0.0))
    if fixed_p95 < 5.0:
        failures.append(f"固定深度近似 P95 误差仅 {fixed_p95:.2f}px，探针未暴露距离相关视差")

    thresholds = result.get("roi_thresholds", {})
    threshold_pairs = (
        ("min_coverage_ratio", "min_roi_coverage"),
        ("min_projected_point_ratio", "min_projected_point_ratio"),
        ("guidance_distance_mm", "guidance_distance_mm"),
        ("max_capture_distance_mm", "max_capture_distance_mm"),
    )
    for python_key, kotlin_key in threshold_pairs:
        python_value = float(thresholds.get(python_key, float("nan")))
        kotlin_value = float(kotlin_result.get(kotlin_key, float("nan")))
        if not (abs(python_value - kotlin_value) <= 1e-9):
            failures.append(f"质量门常量不一致 {python_key}: Python={python_value} Kotlin={kotlin_value}")

    roi_metrics = result.get("roi_metrics", [])
    if not roi_metrics:
        failures.append("缺少当前真帧 ROI 指标")
        reference_roi = {}
    else:
        main_width = float(kotlin_result.get("roi_viewport_width_dp", float("nan")))
        reference_roi = next(
            (metric for metric in roi_metrics if abs(float(metric.get("viewport_width_dp", 0.0)) - main_width) < 1e-6),
            {},
        )
        if not reference_roi:
            failures.append(f"Python 结果缺少 Kotlin 主视口 {main_width:g}dp 的 ROI 指标")
        python_roi_coverage = float(reference_roi.get("coverage_ratio", 0.0))
        kotlin_roi_coverage = float(kotlin_result.get("roi_coverage_ratio", 0.0))
        if abs(python_roi_coverage - kotlin_roi_coverage) > 0.005:
            failures.append(
                f"Kotlin/Python 框内覆盖率差 {abs(python_roi_coverage - kotlin_roi_coverage):.3%} 超过 0.5%"
            )
        python_points = int(reference_roi.get("projected_points", -1))
        kotlin_points = int(kotlin_result.get("roi_projected_points", -2))
        if python_points != kotlin_points:
            failures.append(f"Kotlin/Python 框内原始投影点不一致 {kotlin_points}!={python_points}")
        python_far = float(reference_roi.get("far_enough_ratio", 0.0))
        kotlin_far = float(kotlin_result.get("roi_far_enough_ratio", 0.0))
        if abs(python_far - kotlin_far) > 1e-6:
            failures.append(f"Kotlin/Python 30cm 通过比例不一致 {kotlin_far:.6f}!={python_far:.6f}")
        python_p10 = reference_roi.get("distance_p10_mm")
        kotlin_p10 = kotlin_result.get("roi_distance_p10_mm")
        if python_p10 is None or kotlin_p10 is None or abs(float(python_p10) - float(kotlin_p10)) > 1.0:
            failures.append(f"Kotlin/Python 框内距离 P10 不一致 {kotlin_p10}!={python_p10}")
        python_median = reference_roi.get("distance_median_mm")
        kotlin_median = kotlin_result.get("roi_distance_median_mm")
        if python_median is None or kotlin_median is None or abs(float(python_median) - float(kotlin_median)) > 1.0:
            failures.append(f"Kotlin/Python 框内距离中位数不一致 {kotlin_median}!={python_median}")
        if bool(reference_roi.get("ready", False)) != bool(kotlin_result.get("roi_ready", False)):
            failures.append("Kotlin/Python 当前真帧质量门结论不一致")
        if not bool(kotlin_result.get("synthetic_ready", False)):
            failures.append("Kotlin 生产质量门未放行覆盖充分且不超过40cm的合成帧")
        if not bool(kotlin_result.get("synthetic_near_ready", False)):
            failures.append("Kotlin 生产质量门错误增加了未要求的近距离硬门")
        if bool(kotlin_result.get("synthetic_too_far_ready", True)):
            failures.append("Kotlin 生产质量门错误放行超过40cm的高覆盖合成帧")
        if bool(kotlin_result.get("synthetic_invalid_ready", True)):
            failures.append("Kotlin 生产质量门错误放行全无效深度帧")
        if int(kotlin_result.get("auto_gate_trigger_count", 0)) != 1:
            failures.append("生产稳定门固定序列未恰好触发一次自动拍摄")
        if int(kotlin_result.get("auto_capture_claim_count", 0)) != 1:
            failures.append("手动与自动快门竞态未收敛为一次捕获许可")
        if int(kotlin_result.get("auto_recognition_claim_count", 0)) != 1:
            failures.append("还原成功未收敛为一次自动识别许可")
        if not bool(kotlin_result.get("auto_rearmed_after_transient_quality", False)):
            failures.append("burst 瞬时质量失败后自动稳定门未重新武装")
        if (
            float(reference_roi.get("coverage_ratio", 0.0)) < float(thresholds.get("min_coverage_ratio", 1.0))
            or float(reference_roi.get("projected_point_ratio", 0.0))
            < float(thresholds.get("min_projected_point_ratio", 1.0))
            or float(reference_roi.get("distance_median_mm", float("inf")))
            > float(thresholds.get("max_capture_distance_mm", 0.0))
            or float(reference_roi.get("far_enough_ratio", 1.0)) >= 0.5
            or not bool(reference_roi.get("ready", False))
        ):
            failures.append("cap_036 应证明覆盖/支撑达标、距离不超过40cm即可拍，不设近距离硬门")

    history = result.get("historical_samples", [])
    kotlin_history = kotlin_result.get("historical_samples", [])
    if len(history) < 10:
        failures.append(f"历史 ROI 样本仅 {len(history)} 个，无法验证门限分布")
        min_success_coverage = float("nan")
        max_non_success_coverage = float("nan")
    else:
        success_coverages = [
            float(metric["coverage_ratio"])
            for sample in history
            if bool(sample.get("restore_ok", False))
            for metric in sample.get("roi_metrics", [])
        ]
        non_success_coverages = [
            float(metric["coverage_ratio"])
            for sample in history
            if not bool(sample.get("restore_ok", False))
            for metric in sample.get("roi_metrics", [])
        ]
        min_success_coverage = min(success_coverages, default=0.0)
        max_non_success_coverage = max(non_success_coverages, default=0.0)
        min_coverage = float(thresholds.get("min_coverage_ratio", 1.0))
        min_points = float(thresholds.get("min_projected_point_ratio", 1.0))

        python_samples = {sample_name(sample): sample for sample in history}
        kotlin_samples = {sample_name(sample): sample for sample in kotlin_history}
        if set(python_samples) != set(kotlin_samples):
            failures.append(
                "Kotlin/Python 历史样本集合不一致 "
                f"仅Python={sorted(set(python_samples) - set(kotlin_samples))} "
                f"仅Kotlin={sorted(set(kotlin_samples) - set(python_samples))}"
            )
        for capture in sorted(set(python_samples) & set(kotlin_samples)):
            python_widths = metrics_by_width(python_samples[capture])
            kotlin_widths = metrics_by_width(kotlin_samples[capture])
            if set(python_widths) != set(REFERENCE_WIDTHS_DP) or set(kotlin_widths) != set(REFERENCE_WIDTHS_DP):
                failures.append(f"{capture} 缺少 360/411/432dp 全部 ROI 指标")
                continue
            for width_dp in REFERENCE_WIDTHS_DP:
                python_metric = python_widths[width_dp]
                kotlin_metric = kotlin_widths[width_dp]
                python_roi = python_metric.get("normalized_roi", {})
                kotlin_roi = kotlin_metric.get("normalized_roi", {})
                for key in ("left", "top", "right", "bottom"):
                    if abs(float(python_roi.get(key, float("nan"))) - float(kotlin_roi.get(key, float("nan")))) > 1e-7:
                        failures.append(f"{capture}/{width_dp:g}dp 的生产 ROI {key} 跨端不一致")
                coverage_delta = abs(
                    float(python_metric.get("coverage_ratio", 0.0))
                    - float(kotlin_metric.get("coverage_ratio", 0.0))
                )
                if coverage_delta > 0.005:
                    failures.append(f"{capture}/{width_dp:g}dp 框内覆盖率跨端差 {coverage_delta:.3%}")
                if int(python_metric.get("projected_points", -1)) != int(kotlin_metric.get("projected_points", -2)):
                    failures.append(f"{capture}/{width_dp:g}dp 原始投影点数跨端不一致")
                far_delta = abs(
                    float(python_metric.get("far_enough_ratio", 0.0))
                    - float(kotlin_metric.get("far_enough_ratio", 0.0))
                )
                if far_delta > 1e-6:
                    failures.append(f"{capture}/{width_dp:g}dp 30cm 诊断比例跨端差 {far_delta:.6f}")
                for key, label in (("distance_p10_mm", "P10"), ("distance_median_mm", "中位数")):
                    python_distance = python_metric.get(key)
                    kotlin_distance = kotlin_metric.get(key)
                    if (
                        python_distance is None
                        or kotlin_distance is None
                        or abs(float(python_distance) - float(kotlin_distance)) > 1.0
                    ):
                        failures.append(
                            f"{capture}/{width_dp:g}dp 距离{label}跨端不一致 "
                            f"{kotlin_distance}!={python_distance}"
                        )
                if bool(python_metric.get("ready", False)) != bool(kotlin_metric.get("ready", False)):
                    failures.append(f"{capture}/{width_dp:g}dp 质量门结论跨端不一致")

        successful_samples = [sample for sample in history if bool(sample.get("restore_ok", False))]
        if not successful_samples:
            failures.append("历史样本中没有还原成功的正样本")
        for sample in successful_samples:
            not_ready = [
                f"{width:g}dp"
                for width, metric in metrics_by_width(sample).items()
                if not bool(metric.get("ready", False))
            ]
            if not_ready:
                failures.append(f"{sample_name(sample)} 成功样本未在全部参考宽度 Ready: {not_ready}")

        for prefix in ("cap_023_", "cap_031_", "cap_034_"):
            sample = find_sample(history, prefix)
            if sample is None:
                failures.append(f"缺少距离足但覆盖差的反例 {prefix}")
                continue
            for width_dp, metric in metrics_by_width(sample).items():
                p10 = metric.get("distance_p10_mm")
                if p10 is None or float(p10) < 300.0 or float(metric.get("far_enough_ratio", 0.0)) < 0.90:
                    failures.append(f"{sample_name(sample)}/{width_dp:g}dp 未证明距离充足")
                if bool(metric.get("ready", False)):
                    failures.append(f"{sample_name(sample)}/{width_dp:g}dp 距离足但被错误放行")
                if (
                    float(metric.get("coverage_ratio", 0.0)) >= min_coverage
                    and float(metric.get("projected_point_ratio", 0.0)) >= min_points
                ):
                    failures.append(f"{sample_name(sample)}/{width_dp:g}dp 未形成覆盖/支撑不足反例")

        boundary = find_sample(history, "cap_030_")
        if boundary is None:
            failures.append("缺少 cap_030 的 95% 覆盖边界样本")
        else:
            for width_dp, metric in metrics_by_width(boundary).items():
                boundary_coverage = float(metric.get("coverage_ratio", 0.0))
                if not (0.94 <= boundary_coverage < min_coverage):
                    failures.append(
                        f"{sample_name(boundary)}/{width_dp:g}dp 覆盖 {boundary_coverage:.2%} 不是 95% 边界"
                    )
                if float(metric.get("projected_point_ratio", 0.0)) < min_points:
                    failures.append(f"{sample_name(boundary)}/{width_dp:g}dp 原始点支撑不足，无法单独验证覆盖边界")
                if bool(metric.get("ready", False)):
                    failures.append(f"{sample_name(boundary)}/{width_dp:g}dp 低于 95% 却被放行")

    if failures:
        print("❌ 异常：" + "；".join(failures))
        return 2
    if warnings:
        print("⚠️ 警告：" + "；".join(warnings))
        return 1
    print(
        "✅ 正常：原厂逐像素投影固定向量一致；真帧有效点 "
        f"{result['valid_depth_points']}，彩色视场命中 {result['in_color_view_ratio']:.1%}，"
        f"3×3 覆盖 {coverage:.1%}（Kotlin {kotlin_coverage:.1%}），Kotlin P95 {kotlin_p95_ms:.1f}ms；"
        f"框内覆盖 {float(reference_roi.get('coverage_ratio', 0.0)):.1%}、原始点支撑 "
        f"{int(reference_roi.get('projected_points', 0))}、中位距离 "
        f"{float(reference_roi.get('distance_median_mm', 0.0)) / 10.0:.1f}cm、≥30cm 比例 "
        f"{float(reference_roi.get('far_enough_ratio', 0.0)):.1%}（仅诊断），当前样本按空间质量与40cm上限可拍；"
        f"历史成功覆盖最低 {min_success_coverage:.1%}、非成功样本最高 {max_non_success_coverage:.1%}；"
        f"固定深度近似 P95 漂移 {fixed_p95:.2f}px，必须动态投影。"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
