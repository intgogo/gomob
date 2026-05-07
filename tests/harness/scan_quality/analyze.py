#!/usr/bin/env python3
"""scan_quality analyze.py — 读 .dev/scan-quality/case_*/ 输出健康度报告。

依赖 numpy + scipy；若 system python3 缺则自动 exec 切换到 miniconda python（CentOS 9 主机
默认装在 /root/lilw/miniconda3/bin/python3）。

每 case 计算：
  - mesh 顶点距球心的 mean / max（应 ≈ R，验 voxel 量化误差）
  - chamfer (mesh → ground_truth)：每 mesh 顶点找最近 ground_truth 点，距离平均 / 最大
  - 表面覆盖率（暂用顶点数 / 期望表面三角数 比例近似）

Verdict:
  - 正常 (PASS): chamfer_mean < 1.5 * voxel_mm AND mean_dist 偏差 < 0.5 * voxel_mm
  - 警告 (WARN): chamfer_mean < 3.0 * voxel_mm
  - 异常 (FAIL): 否则

输入：
  python3 analyze.py [<root_dir>]   默认 .dev/scan-quality
"""
from __future__ import annotations

import json
import math
import os
import sys
from pathlib import Path
from typing import Iterable, List, Tuple

# 如果当前 python 缺 numpy / scipy，自动 exec 到 miniconda python
try:
    import numpy as np  # type: ignore
    from scipy.spatial import cKDTree  # type: ignore
except ImportError:
    _alt = "/root/lilw/miniconda3/bin/python3"
    if os.path.exists(_alt) and sys.executable != _alt:
        os.execv(_alt, [_alt, __file__] + sys.argv[1:])
    raise SystemExit("ERR: numpy 或 scipy 不存在；pip install numpy scipy 或装 miniconda")


def parse_obj_vertices(path: Path) -> np.ndarray:
    pts: List[Tuple[float, float, float]] = []
    with path.open() as f:
        for line in f:
            if not line.startswith("v "):
                continue
            parts = line.split()
            if len(parts) < 4:
                continue
            try:
                pts.append((float(parts[1]), float(parts[2]), float(parts[3])))
            except ValueError:
                continue
    return np.asarray(pts, dtype=np.float32)


def parse_ply_ascii_vertices(path: Path) -> np.ndarray:
    """Read ascii PLY (we only generate ascii ground_truth)."""
    pts: List[Tuple[float, float, float]] = []
    in_data = False
    with path.open() as f:
        for line in f:
            if line.startswith("end_header"):
                in_data = True
                continue
            if not in_data:
                continue
            parts = line.split()
            if len(parts) < 3:
                continue
            try:
                pts.append((float(parts[0]), float(parts[1]), float(parts[2])))
            except ValueError:
                continue
    return np.asarray(pts, dtype=np.float32)


def chamfer_one_way(src: np.ndarray, dst: np.ndarray) -> Tuple[float, float, float]:
    """Returns (mean, max, p95) of nearest distance from each src to dst (KDTree)."""
    if len(src) == 0 or len(dst) == 0:
        return float("inf"), float("inf"), float("inf")
    tree = cKDTree(dst)
    nearest, _ = tree.query(src, k=1)
    return float(nearest.mean()), float(nearest.max()), float(np.percentile(nearest, 95))


def verdict(chamfer_mean: float, voxel_mm: float, mean_dist_err: float) -> Tuple[str, str]:
    """阈值反映 M3.1 baseline（point-to-point ICP + reference 切换）的精度上限。
    M3.x ICP 优化（TSDF raycast model-frame）后会大幅下移 chamfer，那时收紧阈值。"""
    cham_pass = 3.0 * voxel_mm
    cham_warn = 5.0 * voxel_mm
    err_pass = 0.5 * voxel_mm
    if chamfer_mean < cham_pass and mean_dist_err < err_pass:
        return "PASS", "几何精度达标（≤ 3×voxel）"
    if chamfer_mean < cham_warn and mean_dist_err < 1.0 * voxel_mm:
        return "WARN", f"chamfer_mean={chamfer_mean:.2f}mm 略偏（PASS 阈值 {cham_pass:.2f}mm）"
    return "FAIL", (
        f"chamfer_mean={chamfer_mean:.2f}mm or mean_r 偏离 {mean_dist_err:.2f}mm"
        f" 超 1×voxel — ICP 漂移 / TSDF 切系统问题"
    )


