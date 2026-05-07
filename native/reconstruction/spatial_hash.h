// 3D 空间哈希格 — ICP 最近邻查询用
//
// 为什么不是 KdTree：cell-based 哈希在 ICP 配准这类"小位移、密集点云"场景下查询常数远小于 KdTree
// （KdTree 树深度 log N，哈希查 27 个桶 O(平均桶长)），且实现 ≤ 100 行不依赖额外库。
//
// 设计：
//   - 把点云按 cell_mm 装桶；NearestNeighbor 查询邻接 3×3×3 = 27 个 cell
//   - cell 选 ICP 拒绝距离的 1/2 — 拒绝距离 100mm 时 cell=50mm，27 个 cell 必定覆盖最近点
//   - 哈希键用 21bit-per-axis pack 进 uint64_t；±1M cell 对应 ±50km，远超扫描场景

#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <unordered_map>
#include <vector>

namespace gomob::reconstruction {

class SpatialHash3D {
public:
    SpatialHash3D(const float* points, std::size_t count, float cell_mm)
            : cell_(cell_mm > 1e-6f ? cell_mm : 1.0f),
              inv_cell_(1.0f / cell_),
              points_(points),
              count_(count) {
        buckets_.reserve(count_ * 2);
        for (std::size_t i = 0; i < count_; ++i) {
            const float* p = points_ + i * 3;
            // 跳过深度=0 占位点（depthToPointCloud 把无效像素填了 (0,0,0)）
            if (p[0] == 0.f && p[1] == 0.f && p[2] == 0.f) continue;
            buckets_[CellKey(p[0], p[1], p[2])].push_back(i);
        }
    }

    // 找到最近邻索引 + 距离平方；返回 false 表示无邻接（点云空 / 查询点远离整个云）
    bool NearestNeighbor(float x, float y, float z,
                         std::size_t& out_idx, float& out_dist_sq) const {
        int cx = static_cast<int>(std::floor(x * inv_cell_));
        int cy = static_cast<int>(std::floor(y * inv_cell_));
        int cz = static_cast<int>(std::floor(z * inv_cell_));
        out_dist_sq = std::numeric_limits<float>::infinity();
        bool found = false;
        for (int dz = -1; dz <= 1; ++dz)
        for (int dy = -1; dy <= 1; ++dy)
        for (int dx = -1; dx <= 1; ++dx) {
            auto it = buckets_.find(EncodeKey(cx + dx, cy + dy, cz + dz));
            if (it == buckets_.end()) continue;
            for (std::size_t idx : it->second) {
                const float* p = points_ + idx * 3;
                float ex = p[0] - x, ey = p[1] - y, ez = p[2] - z;
                float d = ex * ex + ey * ey + ez * ez;
                if (d < out_dist_sq) {
                    out_dist_sq = d;
                    out_idx = idx;
                    found = true;
                }
            }
        }
        return found;
    }

    std::size_t indexed_count() const {
        std::size_t n = 0;
        for (auto const& kv : buckets_) n += kv.second.size();
        return n;
    }

private:
    static uint64_t EncodeKey(int cx, int cy, int cz) {
        // 21bit per axis (signed, biased)；总 63bit 装 uint64
        constexpr int kBias = 1 << 20;
        constexpr uint64_t kMask = (1ULL << 21) - 1;
        return (static_cast<uint64_t>(cx + kBias) & kMask) |
               ((static_cast<uint64_t>(cy + kBias) & kMask) << 21) |
               ((static_cast<uint64_t>(cz + kBias) & kMask) << 42);
    }
    uint64_t CellKey(float x, float y, float z) const {
        int cx = static_cast<int>(std::floor(x * inv_cell_));
        int cy = static_cast<int>(std::floor(y * inv_cell_));
        int cz = static_cast<int>(std::floor(z * inv_cell_));
        return EncodeKey(cx, cy, cz);
    }

    float cell_;
    float inv_cell_;
    const float* points_;
    std::size_t count_;
    std::unordered_map<uint64_t, std::vector<std::size_t>> buckets_;
};

} // namespace gomob::reconstruction
