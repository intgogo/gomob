"""texture_bench — M3.14 UV-atlas 纹理烘焙「行为好不好」基准。

合成体表面赋**高频正弦纹理**(波长 ~1.6cm,细于 voxel 6mm),顶点色(顶点采样+三角内线性插值)
无法承载这种亚三角细节;UV 纹理图集能。烘焙 albedo 后,在每个三角**内部点(质心)**比较:
  - 顶点色插值色 vs GT 高频色 → 误差(应高:细节被插值抹平)
  - 烘焙纹理采样色 vs GT 高频色 → 误差(应低:纹理保留细节)
门:纹理误差 < 顶点色误差(证纹理烘焙带来真实增益)。确定性(固定 seed)。
"""
from __future__ import annotations

import os
import sys

import numpy as np
import open3d as o3d

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, "..", "..", "..", "server", "fusion_service"))
sys.path.insert(0, os.path.join(HERE, "..", "scan_fusion"))
from fusion_core import fuse_with_poses, bake_albedo, FusionConfig  # noqa: E402
import synth_dataset as synth                                       # noqa: E402


def _sample_albedo(albedo, uv, flip_v):
    h, w = albedo.shape[:2]
    u = np.clip(uv[:, 0], 0, 1)
    v = np.clip(uv[:, 1], 0, 1)
    row = np.clip(np.round(((1 - v) if flip_v else v) * (h - 1)).astype(int), 0, h - 1)
    col = np.clip(np.round(u * (w - 1)).astype(int), 0, w - 1)
    return albedo[row, col].astype(np.float64) / 255.0


def main():
    o3d.utility.random.seed(0)
    print("[texture_bench] 合成纹理体(正弦 RGB,波长~9cm)+ 低多边形(voxel 20mm),10 视角")
    frames, gt, exts = synth.build_dataset(n_views=10, noisy=False, color_fn=synth.surface_color)
    # voxel 20mm → 低多边形网格(~300 顶点):顶点色稀疏受限,凸显图像分辨率纹理的优势。
    mesh, poses = fuse_with_poses(frames, FusionConfig(enable_confidence=False, voxel_size_mm=20.0))
    tm, albedo = bake_albedo(mesh, frames, poses, tex_size=1024)
    # V/VC/T/UV 必须全取自同一个 tm:bake_albedo 内部清理过网格(去退化/重复/非流形),
    # 三角索引与原始 mesh 不再对应,混用会致 UV 错位。
    V = tm.vertex.positions.numpy()
    VC = tm.vertex.colors.numpy().astype(np.float64)
    if VC.max() > 1.0 + 1e-6:
        VC = VC / 255.0
    T = tm.triangle.indices.numpy().astype(np.int64)
    UV = tm.triangle.texture_uvs.numpy()             # [n_tri,3,2],与 T 同序
    print(f"  fused 顶点={len(V)} 面={len(T)} "
          f"albedo={albedo.shape[0]}² 覆盖={ (albedo.sum(2)>0).mean()*100:.0f}%")

    # 三角质心:顶点色插值色 / UV / 3D 位置
    cen3d = V[T].mean(axis=1)                         # [n_tri,3](cam0 帧)
    vcol = VC[T].mean(axis=1)                         # 顶点色在质心的线性插值
    uvc = UV.mean(axis=1)                             # [n_tri,2]

    # GT 高频色:质心 → GT 帧 → high_freq_color
    inv0 = np.linalg.inv(exts[0])
    gtp = (inv0[:3, :3] @ cen3d.T + inv0[:3, 3:4]).T
    gtcol = synth.surface_color(gtp)                 # [n_tri,3] 0..1

    # 纹理采样(两种 v 约定取更优,记录用哪种)
    best = None
    for flip in (False, True):
        tcol = _sample_albedo(albedo, uvc, flip)
        covered = tcol.sum(1) > 0.02                 # 排除未覆盖纹素(gutter 黑)
        err = np.abs(tcol[covered] - gtcol[covered]).mean()
        if best is None or err < best[1]:
            best = (flip, err, covered)
    flip, tex_err, covered = best
    vtx_err = np.abs(vcol[covered] - gtcol[covered]).mean()

    print(f"  覆盖三角={covered.mean()*100:.0f}% v翻转={flip}")
    print(f"  顶点色误差={vtx_err:.4f}  纹理误差={tex_err:.4f}  "
          f"(纹理降 {(1-tex_err/vtx_err)*100:.0f}%)")
    print("\n=== 判定 ===")
    ok = tex_err < 0.85 * vtx_err                    # 要求 ≥15% 改善,避免近平手噪声误判
    print(f"纹理误差 {tex_err:.4f} < 0.85×顶点色误差 {0.85*vtx_err:.4f}(顶点 {vtx_err:.4f}): {'✓' if ok else '✗'}")
    verdict = "正常" if ok else "异常"
    print(f"\n>>> {verdict}:UV 纹理烘焙 "
          f"{'以图像分辨率重建表面色,显著优于稀疏顶点色' if ok else '未见足够增益,需查 UV/投影约定或参数'}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
