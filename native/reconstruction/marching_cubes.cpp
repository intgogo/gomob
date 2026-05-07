// Marching Tetrahedra 实施 — 详见 marching_cubes.h 头文件设计说明。
//
// 算法：
//   for 每个 cube (i,j,k → i+1,j+1,k+1):
//     skip if 任何顶点 weight < min_weight
//     拆 6 tetrahedra
//     for 每个 tet (4 顶点):
//       cube_index = 4 顶点 sdf 符号编码（0..15）
//       if 0 or 15: skip
//       else:
//         查 LUT 拿要画的边索引（每 3 个 = 一个三角形）
//         每条边线性插值零点位置
//         winding 修正：法向 dot sdf 梯度 > 0（朝外） — 错则交换 v1<->v2
//         追加到 mesh

#include "marching_cubes.h"
#include "tsdf.h"

#include <Eigen/Dense>

#include <algorithm>
#include <array>
#include <cmath>

namespace gomob::reconstruction {

namespace {

// cube 8 顶点的偏移
constexpr int kCornerOffsets[8][3] = {
    {0,0,0}, {1,0,0}, {1,1,0}, {0,1,0},
    {0,0,1}, {1,0,1}, {1,1,1}, {0,1,1},
};

// 6-tet 分解（共享 0–6 主对角，标准分解）
constexpr int kTetCorners[6][4] = {
    {0, 1, 2, 6},
    {0, 2, 3, 6},
    {0, 1, 5, 6},
    {0, 4, 5, 6},
    {0, 3, 7, 6},
    {0, 4, 7, 6},
};

// tet 6 条边（顶点对索引），编号 0..5
constexpr int kTetEdges[6][2] = {
    {0, 1}, {0, 2}, {0, 3}, {1, 2}, {1, 3}, {2, 3},
};

// MT LUT：每 case 输出 0/3/6 个边索引，结尾 -1
//   bit 编码：idx = (sign(v0)<<3) | (sign(v1)<<2) | (sign(v2)<<1) | sign(v3)，sign=1 表示 sdf<0（"内部"）
//   winding 后处理用 SDF 梯度修正
constexpr int kTetTable[16][7] = {
    {-1,-1,-1,-1,-1,-1,-1},  // 0
    { 2, 4, 5,-1,-1,-1,-1},  // 1: v3 isolated
    { 1, 3, 5,-1,-1,-1,-1},  // 2: v2 isolated
    { 1, 3, 4, 1, 4, 2,-1},  // 3: v2,v3 inside
    { 0, 3, 4,-1,-1,-1,-1},  // 4: v1 isolated
    { 0, 3, 5, 0, 5, 2,-1},  // 5: v1,v3 inside
    { 0, 1, 5, 0, 5, 4,-1},  // 6: v1,v2 inside
    { 0, 1, 2,-1,-1,-1,-1},  // 7: v0 outside
    { 0, 1, 2,-1,-1,-1,-1},  // 8: v0 inside
    { 0, 4, 1, 1, 4, 5,-1},  // 9: v0,v3 inside
    { 0, 3, 2, 2, 3, 5,-1},  // 10: v0,v2 inside
    { 0, 3, 4,-1,-1,-1,-1},  // 11: v1 outside
    { 1, 3, 2, 2, 3, 4,-1},  // 12: v0,v1 inside
    { 1, 3, 5,-1,-1,-1,-1},  // 13: v2 outside
    { 2, 4, 5,-1,-1,-1,-1},  // 14: v3 outside
    {-1,-1,-1,-1,-1,-1,-1},  // 15
};

inline float ZeroCrossing(float s0, float s1) {
    if (std::abs(s0 - s1) < 1e-6f) return 0.5f;
    float t = s0 / (s0 - s1);
    return std::max(0.0f, std::min(1.0f, t));
}

// 中心差分估 SDF 梯度（voxel 索引坐标）
Eigen::Vector3f SdfGrad(const TsdfVolume& vol, int i, int j, int k) {
    float sl, sr, wdummy;
    vol.Get(i - 1, j, k, sl, wdummy); vol.Get(i + 1, j, k, sr, wdummy);
    float gx = sr - sl;
    vol.Get(i, j - 1, k, sl, wdummy); vol.Get(i, j + 1, k, sr, wdummy);
    float gy = sr - sl;
    vol.Get(i, j, k - 1, sl, wdummy); vol.Get(i, j, k + 1, sr, wdummy);
    float gz = sr - sl;
    return Eigen::Vector3f(gx, gy, gz);
}

} // anonymous

Mesh ExtractMesh(const TsdfVolume& vol, const MeshConfig& cfg) {
    Mesh mesh;
    int N = vol.dim();
    if (N < 2) return mesh;

    for (int k = 0; k < N - 1; ++k)
    for (int j = 0; j < N - 1; ++j)
    for (int i = 0; i < N - 1; ++i) {
        // 8 顶点的 sdf / weight / 世界坐标
        float s[8]; float w[8];
        std::array<float, 3> pos[8];
        bool any_low_weight = false;
        for (int c = 0; c < 8; ++c) {
            int ci = i + kCornerOffsets[c][0];
            int cj = j + kCornerOffsets[c][1];
            int ck = k + kCornerOffsets[c][2];
            vol.Get(ci, cj, ck, s[c], w[c]);
            if (w[c] < cfg.min_weight) { any_low_weight = true; break; }
            pos[c] = vol.VoxelCenter(ci, cj, ck);
        }
        if (any_low_weight) continue;

        // SDF 梯度（用 cube 中心 i+0.5 的近似 → 取 (i,j,k) 的梯度）
        Eigen::Vector3f grad = SdfGrad(vol, i, j, k);

        for (int t = 0; t < 6; ++t) {
            float ts[4];
            std::array<float, 3> tposes[4];
            for (int v = 0; v < 4; ++v) {
                ts[v] = s[kTetCorners[t][v]];
                tposes[v] = pos[kTetCorners[t][v]];
            }
            int idx = ((ts[0] < 0.f) << 3) | ((ts[1] < 0.f) << 2) |
                      ((ts[2] < 0.f) << 1) |  (ts[3] < 0.f);
            const int* row = kTetTable[idx];
            if (row[0] < 0) continue;

            // 计算每条相关边的零点位置
            std::array<float, 3> ep[6];
            bool has[6] = {false, false, false, false, false, false};
            for (int e = 0; e < 6; ++e) {
                int va = kTetEdges[e][0], vb = kTetEdges[e][1];
                if ((ts[va] < 0.f) == (ts[vb] < 0.f)) continue;
                has[e] = true;
                float tt = ZeroCrossing(ts[va], ts[vb]);
                for (int d = 0; d < 3; ++d) {
                    ep[e][d] = tposes[va][d] + tt * (tposes[vb][d] - tposes[va][d]);
                }
            }

            // 输出三角形（每 3 个边索引一组）
            for (int ti = 0; ti < 7 && row[ti] >= 0; ti += 3) {
                int e0 = row[ti], e1 = row[ti + 1], e2 = row[ti + 2];
                if (!has[e0] || !has[e1] || !has[e2]) continue;  // 防御：LUT 安全
                Eigen::Vector3f p0(ep[e0][0], ep[e0][1], ep[e0][2]);
                Eigen::Vector3f p1(ep[e1][0], ep[e1][1], ep[e1][2]);
                Eigen::Vector3f p2(ep[e2][0], ep[e2][1], ep[e2][2]);

                // winding 修正：法向 dot sdf 梯度 > 0 → 朝外（sdf 增加方向 = 自由空间方向）
                Eigen::Vector3f n = (p1 - p0).cross(p2 - p0);
                if (n.dot(grad) < 0.f) std::swap(p1, p2);
                n = (p1 - p0).cross(p2 - p0);
                float nlen = n.norm();
                if (nlen < 1e-10f) continue;  // 退化三角形（共线）
                n /= nlen;

                uint32_t i0 = static_cast<uint32_t>(mesh.vertices.size() / 3);
                auto push_vert = [&](const Eigen::Vector3f& p) {
                    mesh.vertices.push_back(p.x());
                    mesh.vertices.push_back(p.y());
                    mesh.vertices.push_back(p.z());
                    mesh.normals.push_back(n.x());
                    mesh.normals.push_back(n.y());
                    mesh.normals.push_back(n.z());
                };
                push_vert(p0); push_vert(p1); push_vert(p2);
                mesh.indices.push_back(i0);
                mesh.indices.push_back(i0 + 1);
                mesh.indices.push_back(i0 + 2);
            }
        }
    }
    return mesh;
}

} // namespace gomob::reconstruction
