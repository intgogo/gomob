// TSDF voxel grid — 多帧深度积分到隐式场（Truncated Signed Distance Function）
//
// 业务定位（详见 docs/architecture/04-reconstruction-pipeline.md §3.2）：
//   - 累积多帧深度：多观测在同一体素上加权平均 → 天然降噪
//   - 隐式表面：体素存"到表面有符号距离"，零等值面 = 物体表面
//   - 后续接 Marching Cubes 提 mesh
//
// 关键决策：
//   - 体素结构：稠密 3D 数组（grid_dim^3 × 8 bytes/voxel = sdf+weight）
//     · grid_extent=400mm voxel=2mm → 200^3 voxel = 8M × 8B = 64MB；端侧可承受
//     · 大物体 voxel 调到 5mm 节省内存
//   - 投影积分（Curless & Levoy 1996 经典做法）：每个体素中心 P_v 用 pose⁻¹ 变到相机系，
//     投到 depth 像素 (u,v)，读 z = depth(u,v)，sdf = z - P_v.z（相机看出去那个体素该多远）
//     更新：W' = W + 1, sdf' = (W*sdf + new_sdf) / W'
//   - truncation：超过 truncation_dist 的 sdf 不更新（远离表面 → 不可信）
//   - 网格中心：默认 (0,0,0)；sessionCreate 用户给 grid_extent_mm 后自动计算偏移

#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace gomob::reconstruction {

struct TsdfConfig {
    float voxel_size_mm = 2.0f;
    float grid_extent_mm = 400.0f;       // 立方体边长，物体应放在 ±extent/2 内
    float truncation_dist_mm = 8.0f;      // 通常 4×voxel
    float weight_clamp = 100.0f;          // 体素权重上限（避免老观测主导）
    std::array<float, 3> grid_origin_mm = {-200.0f, -200.0f, -200.0f}; // 网格 (0,0,0) 角的世界坐标
};

struct TsdfStats {
    int allocated_voxels;        // grid_dim^3
    int integrated_voxels;       // weight > 0 的体素数
    int integrated_frames;
};

class TsdfVolume {
public:
    explicit TsdfVolume(const TsdfConfig& cfg);

    // 用一帧 16bit mm 深度图 + 内参 + 相机位姿 (世界 → 相机) 积分一次
    // pose7: [tx,ty,tz,qx,qy,qz,qw]，含义"相机在世界系的位姿"，即 P_w = R*P_c + t
    // 返回：本次实际更新的体素数（>0 → TSDF 在工作；==0 → 内参/grid 位置/位姿任一错位）
    int Integrate(const uint16_t* depth_mm, int width, int height,
                  double fx, double fy, double cx, double cy,
                  const float* pose7);

    int dim() const { return grid_dim_; }
    float voxel_size() const { return cfg_.voxel_size_mm; }
    const TsdfConfig& config() const { return cfg_; }
    int frame_count() const { return frame_count_; }
    TsdfStats Stats() const;

    // 取体素 (i,j,k) 的 sdf / weight；越界返回 (1.0, 0.0)
    void Get(int i, int j, int k, float& out_sdf, float& out_weight) const;

    // 直接设置体素值（合成测试 / 离线导入用；正常 streaming 走 Integrate）
    void Set(int i, int j, int k, float sdf, float weight);

    // 体素 (i,j,k) 的世界中心坐标
    std::array<float, 3> VoxelCenter(int i, int j, int k) const {
        return {
            cfg_.grid_origin_mm[0] + (i + 0.5f) * cfg_.voxel_size_mm,
            cfg_.grid_origin_mm[1] + (j + 0.5f) * cfg_.voxel_size_mm,
            cfg_.grid_origin_mm[2] + (k + 0.5f) * cfg_.voxel_size_mm,
        };
    }

    const std::vector<float>& sdf() const { return sdf_; }
    const std::vector<float>& weight() const { return weight_; }

private:
    inline std::size_t Index(int i, int j, int k) const {
        return (static_cast<std::size_t>(k) * grid_dim_ + j) * grid_dim_ + i;
    }

    TsdfConfig cfg_;
    int grid_dim_;                 // grid_extent / voxel_size，向上取整
    std::vector<float> sdf_;       // size = grid_dim^3，初始 1.0（远离表面）
    std::vector<float> weight_;    // size = grid_dim^3，初始 0.0
    int frame_count_ = 0;
};

} // namespace gomob::reconstruction
