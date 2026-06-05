// 激光融合几何 — 变换 / 并集 / 随机降采样 / AABB 裁剪（Eigen-only，去 PCL）
// 迁移自 /root/lilw/lidar/src/cloud/fusion.{h,cpp}。融合=纯点集 UNION（非配准，配准在 registration）。
// 降采样=随机保留比例（synthesis_voxel 语义，非 VoxelGrid 叶大小）。单位 mm。
#pragma once

#include <cstdint>
#include "lidar/lidar_types.h"

namespace gomob::lidar {

// 对点云施加 4×4 刚体变换（齐次）。
Cloud transformCloud(const Cloud& in, const Eigen::Matrix4f& T);

// 多个已在同一帧的点云并集（push_back 拼接，count(out)=sum）。
Cloud fuseUnion(const std::vector<const Cloud*>& clouds);

// 随机保留比例降采样：keep=round(N*ratio)；ratio>=1 全保留。Fisher-Yates，seed 固定可复现。
Cloud randomKeep(const Cloud& in, float ratio, std::uint32_t seed = 1);

// AABB 裁剪（mm，cloud 单位）。inside=true 保留盒内。
Cloud cropBox(const Cloud& in, const Eigen::Vector3f& mn, const Eigen::Vector3f& mx, bool inside = true);

}  // namespace gomob::lidar
