// Marching Tetrahedra — TSDF voxel grid → 三角网格
//
// 为什么不是经典 Marching Cubes？经典 MC 需要 256 项 edge/tri LUT（~4500 整数手抄出错率高）；
// MT 把 cube 拆 6 个四面体，每个 tet 16 case，LUT 极小（< 50 行），数学等价无歧义。
// 端侧物体级重建对极少数歧义 case 不敏感；后续如需 watertight 严格性再升级到 MC 33 (Lewiner 2003)。
//
// 业务定位（详见 docs/architecture/04-reconstruction-pipeline.md §3.3）：
//   - 输入：TSDF voxel grid（已通过 Integrate 多帧累积）
//   - 输出：Mesh（顶点 / 法向 / 索引），可直接 PLY/OBJ 导出
//   - 法向方向：朝物体外（用 SDF 梯度方向校正 winding）

#pragma once

#include <cstdint>
#include <vector>

namespace gomob::reconstruction {

class TsdfVolume; // forward

struct Mesh {
    std::vector<float> vertices;    // [x0,y0,z0, ...] mm
    std::vector<float> normals;     // [nx0,ny0,nz0, ...] 单位向量
    std::vector<uint32_t> indices;  // 每 3 个 = 一个三角形（CCW，外法向）

    std::size_t vertex_count() const { return vertices.size() / 3; }
    std::size_t triangle_count() const { return indices.size() / 3; }
};

struct MeshConfig {
    float min_weight = 1.0f;        // weight < min_weight 的体素不参与（避免空洞处虚假面）
    bool deduplicate_vertices = false; // 简单实现下每个三角形 3 顶点不复用；后续可加哈希去重
};

// 从 TSDF 提取 mesh
Mesh ExtractMesh(const TsdfVolume& vol, const MeshConfig& cfg = {});

} // namespace gomob::reconstruction
