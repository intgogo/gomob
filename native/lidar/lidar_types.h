// 激光双单元车辆外廓 — 几何内核公共类型（Eigen-only，无 PCL）
// 迁移自 /root/lilw/lidar/src/cloud/types.h，去 PCL：点云 = std::vector<Eigen::Vector3f>。
// 单位约定：全程 **毫米(mm)**，对齐 gomob native（reconstruction/icp 等）；与桌面版的米制差 ×1000。
#pragma once

#include <cmath>
#include <cstdint>
#include <vector>
#include <Eigen/Geometry>

namespace gomob::lidar {

// 点云 = 扁平点列（mm 世界系）。零拷贝出 JNI 时再摊平成 float[]。
using Cloud = std::vector<Eigen::Vector3f>;

// 轴对齐包围盒（mm）。
struct Bbox {
    Eigen::Vector3f min{Eigen::Vector3f::Constant(1e30f)};
    Eigen::Vector3f max{Eigen::Vector3f::Constant(-1e30f)};
    void expand(const Eigen::Vector3f& p) { min = min.cwiseMin(p); max = max.cwiseMax(p); }
    Eigen::Vector3f size() const { return max - min; }
    bool empty() const { return (min.array() > max.array()).any(); }
};

inline Bbox boundingBox(const Cloud& c) {
    Bbox b;
    for (const auto& p : c) b.expand(p);
    return b;
}

// Hamilton 标量在前 [w,x,y,z]，加载即归一化（设备四元数非单位，见 spec §3.2）。
inline Eigen::Quaternionf quatWXYZ(float w, float x, float y, float z) {
    Eigen::Quaternionf q(w, x, y, z);
    q.normalize();
    return q;
}

}  // namespace gomob::lidar
