// real_recon_bench — 真实硬件数据上的置信加权重建对比(host,探索性)
//
// 用 host_capture 采的真 density-first depth + 真 light-IR,跑置信加权 vs 均权 TSDF,
// 以 18 帧逐像素时域中值表面为稳健参考,算单向 chamfer(顶点→参考最近距离)。
// 静态近物、identity 位姿。复用 shipping 的 p100r3_ir_speckle_confidence(证真实代码路径)。
//
// 探索性(非硬门):内参为近似值、无 CAD 真值、单视角,故只看「加权 chamfer 是否低于均权」的相对趋势。
// ⚠ 参考面(时域中值)来自同一批帧,非独立真值——中值与加权都抗飞点,故"加权更贴中值"部分是同源偏好,
//   不能当作绝对精度证明;但它确实说明**均权被中值/加权共同抵抗的飞点拉偏了**,这点有意义。
// 绝对门交给合成基准 recon_conf_bench(确定性、有真球 CAD 真值,非循环)。
//
// 用法:real_recon_bench <host_capture_dir>(含 depth_NN.raw + lightir_NN.raw)

#include "reconstruction/tsdf.h"
#include "reconstruction/marching_cubes.h"
#include "reconstruction/spatial_hash.h"
#include "berxel/portable/gomob_berxel_portable.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <vector>

using namespace gomob::reconstruction;

namespace {
constexpr int W = 640, HH = 400;
constexpr double FX = 550, FY = 550, CX = 320, CY = 200;  // 近似内参(相对比较不依赖精确值)
constexpr float FRAC = 8.0f;                               // depth pix=2(13I_3D) raw/8=mm

std::vector<uint16_t> LoadRaw(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return {};
    std::vector<uint16_t> v(W * HH);
    f.read(reinterpret_cast<char*>(v.data()), static_cast<std::streamsize>(v.size() * 2));
    if (!f) return {};
    return v;
}

std::vector<std::vector<uint16_t>> LoadSeq(const std::string& dir, const char* prefix) {
    std::vector<std::vector<uint16_t>> out;
    for (int i = 0; i < 64; ++i) {
        char name[64]; std::snprintf(name, sizeof(name), "%s_%02d.raw", prefix, i);
        auto v = LoadRaw(dir + "/" + name);
        if (v.empty()) break;
        out.push_back(std::move(v));
    }
    return out;
}

TsdfConfig Cfg(float center_z) {
    TsdfConfig c;
    c.voxel_size_mm = 4.0f;
    c.grid_extent_mm = 400.0f;
    c.truncation_dist_mm = 16.0f;
    c.weight_clamp = 100.0f;
    c.grid_origin_mm = {-200.0f, -200.0f, center_z - 200.0f};
    return c;
}

// depth raw → mm(uint16)
std::vector<uint16_t> ToMm(const std::vector<uint16_t>& raw) {
    std::vector<uint16_t> mm(raw.size());
    for (size_t i = 0; i < raw.size(); ++i) mm[i] = static_cast<uint16_t>(raw[i] / FRAC + 0.5f);
    return mm;
}

Mesh Recon(const std::vector<std::vector<uint16_t>>& depth_mm,
           const std::vector<std::vector<uint8_t>>& confs, float center_z, bool weighted) {
    TsdfVolume vol(Cfg(center_z));
    float pose[7] = {0, 0, 0, 0, 0, 0, 1};
    for (size_t i = 0; i < depth_mm.size(); ++i)
        vol.Integrate(depth_mm[i].data(), W, HH, FX, FY, CX, CY, pose,
                      weighted ? confs[i].data() : nullptr);
    MeshConfig mc; mc.min_weight = 1.0f;
    return ExtractMesh(vol, mc);
}

// 顶点→参考网格的单向平均最近距离(chamfer 半边)
double ChamferTo(const Mesh& src, const Mesh& ref) {
    if (src.vertices.empty() || ref.vertices.empty()) return 0.0;
    SpatialHash3D hash(ref.vertices.data(), ref.vertices.size() / 3, 20.0f);
    double sum = 0; std::size_t n = src.vertices.size() / 3, cnt = 0;
    for (std::size_t i = 0; i < n; ++i) {
        std::size_t idx; float dsq;
        if (hash.NearestNeighbor(src.vertices[i*3], src.vertices[i*3+1], src.vertices[i*3+2], idx, dsq)) {
            sum += std::sqrt(dsq); ++cnt;
        }
    }
    return cnt ? sum / cnt : 0.0;
}
}  // namespace

