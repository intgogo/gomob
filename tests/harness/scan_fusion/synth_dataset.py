"""synth_dataset — 从已知 GT mesh 用光线投射渲染多视角 RGBD(供 fusion harness)。

GT mesh = 非对称带色物体(box+sphere 拼,边长 ~200mm),保证 FPFH/Color-ICP 配准无歧义、有色梯度。
相机环绕物体 N 个角度,raycast 出 dense 深度 + 插值顶点色;可注入弱回波噪声 + 对应低 conf。
返回 (frames, gt_mesh),frames 为 fusion_core.RgbdFrame。确定性(固定 RNG seed)。
"""
from __future__ import annotations

import numpy as np
import open3d as o3d

import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', '..', '..', 'server', 'fusion_service'))
from fusion_core import Intrinsic, RgbdFrame  # noqa: E402


def make_gt_mesh() -> o3d.geometry.TriangleMesh:
    """非对称带色物体:中心 box + 偏置 sphere + 小 box,提供角点/曲面/色梯度。单位 m,~0.2m 尺度。"""
    box = o3d.geometry.TriangleMesh.create_box(0.16, 0.10, 0.12)
    box.translate(-box.get_center())
    sph = o3d.geometry.TriangleMesh.create_sphere(0.05, resolution=20)
    sph.translate(np.array([0.06, 0.05, 0.0]))
    nub = o3d.geometry.TriangleMesh.create_box(0.04, 0.04, 0.08)
    nub.translate(np.array([-0.08, -0.02, 0.04]))
    mesh = box + sph + nub
    mesh.translate(-mesh.get_center())              # 物体中心到原点
    mesh.compute_vertex_normals()
    # 按位置上色(给 Color-ICP 色梯度;也让不同面可区分)
    v = np.asarray(mesh.vertices)
    vmin, vmax = v.min(0), v.max(0)
    col = (v - vmin) / np.maximum(vmax - vmin, 1e-6)
    mesh.vertex_colors = o3d.utility.Vector3dVector(col)
    return mesh


def _look_at_extrinsic(eye, center, up=(0, 1, 0)) -> np.ndarray:
    """world→cam 外参(Open3D 针孔:相机看 +z,y 朝下)。"""
    eye = np.asarray(eye, float); center = np.asarray(center, float); up = np.asarray(up, float)
    z = center - eye; z /= np.linalg.norm(z)            # forward(光轴)
    x = np.cross(z, up); x /= np.linalg.norm(x)         # right
    y = np.cross(z, x)                                  # down
    R = np.stack([x, y, z], axis=0)                     # world→cam 旋转(行=cam 轴)
    t = -R @ eye
    ext = np.identity(4)
    ext[:3, :3] = R; ext[:3, 3] = t
    return ext


def surface_color(pw: np.ndarray, freq: float = 70.0) -> np.ndarray:
    """按世界坐标取正弦 RGB 纹理(默认波长 ~9cm)。入 (M,3) 世界点,出 (M,3) float [0,1]。

    用于验证纹理烘焙:在低多边形网格(抽稀/粗 voxel)上,顶点色按稀疏顶点采样 + TSDF 均值,
    分辨率受限;UV 纹理图集以**图像分辨率**回投影重建表面色,更准。频率取"远大于几何误差"区间
    (波长≫重建误差 mm 级),避免几何误差解相关纹理(几何锁定色对几何误差敏感,见 scan_fusion_texture)。"""
    return np.stack([0.5 + 0.5 * np.sin(pw[:, 0] * freq),
                     0.5 + 0.5 * np.sin(pw[:, 1] * freq + 2.094),
                     0.5 + 0.5 * np.sin(pw[:, 2] * freq + 4.189)], axis=1)


# 兼容旧名
high_freq_color = surface_color