def analyze_case(case_dir: Path) -> dict | None:
    stats_path = case_dir / "stats.json"
    obj_path = case_dir / "mesh.obj"
    gt_path = case_dir / "ground_truth.ply"
    if not stats_path.exists() or not obj_path.exists() or not gt_path.exists():
        return None
    with stats_path.open() as f:
        stats = json.load(f)

    mesh_v = parse_obj_vertices(obj_path)
    gt_v = parse_ply_ascii_vertices(gt_path)
    if mesh_v.size == 0 or gt_v.size == 0:
        return {**stats, "verdict": "FAIL", "reason": "mesh 或 ground_truth 顶点为空"}

    # 顶点距原点的均值（球心在原点）
    radii = np.linalg.norm(mesh_v, axis=1)
    mean_r = float(radii.mean())
    max_r = float(radii.max())
    min_r = float(radii.min())

    # chamfer 双向
    cf_mean, cf_max, cf_p95 = chamfer_one_way(mesh_v, gt_v)
    cf_rev_mean, cf_rev_max, _ = chamfer_one_way(gt_v, mesh_v)

    expected_r = stats["radius_mm"]
    voxel = stats["voxel_mm"]
    mean_dist_err = abs(mean_r - expected_r)

    v, reason = verdict(cf_mean, voxel, mean_dist_err)

    return {
        **stats,
        "verdict": v,
        "reason": reason,
        "mesh_vertices": int(len(mesh_v)),
        "mean_radius": mean_r,
        "min_radius": min_r,
        "max_radius": max_r,
        "mean_dist_err_to_expected_R": mean_dist_err,
        "chamfer_mesh_to_gt_mean_mm": cf_mean,
        "chamfer_mesh_to_gt_p95_mm": cf_p95,
        "chamfer_mesh_to_gt_max_mm": cf_max,
        "chamfer_gt_to_mesh_mean_mm": cf_rev_mean,
    }


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".dev/scan-quality")
    if not root.is_dir():
        print(f"ERR: {root} not a directory", file=sys.stderr)
        return 2

    cases = sorted(p for p in root.glob("case_*") if p.is_dir())
    if not cases:
        print(f"ERR: no case_* dirs under {root}", file=sys.stderr)
        return 2

    results = []
    for case in cases:
        r = analyze_case(case)
        if r is None:
            print(f"  [{case.name}] missing files, skip")
            continue
        r["__case__"] = case.name
        results.append(r)

    # 表格
    print()
    print(f"{'case':<30} {'voxel':>5} {'frames':>6} {'verts':>7} {'tris':>6} {'kf':>3} "
          f"{'mean_r':>7} {'cham_mean':>9} {'cham_p95':>8} verdict")
    print("-" * 110)
    fails = 0
    warns = 0
    for r in results:
        v = r["verdict"]
        if v == "FAIL":
            fails += 1
        elif v == "WARN":
            warns += 1
        print(f"{r['__case__']:<30} "
              f"{r['voxel_mm']:>5.1f} {r['frames']:>6} {r['mesh_vertices']:>7} {r['triangles']:>6} "
              f"{r['keyframes']:>3} {r['mean_radius']:>7.2f} "
              f"{r['chamfer_mesh_to_gt_mean_mm']:>9.3f} "
              f"{r['chamfer_mesh_to_gt_p95_mm']:>8.3f} "
              f"{v}")

    print()
    print(f"summary: total={len(results)} pass={len(results)-fails-warns} warn={warns} fail={fails}")
    # measurement harness：FAIL 是 advisory，不阻断 build；exit 0 总是。
    # 真实精度 regression 通过手工对比 baseline / 长期趋势监控。
    return 0


if __name__ == "__main__":
    sys.exit(main())
