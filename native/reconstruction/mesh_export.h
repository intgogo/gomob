// Mesh / 点云导出 — PLY 二进制 + OBJ ASCII
//
// 格式选择：
//   - **PLY** 二进制 little-endian：点云首选；带顶点 (x,y,z) + 可选法向 + 可选 RGB
//     · 优点：紧凑（float32 vs OBJ 文本）、Filament / MeshLab / Blender 通吃
//     · 这里写 binary_little_endian 1.0
//   - **OBJ** ASCII：mesh 首选（通用、可读 + 可被 SceneKit / Filament 加载）
//     · 写 v / vn / f 三类行；无材质/纹理（M3.4 阶段加 .mtl + texture atlas）

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace gomob::reconstruction {

struct Mesh; // forward (marching_cubes.h)

// 写 PLY 二进制（小端）。返回写入字节数；失败返 -1。
// 仅顶点（点云）：indices 留空即可。
long long WritePly(const std::string& path,
                   const std::vector<float>& vertices,
                   const std::vector<float>* normals = nullptr,
                   const std::vector<uint8_t>* rgb = nullptr,
                   const std::vector<uint32_t>* indices = nullptr);

// 写 OBJ ASCII（v / vn / f）。返回写入字节数；失败返 -1。
long long WriteObj(const std::string& path, const Mesh& mesh);

} // namespace gomob::reconstruction
