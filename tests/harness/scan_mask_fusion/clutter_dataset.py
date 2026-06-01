"""clutter_dataset — 造「目标物 + 地面 + 干扰物」的多视角 RGBD 场景(供 mask 引导融合 harness)。

证 M3.17 ① 的价值需要**背景**:目标周围有地面、旁边有干扰物。流程:
  目标物(复用 scan_fusion.make_gt_mesh,~0.2m 非对称带色)+ 地面大平面 + 旁置干扰球
  → 合并成一个 RaycastingScene(目标三角形排在最前 → primitive_id < n_obj_tris 即目标命中)
  → 环绕 raycast,每视角产出:
     full_depth/full_color  —— 整场景(目标+背景),作融合输入(再加噪)
     obj_mask(GT)          —— 该视角目标命中(精确已知,代 mask 真值)
     ext(world→cam)        —— 外参
     box                    —— 目标世界 AABB 投到该视角的 2D 包围盒 + 外扩(模拟人工松框)

像素配色按归属区分(目标=高频橙纹理 / 地面=低频冷纹理 / 干扰=洋红),让 SAM 能按外观把
目标从紧贴的地面里分出来(纯框裁剪做不到——松框内必含地面)。确定性(固定 seed)。
"""
from __future__ import annotations

import os
import sys

import numpy as np
import open3d as o3d

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, "..", "scan_fusion"))
sys.path.insert(0, os.path.join(HERE, "..", "..", "..", "server", "fusion_service"))
from fusion_core import Intrinsic, RgbdFrame  # noqa: E402
from synth_dataset import (_look_at_extrinsic, add_weak_noise,  # noqa: E402
                           make_gt_mesh, observed_surface)

SEED = 4100


def _obj_color_fn(aabb: o3d.geometry.AxisAlignedBoundingBox):
    """目标按世界坐标取**非周期线性渐变**色(同 make_gt_mesh 的 (v-vmin)/(vmax-vmin))。
    刻意不用周期正弦纹理:周期纹理会让 FPFH/Color-ICP 在非相邻视角特征混叠 → 误匹配致位姿翻转,
    那是配准对周期纹理的鲁棒性课题(另记 finding),不该混进"验证 mask 机制"的本 harness。"""
    vmin = np.asarray(aabb.get_min_bound())
    vmax = np.asarray(aabb.get_max_bound())
    span = np.maximum(vmax - vmin, 1e-6)

    def fn(pw: np.ndarray) -> np.ndarray:
        return np.clip((pw - vmin) / span, 0.0, 1.0)
    return fn


def _floor_color(pw: np.ndarray) -> np.ndarray:
    """地面:低频冷色棋盘格纹理(与目标橙色高频纹理外观显著不同 → SAM 可分)。入 (M,3) 世界点。"""
    chk = ((np.floor(pw[:, 0] * 18).astype(int) + np.floor(pw[:, 2] * 18).astype(int)) % 2)
    base = np.where(chk[:, None] == 0, np.array([0.18, 0.30, 0.55]), np.array([0.10, 0.20, 0.42]))
    return base


def _distractor_color(pw: np.ndarray) -> np.ndarray:
    """干扰物:洋红低频,明显异于目标。"""
    s = 0.5 + 0.3 * np.sin(pw[:, 1] * 40.0)
    return np.stack([0.70 + 0.0 * s, 0.10 + 0.2 * s, 0.55 + 0.0 * s], axis=1)


def build_scene_mesh():
    """合成场景:目标(原点)+ 地面平面(下方)+ 干扰球(旁置)。
    返回 (combined, n_obj_tris, n_floor_tris, obj_aabb)。三角形排序 = 目标→地面→干扰,
    故 primitive_id 落区间即判归属:<n_obj 目标;[n_obj,n_obj+n_floor) 地面;其余干扰。"""
    obj = make_gt_mesh()                                       # ~0.2m 非对称带色目标,中心在原点
    obj_aabb = obj.get_axis_aligned_bounding_box()
    y_bottom = obj_aabb.get_min_bound()[1]

    floor = o3d.geometry.TriangleMesh.create_box(0.9, 0.01, 0.9)
    floor.translate(np.array([-0.45, y_bottom - 0.012, -0.45]))   # 目标正下方一张大平面
    floor.compute_vertex_normals()

    dist = o3d.geometry.TriangleMesh.create_sphere(0.05, resolution=16)
    dist.translate(np.array([0.30, y_bottom + 0.05, 0.0]))        # 旁置干扰物,离目标够远
    dist.compute_vertex_normals()

    n_obj_tris = len(obj.triangles)
    n_floor_tris = len(floor.triangles)
    combined = obj + floor + dist                                 # 目标在最前,地面居中
    return combined, n_obj_tris, n_floor_tris, obj_aabb


def _project_box(aabb: o3d.geometry.AxisAlignedBoundingBox, ext, K, W, H, pad=12):
    """目标世界 AABB 8 角点投到该视角 → 2D 包围盒 + 外扩(模拟人工松框)。"""
    corners = np.asarray(aabb.get_box_points())                   # (8,3) world
    cam = (ext[:3, :3] @ corners.T + ext[:3, 3:4]).T              # → cam
    cam = cam[cam[:, 2] > 1e-4]                                   # 取相机前方
    if cam.shape[0] == 0:                                         # 目标整体在相机后(本 harness 环绕看原点不会发生)
        raise ValueError("目标 AABB 全部落在相机后方,无法投影出框(检查相机/物体摆位)")
    uv = (K @ cam.T).T
    uv = uv[:, :2] / uv[:, 2:3]
    x0, y0 = uv.min(0)
    x1, y1 = uv.max(0)
    return [float(np.clip(x0 - pad, 0, W - 1)), float(np.clip(y0 - pad, 0, H - 1)),
            float(np.clip(x1 + pad, 0, W - 1)), float(np.clip(y1 + pad, 0, H - 1))]


