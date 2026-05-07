// Marching Cubes (Tetrahedra) host 单测 — 验证从合成 SDF 场提 mesh
//
// 测试 1: 球体 SDF（sdf = ||P|| - R），提 mesh 后顶点距球心应都 ≈ R
// 测试 2: 平面 SDF（sdf = z），提 mesh 后所有顶点应在 z=0 上
// 测试 3: 空场（全 weight=0）→ 0 三角形（不输出虚假面）

#include "reconstruction/marching_cubes.h"
#include "reconstruction/tsdf.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace {

bool CheckRange(const char* tag, float got, float lo, float hi) {
    bool ok = got >= lo && got <= hi;
    std::printf("  %-40s got=%.4f range=[%.4f,%.4f] -> %s\n",
                tag, got, lo, hi, ok ? "OK" : "FAIL");
    return ok;
}

// 用解析 SDF 直接填 TsdfVolume（绕过 Integrate）
template <typename SdfFn>
void FillSdf(gomob::reconstruction::TsdfVolume& vol, SdfFn fn, float trunc_mm) {
    int N = vol.dim();
    for (int k = 0; k < N; ++k)
    for (int j = 0; j < N; ++j)
    for (int i = 0; i < N; ++i) {
        auto c = vol.VoxelCenter(i, j, k);
        float s = fn(c[0], c[1], c[2]);
        // 归一化到 truncation
        if (s > trunc_mm) s = trunc_mm;
        if (s < -trunc_mm) s = -trunc_mm;
        vol.Set(i, j, k, s / trunc_mm, /*weight=*/1.0f);
    }
}

int TestSphereExtraction() {
    std::printf("[mc_sphere_extraction]\n");
    using namespace gomob::reconstruction;
    TsdfConfig cfg;
    cfg.voxel_size_mm = 5.0f;
    cfg.grid_extent_mm = 200.0f;
    cfg.truncation_dist_mm = 20.0f;
    cfg.grid_origin_mm = {-100.0f, -100.0f, -100.0f};
    TsdfVolume vol(cfg);

    const float R = 60.0f;
    FillSdf(vol, [R](float x, float y, float z) {
        return std::sqrt(x*x + y*y + z*z) - R;
    }, cfg.truncation_dist_mm);

    Mesh mesh = ExtractMesh(vol);
    std::printf("  vertices=%zu triangles=%zu\n", mesh.vertex_count(), mesh.triangle_count());

    if (mesh.triangle_count() == 0) {
        std::printf("  no triangles emitted -> FAIL\n");
        return 1;
    }

    // 顶点距球心的平均距离应 ≈ R
    float sum = 0; float min_d = 1e9f, max_d = 0;
    for (std::size_t v = 0; v < mesh.vertex_count(); ++v) {
        float x = mesh.vertices[v*3];
        float y = mesh.vertices[v*3+1];
        float z = mesh.vertices[v*3+2];
        float d = std::sqrt(x*x + y*y + z*z);
        sum += d;
        if (d < min_d) min_d = d;
        if (d > max_d) max_d = d;
    }
    float mean = sum / mesh.vertex_count();
    std::printf("  vertex distance: min=%.2f mean=%.2f max=%.2f (expected R=%.2f)\n",
                min_d, mean, max_d, R);

    bool ok = true;
    ok &= CheckRange("mean_dist_to_origin",   mean, R - 2.0f, R + 2.0f);
    ok &= CheckRange("min_dist_to_origin",    min_d, R - 4.0f, R + 1.0f);
    ok &= CheckRange("max_dist_to_origin",    max_d, R - 1.0f, R + 4.0f);
    // 球面三角形数量应在合理范围（顶点不去重，每三角 3 顶点）
    ok &= CheckRange("triangle_count",        static_cast<float>(mesh.triangle_count()),
                     500.0f, 50000.0f);

    // 法向方向：每顶点的法向 dot 顶点位置（单位化后）应近 1（外法向）
    float dot_sum = 0;
    for (std::size_t v = 0; v < mesh.vertex_count(); ++v) {
        float x = mesh.vertices[v*3], y = mesh.vertices[v*3+1], z = mesh.vertices[v*3+2];
        float nx = mesh.normals[v*3], ny = mesh.normals[v*3+1], nz = mesh.normals[v*3+2];
        float pn = std::sqrt(x*x + y*y + z*z);
        if (pn < 1e-6f) continue;
        dot_sum += (x*nx + y*ny + z*nz) / pn;
    }
    float dot_avg = dot_sum / mesh.vertex_count();
    ok &= CheckRange("normal·radial_avg(>0.9)", dot_avg, 0.9f, 1.01f);
    return ok ? 0 : 1;
}

int TestPlaneExtraction() {
    std::printf("[mc_plane_extraction]\n");
    using namespace gomob::reconstruction;
    TsdfConfig cfg;
    cfg.voxel_size_mm = 10.0f;
    cfg.grid_extent_mm = 200.0f;
    cfg.truncation_dist_mm = 15.0f;
    cfg.grid_origin_mm = {-100.0f, -100.0f, -100.0f};
    TsdfVolume vol(cfg);

    // 平面 z=0：sdf = z
    FillSdf(vol, [](float, float, float z) { return z; }, cfg.truncation_dist_mm);

    Mesh mesh = ExtractMesh(vol);
    std::printf("  vertices=%zu triangles=%zu\n", mesh.vertex_count(), mesh.triangle_count());

    bool ok = mesh.triangle_count() > 0;
    if (!ok) { std::printf("  no triangles -> FAIL\n"); return 1; }

    // 所有顶点 |z| 应 < voxel_size
    float max_abs_z = 0;
    for (std::size_t v = 0; v < mesh.vertex_count(); ++v) {
        float z = mesh.vertices[v*3+2];
        if (std::abs(z) > max_abs_z) max_abs_z = std::abs(z);
    }
    ok &= CheckRange("max|z|", max_abs_z, 0.0f, 1.0f); // 平面 sdf = z 是精确的，零点应在 z=0
    return ok ? 0 : 1;
}

int TestEmptyVolume() {
    std::printf("[mc_empty_volume]\n");
    using namespace gomob::reconstruction;
    TsdfConfig cfg;
    cfg.voxel_size_mm = 10.0f;
    cfg.grid_extent_mm = 100.0f;
    TsdfVolume vol(cfg);
    // 不填任何东西 → 全 weight=0
    Mesh mesh = ExtractMesh(vol);
    std::printf("  triangles=%zu\n", mesh.triangle_count());
    bool ok = (mesh.triangle_count() == 0);
    std::printf("  -> %s\n", ok ? "OK" : "FAIL");
    return ok ? 0 : 1;
}

} // anonymous

int main() {
    int fails = 0;
    fails += TestSphereExtraction();
    fails += TestPlaneExtraction();
    fails += TestEmptyVolume();
    std::printf("\n[mc_test summary] failures=%d\n", fails);
    return fails;
}
