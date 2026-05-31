"""quality_bench — M3.16 多视角重建端到端质量评估(Open3D)。

合成 Stanford Bunny 8 角度 RGBD → fusion_core 端到端重建 → 量三项质量指标,写 metrics.json:
  1. mesh chamfer(mm)        重建网格 vs 传感器观测面(精度)
  2. 点云覆盖度(%@τ)         观测面被重建覆盖的比例(完整度,τ=3/5/8/10mm)
  3. UV atlas 利用率(%)      iso-charts 展开后三角 UV 面积和 / 单位方(纹理打包效率)

判定交给 analyze.py(硬门 = chamfer+coverage,UV 软报告)。
真实卡车数据:GOMOB_TRUCK_DATASET 指向 RgbdShot bundle(.zip)则跑真实重建并报告(无 GT,
只出 mesh 统计 + UV 利用率);未提供则诚实标 skipped(非失败,不伪造)。确定性(固定 seed)。
"""
from __future__ import annotations

import json
import os
import sys
import time

import numpy as np
import open3d as o3d

HERE = os.path.dirname(__file__)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "scan_fusion"))
sys.path.insert(0, os.path.join(HERE, "..", "..", "..", "server", "fusion_service"))
from fusion_core import fuse_with_poses, FusionConfig   # noqa: E402
import synth_dataset as synth                            # noqa: E402
import bunny_dataset as bunny                            # noqa: E402

VOXEL_MM = 5.0
N_VIEWS = 8
TAUS_MM = (3.0, 5.0, 8.0, 10.0)


def _clean_for_uv(mesh: o3d.geometry.TriangleMesh) -> o3d.geometry.TriangleMesh:
    """与 fusion_core.bake_albedo 同样的清理(去退化/重复/非流形),UV 展开要求近流形。"""
    m = o3d.geometry.TriangleMesh(mesh)
    m.remove_degenerate_triangles()
    m.remove_duplicated_vertices()
    m.remove_duplicated_triangles()
    m.remove_non_manifold_edges()
    return m


def uv_utilization(mesh: o3d.geometry.TriangleMesh, tex_size: int = 1024) -> float:
    """iso-charts(Open3D compute_uvatlas)展开后,三角 UV 面积和占单位方比例(%)。
    UV 在 [0,1]²,故面积和即打包利用率;有机 MC 网格小 chart 多,实测天花板 ~65%(见 README)。"""
    tm = o3d.t.geometry.TriangleMesh.from_legacy(_clean_for_uv(mesh))
    tm.compute_uvatlas(size=tex_size)
    uv = tm.triangle.texture_uvs.numpy()                 # [n_tri,3,2]
    e1 = uv[:, 1] - uv[:, 0]
    e2 = uv[:, 2] - uv[:, 0]
    area = 0.5 * np.abs(e1[:, 0] * e2[:, 1] - e1[:, 1] * e2[:, 0])
    return float(area.sum() * 100.0)


def accuracy_and_coverage(fused: o3d.geometry.TriangleMesh, ref: o3d.geometry.PointCloud,
                          ext_world_to_cam0: np.ndarray, n_sample: int = 80000):
    """fused(cam0 帧)对齐回 GT 世界系,与观测面 ref 比:chamfer + 双向分量 + 各 τ 覆盖度。

    ext_world_to_cam0 = render 时 exts[0](world→cam0 外参);fused 重建世界系==cam0 帧,
    左乘 inv 变回 GT 世界系(与 M3.14 fusion_bench 同法,已用 GT 位姿对照验证逼近真值)。
    返回 (chamfer_mm, accuracy_mm, completeness_mm, coverage)。对称 chamfer 会把"精度差但完整"
    与"完整差但精度高"平均掉,故另出两个方向分量:
      accuracy_mm   = mean(fused→ref):重建点离观测面多远(飞点/伪几何会抬高)
      completeness_mm = mean(ref→fused):观测面被重建覆盖多紧(漏洞/翻转会抬高)。"""
    f = o3d.geometry.TriangleMesh(fused)
    f.transform(np.linalg.inv(ext_world_to_cam0))
    if len(f.vertices) == 0:
        return float("inf"), float("inf"), float("inf"), {f"{t}mm": 0.0 for t in TAUS_MM}
    fp = f.sample_points_uniformly(n_sample) if len(f.triangles) else o3d.geometry.PointCloud(f.vertices)
    d_fg = np.asarray(fp.compute_point_cloud_distance(ref))   # fused→ref(精度)
    d_gf = np.asarray(ref.compute_point_cloud_distance(fp))   # ref→fused(完整度)
    accuracy = d_fg.mean() * 1000.0
    completeness = d_gf.mean() * 1000.0
    chamfer = (accuracy + completeness) / 2
    coverage = {f"{t}mm": float((d_gf < t / 1000.0).mean() * 100.0) for t in TAUS_MM}
    return float(chamfer), float(accuracy), float(completeness), coverage


