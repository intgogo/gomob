#!/usr/bin/env python3
# laser_ab_refine harness：合成非共面靶闭环 + 可选真实 A/B PCD 外参精修。
from __future__ import annotations

import json
import math
import os
import sys
from pathlib import Path

import numpy as np
from scipy.spatial.transform import Rotation

sys.path.insert(0, os.path.dirname(__file__))
from refine import (  # noqa: E402
    RefineConfig,
    load_btoa_json,
    load_pcd,
    matrix_delta,
    matrix_stats,
    refine_b_to_a,
    save_refine_json,
    se3_matrix,
    transform_error,
    transform_points,
)


def plane_grid(axis: int, fixed: float, a0: float, a1: float, b0: float, b1: float, step: float) -> np.ndarray:
    aa = np.arange(a0, a1 + 0.1, step)
    bb = np.arange(b0, b1 + 0.1, step)
    a, b = np.meshgrid(aa, bb)
    f = np.full(a.size, fixed, dtype=np.float64)
    a = a.ravel()
    b = b.ravel()
    if axis == 0:
        return np.stack([f, a, b], axis=1)
    if axis == 1:
        return np.stack([a, f, b], axis=1)
    return np.stack([a, b, f], axis=1)


def make_non_coplanar_target(step: float = 18.0) -> np.ndarray:
    """三块互相垂直平面 + 非对称台阶，避免单平面/对称体退化。"""
    parts = [
        plane_grid(2, 0.0, -420, 620, -380, 540, step),
        plane_grid(0, -420.0, -380, 540, 0, 760, step),
        plane_grid(1, -380.0, -420, 620, 0, 760, step),
        plane_grid(2, 260.0, -230, 120, -120, 260, step),
        plane_grid(0, 120.0, -120, 260, 260, 560, step),
        plane_grid(1, 260.0, -230, 120, 260, 560, step),
        plane_grid(2, 520.0, 180, 500, 40, 420, step),
        plane_grid(0, 500.0, 40, 420, 0, 520, step),
    ]
    pts = np.vstack(parts)
    # 加几条边线，提高沿面方向约束，但仍保持真实点云式离散采样。
    y = np.arange(-350, 500, step)
    z = np.arange(0, 720, step)
    edge1 = np.stack([np.full_like(y, -420.0), y, np.full_like(y, 760.0)], axis=1)
    edge2 = np.stack([np.full_like(z, 620.0), np.full_like(z, -380.0), z], axis=1)
    return np.vstack([pts, edge1, edge2]).astype(np.float64)


def make_transform(rot_deg: tuple[float, float, float], trans_mm: tuple[float, float, float]) -> np.ndarray:
    mat = np.eye(4, dtype=np.float64)
    mat[:3, :3] = Rotation.from_euler("xyz", rot_deg, degrees=True).as_matrix()
    mat[:3, 3] = np.asarray(trans_mm, dtype=np.float64)
    return mat