int main(int argc, char** argv) {
    std::string dir = argc > 1 ? argv[1] : ".dev/depth_ir_guided/host_capture";
    auto draw = LoadSeq(dir, "depth");
    auto iraw = LoadSeq(dir, "lightir");
    if (draw.size() < 5 || iraw.empty()) {
        std::printf("[real_recon_bench] 跳过:%s 下 depth/lightir 帧不足(depth=%zu ir=%zu)\n",
                    dir.c_str(), draw.size(), iraw.size());
        return 0;  // 探索性,数据缺失不算失败
    }
    const std::size_t N = std::min(draw.size(), iraw.size());
    std::printf("[real_recon_bench] %s  depth=%zu ir=%zu 用前 %zu 对\n",
                dir.c_str(), draw.size(), iraw.size(), N);

    // depth → mm;每帧 conf(host light-IR 纯 10bit → 放进高字节喂 shipping 函数)
    std::vector<std::vector<uint16_t>> dmm;
    std::vector<std::vector<uint8_t>> confs;
    for (std::size_t f = 0; f < N; ++f) {
        dmm.push_back(ToMm(draw[f]));
        std::vector<uint16_t> packed(iraw[f].size());
        for (size_t i = 0; i < packed.size(); ++i)
            packed[i] = static_cast<uint16_t>((iraw[f][i] >> 2) << 8);  // 10bit→8bit→高字节
        confs.push_back(gomob::berxel::host::p100r3_ir_speckle_confidence(packed, W, HH));
    }

    // 场景中心 z = 中值深度
    std::vector<uint16_t> allmm;
    for (auto& d : dmm) for (uint16_t v : d) if (v > 0) allmm.push_back(v);
    std::nth_element(allmm.begin(), allmm.begin() + allmm.size()/2, allmm.end());
    float center_z = allmm.empty() ? 380.0f : allmm[allmm.size()/2];
    // conf 统计
    double conf_mean = 0; std::size_t cn = 0;
    for (auto& c : confs) for (uint8_t v : c) { if (v > 0) { conf_mean += v; ++cn; } }
    std::printf("  场景中值深度=%.0fmm  有效像素平均 conf=%.0f\n",
                center_z, cn ? conf_mean / cn : 0);

    // 参考:逐像素时域中值深度(稳健,抗飞点)→ 单帧积分(conf 255)
    std::vector<uint16_t> med(W * HH, 0);
    std::vector<uint16_t> col;
    for (int p = 0; p < W * HH; ++p) {
        col.clear();
        for (auto& d : dmm) if (d[p] > 0) col.push_back(d[p]);
        if (col.empty()) continue;
        std::nth_element(col.begin(), col.begin() + col.size()/2, col.end());
        med[p] = col[col.size()/2];
    }
    TsdfVolume refv(Cfg(center_z));
    float pose[7] = {0,0,0,0,0,0,1};
    refv.Integrate(med.data(), W, HH, FX, FY, CX, CY, pose);
    MeshConfig mc; mc.min_weight = 1.0f;
    Mesh ref = ExtractMesh(refv, mc);

    Mesh mu = Recon(dmm, confs, center_z, false);
    Mesh mw = Recon(dmm, confs, center_z, true);
    double cu = ChamferTo(mu, ref), cw = ChamferTo(mw, ref);
    std::printf("  参考(时域中值)顶点=%zu\n", ref.vertex_count());
    std::printf("  均权重建 顶点=%zu  chamfer→中值面=%.2fmm\n", mu.vertex_count(), cu);
    std::printf("  加权重建 顶点=%zu  chamfer→中值面=%.2fmm\n", mw.vertex_count(), cw);
    double improve = cu > 0 ? (cu - cw) / cu * 100 : 0;
    std::printf("  → 加权 chamfer 相对均权 %s %.1f%%\n",
                cw < cu ? "降低" : "升高", std::abs(improve));
    std::printf("\n[real_recon_bench] 探索性观测:%s(真硬件数据;绝对门见合成基准)\n",
                cw < cu ? "加权重建更贴近稳健中值面 ✓" : "加权未见优势(查 conf 分布/场景)");
    return 0;  // 探索性,恒 0
}
