#include "measurement/preprocess.h"

#include <cmath>
#include <unordered_map>
#include <vector>

namespace gomob::measure {
namespace {

// 体素整数坐标。
inline int ifloor(float v, float leaf) { return static_cast<int>(std::floor(v / leaf)); }

// (i,j,k) → int64 键（各 21 bit，范围 ±2^20 体素，leaf=10mm 即 ±10km，远超场景）。
inline std::int64_t vkey(int i, int j, int k) {
    const std::int64_t M = 0x1FFFFF;
    return ((static_cast<std::int64_t>(i) & M) << 42) |
           ((static_cast<std::int64_t>(j) & M) << 21) |
           (static_cast<std::int64_t>(k) & M);
}

}  // namespace

Cloud voxelDownsample(const Cloud& in, float leaf) {
    if (leaf <= 0.f || in.empty()) return in;
    struct Accum { Eigen::Vector3f sum{Eigen::Vector3f::Zero()}; int n{0}; };
    std::unordered_map<std::int64_t, Accum> grid;
    grid.reserve(in.size());
    for (const auto& p : in) {
        Accum& a = grid[vkey(ifloor(p.x(), leaf), ifloor(p.y(), leaf), ifloor(p.z(), leaf))];
        a.sum += p;
        ++a.n;
    }
    Cloud out;
    out.reserve(grid.size());
    for (const auto& kv : grid) out.push_back(kv.second.sum / static_cast<float>(kv.second.n));
    return out;
}

Cloud radiusOutlierRemoval(const Cloud& in, float radius, int min_neighbors) {
    if (radius <= 0.f || min_neighbors <= 0 || in.empty()) return in;
    // 网格桶（cell = radius）：键 → 点索引列表。
    std::unordered_map<std::int64_t, std::vector<int>> grid;
    grid.reserve(in.size());
    for (int i = 0; i < static_cast<int>(in.size()); ++i) {
        const auto& p = in[i];
        grid[vkey(ifloor(p.x(), radius), ifloor(p.y(), radius), ifloor(p.z(), radius))].push_back(i);
    }
    const float r2 = radius * radius;
    Cloud out;
    out.reserve(in.size());
    for (int i = 0; i < static_cast<int>(in.size()); ++i) {
        const auto& p = in[i];
        const int ci = ifloor(p.x(), radius), cj = ifloor(p.y(), radius), ck = ifloor(p.z(), radius);
        int cnt = 0;
        for (int di = -1; di <= 1 && cnt < min_neighbors; ++di)
            for (int dj = -1; dj <= 1 && cnt < min_neighbors; ++dj)
                for (int dk = -1; dk <= 1 && cnt < min_neighbors; ++dk) {
                    auto it = grid.find(vkey(ci + di, cj + dj, ck + dk));
                    if (it == grid.end()) continue;
                    for (int j : it->second) {
                        if (j == i) continue;
                        if ((in[j] - p).squaredNorm() <= r2 && ++cnt >= min_neighbors) break;
                    }
                }
        if (cnt >= min_neighbors) out.push_back(p);
    }
    return out;
}

Cloud largestEuclideanCluster(const Cloud& in, float leaf) {
    if (leaf <= 0.f || in.empty()) return in;
    // 唯一体素 + 每体素点数。
    std::unordered_map<std::int64_t, int> vox_id;  // 键 → 唯一体素下标
    vox_id.reserve(in.size());
    std::vector<std::int64_t> keys;                // 下标 → 键
    std::vector<int> vox_pts;                      // 下标 → 点数
    std::vector<std::int64_t> pt_key(in.size());   // 点 → 体素键
    for (int i = 0; i < static_cast<int>(in.size()); ++i) {
        const auto& p = in[i];
        std::int64_t k = vkey(ifloor(p.x(), leaf), ifloor(p.y(), leaf), ifloor(p.z(), leaf));
        pt_key[i] = k;
        auto it = vox_id.find(k);
        if (it == vox_id.end()) {
            vox_id.emplace(k, static_cast<int>(keys.size()));
            keys.push_back(k);
            vox_pts.push_back(1);
        } else {
            ++vox_pts[it->second];
        }
    }
    // 体素整数坐标（从键还原，带 21-bit 符号扩展）。
    auto decode = [](std::int64_t k, int& i, int& j, int& kk) {
        auto sx = [](std::int64_t v) { v &= 0x1FFFFF; return static_cast<int>(v & 0x100000 ? v | ~0x1FFFFFLL : v); };
        kk = sx(k);
        j = sx(k >> 21);
        i = sx(k >> 42);
    };
    // 26-连通 BFS，取点数最多的连通体。
    const int nv = static_cast<int>(keys.size());
    std::vector<char> seen(nv, 0);
    std::vector<int> best, stack;
    long best_pts = -1;
    for (int s = 0; s < nv; ++s) {
        if (seen[s]) continue;
        stack.assign(1, s);
        seen[s] = 1;
        std::vector<int> comp;
        long pts = 0;
        while (!stack.empty()) {
            int cur = stack.back();
            stack.pop_back();
            comp.push_back(cur);
            pts += vox_pts[cur];
            int ci, cj, ck;
            decode(keys[cur], ci, cj, ck);
            for (int di = -1; di <= 1; ++di)
                for (int dj = -1; dj <= 1; ++dj)
                    for (int dk = -1; dk <= 1; ++dk) {
                        if (!di && !dj && !dk) continue;
                        auto it = vox_id.find(vkey(ci + di, cj + dj, ck + dk));
                        if (it != vox_id.end() && !seen[it->second]) {
                            seen[it->second] = 1;
                            stack.push_back(it->second);
                        }
                    }
        }
        if (pts > best_pts) { best_pts = pts; best.swap(comp); }
    }
    // 主连通体的体素键集合。
    std::unordered_map<std::int64_t, char> keep;
    keep.reserve(best.size());
    for (int v : best) keep.emplace(keys[v], 1);
    Cloud out;
    out.reserve(static_cast<std::size_t>(best_pts));
    for (int i = 0; i < static_cast<int>(in.size()); ++i)
        if (keep.count(pt_key[i])) out.push_back(in[i]);
    return out;
}

}  // namespace gomob::measure
