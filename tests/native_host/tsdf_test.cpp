// TSDF host 单测 — 验证体素积分正确性
//
// 合成场景：相机在原点视 +z 方向；前方 z=200mm 处一无限大平面；depth 图全 200mm（在视野内的像素）
// 期待：z=205mm 体素 sdf ≈ -0.625（=(200-205)/8），weight=1
//       z=195mm 体素 sdf ≈ +0.625，weight=1
//       z=180mm 体素超出 truncation → weight=0 不更新

#include "reconstruction/tsdf.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace {

bool CheckClose(const char* tag, float got, float expected, float tol) {
    bool ok = std::abs(got - expected) < tol;
    std::printf("  %-40s got=%.4f expected=%.4f tol=%.4f -> %s\n",
                tag, got, expected, tol, ok ? "OK" : "FAIL");
    return ok;
}

int TestPlanarIntegration() {
    std::printf("[tsdf_planar_integration]\n");
    using namespace gomob::reconstruction;

    TsdfConfig cfg;
    cfg.voxel_size_mm = 10.0f;
    cfg.grid_extent_mm = 600.0f;
    cfg.truncation_dist_mm = 8.0f;
    cfg.weight_clamp = 100.0f;
    cfg.grid_origin_mm = {-300.0f, -300.0f, -300.0f};
    TsdfVolume vol(cfg);

    // 合成 480×480 depth 全 200mm，零像素跳过
    const int W = 480, H = 480;
    std::vector<uint16_t> depth(W * H, 200);
    double fx = 200.0, fy = 200.0, cx = 240.0, cy = 240.0;

    // 相机位姿：原点 + 单位旋转
    float pose[7] = {0, 0, 0, 0, 0, 0, 1};
    vol.Integrate(depth.data(), W, H, fx, fy, cx, cy, pose);

    // 体素索引：grid_origin=-300, voxel=10 → wx = -300 + (i+0.5)*10
    // wx=0 → i+0.5=30 → i=29 (中心 -295) 或 i=30 (中心 -295... 等等)
    // i=29: wx = -300 + 29.5*10 = -5mm
    // i=30: wx = +5mm
    // 中央列 i=29~30, j=29~30
    // k=49: wz = -300 + 49.5*10 = 195mm → sdf = 200-195 = +5mm → +5/8 = +0.625
    // k=50: wz = +205mm → sdf = -5/8 = -0.625
    // k=48: wz = +185mm → sdf = +15mm > trunc → 截断到 +1 (truncated)
    //       但代码: sdf=15 > 8 → sdf=trunc=8 → sdf_norm=1.0
    // k=51: wz = +215mm → sdf = -15 < -trunc → 跳过 (weight 不变)

    bool ok = true;
    {
        float s, w;
        vol.Get(30, 30, 50, s, w);
        ok &= CheckClose("k=50 (z=205mm) sdf",     s, -0.625f, 0.05f);
        ok &= CheckClose("k=50 (z=205mm) weight",  w,  1.0f,   0.01f);
    }
    {
        float s, w;
        vol.Get(30, 30, 49, s, w);
        ok &= CheckClose("k=49 (z=195mm) sdf",     s,  0.625f, 0.05f);
        ok &= CheckClose("k=49 (z=195mm) weight",  w,  1.0f,   0.01f);
    }
    {
        float s, w;
        vol.Get(30, 30, 48, s, w);
        // z=185mm，距表面 +15mm > trunc=8 → 截断到 +trunc → +1.0
        ok &= CheckClose("k=48 (z=185mm) sdf trunc", s, 1.0f, 0.01f);
        ok &= CheckClose("k=48 (z=185mm) weight",    w, 1.0f, 0.01f);
    }
    {
        float s, w;
        vol.Get(30, 30, 51, s, w);
        // z=215mm，距表面 -15mm < -trunc → 不更新（被遮挡假设）
        ok &= CheckClose("k=51 (z=215mm) weight=0",  w, 0.0f, 0.01f);
    }
    {
        // 视野外的体素：wx 太大 → u 出 [0,W)
        // i=0 → wx=-295 → u = 200 * (-295/195) + 240 ≈ -62 < 0 → 跳过
        float s, w;
        vol.Get(0, 30, 49, s, w);
        ok &= CheckClose("i=0 (out of view) weight=0", w, 0.0f, 0.01f);
    }
    return ok ? 0 : 1;
}

int TestMultiFrameWeightAccumulate() {
    std::printf("[tsdf_multiframe_weight]\n");
    using namespace gomob::reconstruction;
    TsdfConfig cfg;
    cfg.voxel_size_mm = 10.0f;
    cfg.grid_extent_mm = 600.0f;
    cfg.grid_origin_mm = {-300.0f, -300.0f, -300.0f};
    TsdfVolume vol(cfg);

    const int W = 480, H = 480;
    std::vector<uint16_t> depth(W * H, 200);
    float pose[7] = {0, 0, 0, 0, 0, 0, 1};

    for (int i = 0; i < 5; ++i) vol.Integrate(depth.data(), W, H, 200.0, 200.0, 240.0, 240.0, pose);

    bool ok = true;
    float s, w;
    vol.Get(30, 30, 50, s, w);
    ok &= CheckClose("frame_count",    static_cast<float>(vol.frame_count()), 5.0f, 0.01f);
    ok &= CheckClose("weight after 5", w, 5.0f,   0.01f);
    ok &= CheckClose("sdf after 5",    s, -0.625f, 0.05f); // 一致观测，sdf 不变
    return ok ? 0 : 1;
}

int TestStats() {
    std::printf("[tsdf_stats]\n");
    using namespace gomob::reconstruction;
    TsdfConfig cfg;
    cfg.voxel_size_mm = 10.0f;
    cfg.grid_extent_mm = 600.0f;
    cfg.grid_origin_mm = {-300.0f, -300.0f, -300.0f};
    TsdfVolume vol(cfg);

    auto s0 = vol.Stats();
    std::printf("  alloc=%d integrated=%d frames=%d (initial)\n",
                s0.allocated_voxels, s0.integrated_voxels, s0.integrated_frames);

    const int W = 480, H = 480;
    std::vector<uint16_t> depth(W * H, 200);
    float pose[7] = {0, 0, 0, 0, 0, 0, 1};
    vol.Integrate(depth.data(), W, H, 200.0, 200.0, 240.0, 240.0, pose);

    auto s1 = vol.Stats();
    std::printf("  alloc=%d integrated=%d frames=%d (after 1 frame)\n",
                s1.allocated_voxels, s1.integrated_voxels, s1.integrated_frames);

    bool ok = true;
    ok &= (s0.allocated_voxels == 60 * 60 * 60);
    ok &= (s0.integrated_voxels == 0);
    ok &= (s1.integrated_frames == 1);
    ok &= (s1.integrated_voxels > 0);
    std::printf("  -> %s\n", ok ? "OK" : "FAIL");
    return ok ? 0 : 1;
}

} // anonymous

int main() {
    int fails = 0;
    fails += TestPlanarIntegration();
    fails += TestMultiFrameWeightAccumulate();
    fails += TestStats();
    std::printf("\n[tsdf_test summary] failures=%d\n", fails);
    return fails;
}
