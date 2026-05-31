"""fusion_core — 多视角 RGBD 云端融合算法核(Open3D)。

04b §5.1 管线:per-view 点云 → pairwise(FPFH+RANSAC 粗对齐 → Color-ICP 精修)
→ 全局 PoseGraph 优化(multiway registration)→ conf 加权 TSDF 积分 → Marching Cubes。

置信加权(承接端侧 M1.6.20 语义到 Open3D):
  Open3D 的 RGBDImage→PointCloud 与 ScalableTSDFVolume.integrate **无 per-point/per-pixel 权重 API**。
  故把端侧"软加权"在 Open3D 落成**可行等价**:按 conf 阈值预掩码深度(conf<thr 的像素置 0,不参与
  点云/配准/积分)。这正是已验证的 mask_recovery 形态(conf≥阈值保留干净像素、密度仍可观),
  且 registration 与 integration 用同一份 conf-masked 深度,保证位姿与体素来自同一可信像素集。
  (真要"软加权"需自写 C++ TSDF 扩展,列为后续;当前硬阈值已能兑现 density-first+置信 的收益。)

纯函数,无 NATS/MinIO/HTTP 依赖,供 fusion_service(app.py)与 harness 共用。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Optional

import numpy as np
import open3d as o3d


@dataclass
class Intrinsic:
    width: int
    height: int
    fx: float
    fy: float
    cx: float
    cy: float

    def o3d(self) -> o3d.camera.PinholeCameraIntrinsic:
        return o3d.camera.PinholeCameraIntrinsic(
            self.width, self.height, self.fx, self.fy, self.cx, self.cy)


@dataclass
class RgbdFrame:
    """一视角 RGBD。color uint8 HxWx3;depth_mm uint16/float HxW(mm);conf 可选 uint8 HxW(0..255)。"""
    color: np.ndarray
    depth_mm: np.ndarray
    intr: Intrinsic
    conf: Optional[np.ndarray] = None


@dataclass
class FusionConfig:
    voxel_size_mm: float = 6.0          # 04b:与 P100R3 噪声底匹配
    sdf_trunc_mm: float = 24.0          # 4×voxel
    conf_threshold: int = 80            # conf<thr 的像素预掩码(端侧 M1.6.20 / mask_recovery 同阈)
    enable_confidence: bool = True
    depth_trunc_mm: float = 8000.0      # P100R3 工作距离上限
    fpfh_voxel_mm: float = 12.0         # 粗配准下采样(= 2×voxel)
    icp_max_corr_mm: float = 30.0       # Color-ICP 最大对应距离
    loop_closure: bool = True           # 末视角→首视角闭环边(04b 强制)


def _masked_depth(f: RgbdFrame, cfg: FusionConfig) -> np.ndarray:
    """按 conf 阈值预掩码 + 工作距离裁剪,返回 float32 mm 深度(掩掉的置 0)。"""
    d = f.depth_mm.astype(np.float32).copy()
    d[d > cfg.depth_trunc_mm] = 0.0
    if cfg.enable_confidence and f.conf is not None:
        d[f.conf < cfg.conf_threshold] = 0.0
    return d


def _rgbd_image(f: RgbdFrame, cfg: FusionConfig) -> o3d.geometry.RGBDImage:
    color = o3d.geometry.Image(np.ascontiguousarray(f.color.astype(np.uint8)))
    depth_m = (_masked_depth(f, cfg) / 1000.0).astype(np.float32)  # mm → m
    depth = o3d.geometry.Image(np.ascontiguousarray(depth_m))
    return o3d.geometry.RGBDImage.create_from_color_and_depth(
        color, depth, depth_scale=1.0, depth_trunc=cfg.depth_trunc_mm / 1000.0,
        convert_rgb_to_intensity=False)


def make_pointcloud(f: RgbdFrame, cfg: FusionConfig) -> o3d.geometry.PointCloud:
    rgbd = _rgbd_image(f, cfg)
    pcd = o3d.geometry.PointCloud.create_from_rgbd_image(rgbd, f.intr.o3d())
    return pcd


def _prep(pcd: o3d.geometry.PointCloud, voxel_m: float):
    """下采样 + 法向 + FPFH(供 RANSAC 与 Color-ICP 复用,避免全连接图 O(n²) 重算)。"""
    down = pcd.voxel_down_sample(voxel_m)
    down.estimate_normals(o3d.geometry.KDTreeSearchParamHybrid(radius=voxel_m * 2, max_nn=30))
    fpfh = o3d.pipelines.registration.compute_fpfh_feature(
        down, o3d.geometry.KDTreeSearchParamHybrid(radius=voxel_m * 5, max_nn=100))
    return down, fpfh


def pairwise_register(src_d, src_f, dst_d, dst_f, cfg: FusionConfig):
    """已下采样(带色+法向+FPFH)点云的 src→dst 配准:FPFH+RANSAC 粗 → Color-ICP 多尺度精。
    返回 (4x4 变换, information 矩阵)。Color-ICP 在下采样云上跑(带色,提速)。"""
    voxel_m = cfg.fpfh_voxel_mm / 1000.0
    dist = voxel_m * 1.5
    ransac = o3d.pipelines.registration.registration_ransac_based_on_feature_matching(
        src_d, dst_d, src_f, dst_f, True, dist,
        o3d.pipelines.registration.TransformationEstimationPointToPoint(False), 3,
        [o3d.pipelines.registration.CorrespondenceCheckerBasedOnEdgeLength(0.9),
         o3d.pipelines.registration.CorrespondenceCheckerBasedOnDistance(dist)],
        o3d.pipelines.registration.RANSACConvergenceCriteria(100000, 0.999))
    current = ransac.transformation
    for corr, iters in ((dist, 50), (dist / 2, 30)):    # 多尺度对应距离(粗→细)精修
        try:
            res = o3d.pipelines.registration.registration_colored_icp(
                src_d, dst_d, corr, current,
                o3d.pipelines.registration.TransformationEstimationForColoredICP(),
                o3d.pipelines.registration.ICPConvergenceCriteria(max_iteration=iters))
            current = res.transformation
        except RuntimeError:
            break  # 弱纹理面 color-icp 可能退化,保留上一级结果
    info = o3d.pipelines.registration.get_information_matrix_from_point_clouds(
        src_d, dst_d, dist, current)
    return current, info


def build_pose_graph(pcds, cfg: FusionConfig) -> o3d.pipelines.registration.PoseGraph:
    """全连接 multiway registration(Open3D 官方范式)→ 全局优化。

    注册所有 i<j 对:相邻(j==i+1)=里程计确定边并推进节点初值;非相邻=闭环候选不确定边。
    全连接给冗余约束 → 单条 pairwise 失败(无重叠/弱纹理误配)可被其它边绕过,
    避免纯顺序链上一处翻转就让下游所有位姿崩坏(实测 view7-9 曾翻 141°/741mm)。
    全局优化的 line process + edge_prune 自动下调坏边权重。约定:
      pairwise_register(src,dst)→T 使 src 对齐 dst;odometry=T@odometry;节点存 inv(odometry)=cam→world。
    """
    PG = o3d.pipelines.registration
    voxel_m = cfg.fpfh_voxel_mm / 1000.0
    preps = [_prep(p, voxel_m) for p in pcds]            # 每云下采样+FPFH 只算一次
    n = len(pcds)
    pg = PG.PoseGraph()
    odometry = np.identity(4)
    pg.nodes.append(PG.PoseGraphNode(np.linalg.inv(odometry)))  # node0=inv(I)=I
    for src in range(n):
        for dst in range(src + 1, n):
            T, info = pairwise_register(preps[src][0], preps[src][1],
                                        preps[dst][0], preps[dst][1], cfg)
            if dst == src + 1:                          # 相邻=里程计,确定边 + 推进节点初值
                odometry = np.dot(T, odometry)
                pg.nodes.append(PG.PoseGraphNode(np.linalg.inv(odometry)))  # cam(dst)→world
                pg.edges.append(PG.PoseGraphEdge(src, dst, T, info, uncertain=False))
            elif cfg.loop_closure:                      # 非相邻=闭环候选,不确定边
                pg.edges.append(PG.PoseGraphEdge(src, dst, T, info, uncertain=True))
    o3d.pipelines.registration.global_optimization(
        pg,
        o3d.pipelines.registration.GlobalOptimizationLevenbergMarquardt(),
        o3d.pipelines.registration.GlobalOptimizationConvergenceCriteria(),
        o3d.pipelines.registration.GlobalOptimizationOption(
            max_correspondence_distance=cfg.icp_max_corr_mm / 1000.0,
            edge_prune_threshold=0.25, reference_node=0))
    return pg


def integrate_tsdf(frames, poses_cam_to_world, cfg: FusionConfig) -> o3d.geometry.TriangleMesh:
    """conf-masked 深度按优化位姿积分进 ScalableTSDFVolume → Marching Cubes 提 mesh。"""
    vol = o3d.pipelines.integration.ScalableTSDFVolume(
        voxel_length=cfg.voxel_size_mm / 1000.0,
        sdf_trunc=cfg.sdf_trunc_mm / 1000.0,
        color_type=o3d.pipelines.integration.TSDFVolumeColorType.RGB8)
    for f, pose in zip(frames, poses_cam_to_world):
        rgbd = _rgbd_image(f, cfg)
        vol.integrate(rgbd, f.intr.o3d(), np.linalg.inv(pose))  # integrate 收 world→cam(extrinsic)
    mesh = vol.extract_triangle_mesh()
    mesh.compute_vertex_normals()
    return mesh


def fuse(frames, cfg: Optional[FusionConfig] = None) -> o3d.geometry.TriangleMesh:
    """端到端:N 帧 RgbdFrame → 融合 mesh。位姿由 multiway registration 自估(无需外部 pose)。"""
    cfg = cfg or FusionConfig()
    if len(frames) < 2:
        raise ValueError("至少 2 帧")
    o3d.utility.random.seed(0)   # 定 RANSAC/采样全局随机 → 同输入同输出,可复现
    pcds = [make_pointcloud(f, cfg) for f in frames]
    pg = build_pose_graph(pcds, cfg)
    poses = [np.asarray(node.pose) for node in pg.nodes]  # cam→world
    # 重锚到 cam0 帧:不依赖 Open3D reference_node 必把 node0 锁在 identity(版本/数值未必),
    # 显式左乘 inv(pose0) → 构造性保证 world==cam0(harness 用 inv(exts[0]) 对齐 GT 的前提)。
    t0_inv = np.linalg.inv(poses[0])
    poses = [t0_inv @ p for p in poses]
    return integrate_tsdf(frames, poses, cfg)
