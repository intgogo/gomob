#!/usr/bin/env python3
# A/B 点云 B→A 外参精修：从 site marker 初值出发，用几何 ICP 求 ΔT_cloud。
from __future__ import annotations

import argparse
import json
import math
import os
from dataclasses import dataclass
from typing import Any

import numpy as np
from scipy.optimize import least_squares
from scipy.spatial import cKDTree
from scipy.spatial.transform import Rotation


@dataclass
class RefineConfig:
    voxel_mm: float = 12.0
    normal_k: int = 24
    max_points: int = 12000
    max_corr_mm: float = 140.0
    trim_quantile: float = 0.88
    min_corr: int = 300
    max_iter: int = 18
    loss_scale_mm: float = 8.0
    point_weight: float = 0.035
    stop_trans_mm: float = 0.03
    stop_rot_deg: float = 0.003


def transform_points(points: np.ndarray, mat: np.ndarray) -> np.ndarray:
    return points @ mat[:3, :3].T + mat[:3, 3]


def se3_matrix(xi: np.ndarray) -> np.ndarray:
    mat = np.eye(4, dtype=np.float64)
    mat[:3, :3] = Rotation.from_rotvec(xi[:3]).as_matrix()
    mat[:3, 3] = xi[3:6]
    return mat


def matrix_delta(final: np.ndarray, init: np.ndarray) -> np.ndarray:
    return final @ np.linalg.inv(init)


def matrix_stats(mat: np.ndarray) -> dict[str, float]:
    rot_deg = math.degrees(Rotation.from_matrix(mat[:3, :3]).magnitude())
    trans_mm = float(np.linalg.norm(mat[:3, 3]))
    return {"rot_deg": float(rot_deg), "trans_mm": trans_mm}


def transform_error(est: np.ndarray, truth: np.ndarray) -> dict[str, float]:
    rot = est[:3, :3] @ truth[:3, :3].T
    return {
        "rot_deg": float(math.degrees(Rotation.from_matrix(rot).magnitude())),
        "trans_mm": float(np.linalg.norm(est[:3, 3] - truth[:3, 3])),
        "max_abs_matrix": float(np.max(np.abs(est - truth))),
    }


def _finite_xyz(points: np.ndarray) -> np.ndarray:
    pts = np.asarray(points, dtype=np.float64).reshape(-1, 3)
    mask = np.isfinite(pts).all(axis=1) & (np.abs(pts) < 1e6).all(axis=1)
    return pts[mask]


def voxel_downsample(points: np.ndarray, voxel_mm: float, max_points: int = 0) -> np.ndarray:
    pts = _finite_xyz(points)
    if len(pts) == 0:
        return pts
    if voxel_mm > 0:
        key = np.floor(pts / voxel_mm).astype(np.int64)
        order = np.lexsort((key[:, 2], key[:, 1], key[:, 0]))
        key = key[order]
        pts = pts[order]
        starts = np.r_[0, np.flatnonzero(np.any(np.diff(key, axis=0) != 0, axis=1)) + 1]
        counts = np.diff(np.r_[starts, len(pts)])
        sums = np.add.reduceat(pts, starts, axis=0)
        pts = sums / counts[:, None]
    if max_points and len(pts) > max_points:
        idx = np.linspace(0, len(pts) - 1, max_points).round().astype(np.int64)
        pts = pts[idx]
    return pts


def estimate_normals(points: np.ndarray, k: int = 24) -> tuple[np.ndarray, np.ndarray]:
    pts = _finite_xyz(points)
    if len(pts) < 4:
        raise ValueError("点数过少，无法估计法向")
    kk = min(max(4, k), len(pts))
    tree = cKDTree(pts)
    _, nn = tree.query(pts, k=kk)
    normals = np.zeros_like(pts)
    valid = np.zeros(len(pts), dtype=bool)
    center = np.median(pts, axis=0)
    for i, ids in enumerate(nn):
        neigh = pts[np.atleast_1d(ids)]
        cov = np.cov((neigh - neigh.mean(axis=0)).T)
        vals, vecs = np.linalg.eigh(cov)
        total = float(vals.sum())
        if not np.isfinite(total) or total <= 1e-9:
            continue
        curvature = float(vals[0] / total)
        n = vecs[:, 0]
        # 固定法向符号，便于调试；残差平方本身不依赖朝向。
        if float(n @ (pts[i] - center)) < 0:
            n = -n
        if curvature <= 0.08 and np.isfinite(n).all():
            normals[i] = n
            valid[i] = True
    return normals, valid


