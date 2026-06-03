// 激光双单元配准（R5）— 把 unit-B 对齐进 unit-A 帧。
// 两台设备 b2w 都=identity，各自点云在独立设备帧；用配准恢复机间变换。
// 两台对扫，先 4-yaw（0/90/180/270° about Z + 质心匹配）粗初值，再 ICP（复用 gomob
// reconstruction::IcpRegister 的 Eigen SVD/Umeyama 内核）。site-extrinsic 由 Kotlin 读 JSON 后
// 以 4×4 传入，本层只做 ICP 兜底。单位 mm。
#pragma once

#include "lidar/lidar_types.h"

namespace gomob::lidar {

struct RegistrationResult {
    Eigen::Matrix4f transform{Eigen::Matrix4f::Identity()};  // source -> target
    float fitness{1e9f};      // ICP 平均配对误差 mm（越小越好）
    bool  converged{false};
    int   best_yaw_deg{0};    // 哪个粗初值胜出
};

// 把 source 配准到 target（mm）。4 个 yaw 初值各跑一次 ICP，返回最优（变换作用于完整 source）。
RegistrationResult registerTwoUnits(const Cloud& source, const Cloud& target,
                                    float reject_pair_dist_mm = 1500.0f, int max_iter = 40);

}  // namespace gomob::lidar