def render_views(mesh, intr: Intrinsic, n_views=10, radius=0.45, elev_deg=18.0, color_fn=None):
    """环绕 raycast → 每视角 (depth_mm float32 HxW, color uint8 HxWx3, extrinsic world→cam)。

    单环固定仰角:相邻视角仅差方位、重叠强且均匀,multiway 配准稳。
    (曾试仰角随方位振荡补顶/底覆盖,反把视角裂成顶/底两簇、簇间耦合弱致底簇整体翻转 120°+,
    得不偿失;固定仰角下不可观测的底面由"仅观测面" chamfer 度量排除,见 fusion_bench。)
    color_fn 不为 None 时按命中点世界坐标逐像素取色(高频 GT 纹理);否则重心插值顶点色。"""
    scene = o3d.t.geometry.RaycastingScene()
    scene.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(mesh))
    tris = np.asarray(mesh.triangles)
    vcol = np.asarray(mesh.vertex_colors)
    K = np.array([[intr.fx, 0, intr.cx], [0, intr.fy, intr.cy], [0, 0, 1]])
    elev = np.deg2rad(elev_deg)
    ce = np.cos(elev)
    out = []
    for i in range(n_views):
        ang = 2 * np.pi * i / n_views
        eye = radius * np.array([ce * np.cos(ang), np.sin(elev), ce * np.sin(ang)])
        ext = _look_at_extrinsic(eye, [0, 0, 0])
        rays = scene.create_rays_pinhole(K.astype(np.float64), ext.astype(np.float64),
                                         intr.width, intr.height)
        ans = scene.cast_rays(rays)
        t_hit = ans['t_hit'].numpy()                    # 沿射线欧氏距离, inf=未命中
        prim = ans['primitive_ids'].numpy()
        uv = ans['primitive_uvs'].numpy()
        rd = rays.numpy()[..., 3:]                      # 单位方向(world)
        org = rays.numpy()[..., :3]
        hit = np.isfinite(t_hit)
        t_safe = np.where(hit, t_hit, 0.0)              # 非命中 t=inf → 置 0,免 inf 流入 matmul
        pts_w = org + t_safe[..., None] * rd            # 命中点(world);非命中点后续按 hit 掩掉
        # → 相机系 z = 深度
        pc = (ext[:3, :3] @ pts_w.reshape(-1, 3).T + ext[:3, 3:4]).T.reshape(intr.height, intr.width, 3)
        depth_mm = np.where(hit, pc[..., 2] * 1000.0, 0.0).astype(np.float32)
        depth_mm[~hit] = 0.0
        # 色:高频 GT 纹理(逐像素)或重心插值顶点色
        color = np.zeros((intr.height, intr.width, 3), np.uint8)
        hi = np.where(hit)
        if hi[0].size:
            if color_fn is not None:
                c = color_fn(pts_w[hi])                  # 逐像素世界坐标高频色
            else:
                tri = tris[prim[hi]]                     # (M,3) 顶点索引
                w12 = uv[hi]                             # (M,2)
                w0 = 1.0 - w12[:, 0] - w12[:, 1]
                c = (w0[:, None] * vcol[tri[:, 0]] + w12[:, 0:1] * vcol[tri[:, 1]]
                     + w12[:, 1:2] * vcol[tri[:, 2]])
            color[hi] = np.clip(c * 255.0, 0, 255).astype(np.uint8)
        out.append((depth_mm, color, ext))
    return out


def add_weak_noise(depth_mm, seed, p_bad=0.40, sigma_mm=40.0, flyer_mm=150.0):
    """对一部分有效像素注弱回波噪声(大噪+概率飞点),返回 (noisy_depth, conf uint8)。
    弱像素逐帧随机 → conf=40;好像素 conf=255;无效像素 conf=0。"""
    g = np.random.RandomState(seed)
    valid = depth_mm > 0
    conf = np.where(valid, 255, 0).astype(np.uint8)
    noisy = depth_mm.copy()
    bad = valid & (g.random_sample(depth_mm.shape) < p_bad)
    n = bad.sum()
    if n:
        noise = g.normal(0, sigma_mm, n)
        flyer = (g.random_sample(n) < 0.25) * (g.choice([-1, 1], n)) * (g.random_sample(n) * 60 + flyer_mm)
        noisy[bad] = np.maximum(0.0, depth_mm[bad] + noise + flyer)
        conf[bad] = 40
    # 好像素也加小噪(σ2mm)模拟传感器底噪
    good = valid & ~bad
    noisy[good] = np.maximum(0.0, depth_mm[good] + g.normal(0, 2.0, good.sum()))
    return noisy.astype(np.float32), conf


def observed_surface(frames, exts, voxel_mm=2.0):
    """各视角(clean)深度反投影到 GT 世界系的并集 → 传感器真正观测到的 GT 表面点云。

    作 chamfer 参考:只含被某视角看到的面,排除不可观测区(纯赤道环绕看不到的底面)。
    使门① chamfer 度量纯重建精度(配准+PGO+TSDF),而非不可达覆盖带来的完整度惩罚。
    须传 clean(无噪)frames——参考必须是真值几何。"""
    pts = []
    for f, ext in zip(frames, exts):
        d = f.depth_mm.astype(np.float64) / 1000.0
        ys, xs = np.where(d > 0)
        z = d[ys, xs]
        x = (xs - f.intr.cx) * z / f.intr.fx
        y = (ys - f.intr.cy) * z / f.intr.fy
        cam = np.stack([x, y, z], axis=1)
        inv = np.linalg.inv(ext)                       # cam → GT 世界系
        world = (inv[:3, :3] @ cam.T + inv[:3, 3:4]).T
        pts.append(world)
    pc = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(np.concatenate(pts, axis=0)))
    return pc.voxel_down_sample(voxel_mm / 1000.0)


def build_dataset(n_views=10, noisy=True, seed0=7000, color_fn=None):
    intr = Intrinsic(width=640, height=480, fx=525.0, fy=525.0, cx=320.0, cy=240.0)
    gt = make_gt_mesh()
    views = render_views(gt, intr, n_views=n_views, color_fn=color_fn)
    frames, exts = [], []
    for i, (depth_mm, color, ext) in enumerate(views):
        if noisy:
            d, conf = add_weak_noise(depth_mm, seed0 + i)
        else:
            d, conf = depth_mm, np.where(depth_mm > 0, 255, 0).astype(np.uint8)
        frames.append(RgbdFrame(color=color, depth_mm=d, intr=intr, conf=conf))
        exts.append(ext)
    # exts[0] = GT(world)→cam0;重建世界系 == cam0 帧(PoseGraph reference_node=0 固定 identity),
    # 故 fused 用 inv(exts[0]) 变回 GT 帧即可与 GT 直接比 chamfer(免二次配准歧义)。
    return frames, gt, exts