def nearest_stats(a_points: np.ndarray, b_points: np.ndarray, b_to_a: np.ndarray) -> dict[str, float]:
    a = _finite_xyz(a_points)
    b = _finite_xyz(b_points)
    if len(a) == 0 or len(b) == 0:
        return {"count": 0, "median_mm": math.inf, "p95_mm": math.inf, "rms_mm": math.inf}
    tree = cKDTree(a)
    d, _ = tree.query(transform_points(b, b_to_a), k=1)
    return {
        "count": int(len(d)),
        "median_mm": float(np.median(d)),
        "p95_mm": float(np.percentile(d, 95)),
        "rms_mm": float(np.sqrt(np.mean(d * d))),
        "within_5mm_frac": float(np.mean(d <= 5.0)),
        "within_10mm_frac": float(np.mean(d <= 10.0)),
        "within_20mm_frac": float(np.mean(d <= 20.0)),
    }


def refine_b_to_a(
    a_points: np.ndarray,
    b_points: np.ndarray,
    init_b_to_a: np.ndarray,
    cfg: RefineConfig | None = None,
) -> dict[str, Any]:
    cfg = cfg or RefineConfig()
    a = voxel_downsample(a_points, cfg.voxel_mm, cfg.max_points)
    b = voxel_downsample(b_points, cfg.voxel_mm, cfg.max_points)
    if len(a) < cfg.min_corr or len(b) < cfg.min_corr:
        raise ValueError(f"点数不足：A={len(a)} B={len(b)} min_corr={cfg.min_corr}")

    normals, normal_valid = estimate_normals(a, cfg.normal_k)
    tree = cKDTree(a)
    current = np.asarray(init_b_to_a, dtype=np.float64).reshape(4, 4).copy()
    initial_stats = nearest_stats(a, b, current)
    history: list[dict[str, float]] = []

    for it in range(cfg.max_iter):
        moved = transform_points(b, current)
        dist, idx = tree.query(moved, k=1)
        valid = (dist <= cfg.max_corr_mm) & normal_valid[idx]
        if int(valid.sum()) < cfg.min_corr:
            raise ValueError(f"有效对应过少：iter={it} corr={int(valid.sum())} min_corr={cfg.min_corr}")
        cutoff = float(np.quantile(dist[valid], cfg.trim_quantile))
        valid &= dist <= max(cutoff, cfg.voxel_mm * 1.2)
        src = b[valid]
        dst = a[idx[valid]]
        nrm = normals[idx[valid]]
        if len(src) > cfg.max_points:
            keep = np.linspace(0, len(src) - 1, cfg.max_points).round().astype(np.int64)
            src, dst, nrm = src[keep], dst[keep], nrm[keep]

        def residual(xi: np.ndarray) -> np.ndarray:
            mat = se3_matrix(xi) @ current
            diff = transform_points(src, mat) - dst
            plane = np.einsum("ij,ij->i", diff, nrm)
            if cfg.point_weight <= 0:
                return plane
            return np.concatenate([plane, diff.reshape(-1) * cfg.point_weight])

        res = least_squares(
            residual,
            np.zeros(6, dtype=np.float64),
            loss="soft_l1",
            f_scale=cfg.loss_scale_mm,
            max_nfev=80,
            x_scale=np.array([0.02, 0.02, 0.02, 25.0, 25.0, 25.0], dtype=np.float64),
        )
        step = se3_matrix(res.x)
        current = step @ current
        step_stats = matrix_stats(step)
        after = nearest_stats(a, b, current)
        history.append({
            "iter": float(it + 1),
            "corr": float(len(src)),
            "cost": float(res.cost),
            "step_rot_deg": step_stats["rot_deg"],
            "step_trans_mm": step_stats["trans_mm"],
            "median_mm": after["median_mm"],
            "p95_mm": after["p95_mm"],
        })
        if step_stats["trans_mm"] <= cfg.stop_trans_mm and step_stats["rot_deg"] <= cfg.stop_rot_deg:
            break

    final_stats = nearest_stats(a, b, current)
    delta = matrix_delta(current, init_b_to_a)
    return {
        "ok": True,
        "a_points": int(len(a)),
        "b_points": int(len(b)),
        "initial": initial_stats,
        "final": final_stats,
        "delta": matrix_stats(delta),
        "b_to_a_final": current,
        "delta_matrix": delta,
        "history": history,
    }


