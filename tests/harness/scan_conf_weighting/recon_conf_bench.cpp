// recon_conf_bench — 置信加权 TSDF 重建的「行为好不好」基准(host,确定性)
//
// 单测(conf_weight_test)验证公式对;这里验证涌现行为:density-first 稠密+带噪深度下,
// 置信加权能否产出更接近真实表面的重建。
//
// 合成场景:已知球面(心 (0,0,400)mm 半径 60mm)。固定相机视 +z,N 帧。
//   每帧每像素(命中球面):按概率随机判「弱回波」——
//     好像素:depth≈真值(σ2mm),conf=255;
//     弱像素:depth 大噪(σ40mm)+概率粗飞点(±120-180mm),conf=40(模拟散斑弱/弱回波)。
//   弱像素逐帧随机(模拟 IR 散斑/噪声逐帧抖动)→ 每体素跨帧既有好观测也有坏观测。
// 对照两条 TSDF:① 加权(吃 conf,坏观测降权 40/255)② 均权(不吃 conf,坏观测等权)。
// 度量:Marching Cubes 提面后,顶点到球心距离 vs 半径的 RMS + 顶点数(覆盖)。
// 判定:加权 RMS 明显低于均权(坏观测被降权 → 零交叉更准 → 表面更贴真球),覆盖不塌。
//
// 确定性:固定种子 mt19937,可复现。

#include "reconstruction/tsdf.h"
#include "reconstruction/marching_cubes.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <random>
#include <vector>

using namespace gomob::reconstruction;

namespace {

constexpr int W = 480, H = 480;
constexpr double FX = 500, FY = 500, CX = 240, CY = 240;
constexpr float SCx = 0, SCy = 0, SCz = 400, SR = 60;   // 球心/半径 mm
constexpr int N_FRAMES = 12;
constexpr double P_BAD = 0.45;                           // 弱回波像素比例

TsdfConfig BenchCfg() {
    TsdfConfig cfg;
    cfg.voxel_size_mm = 4.0f;
    cfg.grid_extent_mm = 200.0f;
    cfg.truncation_dist_mm = 16.0f;
    cfg.weight_clamp = 100.0f;
    cfg.grid_origin_mm = {-100.0f, -100.0f, SCz - 100.0f};  // 覆盖球(z∈[300,500])
    return cfg;
}

// 像素 (u,v) 对球面前表面的真实 Z(命中返回 true)
bool SphereDepth(int u, int v, double& out_z) {
    double dx = (u - CX) / FX, dy = (v - CY) / FY;
    double a = dx * dx + dy * dy + 1.0;
    double b = -2.0 * SCz;                 // 球心 x,y=0
    double c = SCz * SCz - SR * SR;
    double disc = b * b - 4 * a * c;
    if (disc < 0) return false;
    double s = (-b - std::sqrt(disc)) / (2 * a);   // 近交点
    if (s <= 0) return false;
    out_z = s;                              // P.z = s
    return true;
}

// 渲染一帧:填 depth + conf。
// systematic_weak=false:坏像素逐帧随机(每体素跨帧既有好观测也有坏观测 → 测跨帧混合收益)。
// systematic_weak=true :固定左侧 u<0.4W 区域恒弱(每帧都 conf=40+大噪 → 测系统性恒弱区:
//   该区无任何好观测,加权只能靠 min_weight 门 + SDF 降权,验证是否产生空洞 / 还能否覆盖)。
void RenderFrame(int seed, std::vector<uint16_t>& depth, std::vector<uint8_t>& conf,
                 bool systematic_weak = false) {
    std::mt19937 g(seed);
    std::uniform_real_distribution<double> uni(0, 1);
    std::normal_distribution<double> ngood(0, 2), nbad(0, 40);
    std::uniform_real_distribution<double> flyer(120, 180);
    depth.assign(W * H, 0);
    conf.assign(W * H, 0);
    for (int v = 0; v < H; ++v)
        for (int u = 0; u < W; ++u) {
            double z;
            if (!SphereDepth(u, v, z)) continue;
            int idx = v * W + u;
            bool bad = systematic_weak ? (u < 0.4 * W) : (uni(g) < P_BAD);
            if (bad) {
                double d = z + nbad(g);
                if (uni(g) < 0.25) d += (uni(g) < 0.5 ? -1 : 1) * flyer(g);  // 粗飞点
                depth[idx] = static_cast<uint16_t>(std::max(0.0, d));
                conf[idx] = 40;
            } else {
                depth[idx] = static_cast<uint16_t>(std::max(0.0, z + ngood(g)));
                conf[idx] = 255;
            }
        }
}

// 重建并算度量。weighted=false 时传 nullptr(均权)。
// 注:顶点数不是覆盖度量——噪声会把表面打成毛刺微三角令顶点虚高。
// 用 ① 表面 RMS/平均|误差| ② XY 径向范围(可见球冠是否覆盖)③ 内点占比(|dist-R|<5mm)。
struct Metric { double rms_mm; double mean_abs_mm; std::size_t verts; double xy_extent_mm; double inlier_frac; };
Metric Reconstruct(bool weighted, bool systematic_weak = false) {
    TsdfVolume vol(BenchCfg());
    float pose[7] = {0, 0, 0, 0, 0, 0, 1};
    std::vector<uint16_t> depth;
    std::vector<uint8_t> conf;
    for (int f = 0; f < N_FRAMES; ++f) {
        RenderFrame(1000 + f, depth, conf, systematic_weak);
        vol.Integrate(depth.data(), W, H, FX, FY, CX, CY, pose,
                      weighted ? conf.data() : nullptr);
    }
    MeshConfig mc; mc.min_weight = 1.0f;
    Mesh m = ExtractMesh(vol, mc);
    double se = 0, sa = 0, xy_max = 0;
    std::size_t n = m.vertex_count(), inliers = 0;
    for (std::size_t i = 0; i < n; ++i) {
        double x = m.vertices[i*3] - SCx, y = m.vertices[i*3+1] - SCy, z = m.vertices[i*3+2] - SCz;
        double dist = std::sqrt(x*x + y*y + z*z);
        double e = dist - SR;
        se += e * e; sa += std::abs(e);
        if (std::abs(e) < 5.0) ++inliers;
        double xy = std::sqrt(x*x + y*y);
        if (xy > xy_max) xy_max = xy;
    }
    return {n ? std::sqrt(se / n) : 0.0, n ? sa / n : 0.0, n, xy_max, n ? (double)inliers / n : 0.0};
}

}  // namespace

