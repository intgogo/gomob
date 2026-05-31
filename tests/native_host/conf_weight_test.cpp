// conf_weight_test — 置信加权 TSDF + ICP 的公式正确性单测(host)
//
// 验证 M1.6.x 落地的 per-pixel 置信加权:
//   T1 conf=255 全帧 == 无 conf(均权,向后兼容)
//   T2 低 conf 帧软加权 = Curless&Levoy 加权平均(w1=w0+conf/255, sdf 按权混合)
//   T3 conf=0 全帧 → 不贡献(无效/飞点剔除)
//   C1 ICP 均权(weights=1) == 无权(向后兼容)
//   C2 ICP 加权:低置信外点被降权 → 位姿由高置信内点主导(防噪声拉偏)
//
// 几何与 tsdf_test 一致:相机原点视 +z,voxel 10mm,体素 (30,30,50)=世界 z=205mm。

#include "reconstruction/tsdf.h"
#include "reconstruction/icp.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace {

bool CheckClose(const char* tag, float got, float expected, float tol) {
    bool ok = std::abs(got - expected) < tol;
    std::printf("  %-44s got=%.4f expected=%.4f tol=%.4f -> %s\n",
                tag, got, expected, tol, ok ? "OK" : "FAIL");
    return ok;
}

gomob::reconstruction::TsdfConfig PlanarCfg() {
    gomob::reconstruction::TsdfConfig cfg;
    cfg.voxel_size_mm = 10.0f;
    cfg.grid_extent_mm = 600.0f;
    cfg.truncation_dist_mm = 8.0f;
    cfg.weight_clamp = 100.0f;
    cfg.grid_origin_mm = {-300.0f, -300.0f, -300.0f};
    return cfg;
}

// T1: conf 全 255 与不传 conf 结果一致
int TestConfFullEqualsUniform() {
    std::printf("[conf_tsdf_full255_equals_uniform]\n");
    using namespace gomob::reconstruction;
    const int W = 480, H = 480;
    std::vector<uint16_t> depth(W * H, 200);
    std::vector<uint8_t> conf(W * H, 255);
    float pose[7] = {0, 0, 0, 0, 0, 0, 1};

    TsdfVolume a(PlanarCfg()), b(PlanarCfg());
    a.Integrate(depth.data(), W, H, 200, 200, 240, 240, pose);              // 无 conf
    b.Integrate(depth.data(), W, H, 200, 200, 240, 240, pose, conf.data()); // conf=255

    float sa, wa, sb, wb;
    a.Get(30, 30, 50, sa, wa);
    b.Get(30, 30, 50, sb, wb);
    bool ok = true;
    ok &= CheckClose("conf255 sdf == uniform sdf",    sb, sa, 1e-4f);
    ok &= CheckClose("conf255 weight == uniform weight", wb, wa, 1e-4f);
    return ok ? 0 : 1;
}

