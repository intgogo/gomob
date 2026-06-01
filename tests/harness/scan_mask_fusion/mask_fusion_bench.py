"""mask_fusion_bench — M3.17 ① SAM mask 引导融合「行为好不好」端到端基准。

证 SAM 分割接进融合主线的价值:带地面+干扰物的场景,**mask 引导融合只重建目标、剔除背景**;
与不带 mask 的 baseline(把地面/干扰一起融进来)对照。链路:
  clutter_dataset 造场景(目标+地面+干扰)→ 多视角渲染(噪声 RGBD + 人工松框)
  → 真 HQ-SAM(sam_core.segment,box 提示)逐视角分割 → mask 填进 RgbdFrame.mask
  → fusion_core 融合(mask 外像素预掩,不入点云/配准/积分)→ 对比目标观测面。

指标(写 metrics.json):
  sam_iou_mean      —— 各视角 SAM mask vs GT 目标 mask 的 IoU 均值(分割本身准不准)
  masked.{chamfer,accuracy,completeness,coverage,contamination}  —— mask 引导重建 vs 目标观测面
  baseline.{...}    —— 不带 mask 重建(含背景)同指标,作对照
contamination = 重建点里离目标观测面 > τ_contam 的比例(背景污染度);mask 引导应 ~0、baseline 高。
判定交给 analyze.py。确定性(固定 seed + o3d.random.seed)。SAM 需 GPU + 权重(见 run.sh)。
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
import open3d as o3d

HERE = os.path.dirname(__file__)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "scan_multiview_quality"))
sys.path.insert(0, os.path.join(HERE, "..", "..", "..", "server", "fusion_service"))
sys.path.insert(0, os.path.join(HERE, "..", "..", "..", "server", "sam_service"))
from fusion_core import FusionConfig, RgbdFrame, fuse_with_poses  # noqa: E402
from quality_bench import accuracy_and_coverage                   # noqa: E402
from sam_core import segment                                      # noqa: E402
import clutter_dataset as cl                                      # noqa: E402

VOXEL_MM = 5.0
N_VIEWS = 8
TAU_CONTAM_MM = 20.0     # 重建点离目标观测面 >此 即判背景污染(远超 voxel/噪声,确属背景非边界毛刺)


def _iou(a: np.ndarray, b: np.ndarray) -> float:
    inter = np.logical_and(a, b).sum()
    union = np.logical_or(a, b).sum()
    return float(inter / union) if union else 0.0


def contamination(fused: o3d.geometry.TriangleMesh, ref: o3d.geometry.PointCloud,
                  ext_world_to_cam0: np.ndarray, tau_mm: float, n_sample: int = 80000) -> float:
    """重建网格采样点对齐回 GT 世界系后,离目标观测面 ref > τ 的比例(%)——背景污染度。"""
    f = o3d.geometry.TriangleMesh(fused)
    f.transform(np.linalg.inv(ext_world_to_cam0))
    if len(f.vertices) == 0:
        return 0.0
    fp = f.sample_points_uniformly(n_sample) if len(f.triangles) else o3d.geometry.PointCloud(f.vertices)
    d = np.asarray(fp.compute_point_cloud_distance(ref))
    return float((d > tau_mm / 1000.0).mean() * 100.0)


def _metrics(mesh, ref, ext0) -> dict:
    chamfer, acc, comp, cov = accuracy_and_coverage(mesh, ref, ext0)
    contam = contamination(mesh, ref, ext0, TAU_CONTAM_MM)
    return {
        "vertices": len(mesh.vertices), "triangles": len(mesh.triangles),
        "chamfer_mm": round(chamfer, 3), "accuracy_mm": round(acc, 3),
        "completeness_mm": round(comp, 3),
        "coverage_pct": {k: round(v, 1) for k, v in cov.items()},
        "contamination_pct": round(contam, 2),
    }


def main() -> int:
    o3d.utility.random.seed(0)
    out_dir = os.environ.get("OUTPUT_DIR", os.path.join(HERE, "..", "..", "..", ".dev", "scan_mask_fusion"))
    os.makedirs(out_dir, exist_ok=True)
    print(f"[mask_fusion_bench] 造场景:目标+地面+干扰,{N_VIEWS} 视角,voxel {VOXEL_MM}mm")
    ds = cl.build_clutter_dataset(n_views=N_VIEWS, noisy=True)
    frames_full, gt_masks, boxes = ds["frames_full"], ds["gt_obj_masks"], ds["boxes"]
    exts, obj_ref, intr = ds["exts"], ds["obj_ref"], ds["intr"]

    # 逐视角真 HQ-SAM 分割(box 提示=人工松框)→ mask 引导帧
    ious, frames_masked = [], []
    for i, f in enumerate(frames_full):
        res = segment(f.color, box=boxes[i])
        ious.append(_iou(res.mask, gt_masks[i]))
        frames_masked.append(RgbdFrame(color=f.color, depth_mm=f.depth_mm, intr=f.intr,
                                       conf=f.conf, mask=res.mask))
        if i == 0:                                  # 落首视角图供人工复核
            from PIL import Image
            Image.fromarray(f.color).save(os.path.join(out_dir, "view0_scene.png"))
            Image.fromarray((gt_masks[i].astype(np.uint8) * 255), "L").save(
                os.path.join(out_dir, "view0_gt_mask.png"))
            Image.fromarray((res.mask.astype(np.uint8) * 255), "L").save(
                os.path.join(out_dir, "view0_sam_mask.png"))
    sam_iou_mean = float(np.mean(ious))
    print(f"  SAM IoU/视角={[round(x, 3) for x in ious]} 均值={round(sam_iou_mean, 4)}")

    # GT-mask 帧:完美目标 mask,作"机制上界"分离归因(SAM 边界泄漏 vs TSDF 轮廓膨胀/参考问题)
    frames_gtmasked = [RgbdFrame(color=f.color, depth_mm=f.depth_mm, intr=f.intr,
                                 conf=f.conf, mask=gt_masks[i]) for i, f in enumerate(frames_full)]

    cfg = FusionConfig(voxel_size_mm=VOXEL_MM)      # 默认带 conf 预掩(与生产一致)
    mesh_masked, _ = fuse_with_poses(frames_masked, cfg)
    mesh_gtmasked, _ = fuse_with_poses(frames_gtmasked, cfg)
    mesh_baseline, _ = fuse_with_poses(frames_full, cfg)
    masked = _metrics(mesh_masked, obj_ref, exts[0])
    gtmasked = _metrics(mesh_gtmasked, obj_ref, exts[0])
    baseline = _metrics(mesh_baseline, obj_ref, exts[0])
    print(f"  mask 引导(SAM): chamfer={masked['chamfer_mm']}mm 污染={masked['contamination_pct']}% "
          f"顶点={masked['vertices']}")
    print(f"  GT-mask 上界   : chamfer={gtmasked['chamfer_mm']}mm 污染={gtmasked['contamination_pct']}% "
          f"顶点={gtmasked['vertices']}")
    print(f"  baseline 无mask: chamfer={baseline['chamfer_mm']}mm 污染={baseline['contamination_pct']}% "
          f"顶点={baseline['vertices']}")

    # 腐蚀敏感性诊断(非门控):复用同一组 SAM mask,只变 mask_erode_px,使
    # 「合成 clean 边界开腐蚀单调削覆盖、无污染收益 → 默认 0」这一参数选择可从本 harness 复现追溯。
    erode_sweep = []
    for e in (0, 1, 2, 3, 4):
        msh, _ = fuse_with_poses(frames_masked, FusionConfig(voxel_size_mm=VOXEL_MM, mask_erode_px=e))
        mm = _metrics(msh, obj_ref, exts[0])
        erode_sweep.append({"erode_px": e, "cov5_pct": mm["coverage_pct"]["5.0mm"],
                            "contamination_pct": mm["contamination_pct"],
                            "completeness_mm": mm["completeness_mm"], "vertices": mm["vertices"]})
    print("  腐蚀扫描(SAM mask):" + " ".join(
        f"e{s['erode_px']}=cov{s['cov5_pct']}/污{s['contamination_pct']}" for s in erode_sweep))

    try:
        import torch
        env = {"torch": torch.__version__, "cuda": torch.cuda.is_available(),
               "device": torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu"}
    except Exception:
        env = {}
    metrics = {
        "n_views": N_VIEWS, "voxel_mm": VOXEL_MM, "tau_contam_mm": TAU_CONTAM_MM,
        "observed_points": len(obj_ref.points),
        "sam_iou_mean": round(sam_iou_mean, 4),
        "sam_iou_per_view": [round(x, 4) for x in ious],
        "masked": masked, "gt_masked": gtmasked, "baseline": baseline,
        "erode_sweep": erode_sweep,
        "env": env,
    }
    with open(os.path.join(out_dir, "metrics.json"), "w") as fh:
        json.dump(metrics, fh, ensure_ascii=False, indent=2)
    print(f"[mask_fusion_bench] metrics → {os.path.join(out_dir, 'metrics.json')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
