"""fusion_bench — M3.14 云端多视角融合算法核「行为好不好」基准(Open3D)。

合成已知 GT 物体 → 多视角带噪 RGBD → fusion_core.fuse() 重建 → 对齐到 GT 帧算 chamfer。
两条硬门(对齐 04b / TODO M3.14 验收):
  ① 干净输入 chamfer ≤ 5mm(算法本身达标:多视角配准+PGO+TSDF 几何正确)。
  ② 带噪输入:conf 加权(阈值预掩码)chamfer ≤ 不加权(承接端侧 M1.6.20 收益到云端)。
确定性(固定 seed)。用法:fusion_bench.py
"""
from __future__ import annotations

import os
import sys
import time

import numpy as np
import open3d as o3d

HERE = os.path.dirname(__file__)
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, '..', '..', '..', 'server', 'fusion_service'))
from fusion_core import fuse, FusionConfig          # noqa: E402
import synth_dataset as synth                        # noqa: E402


def _sample(mesh, n):
    """确定性均匀采样:优先用 Open3D seed 参数(0.17+),旧版无该参数则退回默认(抖动随 n 增大可忽略)。"""
    try:
        return mesh.sample_points_uniformly(n, seed=0)
    except TypeError:
        return mesh.sample_points_uniformly(n)


def chamfer_mm(fused_mesh, ref_cloud, gt_to_cam0, n_sample=80000):
    """fused(重建世界系=cam0 帧)用 inv(gt_to_cam0) 变回 GT 帧,与"观测面"参考点云比对称 chamfer(mm)。
    参考 = 各视角 clean 深度反投影并集(传感器观测到的 GT 面),排除不可观测底面 → 度量纯重建精度。"""
    fused = o3d.geometry.TriangleMesh(fused_mesh)
    fused.transform(np.linalg.inv(gt_to_cam0))       # cam0 帧 → GT 帧
    if len(fused.vertices) == 0:
        return float('inf'), 0
    fpcd = _sample(fused, n_sample) if len(fused.triangles) else \
        o3d.geometry.PointCloud(fused.vertices)
    d_fg = np.asarray(fpcd.compute_point_cloud_distance(ref_cloud))   # fused→ref(精度)
    d_gf = np.asarray(ref_cloud.compute_point_cloud_distance(fpcd))   # ref→fused(观测面完整度)
    cham = (d_fg.mean() + d_gf.mean()) / 2 * 1000.0
    return cham, len(fused.vertices)


def run(label, frames, ref, exts, cfg):
    t0 = time.time()
    mesh = fuse(frames, cfg)
    dt = time.time() - t0
    cham, nv = chamfer_mm(mesh, ref, exts[0])
    print(f"  [{label}] 顶点={nv} chamfer={cham:.2f}mm 耗时={dt:.1f}s "
          f"(conf={'on@'+str(cfg.conf_threshold) if cfg.enable_confidence else 'off'})")
    return cham, nv


def main():
    o3d.utility.random.seed(0)   # 全局定 RANSAC/采样随机 → 确定性
    print("[fusion_bench] 合成 GT 物体 ~0.2m,10 视角环绕")

    # ① 干净输入:验证算法几何正确(配准+PGO+TSDF) chamfer ≤ 5mm
    print("\n① 干净输入(无噪)— 算法达标门")
    fr_clean, gt, exts = synth.build_dataset(n_views=10, noisy=False)
    ref = synth.observed_surface(fr_clean, exts)     # 观测面参考(clean 深度反投影并集)
    cfg_clean = FusionConfig(enable_confidence=False)
    cham_clean, _ = run("clean", fr_clean, ref, exts, cfg_clean)

    # ② 带噪输入:conf 加权 vs 不加权(同视角,参考仍用 clean 观测面)
    print("\n② 带噪输入(40% 弱回波+飞点)— conf 加权收益门")
    fr_noisy, _, exts2 = synth.build_dataset(n_views=10, noisy=True)
    cham_off, _ = run("noisy conf-off", fr_noisy, ref, exts2, FusionConfig(enable_confidence=False))
    cham_on, _ = run("noisy conf-on", fr_noisy, ref, exts2,
                     FusionConfig(enable_confidence=True, conf_threshold=80))

    print("\n=== 判定 ===")
    ok1 = cham_clean <= 5.0
    ok2 = cham_on <= cham_off + 0.2   # 0.2mm 容差吸收采样/离散抖动;去飞点真实收益应远大于此
    improve = (cham_off - cham_on) / cham_off * 100 if cham_off > 0 else 0
    print(f"① 干净 chamfer {cham_clean:.2f}mm ≤ 5mm: {'✓' if ok1 else '✗'}")
    print(f"② 带噪 conf 加权 {cham_on:.2f}mm ≤ 不加权 {cham_off:.2f}mm(降 {improve:.0f}%): {'✓' if ok2 else '✗'}")
    verdict = '正常' if (ok1 and ok2) else ('警告' if (ok1 or ok2) else '异常')
    print(f"\n>>> {verdict}:M3.14 融合算法核 "
          f"{'几何达标 + conf 加权兑现收益' if ok1 and ok2 else '未完全达标,需调参/复查'}")
    return 0 if verdict == '正常' else 1


if __name__ == '__main__':
    sys.exit(main())