// T2: 高 conf 帧 + 低 conf 帧 → 加权平均(权重与 sdf 都按 conf/255 混合)
int TestConfWeightedBlend() {
    std::printf("[conf_tsdf_weighted_blend]\n");
    using namespace gomob::reconstruction;
    const int W = 480, H = 480;
    float pose[7] = {0, 0, 0, 0, 0, 0, 1};

    // 帧 A: depth=200, conf=255 → 体素 z=205: sdf=(200-205)/8=-0.625, w=1.0
    std::vector<uint16_t> dA(W * H, 200);
    std::vector<uint8_t> cA(W * H, 255);
    // 帧 B: depth=210, conf=64 → sdf_B=(210-205)/8=+0.625, cw=64/255=0.2510
    std::vector<uint16_t> dB(W * H, 210);
    std::vector<uint8_t> cB(W * H, 64);

    // 加权:A 后 B(conf64)
    TsdfVolume vw(PlanarCfg());
    vw.Integrate(dA.data(), W, H, 200, 200, 240, 240, pose, cA.data());
    vw.Integrate(dB.data(), W, H, 200, 200, 240, 240, pose, cB.data());
    // 对照:A 后 B(均权,conf 都 255)
    TsdfVolume vu(PlanarCfg());
    std::vector<uint8_t> c255(W * H, 255);
    vu.Integrate(dA.data(), W, H, 200, 200, 240, 240, pose, c255.data());
    vu.Integrate(dB.data(), W, H, 200, 200, 240, 240, pose, c255.data());

    const float cw = 64.0f / 255.0f;                 // 0.2510
    const float w_exp = 1.0f + cw;                   // 1.2510
    const float s_exp = (-0.625f * 1.0f + 0.625f * cw) / w_exp;  // ≈ -0.3742

    float sw, ww, su, wu;
    vw.Get(30, 30, 50, sw, ww);
    vu.Get(30, 30, 50, su, wu);
    bool ok = true;
    ok &= CheckClose("weighted weight = 1+conf/255",  ww, w_exp, 1e-3f);
    ok &= CheckClose("weighted sdf = blend toward A",  sw, s_exp, 5e-3f);
    ok &= CheckClose("uniform weight = 2.0",           wu, 2.0f,  1e-3f);
    ok &= CheckClose("uniform sdf = 0 (50/50)",        su, 0.0f,  5e-3f);
    // 加权 sdf 应明显更靠近高置信 A(-0.625)而非中点 0
    std::printf("  -> 加权 sdf=%.4f(靠 A 的 -0.625) vs 均权 sdf=%.4f(中点) %s\n",
                sw, su, (sw < -0.2f && std::abs(su) < 0.05f) ? "OK" : "FAIL");
    ok &= (sw < -0.2f && std::abs(su) < 0.05f);
    return ok ? 0 : 1;
}

// T3: conf 全 0 → 无体素更新(无效/飞点剔除)
int TestConfZeroSkips() {
    std::printf("[conf_tsdf_zero_skips]\n");
    using namespace gomob::reconstruction;
    const int W = 480, H = 480;
    std::vector<uint16_t> depth(W * H, 200);
    std::vector<uint8_t> conf(W * H, 0);
    float pose[7] = {0, 0, 0, 0, 0, 0, 1};
    TsdfVolume v(PlanarCfg());
    int updated = v.Integrate(depth.data(), W, H, 200, 200, 240, 240, pose, conf.data());
    bool ok = true;
    ok &= CheckClose("conf=0 updated voxels = 0", static_cast<float>(updated), 0.0f, 0.5f);
    ok &= CheckClose("conf=0 integrated voxels = 0",
                     static_cast<float>(v.Stats().integrated_voxels), 0.0f, 0.5f);
    return ok ? 0 : 1;
}

// 造一个 5×5×5 点簇(中心 c,间距 step)
void MakeCluster(std::vector<float>& out, float cx, float cy, float cz, float step) {
    for (int i = -2; i <= 2; ++i)
        for (int j = -2; j <= 2; ++j)
            for (int k = -2; k <= 2; ++k) {
                out.push_back(cx + i * step);
                out.push_back(cy + j * step);
                out.push_back(cz + k * step);
            }
}

// C1: ICP 均权(weights 全 1)与不传 weights 结果一致
int TestIcpUniformWeightsEqual() {
    std::printf("[conf_icp_uniform_equals_noweight]\n");
    using namespace gomob::reconstruction;
    std::vector<float> src; MakeCluster(src, 0, 0, 500, 50);
    const float tt[3] = {10, -5, 3};
    std::vector<float> dst;
    for (std::size_t i = 0; i < src.size(); i += 3) {
        dst.push_back(src[i] + tt[0]);
        dst.push_back(src[i + 1] + tt[1]);
        dst.push_back(src[i + 2] + tt[2]);
    }
    float init[7] = {0, 0, 0, 0, 0, 0, 1};
    std::vector<float> w(src.size() / 3, 1.0f);
    auto r0 = IcpRegister(src.data(), src.size() / 3, dst.data(), dst.size() / 3, init);
    auto r1 = IcpRegister(src.data(), src.size() / 3, dst.data(), dst.size() / 3, init,
                          IcpConfig{}, w.data());
    bool ok = true;
    ok &= CheckClose("uniform-w tx == noweight tx", r1.pose7[0], r0.pose7[0], 0.1f);
    ok &= CheckClose("uniform-w ty == noweight ty", r1.pose7[1], r0.pose7[1], 0.1f);
    ok &= CheckClose("uniform-w tz == noweight tz", r1.pose7[2], r0.pose7[2], 0.1f);
    ok &= CheckClose("recovers true tx",            r1.pose7[0], tt[0], 0.5f);
    return ok ? 0 : 1;
}

