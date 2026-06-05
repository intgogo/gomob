// 测量层预处理（Eigen-only，无 PCL）。对应原厂 8 阶段管线 ② 预处理（docs/16 §3②）。
// 用纯几何/网格算子重写 PCL 的 PassThrough / VoxelGrid / RadiusOutlierRemoval / EuclideanCluster。单位 mm。
#pragma once

#include <cstdint>
#include "measurement/measure_types.h"

namespace gomob::measure {

// 体素降采样：每个边长 leaf 的体素取质心。leaf<=0 原样返回。
Cloud voxelDownsample(const Cloud& in, float leaf);

// 半径离群剔除：保留「半径 radius 内邻居数 >= min_neighbors」的点（近邻用均匀网格加速）。
Cloud radiusOutlierRemoval(const Cloud& in, float radius, int min_neighbors);

// 最大欧氏簇：按 leaf 体素化，占据体素 26-连通 BFS 取点数最多的连通体，
// 返回原分辨率中落在该连通体素集内的点（剥离脱离主体的噪声团）。
Cloud largestEuclideanCluster(const Cloud& in, float leaf);

}  // namespace gomob::measure
