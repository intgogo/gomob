// 激光车辆外廓重建编排（生产流程 pipeline B）。
// 逆向自原厂：两单元世界云 → 机间对齐(site/icp/none) → 点集 UNION → 随机降采样 →
// 可选 AABB 裁剪(zMin 切地面，无 RANSAC) → 输出融合点云。产物=点云（无网格/尺寸）。
// site-extrinsic 由 Kotlin 读 JSON 以 4×4 传入；ICP 兜底见 registration。单位 mm。
#pragma once

#include "lidar/lidar_types.h"

namespace gomob::lidar {

enum class AlignMethod { Site, Icp, None };

struct ScanVehicleParams {
    AlignMethod align{AlignMethod::Icp};
    Eigen::Matrix4f site_extrinsic{Eigen::Matrix4f::Identity()};  // B->A，align==Site 时用
    float keep_ratio{1.0f};               // synthesis_voxel：随机保留比例
    bool  crop{false};
    Eigen::Vector3f crop_min{Eigen::Vector3f::Zero()};   // mm
    Eigen::Vector3f crop_max{Eigen::Vector3f::Zero()};   // mm
    float icp_reject_mm{1500.0f};
};

struct ScanVehicleResult {
    Cloud cloud;                          // 融合产物（mm）
    std::size_t pts_a{0}, pts_b{0}, fused{0}, after_downsample{0}, after_crop{0};
    Eigen::Matrix4f b_to_a{Eigen::Matrix4f::Identity()};
    AlignMethod align_used{AlignMethod::None};
    float fitness{0.0f};                  // ICP 时的平均误差 mm
    Bbox bbox;
};

// 从两单元世界云重建一台车（各在自身设备帧，mm）。
ScanVehicleResult reconstructVehicle(const Cloud& unitA, const Cloud& unitB,
                                     const ScanVehicleParams& p);

}  // namespace gomob::lidar
