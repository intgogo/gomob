// TSDF voxel grid 实施 — 详见 tsdf.h 头文件设计说明。
//
// Curless & Levoy 1996 加权平均 SDF 更新：
//   - 每帧 N×M depth 像素，遍历所有体素？太慢（grid_dim=200 → 8M 体素 vs 256K 像素）
//   - 反向扫：遍历"位姿前向 frustum 内"的体素（cam-system Z 正向 → 投到像素）
//   - 这里先用全体素遍历的实现，正确性优先；后续 NEON / 多线程 / GPU 优化属 M3.x 加速

#include "tsdf.h"

#include <Eigen/Dense>
#include <Eigen/Geometry>

#include <algorithm>
#include <cmath>

namespace gomob::reconstruction {

TsdfVolume::TsdfVolume(const TsdfConfig& cfg) : cfg_(cfg) {
    grid_dim_ = static_cast<int>(std::ceil(cfg_.grid_extent_mm / cfg_.voxel_size_mm));
    if (grid_dim_ < 2) grid_dim_ = 2;
    std::size_t total = static_cast<std::size_t>(grid_dim_) * grid_dim_ * grid_dim_;
    sdf_.assign(total, 1.0f);
    weight_.assign(total, 0.0f);
}

void TsdfVolume::Integrate(const uint16_t* depth_mm, int width, int height,
                           double fx, double fy, double cx, double cy,
                           const float* pose7) {
    // 解相机位姿：相机在世界系，P_w = R*P_c + t；逆变换 P_c = R^T * (P_w - t)
    Eigen::Vector3f t(pose7[0], pose7[1], pose7[2]);
    Eigen::Quaternionf q(pose7[6], pose7[3], pose7[4], pose7[5]);
    q.normalize();
    Eigen::Matrix3f R = q.toRotationMatrix();
    Eigen::Matrix3f Rt = R.transpose();

    const float vsz = cfg_.voxel_size_mm;
    const float trunc = cfg_.truncation_dist_mm;
    const float wmax = cfg_.weight_clamp;

    // 遍历所有体素（端侧 30fps ingest 时 200^3=8M voxel × 简单算术，O(1G) ops/frame，
    // 单线程 arm64 ~几十 ms；M3.x 阶段加 NEON / 多线程）
    for (int k = 0; k < grid_dim_; ++k) {
        float wz = cfg_.grid_origin_mm[2] + (k + 0.5f) * vsz;
        for (int j = 0; j < grid_dim_; ++j) {
            float wy = cfg_.grid_origin_mm[1] + (j + 0.5f) * vsz;
            for (int i = 0; i < grid_dim_; ++i) {
                float wx = cfg_.grid_origin_mm[0] + (i + 0.5f) * vsz;
                Eigen::Vector3f Pw(wx, wy, wz);
                Eigen::Vector3f Pc = Rt * (Pw - t);
                if (Pc.z() <= 0) continue;        // 体素在相机背后
                float u = static_cast<float>(fx * Pc.x() / Pc.z() + cx);
                float v = static_cast<float>(fy * Pc.y() / Pc.z() + cy);
                int ui = static_cast<int>(std::lround(u));
                int vi = static_cast<int>(std::lround(v));
                if (ui < 0 || ui >= width || vi < 0 || vi >= height) continue;
                uint16_t d = depth_mm[vi * width + ui];
                if (d == 0) continue;                  // 无效深度
                float z = static_cast<float>(d);
                float sdf = z - Pc.z();
                if (sdf < -trunc) continue;            // 远在表面后面（被遮挡 → 不更新）
                if (sdf > trunc) sdf = trunc;          // 远在表面前 → 截断到 +trunc（自由空间）
                float sdf_norm = sdf / trunc;          // 归一化到 [-1, +1]

                std::size_t idx = Index(i, j, k);
                float w0 = weight_[idx];
                float s0 = sdf_[idx];
                float w1 = std::min(w0 + 1.0f, wmax);
                float s1 = (s0 * w0 + sdf_norm * 1.0f) / w1;
                sdf_[idx] = s1;
                weight_[idx] = w1;
            }
        }
    }

    frame_count_++;
}

void TsdfVolume::Set(int i, int j, int k, float sdf, float weight) {
    if (i < 0 || j < 0 || k < 0 || i >= grid_dim_ || j >= grid_dim_ || k >= grid_dim_) return;
    std::size_t idx = Index(i, j, k);
    sdf_[idx] = sdf;
    weight_[idx] = weight;
}

void TsdfVolume::Get(int i, int j, int k, float& out_sdf, float& out_weight) const {
    if (i < 0 || j < 0 || k < 0 || i >= grid_dim_ || j >= grid_dim_ || k >= grid_dim_) {
        out_sdf = 1.0f; out_weight = 0.0f; return;
    }
    std::size_t idx = Index(i, j, k);
    out_sdf = sdf_[idx];
    out_weight = weight_[idx];
}

TsdfStats TsdfVolume::Stats() const {
    TsdfStats s{};
    s.allocated_voxels = grid_dim_ * grid_dim_ * grid_dim_;
    s.integrated_voxels = 0;
    for (float w : weight_) if (w > 0.0f) ++s.integrated_voxels;
    s.integrated_frames = frame_count_;
    return s;
}

} // namespace gomob::reconstruction