def render_scene_views(combined, n_obj_tris, n_floor_tris, obj_aabb, intr: Intrinsic,
                       n_views=8, radius=0.45, elev_deg=18.0):
    """环绕 raycast 整场景。每视角返回 dict:depth_mm(float32) / color(uint8) / ext / obj_mask(bool) / box。"""
    scene = o3d.t.geometry.RaycastingScene()
    scene.add_triangles(o3d.t.geometry.TriangleMesh.from_legacy(combined))
    obj_color_fn = _obj_color_fn(obj_aabb)
    K = np.array([[intr.fx, 0, intr.cx], [0, intr.fy, intr.cy], [0, 0, 1]], np.float64)
    elev = np.deg2rad(elev_deg)
    ce = np.cos(elev)
    views = []
    for i in range(n_views):
        ang = 2 * np.pi * i / n_views
        eye = radius * np.array([ce * np.cos(ang), np.sin(elev), ce * np.sin(ang)])
        ext = _look_at_extrinsic(eye, [0, 0, 0])
        rays = scene.create_rays_pinhole(K, ext.astype(np.float64), intr.width, intr.height)
        ans = scene.cast_rays(rays)
        t_hit = ans['t_hit'].numpy()
        prim = ans['primitive_ids'].numpy()
        rd = rays.numpy()[..., 3:]
        org = rays.numpy()[..., :3]
        hit = np.isfinite(t_hit)
        t_safe = np.where(hit, t_hit, 0.0)
        pts_w = org + t_safe[..., None] * rd
        pc = (ext[:3, :3] @ pts_w.reshape(-1, 3).T + ext[:3, 3:4]).T.reshape(intr.height, intr.width, 3)
        depth_mm = np.where(hit, pc[..., 2] * 1000.0, 0.0).astype(np.float32)

        obj_hit = hit & (prim < n_obj_tris)                       # primitive 归属 → 目标 GT mask
        floor_hit = hit & (prim >= n_obj_tris) & (prim < n_obj_tris + n_floor_tris)
        # 配色按归属:目标非周期渐变 / 地面冷棋盘 / 干扰洋红(三者外观显著不同 → SAM 可分)
        color = np.zeros((intr.height, intr.width, 3), np.uint8)
        for sel, fn in ((obj_hit, obj_color_fn), (floor_hit, _floor_color),
                        (hit & ~obj_hit & ~floor_hit, _distractor_color)):
            idx = np.where(sel)
            if idx[0].size:
                c = fn(pts_w[idx])
                color[idx] = np.clip(c * 255.0, 0, 255).astype(np.uint8)

        box = _project_box(obj_aabb, ext, K, intr.width, intr.height)
        views.append({"depth_mm": depth_mm, "color": color, "ext": ext,
                      "obj_mask": obj_hit, "box": box})
    return views


def build_clutter_dataset(n_views=8, noisy=True):
    """造融合用数据集。返回 dict:
      frames_full   —— 含背景的 RgbdFrame(噪声+conf,无 mask),作 baseline 输入
      frames_masked —— 同帧但 .mask=SAM 预测目标 mask(融合时只留目标);**mask 由调用方填**
      gt_obj_masks  —— 各视角目标 GT mask(供 IoU)
      boxes         —— 各视角人工松框
      exts          —— world→cam
      obj_ref       —— 目标-only 观测面参考点云(clean,作 chamfer 参考)
      intr
    注:本模块不引 torch;SAM 调用在 bench 里做(填 frames_masked[i].mask)。"""
    intr = Intrinsic(width=640, height=480, fx=525.0, fy=525.0, cx=320.0, cy=240.0)
    combined, n_obj_tris, n_floor_tris, obj_aabb = build_scene_mesh()
    views = render_scene_views(combined, n_obj_tris, n_floor_tris, obj_aabb, intr, n_views=n_views)

    frames_full, gt_obj_masks, boxes, exts = [], [], [], []
    clean_obj_frames = []
    for i, v in enumerate(views):
        if noisy:
            d, conf = add_weak_noise(v["depth_mm"], SEED + i)
        else:
            d, conf = v["depth_mm"], np.where(v["depth_mm"] > 0, 255, 0).astype(np.uint8)
        frames_full.append(RgbdFrame(color=v["color"], depth_mm=d, intr=intr, conf=conf))
        gt_obj_masks.append(v["obj_mask"])
        boxes.append(v["box"])
        exts.append(v["ext"])
        # 目标-only clean 深度 → 观测面参考(只含目标,排除背景与不可观测底面)
        obj_depth = np.where(v["obj_mask"], v["depth_mm"], 0.0).astype(np.float32)
        clean_obj_frames.append(RgbdFrame(color=v["color"], depth_mm=obj_depth, intr=intr))
    obj_ref = observed_surface(clean_obj_frames, exts, voxel_mm=2.0)
    return {"frames_full": frames_full, "gt_obj_masks": gt_obj_masks, "boxes": boxes,
            "exts": exts, "obj_ref": obj_ref, "intr": intr}
