// 激光线扫 + 轴角 → 世界点云（Stage-4 synthesize / lineToWorld）。
// 前向链 byte-verified（spec_geometry_resolved §1）：
//   P_world = R(q_b2w)·{ Rz(+h)·( T_fix·( R(q_lidar_rot)·P_line + corr_offset ) ) } + b2w_offset
//   P_line  = [cos v·r, sin v·r, 0],  r = dist*range_scale,  h = h_deg·π/180
// 四元数 Hamilton [w,x,y,z] 归一化。单位 mm（dist 已是 mm ⇒ range_scale=1）。
// LDR/PTS 帧解码在 scan_stream（M8.2）；本文件只做几何合成。
#pragma once

#include <vector>
#include "lidar/lidar_types.h"

namespace gomob::lidar {

// 一条极坐标激光线（轴角 h_angle_deg 处）。
struct LdrPoint { float v_angle_deg{0}; float dist_mm{0}; };
struct LdrFrame { float h_angle_deg{0}; std::vector<LdrPoint> points; };

struct SynthesisParams {
    Eigen::Quaternionf q_lidar_rot{1, 0, 0, 0};                 // lidar->axis
    Eigen::Vector3f    lidar_corr_offset{0, 0, 0};              // mm
    Eigen::Matrix4f    T_fix_lidar{Eigen::Matrix4f::Identity()};// axis->device（平移 mm）
    Eigen::Quaternionf q_b2w{1, 0, 0, 0};                       // device->world（真机=identity）
    Eigen::Vector3f    b2w_offset{0, 0, 0};                     // mm
    float range_scale{1.0f};                                    // dist 已 mm ⇒ 1
};

Eigen::Vector3f lineToWorld(float v_angle_rad, float dist_mm, float h_angle_deg, const SynthesisParams& p);

Cloud buildFromLDR(const std::vector<LdrFrame>& frames, const SynthesisParams& p);

}  // namespace gomob::lidar
