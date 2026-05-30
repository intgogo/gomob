// 飞点剔除纯逻辑单测：验证空间几何判据 + 时域联合判定的数学正确性。
// 只链 portable.cpp（无 libusb）。验"代码对不对"；harness depth_flying_pixel 验"行为好不好"。
#include "gomob_berxel_portable.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>

using namespace gomob::berxel::host;

namespace {

constexpr float kScale = 8.0f;  // 13I3D
uint16_t R(float mm) { return static_cast<uint16_t>(mm * kScale + 0.5f); }

// 在 fused 网格上算像素 idx 的空间证据
P100R3FlyingSpatialEvidence evid(const std::vector<uint16_t>& g, int w, int h, int x, int y,
                                 float angle_scale = 1.0f) {
    P100R3FlyingPixelConfig cfg;  // 默认 fx=440.4 fy=424.1 grazing=88
    P100R3FlyingSpatialEvidence ev;
    bool ok = p100r3_flying_spatial_evidence(g, static_cast<uint16_t>(w), static_cast<uint16_t>(h),
                                             static_cast<size_t>(y) * w + x, cfg, angle_scale, &ev);
    assert(ok);
    return ev;
}

// 双侧夹心：中心夹在更近(前景)和更远(背景)崖之间 → sandwich
void sandwich_hit() {
    std::vector<uint16_t> g = {R(500), R(1000), R(1500)};  // W=3 H=1
    auto ev = evid(g, 3, 1, 1, 0);
    assert(ev.sandwich);            // 左 -500 近超界、右 +500 远超界
    assert(ev.support == 0);        // 500/1500 都不在共面带内
    assert(ev.no_support);
}

// 单侧超界 = 真实边缘/遮挡边 → 放行（中心属前景，只有右侧背景超界）
void single_side_pass() {
    std::vector<uint16_t> g = {R(500), R(500), R(1500)};
    auto ev = evid(g, 3, 1, 1, 0);
    assert(!ev.sandwich);
}

// 连续斜面（阶跃在角度上界内）→ 不夹心 + 有共面支撑
void slope_pass() {
    std::vector<uint16_t> g = {R(970), R(1000), R(1030)};  // 阶跃 30mm < step_max(Z=1000)≈65mm
    auto ev = evid(g, 3, 1, 1, 0);
    assert(!ev.sandwich);
    assert(ev.support == 2);        // 两侧都在共面带(70mm)内
    assert(!ev.no_support);
}

// 平整面 → 高共面支撑
void flat_support() {
    std::vector<uint16_t> g(9, R(800));  // 3x3 全 800
    auto ev = evid(g, 3, 3, 1, 1);
    assert(ev.support == 8);
    assert(!ev.no_support);
    assert(!ev.sandwich);
}

// 角度上界随深度缩放：同样 50mm 阶跃，近距超界(夹心)、远距不超界(放行)
void angle_z_scaling() {
    std::vector<uint16_t> near = {R(450), R(500), R(550)};   // step_max(Z=500)≈32mm < 50 → 超界
    assert(evid(near, 3, 1, 1, 0).sandwich);
    std::vector<uint16_t> far = {R(1950), R(2000), R(2050)}; // step_max(Z=2000)≈130mm > 50 → 不超界
    assert(!evid(far, 3, 1, 1, 0).sandwich);
}

// 时域集成：稳定真断崖（无噪声）多帧 → 0 飞点（真边缘单侧 + 时域稳）
void stable_cliff_no_flying() {
    const int W = 8, H = 3, n = 10;
    std::vector<uint16_t> frame(W * H);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) frame[y * W + x] = R(x < 4 ? 500 : 1500);
    P100R3TemporalFilterConfig fcfg; fcfg.spatial_denoise_enable = false;  // 隔离飞点检测，关空间降噪
    P100R3TemporalFilter filt(fcfg);
    std::vector<uint16_t> fused; std::vector<uint8_t> conf, fly;
    P100R3TemporalFilterStats st;
    for (int i = 0; i < n; ++i)
        filt.push(frame, W, H, &fused, &conf, &st, &fly);
    assert(st.flying_pixels == 0);  // 真断崖不该判飞点
}

// 时域集成：中间幽灵飞点列（逐帧变中间深度）多帧 → 暖机后被标记，且只标记飞点列
void intermediate_flyer_flagged() {
    const int W = 9, H = 3, n = 12;
    P100R3TemporalFilterConfig fcfg; fcfg.spatial_denoise_enable = false;  // 隔离飞点检测，关空间降噪
    P100R3TemporalFilter filt(fcfg);
    std::vector<uint16_t> fused; std::vector<uint8_t> conf, fly;
    P100R3TemporalFilterStats st;
    int flyer_col_hits = 0, other_hits = 0;
    for (int i = 0; i < n; ++i) {
        std::vector<uint16_t> frame(W * H);
        for (int y = 0; y < H; ++y) {
            for (int x = 0; x < W; ++x) {
                float mm;
                if (x < 4) mm = 500;          // 前景
                else if (x > 4) mm = 1500;    // 背景
                else mm = 700 + ((i * 137 + y * 53) % 600);  // col4 飞点：逐帧/逐行变中间值
                frame[y * W + x] = R(mm);
            }
        }
        filt.push(frame, W, H, &fused, &conf, &st, &fly);
        if (i == n - 1) {  // 末帧（已过暖机）检查空间分布
            for (int y = 0; y < H; ++y)
                for (int x = 0; x < W; ++x)
                    (x == 4 ? flyer_col_hits : other_hits) += fly[y * W + x] ? 1 : 0;
        }
    }
    assert(flyer_col_hits > 0);   // 飞点列被标记
    assert(other_hits == 0);      // 前景/背景真表面未被误标
}

// 暖机保护：仅 2 帧（frames_seen<min_stable）→ 即便是飞点也不硬删（只可能降权）
void warmup_no_delete() {
    const int W = 9, H = 1;
    P100R3TemporalFilterConfig fcfg; fcfg.spatial_denoise_enable = false;  // 隔离飞点检测，关空间降噪
    P100R3TemporalFilter filt(fcfg);
    std::vector<uint16_t> fused; std::vector<uint8_t> conf, fly;
    P100R3TemporalFilterStats st;
    for (int i = 0; i < 2; ++i) {
        std::vector<uint16_t> frame(W * H);
        for (int x = 0; x < W; ++x)
            frame[x] = R(x < 4 ? 500 : (x > 4 ? 1500 : 700 + i * 200));
        filt.push(frame, W, H, &fused, &conf, &st, &fly);
    }
    assert(st.flying_pixels == 0);  // 暖机期绝不硬删
}

}  // namespace

int main() {
    sandwich_hit();
    single_side_pass();
    slope_pass();
    flat_support();
    angle_z_scaling();
    stable_cliff_no_flying();
    intermediate_flyer_flagged();
    warmup_no_delete();
    std::cout << "berxel_flying_pixel_test PASS\n";
    return 0;
}