// C2: 内点(高权,平移 t_true)+ 外点(低权,平移 t_wrong)→ 加权位姿由内点主导
int TestIcpWeightedRejectsOutliers() {
    std::printf("[conf_icp_weighted_rejects_outliers]\n");
    using namespace gomob::reconstruction;
    // 内点簇(原点附近)平移 (12,0,0);外点簇(x=300 远处)平移 (0,12,0)
    std::vector<float> inl; MakeCluster(inl, 0, 0, 500, 50);
    std::vector<float> out; MakeCluster(out, 300, 0, 500, 50);
    // 用真实 conf→权重映射:内点 conf=255(w=1.0),外点 conf=40(设备 min_conf 下界,w=0.157)
    std::vector<float> src, dst, w;
    for (std::size_t i = 0; i < inl.size(); i += 3) {
        src.push_back(inl[i]); src.push_back(inl[i+1]); src.push_back(inl[i+2]);
        dst.push_back(inl[i] + 12); dst.push_back(inl[i+1]); dst.push_back(inl[i+2]);
        w.push_back(255.0f / 255.0f);
    }
    for (std::size_t i = 0; i < out.size(); i += 3) {
        src.push_back(out[i]); src.push_back(out[i+1]); src.push_back(out[i+2]);
        dst.push_back(out[i]); dst.push_back(out[i+1] + 12); dst.push_back(out[i+2]);
        w.push_back(40.0f / 255.0f);   // 真实最弱置信,非夸张的 0.02
    }
    float init[7] = {0, 0, 0, 0, 0, 0, 1};
    auto ru = IcpRegister(src.data(), src.size()/3, dst.data(), dst.size()/3, init);          // 均权
    auto rw = IcpRegister(src.data(), src.size()/3, dst.data(), dst.size()/3, init,
                          IcpConfig{}, w.data());                                              // 加权

    std::printf("  均权位姿 t=(%.2f,%.2f,%.2f) | 加权位姿 t=(%.2f,%.2f,%.2f)\n",
                ru.pose7[0], ru.pose7[1], ru.pose7[2], rw.pose7[0], rw.pose7[1], rw.pose7[2]);
    bool ok = true;
    // 加权:由内点主导 → 恢复真值 t≈(12,0,0)
    ok &= CheckClose("weighted tx -> true 12",  rw.pose7[0], 12.0f, 2.0f);
    ok &= CheckClose("weighted ty -> true 0",   rw.pose7[1], 0.0f,  2.0f);
    // 判别:外点确实把均权位姿从真值 tx=12 拉偏(均权 tx 比加权 tx 明显小)
    bool pulled = (rw.pose7[0] - ru.pose7[0]) > 3.0f && ru.pose7[0] < 9.0f;
    std::printf("  -> 外点拉偏均权(加权tx %.2f - 均权tx %.2f > 3 且均权<9): %s\n",
                rw.pose7[0], ru.pose7[0], pulled ? "OK" : "FAIL");
    ok &= pulled;
    return ok ? 0 : 1;
}

}  // namespace

int main() {
    int fails = 0;
    fails += TestConfFullEqualsUniform();
    fails += TestConfWeightedBlend();
    fails += TestConfZeroSkips();
    fails += TestIcpUniformWeightsEqual();
    fails += TestIcpWeightedRejectsOutliers();
    std::printf("\n[conf_weight_test summary] failing_groups=%d -> %s\n",
                fails, fails == 0 ? "ALL PASS" : "HAS FAILURES");
    return fails == 0 ? 0 : 1;
}
