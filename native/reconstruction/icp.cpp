// ICP point-to-point 实施 — 详见 icp.h 头文件设计说明。
//
// 算法主循环：
//   for iter in 0..max_iter:
//     1. 用当前 (R,t) 把 src 变换到世界系
//     2. 对每个 src' 在 dst 中找最近邻 (spatial hash)
//     3. 拒绝距离 > 阈值的配对（错配抗性）
//     4. Umeyama SVD 求增量刚体变换 (dR, dt)
//     5. 累积：R ← dR · R, t ← dR · t + dt
//     6. 算 mean_error；若与上次相比改善 < tol 则收敛
//
// 复杂度：O(iter · src_count · 27 · cell_avg_size)，cell_avg ≤ 几十时端侧 30fps 不阻塞。

#include "icp.h"
#include "spatial_hash.h"

#include <Eigen/Dense>
#include <Eigen/Geometry>

#include <cmath>
#include <cstdio>
#include <limits>
#include <vector>

#ifndef GOMOB_ICP_DEBUG
#define GOMOB_ICP_DEBUG 0
#endif

namespace gomob::reconstruction {

IcpResult IcpRegister(const float* src, std::size_t src_count,
                      const float* dst, std::size_t dst_count,
                      const float* initial_pose7,
                      const IcpConfig& cfg) {
    IcpResult result{};
    result.status = IcpResultStatus::DegenerateInput;
    result.pose7 = {initial_pose7[0], initial_pose7[1], initial_pose7[2],
                    initial_pose7[3], initial_pose7[4], initial_pose7[5], initial_pose7[6]};
    result.iterations = 0;
    result.pair_count_final = 0;
    result.mean_error_mm = std::numeric_limits<float>::infinity();

    if (src_count < 6 || dst_count < 6) return result;

    // 解初始位姿
    Eigen::Vector3f t(initial_pose7[0], initial_pose7[1], initial_pose7[2]);
    Eigen::Quaternionf q(initial_pose7[6], initial_pose7[3], initial_pose7[4], initial_pose7[5]);
    q.normalize();
    Eigen::Matrix3f R = q.toRotationMatrix();

    SpatialHash3D dst_hash(dst, dst_count, cfg.spatial_hash_cell_mm);
    if (dst_hash.indexed_count() < 6) return result;

    const float reject_sq = cfg.reject_pair_dist_mm * cfg.reject_pair_dist_mm;
    float prev_mean_err = std::numeric_limits<float>::infinity();

    for (int iter = 0; iter < cfg.max_iter; ++iter) {
        std::vector<Eigen::Vector3f> src_warp;
        std::vector<Eigen::Vector3f> dst_match;
        src_warp.reserve(src_count);
        dst_match.reserve(src_count);

        for (std::size_t i = 0; i < src_count; ++i) {
            const float* p = src + i * 3;
            // 跳过 depthToPointCloud 占位的 (0,0,0)
            if (p[0] == 0.f && p[1] == 0.f && p[2] == 0.f) continue;
            Eigen::Vector3f pw = R * Eigen::Vector3f(p[0], p[1], p[2]) + t;
            std::size_t nn_idx;
            float dsq;
            if (!dst_hash.NearestNeighbor(pw.x(), pw.y(), pw.z(), nn_idx, dsq)) continue;
            if (dsq > reject_sq) continue;
            src_warp.push_back(pw);
            const float* q = dst + nn_idx * 3;
            dst_match.emplace_back(q[0], q[1], q[2]);
        }

        result.iterations = iter + 1;
        result.pair_count_final = static_cast<int>(src_warp.size());
        if (src_warp.size() < 6) {
            // 配对太少，退化；保留上次累积位姿
            if (iter == 0) result.status = IcpResultStatus::DegenerateInput;
            else           result.status = IcpResultStatus::MaxIterReached;
            break;
        }

        // 装配 3×N 矩阵给 Umeyama
        Eigen::Matrix3Xf src_mat(3, src_warp.size());
        Eigen::Matrix3Xf dst_mat(3, dst_match.size());
        for (std::size_t i = 0; i < src_warp.size(); ++i) {
            src_mat.col(i) = src_warp[i];
            dst_mat.col(i) = dst_match[i];
        }
        // with_scaling=false → 纯刚体（不缩放）
        Eigen::Matrix4f T = Eigen::umeyama(src_mat, dst_mat, false);
        Eigen::Matrix3f dR = T.block<3, 3>(0, 0);
        Eigen::Vector3f dt = T.block<3, 1>(0, 3);

        // 累积：新位姿 (R', t') 满足 R'·p + t' = dR·(R·p + t) + dt = (dR·R)·p + (dR·t + dt)
        R = dR * R;
        t = dR * t + dt;

        // 算变换后均方根误差
        float err_sum = 0.f;
        for (std::size_t i = 0; i < src_warp.size(); ++i) {
            err_sum += (dR * src_warp[i] + dt - dst_match[i]).norm();
        }
        float mean_err = err_sum / static_cast<float>(src_warp.size());
        result.mean_error_mm = mean_err;

#if GOMOB_ICP_DEBUG
        std::printf("  [icp iter=%d] pairs=%zu mean_err=%.4fmm dt=(%.2f,%.2f,%.2f)\n",
                    iter, src_warp.size(), mean_err, dt.x(), dt.y(), dt.z());
#endif

        if (std::abs(prev_mean_err - mean_err) < cfg.convergence_tol_mm) {
            result.status = IcpResultStatus::Converged;
            break;
        }
        prev_mean_err = mean_err;

        if (iter + 1 == cfg.max_iter) {
            result.status = IcpResultStatus::MaxIterReached;
        }
    }

    // 输出 7 元素位姿
    Eigen::Quaternionf qo(R);
    qo.normalize();
    result.pose7 = {t.x(), t.y(), t.z(), qo.x(), qo.y(), qo.z(), qo.w()};
    return result;
}

} // namespace gomob::reconstruction
