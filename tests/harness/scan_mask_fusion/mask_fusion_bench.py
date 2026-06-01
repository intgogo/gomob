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
from fusion_core import FusionConfig, RgbdFrame, fuse_with_poses, integrate_tsdf  # noqa: E402
from quality_bench import accuracy_and_coverage                   # noqa: E402
from sam_core import segment                                      # noqa: E402
import clutter_dataset as cl                                      # noqa: E402
import heuristic_roi as hr                                        # noqa: E402

VOXEL_MM = 5.0
N_VIEWS = 8
# 两个阈值同一度量(重建点离目标观测面的距离),不同尺度看不同问题:
#   TAU_CONTAM=20mm:>此 = 远离目标的整块背景(地面/杂物),看"有没有把背景融进来"(门④⑤⑥)。
#   TAU_BURR=8mm  :>此(>voxel5+噪声~2)= 紧贴目标外的多余几何(裙边/锯齿),看"边缘干不干净"(门⑦)。
#   8mm 取值稳健性:SAM(~2%)与启发式(~36%)差 ~18×,τ∈[6,15]mm 下降比都远 ≥80%(见 README 灵敏度说明)。
TAU_CONTAM_MM = 20.0
TAU_BURR_MM = 8.0


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
    o3d.utility.random.seed(0)   # 锁定均匀采样 → burr/污染跨 run 确定(否则采样本身给门⑦带 ~0.04pp 噪声)
    fp = f.sample_points_uniformly(n_sample) if len(f.triangles) else o3d.geometry.PointCloud(f.vertices)
    d = np.asarray(fp.compute_point_cloud_distance(ref))
    return float((d > tau_mm / 1000.0).mean() * 100.0)


def _metrics(mesh, ref, ext0) -> dict:
    o3d.utility.random.seed(0)   # 锁定 accuracy_and_coverage 内部采样;contamination 各自再 seed
    chamfer, acc, comp, cov = accuracy_and_coverage(mesh, ref, ext0)
    contam = contamination(mesh, ref, ext0, TAU_CONTAM_MM)
    burr = contamination(mesh, ref, ext0, TAU_BURR_MM)
    return {
        "vertices": len(mesh.vertices), "triangles": len(mesh.triangles),
        "chamfer_mm": round(chamfer, 3), "accuracy_mm": round(acc, 3),
        "completeness_mm": round(comp, 3),
        "coverage_pct": {k: round(v, 1) for k, v in cov.items()},
        "contamination_pct": round(contam, 2),
        "burr_pct": round(burr, 2),       # 离真表面 >TAU_BURR 的多余几何占比(边缘毛刺/裙边)
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
    mesh_masked, poses_masked = fuse_with_poses(frames_masked, cfg)
    mesh_gtmasked, poses_gt = fuse_with_poses(frames_gtmasked, cfg)
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

    # A/B:M3.14 阶段1 启发式 ROI(纯深度法)vs SAM,验收 04b §5.2 阶段2"毛刺降≥80%"。
    # **受控对比**:三种 mask 共用同一组干净位姿(poses_gt,GT-mask 融合得到)重积分 → 隔离配准
    # (Open3D RANSAC 非确定偶发翻转),纯比"分割致的边缘毛刺"。否则启发式含地面裙边的点云配准更易翻,
    # 高毛刺会混入"配准谁更稳"的伪因(实测固定位姿后启发box 仍 36% → 毛刺确是真实裙边,非翻转)。
    # box=同人工框(steelman,stage1 用户框补救路径)、center=忠实原生整图中心 ROI。
    def _heur_mask(i, f, box_each):
        return hr.foreground_depth(f.depth_mm, box=boxes[i] if box_each else None) > 0
    def _fuse_fixed(maskfn):
        fr = [RgbdFrame(color=f.color, depth_mm=f.depth_mm, intr=f.intr, conf=f.conf, mask=maskfn(i, f))
              for i, f in enumerate(frames_full)]
        return _metrics(integrate_tsdf(fr, poses_gt, cfg), obj_ref, exts[0])
    sam_ab = _fuse_fixed(lambda i, f: frames_masked[i].mask)        # SAM mask,固定位姿
    heur_box = _fuse_fixed(lambda i, f: _heur_mask(i, f, True))
    heur_ctr = _fuse_fixed(lambda i, f: _heur_mask(i, f, False))
    # 毛刺下降(steelman:对启发式 box 版算,其毛刺更低 → 对 SAM 更严苛)
    burr_red = (100.0 * (heur_box["burr_pct"] - sam_ab["burr_pct"]) / heur_box["burr_pct"]
                if heur_box["burr_pct"] > 0 else 0.0)
    print(f"  [固定位姿受控 A/B] SAM 毛刺={sam_ab['burr_pct']}% / 启发box 毛刺={heur_box['burr_pct']}%"
          f"(顶点 {heur_box['vertices']}) / 启发ctr 毛刺={heur_ctr['burr_pct']}% → 降 {round(burr_red, 1)}%")

    # 腐蚀敏感性诊断(非门控):**固定 erode=0 的位姿**(poses_masked),只变 mask_erode_px 重积分 TSDF,
    # 隔离"腐蚀对表面"与"配准抖动"(Open3D RANSAC 非确定偶发翻转,见 finding)→ 使
    # 「合成 clean 边界开腐蚀单调削覆盖、无污染收益 → 默认 0」可干净复现追溯,不被配准噪声污染。
    # 几何合理性:腐蚀只是把 mask 收成子集(少积分边界像素),位姿仍是这组帧对目标的正确解,
    # 不产生"位姿-点云不自洽"偏差;只是覆盖随腐蚀略降,正是要观测的量。
    erode_sweep = []
    for e in (0, 1, 2, 3, 4):
        msh = integrate_tsdf(frames_masked, poses_masked, FusionConfig(voxel_size_mm=VOXEL_MM, mask_erode_px=e))
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
        # A/B 受控对比(固定 poses_gt 重积分,隔离配准)
        "ab_fixed_pose": {"note": "三种 mask 共用 GT-mask 干净位姿重积分,隔离配准纯比分割",
                          "sam": sam_ab, "heuristic_box": heur_box, "heuristic_center": heur_ctr},
        "ab_burr_reduction_pct": round(burr_red, 1),
        "erode_sweep": erode_sweep,
        "env": env,
    }
    with open(os.path.join(out_dir, "metrics.json"), "w") as fh:
        json.dump(metrics, fh, ensure_ascii=False, indent=2)
    print(f"[mask_fusion_bench] metrics → {os.path.join(out_dir, 'metrics.json')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