def load_pcd(path: str) -> np.ndarray:
    with open(path, "rb") as f:
        header_lines: list[str] = []
        data_kind = ""
        while True:
            line = f.readline()
            if not line:
                raise ValueError(f"PCD 缺少 DATA 行: {path}")
            text = line.decode("ascii", "replace").strip()
            header_lines.append(text)
            if text.startswith("DATA"):
                parts = text.split()
                data_kind = parts[1].lower() if len(parts) > 1 else ""
                break
        body = f.read()

    fields: list[str] = []
    sizes: list[int] = []
    types: list[str] = []
    counts: list[int] = []
    points = 0
    for line in header_lines:
        parts = line.split()
        if not parts:
            continue
        key, vals = parts[0], parts[1:]
        if key == "FIELDS":
            fields = vals
        elif key == "SIZE":
            sizes = [int(v) for v in vals]
        elif key == "TYPE":
            types = vals
        elif key == "COUNT":
            counts = [int(v) for v in vals]
        elif key == "POINTS":
            points = int(vals[0])
        elif key == "WIDTH" and points == 0:
            points = int(vals[0])
    if not counts:
        counts = [1] * len(fields)
    if not (fields and sizes and types and len(fields) == len(sizes) == len(types) == len(counts)):
        raise ValueError(f"PCD header 不完整: {path}")
    for name in ("x", "y", "z"):
        if name not in fields:
            raise ValueError(f"PCD 缺少 {name} 字段: {path}")

    if data_kind == "ascii":
        arr = np.loadtxt(path, comments="#", skiprows=len(header_lines), dtype=np.float64)
        if arr.ndim == 1:
            arr = arr.reshape(1, -1)
        col = {name: i for i, name in enumerate(fields)}
        return _finite_xyz(np.stack([arr[:, col["x"]], arr[:, col["y"]], arr[:, col["z"]]], axis=1))
    if data_kind != "binary":
        raise ValueError(f"暂不支持 DATA {data_kind}: {path}")

    dtype_fields = []
    for name, size, typ, count in zip(fields, sizes, types, counts):
        if typ == "F" and size == 4:
            dt = "<f4"
        elif typ == "F" and size == 8:
            dt = "<f8"
        elif typ == "I":
            dt = f"<i{size}"
        elif typ == "U":
            dt = f"<u{size}"
        else:
            raise ValueError(f"不支持字段类型 {name}: TYPE={typ} SIZE={size}")
        dtype_fields.append((name, dt, (count,)) if count > 1 else (name, dt))
    dtype = np.dtype(dtype_fields)
    arr = np.frombuffer(body, dtype=dtype, count=points if points > 0 else -1)
    xyz = np.stack([arr["x"].astype(np.float64), arr["y"].astype(np.float64), arr["z"].astype(np.float64)], axis=1)
    return _finite_xyz(xyz)


def load_btoa_json(path: str) -> np.ndarray:
    with open(path, "r", encoding="utf-8") as f:
        payload = json.load(f)
    raw: Any
    if isinstance(payload, list):
        raw = payload
    elif isinstance(payload, dict):
        raw = payload.get("b_to_a", payload.get("matrix", payload.get("bToA")))
    else:
        raw = None
    if raw is None or len(raw) != 16:
        raise ValueError("BTOA_JSON 必须是 16 元数组，或包含 b_to_a/matrix/bToA")
    mat = np.asarray(raw, dtype=np.float64).reshape(4, 4)
    if not np.isfinite(mat).all():
        raise ValueError("BTOA_JSON 含非有限数")
    return mat


def save_refine_json(path: str, result: dict[str, Any]) -> None:
    serializable = {}
    for key, value in result.items():
        if isinstance(value, np.ndarray):
            serializable[key] = value.reshape(-1).tolist()
        else:
            serializable[key] = value
    with open(path, "w", encoding="utf-8") as f:
        json.dump(serializable, f, ensure_ascii=False, indent=2)


def main() -> int:
    ap = argparse.ArgumentParser(description="A/B 点云 B→A 外参精修")
    ap.add_argument("--a-pcd", default=os.environ.get("A_PCD", ""))
    ap.add_argument("--b-pcd", default=os.environ.get("B_PCD", ""))
    ap.add_argument("--btoa-json", default=os.environ.get("BTOA_JSON", ""))
    ap.add_argument("--out-json", default="")
    args = ap.parse_args()
    if not (args.a_pcd and args.b_pcd and args.btoa_json):
        ap.error("需要 --a-pcd --b-pcd --btoa-json，或设置 A_PCD/B_PCD/BTOA_JSON")
    res = refine_b_to_a(load_pcd(args.a_pcd), load_pcd(args.b_pcd), load_btoa_json(args.btoa_json))
    print(f"初始 median={res['initial']['median_mm']:.2f}mm p95={res['initial']['p95_mm']:.2f}mm")
    print(f"精修 median={res['final']['median_mm']:.2f}mm p95={res['final']['p95_mm']:.2f}mm")
    print(f"ΔT_cloud rot={res['delta']['rot_deg']:.4f}deg trans={res['delta']['trans_mm']:.2f}mm")
    if args.out_json:
        save_refine_json(args.out_json, res)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