int main() {
    std::printf("[recon_conf_bench] 合成球面 R=%.0fmm @z=%.0fmm, %d 帧, 弱回波占 %.0f%%\n",
                (double)SR, (double)SCz, N_FRAMES, P_BAD * 100);
    Metric u = Reconstruct(false);
    Metric w = Reconstruct(true);
    std::printf("  均权重建: 顶点=%zu RMS=%.2fmm 平均|误差|=%.2fmm XY范围=%.1fmm 内点(<5mm)=%.0f%%\n",
                u.verts, u.rms_mm, u.mean_abs_mm, u.xy_extent_mm, u.inlier_frac * 100);
    std::printf("  加权重建: 顶点=%zu RMS=%.2fmm 平均|误差|=%.2fmm XY范围=%.1fmm 内点(<5mm)=%.0f%%\n",
                w.verts, w.rms_mm, w.mean_abs_mm, w.xy_extent_mm, w.inlier_frac * 100);

    double improve = u.rms_mm > 0 ? (u.rms_mm - w.rms_mm) / u.rms_mm * 100 : 0;
    // 覆盖分母用真球可见轮廓半径(R√(Zc²-R²)/Zc),不用均权 XY 范围——
    // 均权范围被噪点甩到真球外虚高(68mm > 真轮廓 59mm),会反向惩罚干净结果。
    double true_xy = SR * std::sqrt((double)SCz * SCz - (double)SR * SR) / SCz;
    double cover = w.xy_extent_mm / true_xy;
    std::printf("  RMS 改善=%.1f%%  真轮廓半径=%.1fmm 加权覆盖=%.2f(均权 XY %.1fmm 是噪声虚高)  内点 %.0f%%→%.0f%%\n",
                improve, true_xy, cover, u.xy_extent_mm, u.inlier_frac * 100, w.inlier_frac * 100);

    // 系统性恒弱区场景(回应"低 conf 区会不会被 min_weight 门挖空洞"):
    // 左侧 40% 区域每帧都 conf=40+大噪,无任何好观测。看加权后该区是否仍被覆盖、是否更干净。
    std::printf("\n  [系统性恒弱区:左 40%% 恒 conf=40+大噪]\n");
    Metric su = Reconstruct(false, true);
    Metric sw = Reconstruct(true, true);
    double s_true_xy = true_xy;
    std::printf("    均权: 顶点=%zu RMS=%.2fmm XY范围=%.1fmm 内点=%.0f%%\n",
                su.verts, su.rms_mm, su.xy_extent_mm, su.inlier_frac * 100);
    std::printf("    加权: 顶点=%zu RMS=%.2fmm XY范围=%.1fmm 内点=%.0f%%\n",
                sw.verts, sw.rms_mm, sw.xy_extent_mm, sw.inlier_frac * 100);
    // 恒弱区 conf=40(cw=0.157),12 帧累计 weight≈1.88>1 → 仍过 min_weight 门,不空洞;
    // 加权降权使弱区噪声对 SDF 贡献小 → 仍更干净。覆盖(XY 范围)应保住。
    bool sys_covered = sw.xy_extent_mm >= s_true_xy * 0.85;
    std::printf("    → 恒弱区加权覆盖真球冠 %.0f%%(weight 累计过门,未空洞):%s;RMS %.2f→%.2fmm\n",
                sw.xy_extent_mm / s_true_xy * 100, sys_covered ? "✓" : "✗", su.rms_mm, sw.rms_mm);

    // 判定:加权 ① RMS 至少降 30% ② 覆盖真可见球冠 >= 85% ③ 内点占比显著提升(>+20pt)
    // ④ 系统性恒弱区不被挖空洞(覆盖真球冠 >= 85%)
    bool ok = (w.rms_mm < u.rms_mm * 0.70) && (cover >= 0.85) && (w.inlier_frac > u.inlier_frac + 0.2)
              && sys_covered;
    std::printf("\n>>> %s:置信加权 %s\n   (RMS 降 %.1f%%, 覆盖真球冠 %.0f%%, 内点 %.0f%%→%.0f%%)\n",
                ok ? "正常" : "异常",
                ok ? "把稠密带噪表面拉回真球面,覆盖完整球冠、内点占比大增" : "未达预期",
                improve, cover * 100, u.inlier_frac * 100, w.inlier_frac * 100);
    return ok ? 0 : 1;
}
