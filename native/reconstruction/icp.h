// ICP point-to-point — 增量配准（Eigen SVD / Umeyama）
//
// 业务定位（详见 docs/architecture/04-reconstruction-pipeline.md §3.1）：
//   - 用户主动转一圈拍物体 → 帧间位姿差小（30fps，每帧旋转 ≤ 12°）
//   - 不需要 SLAM 的全局回环 → point-to-point ICP 做"当前帧 → 关键帧累积体"对齐就够
//   - 退化场景检测：配对不足 6 / 收敛失败 → 返回 ConvergenceState=Failed，调用方决定丢帧或重启
//
// 第一性：不接受 stub。Eigen::umeyama 是教科书级 SVD rigid fit；最近邻用 spatial_hash 实现。

#pragma once

#include <array>
#include <cstddef>
#include <vector>

namespace gomob::reconstruction {

enum class IcpResultStatus {
    Converged,          // 平均误差变化 < 容差，正常收敛
    MaxIterReached,     // 达到 max_iter 仍未收敛但仍可用
    DegenerateInput,    // 配对点 < 6（例如 src 几乎全是无效点 / dst 太稀），不可信
};

struct IcpConfig {
    int max_iter = 30;
    float convergence_tol_mm = 0.05f;   // 平均误差变化 < 此值视为收敛
    float reject_pair_dist_mm = 100.0f; // 配对距离 > 此值丢弃（拒绝错配）
    float spatial_hash_cell_mm = 50.0f; // 空间哈希 cell（建议 = reject_pair_dist / 2）
};

struct IcpResult {
    IcpResultStatus status;
    std::array<float, 7> pose7;        // [tx, ty, tz, qx, qy, qz, qw] mm + 单位四元数
    int iterations;
    int pair_count_final;
    float mean_error_mm;
};

// ICP 增量配准 src → dst。
// - src/dst 都是扁平 [x0,y0,z0, x1,y1,z1, ...]，单位 mm，深度=0 占位点会被自动跳过
// - initial_pose7 同 IcpResult::pose7 格式；首帧调用方应给单位位姿 [0,0,0, 0,0,0,1]
// - src_weights: 可选,长度=src_count 的 per-point 置信权重,**期望 [0,1](如 conf/255)**；nullptr=均权(等同旧行为)。
//   负权 / 超大权未做内部校验(会拉反或数值放大),调用方须保证非负且量级合理。
//   提供时改用加权刚体拟合(Kabsch with weights)：低置信点(弱回波/散斑弱)在位姿求解中被降权,
//   避免噪声点把位姿拉偏(原 Umeyama 所有配对等权,边界/遮挡处噪声有同等杠杆)。
IcpResult IcpRegister(const float* src, std::size_t src_count,
                      const float* dst, std::size_t dst_count,
                      const float* initial_pose7,
                      const IcpConfig& cfg = {},
                      const float* src_weights = nullptr);

} // namespace gomob::reconstruction
