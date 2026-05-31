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
    # 配准尺度默认跟 voxel_size 派生(reg_voxel≈voxel、对应距离≈2.5×voxel):
    #   配准下采样要与重建分辨率同量级,FPFH 特征才够判别、Color-ICP/全局对应才够紧。
    #   旧固定 fpfh=12mm / corr=30mm 对 voxel≈6mm 的紧致物体勉强能用,但对更细 voxel 或复杂
    #   有机体(Bunny)特征过粗、对应过松 → 宽基线对(近背对视角)误配且 fitness 假高,line
    #   process 拦不住 → 位姿翻转。实测 scan_multiview_quality:Bunny 8 视角 7.5mm → 1.8mm。
    #   None=按 voxel 派生;显式赋值可覆盖(目前无调用方覆盖)。
    reg_voxel_mm: Optional[float] = None    # 配准下采样体素(None→voxel_size)
    reg_corr_mm: Optional[float] = None     # Color-ICP / 全局优化最大对应距离(None→2.5×voxel_size)
    loop_closure: bool = True           # 末视角→首视角闭环边(04b 强制)

    def reg_voxel_m(self) -> float:
        """配准下采样体素(m):特征/RANSAC 用。默认 = min(voxel_size, 12mm)。

        跟 voxel_size 派生让细 voxel(如 Bunny voxel5)用细特征、配准更准;但**封顶 12mm**:
        输出 voxel 很粗时(如纹理 harness voxel20)配准仍需足够细的点云才有判别力,
        不能让配准下采样跟着粗到 20mm(实测会让低多边形纹理 bake 位姿偏、纹理增益反转)。
        派生不变式:RANSAC 对应=1.5×reg_voxel、Color-ICP 对应=reg_corr_m()=2.5×reg_voxel(见下)。
        用 `is not None` 而非真值判断:reg_voxel_mm=0 是显式覆盖(虽无意义),不应被当未设。"""
        if self.reg_voxel_mm is not None:
            return self.reg_voxel_mm / 1000.0
        return min(self.voxel_size_mm, 12.0) / 1000.0

    def reg_corr_m(self) -> float:
        """Color-ICP 与全局优化最大对应距离(m),默认 = 2.5×reg_voxel(跟随封顶后的配准体素)。"""
        if self.reg_corr_mm is not None:
            return self.reg_corr_mm / 1000.0
        return 2.5 * self.reg_voxel_m()


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
    返回 (4x4 变换, information 矩阵)。配准尺度跟 voxel 派生(见 FusionConfig)。"""
    ransac_dist = cfg.reg_voxel_m() * 1.5               # RANSAC 对应距离 = 1.5×reg_voxel
    icp_corr = cfg.reg_corr_m()                         # Color-ICP 最大对应距离 = 2.5×reg_voxel
    ransac = o3d.pipelines.registration.registration_ransac_based_on_feature_matching(
        src_d, dst_d, src_f, dst_f, True, ransac_dist,
        o3d.pipelines.registration.TransformationEstimationPointToPoint(False), 3,
        [o3d.pipelines.registration.CorrespondenceCheckerBasedOnEdgeLength(0.9),
         o3d.pipelines.registration.CorrespondenceCheckerBasedOnDistance(ransac_dist)],
        o3d.pipelines.registration.RANSACConvergenceCriteria(100000, 0.999))
    current = ransac.transformation
    for corr, iters in ((icp_corr, 50), (icp_corr / 2, 30)):  # 多尺度对应距离(粗→细)精修
        try:
            res = o3d.pipelines.registration.registration_colored_icp(
                src_d, dst_d, corr, current,
                o3d.pipelines.registration.TransformationEstimationForColoredICP(),
                o3d.pipelines.registration.ICPConvergenceCriteria(max_iteration=iters))
            current = res.transformation
        except RuntimeError:
            break  # 弱纹理面 color-icp 可能退化,保留上一级结果
    info = o3d.pipelines.registration.get_information_matrix_from_point_clouds(
        src_d, dst_d, ransac_dist, current)
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
    reg_voxel = cfg.reg_voxel_m()
    preps = [_prep(p, reg_voxel) for p in pcds]          # 每云下采样+FPFH 只算一次
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
            max_correspondence_distance=cfg.reg_corr_m(),
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


def fuse_with_poses(frames, cfg: Optional[FusionConfig] = None):
    """端到端:N 帧 RgbdFrame → (融合 mesh, 位姿列表)。位姿由 multiway registration 自估。
    poses[i]=cam_i→world(=cam0 帧),供纹理烘焙复用(投影外参=inv(pose))。"""
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
    return integrate_tsdf(frames, poses, cfg), poses


def fuse(frames, cfg: Optional[FusionConfig] = None) -> o3d.geometry.TriangleMesh:
    """端到端:N 帧 RgbdFrame → 融合 mesh(仅顶点色)。"""
    return fuse_with_poses(frames, cfg)[0]


def mesh_stats(mesh: o3d.geometry.TriangleMesh) -> dict:
    return {"vertices": len(mesh.vertices), "triangles": len(mesh.triangles)}


def mesh_to_glb(mesh: o3d.geometry.TriangleMesh) -> bytes:
    """Open3D mesh → GLB 字节。用 trimesh 导出(Open3D 0.19 自带 .glb 写出损坏,实测回读 0 顶点)。
    顶点色直出 GLB(per-vertex color);UV atlas 纹理烘焙列 M3.14 后续。"""
    import trimesh
    v = np.asarray(mesh.vertices)
    f = np.asarray(mesh.triangles)
    if len(v) == 0 or len(f) == 0:
        raise ValueError("空 mesh,无法导出 GLB")
    kw = {}
    if mesh.has_vertex_colors():
        c = (np.clip(np.asarray(mesh.vertex_colors), 0, 1) * 255).astype(np.uint8)
        kw["vertex_colors"] = c
    tm = trimesh.Trimesh(vertices=v, faces=f, process=False, **kw)
    return tm.export(file_type="glb")


def bake_albedo(mesh: o3d.geometry.TriangleMesh, frames, poses, tex_size: int = 1024):
    """对融合 mesh 做 UV 展开(iso-charts)+ 多视角 RGB 投影烘焙 albedo 纹理。
    poses[i]=cam_i→world(=mesh 所在 cam0 帧);投影外参=world→cam=inv(pose)。
    返回 (t_mesh 带 texture_uvs, albedo uint8 HxWx3)。可见性/重叠混合由 Open3D 内部用 mesh 几何处理。"""
    m = o3d.geometry.TriangleMesh(mesh)
    m.remove_degenerate_triangles()
    m.remove_duplicated_vertices()
    m.remove_duplicated_triangles()
    m.remove_non_manifold_edges()
    tm = o3d.t.geometry.TriangleMesh.from_legacy(m)
    tm.compute_uvatlas(size=tex_size)
    images, intrinsics, extrinsics = [], [], []
    for f, pose in zip(frames, poses):
        images.append(o3d.t.geometry.Image(
            o3d.core.Tensor(np.ascontiguousarray(f.color.astype(np.uint8)))))
        K = np.array([[f.intr.fx, 0, f.intr.cx], [0, f.intr.fy, f.intr.cy], [0, 0, 1]], np.float64)
        intrinsics.append(o3d.core.Tensor(K))
        extrinsics.append(o3d.core.Tensor(np.ascontiguousarray(np.linalg.inv(pose))))
    albedo = tm.project_images_to_albedo(images, intrinsics, extrinsics, tex_size, True)
    alb = albedo.as_tensor().numpy()
    if alb.dtype != np.uint8:
        alb = np.clip(alb * 255.0, 0, 255).astype(np.uint8) if alb.max() <= 1.0 + 1e-6 \
            else np.clip(alb, 0, 255).astype(np.uint8)
    return tm, alb[..., :3]


def textured_mesh_to_glb(tm: o3d.t.geometry.TriangleMesh, albedo: np.ndarray) -> bytes:
    """带 UV+albedo 的 t_mesh → GLB(trimesh)。Open3D texture_uvs 是三角属性 [n_tri,3,2],
    去索引为每角一顶点(GLB 用逐顶点 UV);v 轴翻转适配 glTF(原点左上)。"""
    import trimesh
    from PIL import Image
    V = tm.vertex.positions.numpy()
    T = tm.triangle.indices.numpy().astype(np.int64)
    UV = tm.triangle.texture_uvs.numpy().reshape(-1, 2).astype(np.float64)
    verts = V[T].reshape(-1, 3)
    faces = np.arange(len(verts), dtype=np.int64).reshape(-1, 3)
    uv = UV.copy()
    uv[:, 1] = 1.0 - uv[:, 1]                       # glTF v 轴翻转
    vis = trimesh.visual.texture.TextureVisuals(uv=uv, image=Image.fromarray(albedo))
    tri = trimesh.Trimesh(vertices=verts, faces=faces, visual=vis, process=False)
    return tri.export(file_type="glb")