def eval_synthetic() -> dict:
    print(f"[quality_bench] 合成 Stanford Bunny ~0.2m,{N_VIEWS} 视角环绕,voxel {VOXEL_MM}mm")
    frames, gt, exts = bunny.build_bunny_dataset(n_views=N_VIEWS, noisy=False)
    ref = synth.observed_surface(frames, exts)
    t0 = time.time()
    mesh, _ = fuse_with_poses(frames, FusionConfig(enable_confidence=False, voxel_size_mm=VOXEL_MM))
    fusion_ms = int((time.time() - t0) * 1000)
    chamfer, accuracy, completeness, coverage = accuracy_and_coverage(mesh, ref, exts[0])
    uv = uv_utilization(mesh)
    res = {
        "n_views": N_VIEWS, "voxel_mm": VOXEL_MM,
        "gt_vertices": len(gt.vertices),
        "observed_points": len(ref.points),
        "fused": {"vertices": len(mesh.vertices), "triangles": len(mesh.triangles), "fusion_ms": fusion_ms},
        "chamfer_mm": round(chamfer, 3),
        "accuracy_mm": round(accuracy, 3),          # fused→ref(精度,飞点/伪几何抬高)
        "completeness_mm": round(completeness, 3),  # ref→fused(完整度,漏洞/翻转抬高)
        "coverage_pct": {k: round(v, 1) for k, v in coverage.items()},
        "uv_utilization_pct": round(uv, 1),
        "uv_method": "open3d-isocharts",
    }
    print(f"  fused 顶点={res['fused']['vertices']} 面={res['fused']['triangles']} 耗时={fusion_ms}ms")
    print(f"  chamfer={res['chamfer_mm']}mm(精度{res['accuracy_mm']}/完整{res['completeness_mm']}) "
          f"coverage={res['coverage_pct']}  UV利用率={res['uv_utilization_pct']}%")
    return res


def eval_truck() -> dict:
    """真实卡车数据(GOMOB_TRUCK_DATASET 指向 RgbdShot bundle .zip)。无 GT → 只出 mesh 统计 + UV。"""
    path = os.environ.get("GOMOB_TRUCK_DATASET", "").strip()
    if not path:
        return {"status": "skipped", "reason": "GOMOB_TRUCK_DATASET 未设置(真实卡车 RGBD 采集见 TODO ②,尚未就绪)"}
    if not os.path.isfile(path):
        return {"status": "skipped", "reason": f"数据集不存在:{path}"}
    try:
        # 延迟到此处 import:仅当真实卡车数据存在时才需 rgbd_bundle,合成路径不依赖它。
        from rgbd_bundle import unpack
        with open(path, "rb") as fh:
            frames = unpack(fh.read())
        t0 = time.time()
        mesh, _ = fuse_with_poses(frames, FusionConfig(enable_confidence=True, voxel_size_mm=VOXEL_MM))
        fusion_ms = int((time.time() - t0) * 1000)
        uv = uv_utilization(mesh) if len(mesh.triangles) else 0.0
        print(f"  [truck] frames={len(frames)} fused 顶点={len(mesh.vertices)} 面={len(mesh.triangles)} UV={uv:.1f}%")
        return {
            "status": "ok", "dataset": path, "frame_count": len(frames),
            "fused": {"vertices": len(mesh.vertices), "triangles": len(mesh.triangles), "fusion_ms": fusion_ms},
            "uv_utilization_pct": round(uv, 1),
            "note": "真实数据无 GT,仅出重建统计 + UV;精度/完整度需另接真值或扫描复现核对",
        }
    except Exception as e:  # noqa: BLE001 — 真实数据问题如实记录,不掩盖
        return {"status": "error", "dataset": path, "error": f"{type(e).__name__}: {e}"}


def main() -> int:
    o3d.utility.random.seed(0)
    out_dir = os.environ.get("OUTPUT_DIR", os.path.join(HERE, "..", "..", "..", ".dev", "scan_multiview_quality"))
    os.makedirs(out_dir, exist_ok=True)
    metrics = {"synthetic": eval_synthetic(), "truck": eval_truck()}
    if metrics["truck"].get("status") == "skipped":
        print(f"  [truck] 跳过:{metrics['truck']['reason']}")
    out = os.path.join(out_dir, "metrics.json")
    with open(out, "w") as fh:
        json.dump(metrics, fh, ensure_ascii=False, indent=2)
    print(f"[quality_bench] metrics → {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