def make_synthetic_case() -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    rng = np.random.default_rng(20260629)
    model = make_non_coplanar_target()
    truth = make_transform((3.5, -2.0, 7.0), (185.0, -92.0, 38.0))
    init_error = make_transform((1.25, -1.7, 0.85), (46.0, -34.0, 22.0))
    init = init_error @ truth

    a = model + rng.normal(0.0, 0.7, model.shape)
    b_model = model.copy()
    keep = rng.random(len(b_model)) > 0.08
    b_model = b_model[keep]
    b = transform_points(b_model, np.linalg.inv(truth)) + rng.normal(0.0, 0.7, (len(b_model), 3))
    outliers = rng.uniform([-650, -650, -80], [820, 760, 850], size=(max(80, len(b) // 35), 3))
    b = np.vstack([b, transform_points(outliers, np.linalg.inv(truth))])
    return a, b, truth, init


def print_history(history: list[dict[str, float]], limit: int = 6) -> None:
    if not history:
        return
    shown = history[:limit]
    for row in shown:
        print(
            f"  iter {int(row['iter']):02d}: corr={int(row['corr'])} "
            f"step={row['step_rot_deg']:.4f}deg/{row['step_trans_mm']:.3f}mm "
            f"median={row['median_mm']:.2f} p95={row['p95_mm']:.2f}"
        )
    if len(history) > limit:
        row = history[-1]
        print(
            f"  ... iter {int(row['iter']):02d}: corr={int(row['corr'])} "
            f"step={row['step_rot_deg']:.4f}deg/{row['step_trans_mm']:.3f}mm "
            f"median={row['median_mm']:.2f} p95={row['p95_mm']:.2f}"
        )


def run_synthetic(out_dir: Path, warn: list[str], err: list[str]) -> None:
    print("== 合成非共面靶 B→A 外参精修闭环 ==")
    a, b, truth, init = make_synthetic_case()
    before = transform_error(init, truth)
    res = refine_b_to_a(a, b, init, RefineConfig())
    after = transform_error(res["b_to_a_final"], truth)
    delta = matrix_stats(matrix_delta(res["b_to_a_final"], init))
    print(f"  点数 A={len(a)} B={len(b)}")
    print(
        f"  初始误差: rot={before['rot_deg']:.3f}deg trans={before['trans_mm']:.2f}mm "
        f"median={res['initial']['median_mm']:.2f}mm p95={res['initial']['p95_mm']:.2f}mm"
    )
    print_history(res["history"])
    print(
        f"  精修误差: rot={after['rot_deg']:.4f}deg trans={after['trans_mm']:.2f}mm "
        f"median={res['final']['median_mm']:.2f}mm p95={res['final']['p95_mm']:.2f}mm"
    )
    print(f"  ΔT_cloud: rot={delta['rot_deg']:.4f}deg trans={delta['trans_mm']:.2f}mm")

    save_refine_json(str(out_dir / "synthetic_refine.json"), res)
    with open(out_dir / "synthetic_truth.json", "w", encoding="utf-8") as f:
        json.dump(
            {
                "b_to_a_true": truth.reshape(-1).tolist(),
                "b_to_a_marker_init": init.reshape(-1).tolist(),
                "initial_error": before,
                "final_error": after,
            },
            f,
            ensure_ascii=False,
            indent=2,
        )

    if res["initial"]["median_mm"] < 20.0:
        err.append(f"合成初始错位过小，未覆盖真实错位场景: median={res['initial']['median_mm']:.1f}mm")
    if after["rot_deg"] > 0.08:
        err.append(f"合成旋转未恢复到 0.08° 内: {after['rot_deg']:.4f}°")
    if after["trans_mm"] > 2.0:
        err.append(f"合成平移未恢复到 2mm 内: {after['trans_mm']:.2f}mm")
    if res["final"]["p95_mm"] > 6.0:
        err.append(f"合成精修后 p95={res['final']['p95_mm']:.2f}mm > 6mm")
    improve = res["initial"]["median_mm"] / max(res["final"]["median_mm"], 1e-6)
    if improve < 8.0:
        err.append(f"合成 median 改善倍率 {improve:.1f} < 8")


def run_real(out_dir: Path, warn: list[str], err: list[str]) -> None:
    if os.environ.get("LASER_AB_REFINE_PYTHON_REAL") != "1":
        print("\n== Python 真实 A/B PCD 诊断 ==")
        print("  跳过：生产闭环由 Go RefineBToA 验证；仅裁出共同标定靶时显式设 LASER_AB_REFINE_PYTHON_REAL=1。")
        return
    a_pcd = os.environ.get("A_PCD", "")
    b_pcd = os.environ.get("B_PCD", "")
    btoa_json = os.environ.get("BTOA_JSON", os.environ.get("SITE_JSON", ""))
    if not (a_pcd and b_pcd and btoa_json):
        print("\n== 真实 A/B PCD 闭环 ==")
        print("  跳过：设置 A_PCD、B_PCD、BTOA_JSON/SITE_JSON 后启用真实精修。")
        return
    print("\n== 真实 A/B PCD 闭环 ==")
    if not os.path.exists(a_pcd):
        err.append(f"A_PCD 不存在: {a_pcd}")
        return
    if not os.path.exists(b_pcd):
        err.append(f"B_PCD 不存在: {b_pcd}")
        return
    if not os.path.exists(btoa_json):
        err.append(f"BTOA_JSON/SITE_JSON 不存在: {btoa_json}")
        return
    a = load_pcd(a_pcd)
    b = load_pcd(b_pcd)
    init = load_btoa_json(btoa_json)
    cfg = RefineConfig(max_corr_mm=float(os.environ.get("MAX_CORR_MM", "180")))
    res = refine_b_to_a(a, b, init, cfg)
    save_refine_json(str(out_dir / "real_refine.json"), res)
    print(f"  输入: A={len(a)}点 B={len(b)}点 init={btoa_json}")
    print(
        f"  初始: median={res['initial']['median_mm']:.2f}mm "
        f"p95={res['initial']['p95_mm']:.2f}mm within10={res['initial']['within_10mm_frac']*100:.1f}%"
    )
    print_history(res["history"], limit=5)
    print(
        f"  精修: median={res['final']['median_mm']:.2f}mm "
        f"p95={res['final']['p95_mm']:.2f}mm within10={res['final']['within_10mm_frac']*100:.1f}%"
    )
    print(f"  ΔT_cloud: rot={res['delta']['rot_deg']:.4f}deg trans={res['delta']['trans_mm']:.2f}mm")
    if res["final"]["median_mm"] >= res["initial"]["median_mm"] * 0.75:
        warn.append("真实精修 median 改善不足 25%，可能缺少重叠面、初值太差或存在动态物体")
    if res["delta"]["rot_deg"] > 4.0 or res["delta"]["trans_mm"] > 160.0:
        warn.append(
            f"真实 ΔT_cloud 偏大 rot={res['delta']['rot_deg']:.2f}° trans={res['delta']['trans_mm']:.0f}mm，"
            "建议先复核 marker site 标定和 A/B 点云单位"
        )
    if res["final"]["p95_mm"] > 35.0:
        warn.append(f"真实精修后 p95={res['final']['p95_mm']:.1f}mm，融合仍会肉眼错位")


def main() -> int:
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else os.environ.get("OUTPUT_DIR", ".dev/laser_ab_refine"))
    out_dir.mkdir(parents=True, exist_ok=True)
    warn: list[str] = []
    err: list[str] = []
    run_synthetic(out_dir, warn, err)
    run_real(out_dir, warn, err)

    print("\n=== 结论 ===")
    if err:
        print("❌ 异常:")
        for item in err:
            print("  -", item)
        return 1
    if warn:
        print("⚠ 警告:")
        for item in warn:
            print("  -", item)
        return 0
    print("✅ 正常：非共面靶合成闭环可从 marker 初值求出稳定 ΔT_cloud；真实闭环按环境变量启用。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
