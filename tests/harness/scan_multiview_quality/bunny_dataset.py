"""bunny_dataset — Stanford Bunny 作 GT 的多视角 RGBD 合成(供 scan_multiview_quality)。

Bunny 是公认的非平凡有机基准:耳朵 / 凹陷 / 变曲率,远比 scan_fusion 的 box+sphere 难。
本模块只负责"载入 + 归一 + 上色"出 GT mesh,渲染 / 观测面 / 噪声直接复用 scan_fusion/synth_dataset
(render_views/observed_surface/add_weak_noise 对任意 mesh 通用,不重复造轮子)。

assets/bunny.ply 是 Open3D BunnyMesh 抽稀到 ~12k 面的离线副本(~300KB,保留全部几何特征),
随仓库走,harness 不依赖网络(离线可运行是正式能力)。确定性(固定 RNG seed)。
"""
from __future__ import annotations

import os
import sys

import numpy as np
import open3d as o3d

HERE = os.path.dirname(__file__)
# 复用 scan_fusion 的渲染 / 噪声 / 观测面,以及 fusion_core 数据类型
sys.path.insert(0, os.path.join(HERE, "..", "scan_fusion"))
sys.path.insert(0, os.path.join(HERE, "..", "..", "..", "server", "fusion_service"))
import synth_dataset as synth                          # noqa: E402
from fusion_core import Intrinsic, RgbdFrame           # noqa: E402

BUNNY_PLY = os.path.join(HERE, "assets", "bunny.ply")


def make_bunny_mesh(target_size_m: float = 0.20, ply_path: str = BUNNY_PLY) -> o3d.geometry.TriangleMesh:
    """载入 Bunny → 居中 → 缩放到最大边 target_size_m → 法线 → 按位置上色(给 Color-ICP 色梯度)。

    Bunny 原始无顶点色;按归一化坐标染 RGB,既给 Color-ICP 稳定梯度,又让不同部位可区分。
    Bunny 底部有扫描洞(非水密),但 raycast 渲染不受影响,且 UV 展开作用于"重建网格"而非 GT。"""
    if not os.path.isfile(ply_path):
        raise FileNotFoundError(f"Bunny 资产缺失:{ply_path}(应随仓库 vendored)")
    mesh = o3d.io.read_triangle_mesh(ply_path)
    if len(mesh.vertices) == 0:
        raise ValueError(f"Bunny 网格为空:{ply_path}")
    mesh.translate(-mesh.get_center())
    extent = float((mesh.get_max_bound() - mesh.get_min_bound()).max())
    mesh.scale(target_size_m / max(extent, 1e-9), center=(0, 0, 0))
    mesh.compute_vertex_normals()
    v = np.asarray(mesh.vertices)
    vmin, vmax = v.min(0), v.max(0)
    mesh.vertex_colors = o3d.utility.Vector3dVector((v - vmin) / np.maximum(vmax - vmin, 1e-6))
    return mesh


def build_bunny_dataset(n_views: int = 8, noisy: bool = False, radius: float = 0.45,
                        elev_deg: float = 18.0, seed0: int = 9000, color_fn=None):
    """渲染 Bunny 的 n_views 视角 RGBD。返回 (frames, gt_mesh, exts)。

    与 synth.build_dataset 同构:exts[0]=GT(world)→cam0,重建世界系==cam0 帧,
    fused 用 inv(exts[0]) 变回 GT 帧即可直接比对(免二次配准歧义)。"""
    intr = Intrinsic(width=640, height=480, fx=525.0, fy=525.0, cx=320.0, cy=240.0)
    gt = make_bunny_mesh()
    views = synth.render_views(gt, intr, n_views=n_views, radius=radius, elev_deg=elev_deg, color_fn=color_fn)
    frames, exts = [], []
    for i, (depth_mm, color, ext) in enumerate(views):
        if noisy:
            d, conf = synth.add_weak_noise(depth_mm, seed0 + i)
        else:
            d, conf = depth_mm, np.where(depth_mm > 0, 255, 0).astype(np.uint8)
        frames.append(RgbdFrame(color=color, depth_mm=d, intr=intr, conf=conf))
        exts.append(ext)
    return frames, gt, exts
