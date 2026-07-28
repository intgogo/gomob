#!/usr/bin/env python3
"""VIN 多角度还原一致性判定：固定坐标直接重合，禁止评估前做几何配准。"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np


ROOT = Path(__file__).resolve().parents[3]

# VINCreator 内部工作画布是 5000×678，用户可见/OCR 文件严格为中心裁切后的 4425×600。
# 重合评估对所有样本施加同一套固定相似变换，再在探针域计算；禁止逐图配准。
CANVAS_W = 4425
CANVAS_H = 600
EVAL_W = 1200
EVAL_H = 260
CANONICAL_PROBE_PITCH_PX = 64.0
VINCREATOR_PIXELS_PER_MM = 25.0
# 四张 BF301208 原厂 front oracle 的字符节距均值；仅作为绝对字号回归，不参与逐图对齐。
VINCREATOR_ORACLE_PITCH_PX = 170.28
CANONICAL_PITCH_PX = VINCREATOR_ORACLE_PITCH_PX
# 固定 0.36 将原厂 25px/mm 用户图映到 9px/mm 探针域；不读取样本内容。
OUTPUT_TO_EVAL_SCALE = 0.36
# 两颗独立 5fps USB 相机无硬触发。生产接口按原厂半帧边界使用100ms；当前固定 rig 的
# 一致性回归须保持已实测53–55ms相位，留到70ms，避免硬件/处理管线退化被宽门掩盖。
PRODUCTION_CALLBACK_SYNC_MAX_US = 100_000
CALLBACK_SYNC_MAX_US = 70_000
EXPOSURE_SYNC_MAX_US = 25_000
EDGE_TOLERANCE_PX = 3.0

# 原厂画布以严格相同的 x/y 比例缩放后居中。平移和比例只由画布契约决定，不读取样本内容。
EVAL_TRANSLATE_X = (EVAL_W - CANVAS_W * OUTPUT_TO_EVAL_SCALE) / 2.0
EVAL_TRANSLATE_Y = (EVAL_H - CANVAS_H * OUTPUT_TO_EVAL_SCALE) / 2.0

# 固定共同 ROI 只覆盖 17 字符带：目标节距约 170px，经固定 0.36 映射后中心跨度约 981px，
# 目标字高约 239px，对应探针高约 86px。排除刻度、时间戳和随视角变化的钢板污渍背景；
# 边界完全由画布/字号契约给出，禁止按每张检测结果移动。
ROI_X0 = 100
ROI_X1 = 1100
ROI_Y0 = 75
ROI_Y1 = 185

# 这些门直接对应“居中、水平、同尺寸、固定坐标重合”，不是为历史数据调到通过。
CENTER_X_MAX_PX = 6.0 / OUTPUT_TO_EVAL_SCALE
CENTER_Y_MAX_PX = 6.0 / OUTPUT_TO_EVAL_SCALE
ANGLE_ABS_MAX_DEG = 0.35
PITCH_MEAN_ERROR_MAX_PX = 1.5 / OUTPUT_TO_EVAL_SCALE
PITCH_CV_MAX = 0.0075
HEIGHT_CV_MAX = 0.02
HEIGHT_MAX_RELATIVE_DEVIATION_MAX = 0.04
ANCHOR_NORMALIZED_RMS_MAX = 0.13
ANCHOR_MEAN_SCORE_MIN = 0.80
ANCHOR_SCALE_DELTA_MAX = 0.15
EDGE_F1_MEDIAN_MIN = 0.60
EDGE_F1_WORST_MIN = 0.35
# 固定坐标几何必须至少达到同批 VINCreator oracle。绝对门取四张原厂 front 结果的实测边界，
# 不是为 Gomob 单独放宽：原厂自身 Chamfer median≈4.82/worst≈6.78。
CHAMFER_MEDIAN_MAX_PX = 5.5
CHAMFER_WORST_MAX_PX = 7.0
# 金属钢印换视角后明暗极性会翻转；签名 NCC 保留诊断，判定使用 |高通结构| 的极性不变 NCC。
POLARITY_NCC_MEDIAN_MIN = 0.18
POLARITY_NCC_WORST_MIN = 0.08
SYNCED_METRIC_WIDTH_CV_MAX = 0.03
TILT_DIAG_MIN_SAMPLES = 6
TILT_DIAG_MIN_SPAN_DEG = 10.0
TILT_CORRELATION_ABS_MIN = 0.70
TILT_RELATIVE_DRIFT_MAX = 0.04


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def vision_records_fingerprint(records_dir: Path) -> dict:
    """把整批视觉观测录制压成一个指纹，作为报告的溯源锚点。

    逐文件 sha256 再按文件名排序聚合，保证同一批观测无论录制顺序如何都得到同一指纹；
    任何一条观测变了（换模型版本、换服务端、重录）指纹立刻变，报告不会张冠李戴。
    """
    if not records_dir.is_dir():
        return {"path": str(records_dir), "present": False}
    files = sorted(p for p in records_dir.glob("*.json") if p.is_file())
    aggregate = hashlib.sha256()
    for path in files:
        aggregate.update(path.name.encode("utf-8"))
        aggregate.update(sha256_file(path).encode("ascii"))
    return {
        "path": str(records_dir),
        "present": True,
        "record_count": len(files),
        "sha256": aggregate.hexdigest(),
    }


def git_revision() -> dict:
    def run(*args):
        proc = subprocess.run(
            ["git", *args], cwd=ROOT, text=True, capture_output=True, check=False
        )
        return proc.stdout.strip() if proc.returncode == 0 else "unknown"

    return {"commit": run("rev-parse", "HEAD"), "dirty": bool(run("status", "--porcelain"))}


def fixed_roi_mask(shape) -> np.ndarray:
    mask = np.zeros(shape[:2], dtype=bool)
    y1 = min(ROI_Y1, shape[0])
    x1 = min(ROI_X1, shape[1])
    if ROI_Y0 < y1 and ROI_X0 < x1:
        mask[ROI_Y0:y1, ROI_X0:x1] = True
    return mask


def fixed_evaluation_image(image: np.ndarray) -> np.ndarray:
    """把原厂画布统一映到模型探针域；所有样本参数完全相同，不读取图像内容。"""
    matrix = np.asarray(
        [
            [OUTPUT_TO_EVAL_SCALE, 0.0, EVAL_TRANSLATE_X],
            [0.0, OUTPUT_TO_EVAL_SCALE, EVAL_TRANSLATE_Y],
        ],
        dtype=np.float64,
    )
    return cv2.warpAffine(
        image,
        matrix,
        (EVAL_W, EVAL_H),
        flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT,
        borderValue=(128, 128, 128),
    )


def remove_tiny_components(edge: np.ndarray) -> np.ndarray:
    count, labels, stats, _ = cv2.connectedComponentsWithStats(edge.astype(np.uint8), 8)
    kept = np.zeros_like(edge, dtype=bool)
    for idx in range(1, count):
        _, _, width, height, area = stats[idx]
        if area >= 4 and not (width > EVAL_W * 0.45 and height <= 3):
            kept[labels == idx] = True
    return kept


def image_features(image: np.ndarray) -> dict:
    mask = fixed_roi_mask(image.shape)
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    gray = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(12, 3)).apply(gray)
    blurred = cv2.GaussianBlur(gray, (0, 0), 1.0)
    values = blurred[mask]
    median = float(np.median(values)) if values.size else 128.0
    lower = int(max(0, 0.55 * median))
    upper = int(min(255, max(lower + 20, 1.45 * median)))
    edge = cv2.Canny(blurred, lower, upper) > 0
    edge &= mask
    edge = remove_tiny_components(edge)

    background = cv2.GaussianBlur(gray.astype(np.float32), (0, 0), 5.0)
    structure = gray.astype(np.float32) - background
    if values.size:
        local = structure[mask]
        structure = (structure - float(local.mean())) / (float(local.std()) + 1e-6)
        structure = np.clip(structure, -4.0, 4.0)
    return {"mask": mask, "edge": edge, "structure": structure}


def direct_pair_metrics(a: dict, b: dict) -> dict | None:
    common = a["mask"] & b["mask"]
    edge_a = a["edge"] & common
    edge_b = b["edge"] & common
    if int(edge_a.sum()) < 80 or int(edge_b.sum()) < 80 or int(common.sum()) < 500:
        return None

    distance_a = cv2.distanceTransform(
        np.where(edge_a, 0, 255).astype(np.uint8), cv2.DIST_L2, 3
    )
    distance_b = cv2.distanceTransform(
        np.where(edge_b, 0, 255).astype(np.uint8), cv2.DIST_L2, 3
    )
    dist_a = distance_a[edge_b]
    dist_b = distance_b[edge_a]
    precision = float(np.mean(dist_a <= EDGE_TOLERANCE_PX))
    recall = float(np.mean(dist_b <= EDGE_TOLERANCE_PX))
    f1 = 2.0 * precision * recall / max(precision + recall, 1e-9)
    chamfer = 0.5 * (float(dist_a.mean()) + float(dist_b.mean()))

    va = a["structure"][common].astype(np.float64)
    vb = b["structure"][common].astype(np.float64)
    va -= va.mean()
    vb -= vb.mean()
    ncc = float(np.dot(va, vb) / (np.linalg.norm(va) * np.linalg.norm(vb) + 1e-9))
    polarity_a = np.abs(va)
    polarity_b = np.abs(vb)
    polarity_a -= polarity_a.mean()
    polarity_b -= polarity_b.mean()
    polarity_ncc = float(
        np.dot(polarity_a, polarity_b)
        / (np.linalg.norm(polarity_a) * np.linalg.norm(polarity_b) + 1e-9)
    )
    return {
        "edge_f1": f1,
        "chamfer_px": chamfer,
        "direct_ncc": ncc,
        "polarity_invariant_ncc": polarity_ncc,
    }


def coefficient_of_variation(values) -> float:
    values = np.asarray(values, np.float64)
    return float(values.std() / max(abs(values.mean()), 1e-9))


def max_relative_deviation(values) -> float:
    values = np.asarray(values, np.float64)
    median = float(np.median(values))
    return float(np.max(np.abs(values - median)) / max(abs(median), 1e-9))


def aggregate(values) -> dict:
    arr = np.asarray(values, np.float64)
    return {
        "min": float(arr.min()),
        "median": float(np.median(arr)),
        "max": float(arr.max()),
        "mean": float(arr.mean()),
    }


def anchor_metrics(meta: dict) -> dict:
    pitch = float(meta.get("anchor_pitch_px", 0.0) or 0.0)
    height = float(meta.get("anchor_height_px", 0.0) or 0.0)
    rms = float(meta.get("anchor_rms_px", 0.0) or 0.0)
    ratio = height / pitch if pitch > 0 else 0.0
    return {
        "count": int(meta.get("anchor_count", 0) or 0),
        "candidate_count": int(meta.get("anchor_candidate_count", 0) or 0),
        "pitch_px": pitch,
        "rms_px": rms,
        "normalized_rms": rms / pitch if pitch > 0 else None,
        "mean_score": float(meta.get("anchor_mean_score", 0.0) or 0.0),
        "height_px": height,
        "height_pitch_ratio": ratio,
        "canonical_character_height_px": CANONICAL_PITCH_PX * ratio,
        "rotation_deg": float(meta.get("anchor_rotation_deg", 0.0) or 0.0),
        "scale": float(meta.get("anchor_scale", 0.0) or 0.0),
    }


def result_outcome(item: dict) -> str:
    if item.get("ok"):
        return "success"
    return item.get("reject_reason") or "restore_error"


def expected_outcome(group: dict, capture_name: str) -> str:
    return group.get("expected_rejects", {}).get(capture_name, "success")


def tilt_height_diagnostic(samples: list[dict]) -> dict:
    tilts = np.asarray([item["tilt_deg"] for item in samples], np.float64)
    ratios = np.asarray([item["height_pitch_ratio"] for item in samples], np.float64)
    valid = np.isfinite(tilts) & np.isfinite(ratios) & (ratios > 0)
    tilts = tilts[valid]
    ratios = ratios[valid]
    count = int(tilts.size)
    span = float(tilts.max() - tilts.min()) if count else 0.0
    diagnostic = {
        "sample_count": count,
        "tilt_span_deg": span,
        "enabled": count >= TILT_DIAG_MIN_SAMPLES and span >= TILT_DIAG_MIN_SPAN_DEG,
        "pearson_r": None,
        "slope_ratio_per_degree": None,
        "slope_canonical_height_px_per_degree": None,
        "relative_drift_over_observed_span": None,
        "failed": False,
    }
    if count < 2 or float(np.std(tilts)) < 1e-9:
        return diagnostic

    if float(np.std(ratios)) < 1e-9:
        diagnostic.update(
            {
                "pearson_r": 0.0,
                "slope_ratio_per_degree": 0.0,
                "slope_canonical_height_px_per_degree": 0.0,
                "relative_drift_over_observed_span": 0.0,
            }
        )
        return diagnostic

    slope, _ = np.polyfit(tilts, ratios, 1)
    correlation = float(np.corrcoef(tilts, ratios)[0, 1])
    median_ratio = float(np.median(ratios))
    relative_drift = abs(float(slope)) * span / max(abs(median_ratio), 1e-9)
    diagnostic.update(
        {
            "pearson_r": correlation,
            "slope_ratio_per_degree": float(slope),
            "slope_canonical_height_px_per_degree": float(slope * CANONICAL_PITCH_PX),
            "relative_drift_over_observed_span": relative_drift,
        }
    )
    diagnostic["failed"] = bool(
        diagnostic["enabled"]
        and abs(correlation) >= TILT_CORRELATION_ABS_MIN
        and relative_drift > TILT_RELATIVE_DRIFT_MAX
    )
    return diagnostic


def analyze_group(root: Path, group: dict) -> dict:
    name = group["physical_object_id"]
    group_dir = root / name
    expected = group["captures"]
    failures = []
    warnings = []
    results_path = group_dir / "results.json"
    if not results_path.exists():
        return {"group": name, "verdict": "异常", "failures": ["缺 results.json"]}
    results = json.loads(results_path.read_text(encoding="utf-8"))
    by_name = {item["capture"]: item for item in results}

    samples = []
    expected_rejections = []
    all_anchor_diagnostics = []
    for capture in expected:
        capture_name = Path(capture["path"]).name
        expected_result = expected_outcome(group, capture_name)
        item = by_name.get(capture_name)
        if item is None:
            failures.append(f"{capture_name}: 未运行")
            continue
        actual_result = result_outcome(item)
        meta = item.get("meta") or {}
        anchor = anchor_metrics(meta)
        anchor.update(
            {
                "capture": capture_name,
                "outcome": actual_result,
                "tilt_deg": float(meta.get("tilt_deg", 0.0) or 0.0),
            }
        )
        all_anchor_diagnostics.append(anchor)
        if actual_result != expected_result:
            failures.append(
                f"{capture_name}: 期望 {expected_result}，实际 {actual_result}"
                + (f"（{item.get('error')}）" if item.get("error") else "")
            )
            continue
        if expected_result != "success":
            expected_rejections.append(
                {"capture": capture_name, "reject_reason": expected_result, "anchor": anchor}
            )
            continue

        png_name = item.get("png")
        if not png_name:
            failures.append(f"{capture_name}: 成功结果缺 PNG")
            continue
        image = cv2.imread(str(group_dir / png_name), cv2.IMREAD_COLOR)
        if image is None:
            failures.append(f"{capture_name}: PNG 读取失败")
            continue
        if image.shape[:2] != (CANVAS_H, CANVAS_W):
            failures.append(f"{capture_name}: 画布 {image.shape[1]}x{image.shape[0]}")
            continue
        output_anchor = item.get("output_anchor")
        if not output_anchor:
            failures.append(
                f"{capture_name}: 最终图字符格架不可用 {item.get('output_anchor_error', '')}"
            )
            continue
        if item.get("output_anchor_error"):
            failures.append(
                f"{capture_name}: 最终图字符格架不可用 {item['output_anchor_error']}"
            )
            # 检测器仍返回了 17 位候选及其几何诊断。该样本必须继续进入固定坐标像素比较，
            # 不能因格架门失败而把最差图从重合统计里静默剔除；失败本身已在上方保留。
        oracle_features = None
        oracle_path_value = capture.get("oracle_path")
        if oracle_path_value:
            oracle_path = Path(oracle_path_value)
            if not oracle_path.is_absolute():
                oracle_path = ROOT / oracle_path
            oracle_image = cv2.imread(str(oracle_path), cv2.IMREAD_COLOR)
            if oracle_image is None:
                failures.append(f"{capture_name}: 原厂 oracle 读取失败 {oracle_path}")
            elif oracle_image.shape[:2] != (CANVAS_H, CANVAS_W):
                failures.append(
                    f"{capture_name}: 原厂 oracle 画布 "
                    f"{oracle_image.shape[1]}x{oracle_image.shape[0]}"
                )
            else:
                oracle_features = image_features(fixed_evaluation_image(oracle_image))
        elif group.get("factory_oracle_required"):
            failures.append(f"{capture_name}: 缺原厂 oracle_path")

        samples.append(
            {
                "capture": capture_name,
                "manifest": capture,
                "result": item,
                "input_anchor": anchor,
                "output_anchor": output_anchor,
                "features": image_features(fixed_evaluation_image(image)),
                "oracle_features": oracle_features,
            }
        )

    expected_successes = sum(
        expected_outcome(group, Path(item["path"]).name) == "success" for item in expected
    )
    if len(samples) != expected_successes:
        failures.append(f"有效成功还原 {len(samples)}/{expected_successes}")
    if expected_successes > 1 and len(samples) < 2:
        return {
            "group": name,
            "samples": len(samples),
            "expected_successes": expected_successes,
            "verdict": "异常",
            "failures": failures or ["可比成功样本不足 2 张"],
            "expected_rejections": expected_rejections,
        }

    output_geometry = None
    direct_overlap = None
    factory_oracle_overlap = None
    input_anchor_summary = None
    tilt_diagnostic = None
    metric = None
    if samples:
        output_anchors = [sample["output_anchor"] for sample in samples]
        center_x_max = max(abs(float(item["center_x"]) - CANVAS_W / 2.0) for item in output_anchors)
        center_y_max = max(abs(float(item["center_y"]) - CANVAS_H / 2.0) for item in output_anchors)
        angle_abs_max = max(abs(float(item["angle_deg"])) for item in output_anchors)
        pitches = [float(item["pitch_px"]) for item in output_anchors]
        heights = [float(item["height_px"]) for item in output_anchors]
        texts = [str(item.get("text") or "") for item in output_anchors]
        normalized_rms = [
            float(item["rms_px"]) / max(float(item["pitch_px"]), 1e-9)
            for item in output_anchors
        ]
        output_geometry = {
            "canvas_px": {"width": CANVAS_W, "height": CANVAS_H},
            "evaluation_scale": OUTPUT_TO_EVAL_SCALE,
            "center_max_abs_px": {"x": center_x_max, "y": center_y_max},
            "center_max_abs_eval_px": {
                "x": center_x_max * OUTPUT_TO_EVAL_SCALE,
                "y": center_y_max * OUTPUT_TO_EVAL_SCALE,
            },
            "angle_max_abs_deg": angle_abs_max,
            "pitch_px": aggregate(pitches),
            "pitch_cv": coefficient_of_variation(pitches),
            "height_px": aggregate(heights),
            "height_cv": coefficient_of_variation(heights),
            "height_max_relative_deviation": max_relative_deviation(heights),
            "normalized_rms_max": max(normalized_rms),
            "mean_score_min": min(float(item["mean_score"]) for item in output_anchors),
            "recognized_texts": sorted(set(texts)),
        }
        if any(int(item["count"]) != 17 for item in output_anchors):
            failures.append("最终图未全部稳定锚定 17 位字符")
        if center_x_max > CENTER_X_MAX_PX or center_y_max > CENTER_Y_MAX_PX:
            failures.append(f"字符格架未居中 dx={center_x_max:.2f}px dy={center_y_max:.2f}px")
        if angle_abs_max > ANGLE_ABS_MAX_DEG:
            failures.append(f"字符格架未水平 max={angle_abs_max:.3f}°")
        pitch_mean_error = abs(output_geometry["pitch_px"]["mean"] - CANONICAL_PITCH_PX)
        if pitch_mean_error > PITCH_MEAN_ERROR_MAX_PX or output_geometry["pitch_cv"] > PITCH_CV_MAX:
            failures.append(
                f"字符节距漂移 mean={output_geometry['pitch_px']['mean']:.2f}px "
                f"CV={output_geometry['pitch_cv']:.2%}"
            )
        if (
            output_geometry["height_cv"] > HEIGHT_CV_MAX
            or output_geometry["height_max_relative_deviation"]
            > HEIGHT_MAX_RELATIVE_DEVIATION_MAX
        ):
            failures.append(
                "最终字符高度漂移 "
                f"CV={output_geometry['height_cv']:.2%} "
                f"max_dev={output_geometry['height_max_relative_deviation']:.2%}"
            )
        if output_geometry["normalized_rms_max"] > ANCHOR_NORMALIZED_RMS_MAX:
            failures.append(
                f"最终字符格架残差过大 max={output_geometry['normalized_rms_max']:.3f}"
            )
        expected_vin = group.get("expected_vin")
        if expected_vin and group.get("ground_truth_status") == "confirmed":
            for sample, text in zip(samples, texts):
                if text != expected_vin:
                    failures.append(
                        f"{sample['capture']}: 最终字符序列 {text or '<空>'} != {expected_vin}"
                    )

        pairs = []
        for i in range(len(samples)):
            for j in range(i + 1, len(samples)):
                metrics = direct_pair_metrics(samples[i]["features"], samples[j]["features"])
                if metrics is None:
                    failures.append(f"{samples[i]['capture']} vs {samples[j]['capture']}: VIN 边缘不足")
                    continue
                metrics.update({"a": samples[i]["capture"], "b": samples[j]["capture"]})
                pairs.append(metrics)

        if not pairs and len(samples) >= 2:
            failures.append("无可用两两直接重合指标")
        elif pairs:
            f1 = aggregate([item["edge_f1"] for item in pairs])
            chamfer = aggregate([item["chamfer_px"] for item in pairs])
            ncc = aggregate([item["direct_ncc"] for item in pairs])
            polarity_ncc = aggregate([item["polarity_invariant_ncc"] for item in pairs])
            direct_overlap = {
                "pair_count": len(pairs),
                "coordinate_space": f"fixed_{EVAL_W}x{EVAL_H}_probe",
                "output_to_eval_scale": OUTPUT_TO_EVAL_SCALE,
                "edge_f1": f1,
                "chamfer_px": chamfer,
                "direct_ncc": ncc,
                "polarity_invariant_ncc": polarity_ncc,
            }
            if f1["median"] < EDGE_F1_MEDIAN_MIN or f1["min"] < EDGE_F1_WORST_MIN:
                failures.append(
                    f"原坐标边缘重合不足 F1 median={f1['median']:.3f} worst={f1['min']:.3f}"
                )

            oracle_samples = [sample for sample in samples if sample["oracle_features"] is not None]
            if oracle_samples:
                oracle_pairs = []
                for i in range(len(oracle_samples)):
                    for j in range(i + 1, len(oracle_samples)):
                        metrics = direct_pair_metrics(
                            oracle_samples[i]["oracle_features"],
                            oracle_samples[j]["oracle_features"],
                        )
                        if metrics is not None:
                            oracle_pairs.append(metrics)
                if oracle_pairs:
                    factory_oracle_overlap = {
                        "sample_count": len(oracle_samples),
                        "pair_count": len(oracle_pairs),
                        "edge_f1": aggregate([item["edge_f1"] for item in oracle_pairs]),
                        "chamfer_px": aggregate([item["chamfer_px"] for item in oracle_pairs]),
                        "direct_ncc": aggregate([item["direct_ncc"] for item in oracle_pairs]),
                        "polarity_invariant_ncc": aggregate(
                            [item["polarity_invariant_ncc"] for item in oracle_pairs]
                        ),
                    }
                    oracle_f1 = factory_oracle_overlap["edge_f1"]
                    oracle_chamfer = factory_oracle_overlap["chamfer_px"]
                    oracle_polarity = factory_oracle_overlap["polarity_invariant_ncc"]
                    if f1["median"] < oracle_f1["median"] * 0.98 or f1["min"] < oracle_f1["min"] * 0.98:
                        failures.append("固定坐标边缘重合劣于同批 VINCreator oracle")
                    if (
                        chamfer["median"] > oracle_chamfer["median"] * 1.10
                        or chamfer["max"] > oracle_chamfer["max"] * 1.10
                    ):
                        failures.append("固定坐标 Chamfer 劣于同批 VINCreator oracle 10% 以上")
                    if (
                        polarity_ncc["median"] < oracle_polarity["median"] * 0.90
                        or polarity_ncc["min"] < oracle_polarity["min"] * 0.90
                    ):
                        failures.append("极性不变结构相关劣于同批 VINCreator oracle")
                elif group.get("factory_oracle_required"):
                    failures.append("原厂 oracle 无可用两两固定坐标指标")
            if chamfer["median"] > CHAMFER_MEDIAN_MAX_PX or chamfer["max"] > CHAMFER_WORST_MAX_PX:
                failures.append(
                    f"原坐标边缘 Chamfer 过大 median={chamfer['median']:.2f}px "
                    f"worst={chamfer['max']:.2f}px"
                )
            if (
                polarity_ncc["median"] < POLARITY_NCC_MEDIAN_MIN
                or polarity_ncc["min"] < POLARITY_NCC_WORST_MIN
            ):
                failures.append(
                    "原坐标极性不变结构相关过低 "
                    f"NCC median={polarity_ncc['median']:.3f} worst={polarity_ncc['min']:.3f}"
                )

        input_anchors = [sample["input_anchor"] for sample in samples]
        canonical_heights = [item["canonical_character_height_px"] for item in input_anchors]
        input_anchor_summary = {
            "count": len(input_anchors),
            "candidate_count": aggregate([item["candidate_count"] for item in input_anchors]),
            "pitch_px": aggregate([item["pitch_px"] for item in input_anchors]),
            "normalized_rms": aggregate([item["normalized_rms"] for item in input_anchors]),
            "mean_score": aggregate([item["mean_score"] for item in input_anchors]),
            "rotation_deg": aggregate([item["rotation_deg"] for item in input_anchors]),
            "scale": aggregate([item["scale"] for item in input_anchors]),
            "height_pitch_ratio": aggregate([item["height_pitch_ratio"] for item in input_anchors]),
            "canonical_character_height_px": aggregate(canonical_heights),
            "canonical_character_height_cv": coefficient_of_variation(canonical_heights),
            "canonical_character_height_max_relative_deviation": max_relative_deviation(
                canonical_heights
            ),
        }
        for sample in samples:
            anchor = sample["input_anchor"]
            if anchor["count"] != 17:
                failures.append(f"{sample['capture']}: 输入字符锚定数 {anchor['count']} != 17")
            if anchor["normalized_rms"] > ANCHOR_NORMALIZED_RMS_MAX:
                failures.append(
                    f"{sample['capture']}: 输入格架残差 {anchor['normalized_rms']:.3f} > "
                    f"{ANCHOR_NORMALIZED_RMS_MAX:.2f}"
                )
            if anchor["mean_score"] < ANCHOR_MEAN_SCORE_MIN:
                failures.append(
                    f"{sample['capture']}: 输入字符均值置信度 {anchor['mean_score']:.3f}"
                )
            if abs(anchor["rotation_deg"]) > 3.0:
                failures.append(f"{sample['capture']}: 输入格架角度 {anchor['rotation_deg']:.2f}°")
            if abs(anchor["scale"] - 1.0) > ANCHOR_SCALE_DELTA_MAX:
                failures.append(f"{sample['capture']}: 输入尺度修正 {anchor['scale']:.3f}")
        # 输入探针框高受高光、视角和检测框松紧影响，只保留诊断；最终用户图的实际字符高度已在
        # output_geometry 上独立设门，不能让中间检测框噪声覆盖最终成图事实。

        tilt_samples = [
            {
                "tilt_deg": float(sample["result"]["meta"].get("tilt_deg", 0.0) or 0.0),
                "height_pitch_ratio": sample["input_anchor"]["height_pitch_ratio"],
            }
            for sample in samples
        ]
        tilt_diagnostic = tilt_height_diagnostic(tilt_samples)
        if tilt_diagnostic["failed"]:
            failures.append(
                "字符高度/节距与倾角强相关 "
                f"r={tilt_diagnostic['pearson_r']:.3f}，"
                f"观测范围漂移={tilt_diagnostic['relative_drift_over_observed_span']:.1%}"
            )

        metas = [sample["result"]["meta"] for sample in samples]
        widths_mm = [float(meta.get("width_mm", 0.0)) for meta in metas if meta.get("width_mm", 0.0) > 0]
        heights_mm = [float(meta.get("height_mm", 0.0)) for meta in metas if meta.get("height_mm", 0.0) > 0]
        tilts = [float(meta.get("tilt_deg", 0.0) or 0.0) for meta in metas]
        metric = {
            "width_mm_cv": coefficient_of_variation(widths_mm) if widths_mm else None,
            "height_mm_cv": coefficient_of_variation(heights_mm) if heights_mm else None,
            "tilt_min_deg": min(tilts),
            "tilt_max_deg": max(tilts),
            "tilt_span_deg": max(tilts) - min(tilts),
        }

        measured_sync = []
        for sample in samples:
            status = sample["manifest"].get("sync_status")
            delta = sample["result"].get("sync_delta_us")
            if status in {"measured", "host_callback_measured"}:
                if delta is None or delta > CALLBACK_SYNC_MAX_US:
                    failures.append(f"{sample['capture']}: 同步差非法 {delta}")
                else:
                    measured_sync.append(delta)
            elif status == "exposure_corrected":
                if delta is None or delta > EXPOSURE_SYNC_MAX_US:
                    failures.append(f"{sample['capture']}: 曝光同步差非法 {delta}")
                else:
                    measured_sync.append(delta)
        if len(measured_sync) != len(samples):
            warnings.append("历史采集缺 RGB/Depth 独立时间戳，不能验证新同步修复")
        elif metric["width_mm_cv"] is not None and metric["width_mm_cv"] > SYNCED_METRIC_WIDTH_CV_MAX:
            failures.append(f"已同步数据物理宽度 CV={metric['width_mm_cv']:.2%} > 3%")

        if metric["tilt_span_deg"] < 30.0 or metric["tilt_max_deg"] < 45.0:
            warnings.append(
                f"视角覆盖不足：tilt {metric['tilt_min_deg']:.1f}°..{metric['tilt_max_deg']:.1f}°，"
                "不能代表生产门内任意角度"
            )

    if group.get("ground_truth_status") != "confirmed":
        warnings.append("VIN 真值尚未人工复核，不能验 OCR 串一致")

    verdict = "异常" if failures else ("警告" if warnings else "正常")
    return {
        "group": name,
        "expected_vin": group.get("expected_vin"),
        "ground_truth_status": group.get("ground_truth_status"),
        "samples": len(samples),
        "expected_successes": expected_successes,
        "expected_rejections": expected_rejections,
        "output_character_grid": output_geometry,
        "input_anchor_diagnostics": input_anchor_summary,
        "tilt_height_diagnostic": tilt_diagnostic,
        "direct_overlap": direct_overlap,
        "factory_oracle_overlap": factory_oracle_overlap,
        "metric_diagnostics": metric,
        "anchor_samples": all_anchor_diagnostics,
        "verdict": verdict,
        "failures": failures,
        "warnings": warnings,
    }


def main() -> int:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ".dev/vin_restore_consistency")
    manifest_path = Path(
        sys.argv[2]
        if len(sys.argv) > 2
        else os.getenv(
            "VIN_CONSISTENCY_MANIFEST",
            "tests/harness/vin_restore_consistency/manifest_factory_bf301208.json",
        )
    )
    # VIN 区域与逐字符观测已下沉到外部算法服务，本地无模型文件可 hash。
    # 溯源锚点改为这批观测录制本身：报告据此能回答"这个结论建立在哪批观测上"。
    records_dir = Path(
        sys.argv[3] if len(sys.argv) > 3
        else os.getenv("VIN_VISION_REPLAY_DIR", ".dev/vin_vision_records")
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    groups = [analyze_group(out, group) for group in manifest["groups"]]

    if any(group["verdict"] == "异常" for group in groups):
        verdict, exit_code = "异常", 2
    elif any(group["verdict"] == "警告" for group in groups):
        verdict, exit_code = "警告", 1
    else:
        verdict, exit_code = "正常", 0
    report = {
        "verdict": verdict,
        "code": {
            "git": git_revision(),
            "opencv": cv2.__version__,
            "harness_sources": {
                str(path.relative_to(ROOT)): sha256_file(path)
                for path in (
                    Path(__file__).resolve(),
                    ROOT / "server/internal/cvengine/restore/anchor.go",
                    ROOT / "server/internal/cvengine/restore/calibration.go",
                    ROOT / "server/internal/cvengine/restore/render.go",
                    ROOT / "server/internal/cvengine/restore/restore.go",
                    ROOT / "server/internal/cvengine/restore/consistency_real_test.go",
                )
            },
            "vision_records": vision_records_fingerprint(records_dir),
            "manifest_sha256": sha256_file(manifest_path),
        },
        "thresholds": {
            "output_canvas_px": [CANVAS_W, CANVAS_H],
            "fixed_evaluation_canvas_px": [EVAL_W, EVAL_H],
            "output_to_evaluation_scale": OUTPUT_TO_EVAL_SCALE,
            "vincreator_pixels_per_mm": VINCREATOR_PIXELS_PER_MM,
            "vincreator_oracle_pitch_px": VINCREATOR_ORACLE_PITCH_PX,
            "center_max_px": [CENTER_X_MAX_PX, CENTER_Y_MAX_PX],
            "angle_abs_max_deg": ANGLE_ABS_MAX_DEG,
            "canonical_pitch_px": CANONICAL_PITCH_PX,
            "canonical_probe_pitch_px": CANONICAL_PROBE_PITCH_PX,
            "pitch_mean_error_max_px": PITCH_MEAN_ERROR_MAX_PX,
            "pitch_cv_max": PITCH_CV_MAX,
            "height_cv_max": HEIGHT_CV_MAX,
            "height_max_relative_deviation_max": HEIGHT_MAX_RELATIVE_DEVIATION_MAX,
            "anchor_normalized_rms_max": ANCHOR_NORMALIZED_RMS_MAX,
            "anchor_mean_score_min": ANCHOR_MEAN_SCORE_MIN,
            "anchor_scale_delta_max": ANCHOR_SCALE_DELTA_MAX,
            "edge_tolerance_px": EDGE_TOLERANCE_PX,
            "edge_f1_median_min": EDGE_F1_MEDIAN_MIN,
            "edge_f1_worst_min": EDGE_F1_WORST_MIN,
            "chamfer_median_max_px": CHAMFER_MEDIAN_MAX_PX,
            "chamfer_worst_max_px": CHAMFER_WORST_MAX_PX,
            "polarity_ncc_median_min": POLARITY_NCC_MEDIAN_MIN,
            "polarity_ncc_worst_min": POLARITY_NCC_WORST_MIN,
            "tilt_correlation_abs_min": TILT_CORRELATION_ABS_MIN,
            "tilt_relative_drift_max": TILT_RELATIVE_DRIFT_MAX,
            "callback_sync_max_us": CALLBACK_SYNC_MAX_US,
            "production_callback_sync_max_us": PRODUCTION_CALLBACK_SYNC_MAX_US,
            "exposure_sync_max_us": EXPOSURE_SYNC_MAX_US,
        },
        "groups": groups,
    }
    (out / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False),
        encoding="utf-8",
    )
    for group in groups:
        details = group.get("failures") or group.get("warnings") or ["全部门限达标"]
        print(f"{group['group']}: {group['verdict']} — " + "；".join(details))
    print(f"结论：{verdict}")
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
