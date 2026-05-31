# scan_fusion_texture — M3.14 UV-atlas 纹理烘焙行为基准

## 目的

验证融合输出从「仅顶点色」升级到「UV-atlas 纹理图集」的**真实增益**:在低多边形网格上,
纹理以**图像分辨率**回投影重建表面色,显著优于稀疏顶点色 + TSDF 均值。

## 算法(`server/fusion_service/fusion_core.py`)

`bake_albedo(mesh, frames, poses)`:
- `o3d.t.geometry.TriangleMesh.compute_uvatlas`(iso-charts UV 展开,内置,无需 xatlas);
- `project_images_to_albedo(images, intrinsics, extrinsics=inv(pose), tex_size)`——多视角 RGB 回投影烘焙
  albedo,可见性/重叠混合由 Open3D 用 mesh 几何处理;
- `textured_mesh_to_glb`——三角 UV 去索引为逐顶点 UV(GLB 约定)+ v 轴翻转 → trimesh 导带纹理 GLB。
  (Open3D 0.19 自带 `.glb` 写出损坏,故 GLB 一律走 trimesh。)

## 判定门

合成体表面赋正弦 RGB 纹理(波长 ~9cm,远大于重建几何误差,避免几何误差解相关纹理);
voxel 20mm 融合得**低多边形**网格(~300 顶点,顶点色分辨率受限)。烘焙 albedo 后,在每个三角**质心**:

- 顶点色插值色 vs GT 表面色 → 误差(高:稀疏顶点 + 均值);
- 烘焙纹理采样色 vs GT 表面色 → 误差(低:图像分辨率)。

**门:纹理误差 < 0.85 × 顶点色误差**。实测 ~0.040 vs 0.084(纹理降 ~52%),两者均远低于随机底(~0.33)。

## 跑法

```bash
bash tests/harness/scan_fusion_texture/run.sh
```

## 边界(诚实)

- 增益体现为**分辨率优势**(图像分辨率 vs 稀疏顶点),非"顶点欠采样高频"——后者受限于几何误差墙:
  几何锁定色对重建几何误差(mm 级)敏感,波长必须远大于该误差,故无法同时演示"顶点欠采样 + 纹理保真"。
- `project_images_to_albedo` 投影到融合几何上,几何误差会采到偏移像素;故纹理频率取"远大于几何误差"区间。
- 合成纹理,真实带纹理表面增益待真实 RGBD(M3.14 ② 真实端到端)。
