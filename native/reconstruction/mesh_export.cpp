// PLY / OBJ 导出实施 — 详见 mesh_export.h 头文件设计说明。

#include "mesh_export.h"
#include "marching_cubes.h"

#include <cstdio>
#include <cstring>
#include <fstream>

namespace gomob::reconstruction {

long long WritePly(const std::string& path,
                   const std::vector<float>& vertices,
                   const std::vector<float>* normals,
                   const std::vector<uint8_t>* rgb,
                   const std::vector<uint32_t>* indices) {
    std::ofstream f(path, std::ios::binary | std::ios::out | std::ios::trunc);
    if (!f.is_open()) return -1;

    const std::size_t vcount = vertices.size() / 3;
    const std::size_t fcount = indices ? indices->size() / 3 : 0;
    const bool has_normal = normals && normals->size() == vertices.size();
    const bool has_rgb = rgb && rgb->size() == vcount * 3;

    f << "ply\n";
    f << "format binary_little_endian 1.0\n";
    f << "element vertex " << vcount << "\n";
    f << "property float x\nproperty float y\nproperty float z\n";
    if (has_normal) f << "property float nx\nproperty float ny\nproperty float nz\n";
    if (has_rgb) f << "property uchar red\nproperty uchar green\nproperty uchar blue\n";
    if (fcount > 0) {
        f << "element face " << fcount << "\n";
        f << "property list uchar uint vertex_indices\n";
    }
    f << "end_header\n";

    // 顶点
    for (std::size_t i = 0; i < vcount; ++i) {
        f.write(reinterpret_cast<const char*>(&vertices[i*3]), sizeof(float) * 3);
        if (has_normal) f.write(reinterpret_cast<const char*>(&(*normals)[i*3]), sizeof(float) * 3);
        if (has_rgb) f.write(reinterpret_cast<const char*>(&(*rgb)[i*3]), 3);
    }

    // 面：每行 1 byte = 3（顶点数）+ 3 × uint32 = 12 byte 索引
    if (fcount > 0) {
        for (std::size_t t = 0; t < fcount; ++t) {
            uint8_t n = 3;
            f.write(reinterpret_cast<const char*>(&n), 1);
            f.write(reinterpret_cast<const char*>(&(*indices)[t*3]), sizeof(uint32_t) * 3);
        }
    }

    long long bytes = static_cast<long long>(f.tellp());
    f.close();
    return f.fail() ? -1 : bytes;
}

long long WriteObj(const std::string& path, const Mesh& mesh) {
    std::ofstream f(path, std::ios::out | std::ios::trunc);
    if (!f.is_open()) return -1;

    f << "# gomob reconstruction\n";
    f << "# vertices=" << mesh.vertex_count() << " triangles=" << mesh.triangle_count() << "\n";

    char buf[128];
    // v
    for (std::size_t i = 0; i < mesh.vertex_count(); ++i) {
        std::snprintf(buf, sizeof(buf), "v %.4f %.4f %.4f\n",
                      mesh.vertices[i*3], mesh.vertices[i*3+1], mesh.vertices[i*3+2]);
        f << buf;
    }
    // vn
    if (mesh.normals.size() == mesh.vertices.size()) {
        for (std::size_t i = 0; i < mesh.vertex_count(); ++i) {
            std::snprintf(buf, sizeof(buf), "vn %.4f %.4f %.4f\n",
                          mesh.normals[i*3], mesh.normals[i*3+1], mesh.normals[i*3+2]);
            f << buf;
        }
    }
    // f：OBJ 索引从 1 开始，且 v//vn 配对
    bool with_normal = mesh.normals.size() == mesh.vertices.size();
    for (std::size_t t = 0; t < mesh.triangle_count(); ++t) {
        uint32_t a = mesh.indices[t*3] + 1;
        uint32_t b = mesh.indices[t*3+1] + 1;
        uint32_t c = mesh.indices[t*3+2] + 1;
        if (with_normal) {
            std::snprintf(buf, sizeof(buf), "f %u//%u %u//%u %u//%u\n", a, a, b, b, c, c);
        } else {
            std::snprintf(buf, sizeof(buf), "f %u %u %u\n", a, b, c);
        }
        f << buf;
    }

    long long bytes = static_cast<long long>(f.tellp());
    f.close();
    return f.fail() ? -1 : bytes;
}

} // namespace gomob::reconstruction
