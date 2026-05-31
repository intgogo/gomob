// P100R3TemporalFilter 纯逻辑单测：验证多帧融合的数学正确性。
// 只链 portable.cpp（无 libusb），编译期硬证明零设备依赖，Android native 可直接复用。
#include "gomob_berxel_portable.h"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <vector>

using gomob::berxel::host::P100R3TemporalFilter;
using gomob::berxel::host::P100R3TemporalFilterConfig;
using gomob::berxel::host::P100R3TemporalFilterStats;

namespace {

constexpr int kW = 4;
constexpr int kH = 4;
constexpr int kPx = kW * kH;
constexpr float kScale = 8.0f;  // 13I3D：mm = raw/8

uint16_t mm_to_raw(float mm) {
    return static_cast<uint16_t>(std::lround(mm * kScale));
}
float raw_to_mm(uint16_t raw) {
    return static_cast<float>(raw) / kScale;
}

std::vector<uint16_t> flat_frame(float mm) {
    return std::vector<uint16_t>(kPx, mm_to_raw(mm));
}

// 噪声下降 + 无偏移：平坦面 + 零均值噪声，融合后误差应远小于单帧误差。
void reduces_noise_without_bias() {
    const float truth = 400.0f;
    // 8 帧零均值噪声（mm），swing 远小于运动门限（默认 45mm）→ 全部 blend 不 reset。
    const float noise[8] = {+24, -20, +16, -24, +20, -16, +24, -20};
    float mean_noise = 0.0f;
    for (float n : noise) mean_noise += n;
    mean_noise /= 8.0f;  // = 0.5mm

    P100R3TemporalFilter filter;  // 默认 window=8
    std::vector<uint16_t> fused;
    std::vector<uint8_t> conf;
    P100R3TemporalFilterStats stats;

    float last_single_err = 0.0f;
    for (int i = 0; i < 8; ++i) {
        auto frame = flat_frame(truth + noise[i]);
        bool ok = filter.push(frame, kW, kH, &fused, &conf, &stats);
        assert(ok);
        last_single_err = std::abs(noise[i]);
    }
    assert(stats.motion_resets == 0);            // 噪声 < 门限，不应误判运动
    assert(stats.single_sample_pixels == 0);     // 第 8 帧每像素都已满样本
    assert(std::abs(stats.mean_window_fill - 8.0f) < 1e-3f);

    const float fused_mm = raw_to_mm(fused[0]);
    const float fused_err = std::abs(fused_mm - truth);
    // 融合误差应接近噪声均值（0.5mm），且远小于最后一帧单帧误差（20mm）。
    assert(fused_err < 3.0f);
    assert(fused_err < last_single_err * 0.25f);
    assert(std::abs(fused_mm - (truth + mean_noise)) < 1.0f);  // 无系统偏移
    // 真置信：±24mm 噪声（窗口 span 48mm）→ 稳定性置信降权（诚实反映 raw 抖动），仍 > 下限
    // （融合值经平均后可用）。低噪/常量场景的满置信由 holds_estimate_on_dropout 覆盖。
    assert(conf[0] < filter.config().full_confidence);
    assert(conf[0] >= filter.config().conf_min_valid);
}

// 运动不拖影：稳定一段后阶跃 > 门限，输出应快速跳到新值而非旧+新中点。
void motion_resets_window() {
    const float near_mm = 400.0f;
    const float far_mm = 700.0f;  // 阶跃 300mm >> 门限
    P100R3TemporalFilter filter;
    std::vector<uint16_t> fused;
    P100R3TemporalFilterStats stats;

    for (int i = 0; i < 8; ++i) {
        auto f = flat_frame(near_mm);
        filter.push(f, kW, kH, &fused, nullptr, &stats);
    }
    assert(std::abs(raw_to_mm(fused[0]) - near_mm) < 2.0f);

    // 阶跃到 far：本帧应触发 reset，输出立刻贴近 far（不是 (400+700)/2=550 的拖影）。
    auto step = flat_frame(far_mm);
    filter.push(step, kW, kH, &fused, nullptr, &stats);
    assert(stats.motion_resets == static_cast<uint32_t>(kPx));
    const float after = raw_to_mm(fused[0]);
    assert(std::abs(after - far_mm) < 2.0f);
    assert(after > 650.0f);  // 绝不在中点
}

// 短暂掉点：某帧像素=0 不清窗，保留估计；confidence 随样本增长。
void holds_estimate_on_dropout() {
    const float truth = 500.0f;
    P100R3TemporalFilter filter;
    std::vector<uint16_t> fused;
    std::vector<uint8_t> conf;
    P100R3TemporalFilterStats stats;

    // 第 1 帧：单样本 → single_sample_confidence
    filter.push(flat_frame(truth), kW, kH, &fused, &conf, &stats);
    assert(stats.single_sample_pixels == static_cast<uint32_t>(kPx));
    assert(conf[0] == filter.config().single_sample_confidence);

    // 再喂 3 帧 → 达到 min_samples_full_conf(=4) → 满 confidence
    for (int i = 0; i < 3; ++i) filter.push(flat_frame(truth), kW, kH, &fused, &conf, &stats);
    assert(conf[0] == filter.config().full_confidence);

    // 一帧全 0（掉点）：估计应保留（非 0），仍输出 truth 附近。
    std::vector<uint16_t> blank(kPx, 0);
    filter.push(blank, kW, kH, &fused, &conf, &stats);
    assert(fused[0] != 0);
    assert(std::abs(raw_to_mm(fused[0]) - truth) < 2.0f);
    assert(conf[0] == filter.config().full_confidence);  // 样本数不减，仍满置信
}

// reset() 清空跨 burst 状态：reset 后第一帧应回到单样本。
void reset_clears_state() {
    P100R3TemporalFilter filter;
    std::vector<uint16_t> fused;
    std::vector<uint8_t> conf;
    P100R3TemporalFilterStats stats;
    for (int i = 0; i < 5; ++i) filter.push(flat_frame(450.0f), kW, kH, &fused, &conf, &stats);
    filter.reset();
    filter.push(flat_frame(450.0f), kW, kH, &fused, &conf, &stats);
    assert(stats.single_sample_pixels == static_cast<uint32_t>(kPx));
    assert(conf[0] == filter.config().single_sample_confidence);
}

// 尺寸变化自动重置（防越界）。
void resizes_safely() {
    P100R3TemporalFilter filter;
    std::vector<uint16_t> fused;
    filter.push(flat_frame(400.0f), kW, kH, &fused, nullptr, nullptr);
    std::vector<uint16_t> big(8 * 8, mm_to_raw(400.0f));
    bool ok = filter.push(big, 8, 8, &fused, nullptr, nullptr);
    assert(ok);
    assert(fused.size() == 64u);
    // 尺寸不匹配应被拒
    assert(!filter.push(big, 4, 4, &fused, nullptr, nullptr));
}

// IR 散斑置信:平坦区(std=0)→min_conf、强对比区→高置信、无回波(高字节=0)→0。
void ir_speckle_confidence_maps_contrast() {
    const int w = 32, h = 8;
    std::vector<uint16_t> ir(static_cast<size_t>(w) * h, 0);
    // 大部分平坦(低对比,贴真实重偏态分布:中值对比度低)+ 末 4 列强交替(高对比小patch)。
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const uint8_t hi = (x < 28) ? 100                         // 主区:平坦(低对比)
                                        : ((x % 2 == 0) ? 40 : 220);  // 末 4 列:强交替(高对比)
            ir[static_cast<size_t>(y) * w + x] = static_cast<uint16_t>(hi) << 8;
        }
    }
    ir[static_cast<size_t>(3) * w + 20] = 0;  // 一个无 IR 回波像素(高字节=0)
    auto conf = gomob::berxel::host::p100r3_ir_speckle_confidence(ir, w, h);
    assert(conf.size() == static_cast<size_t>(w) * h);
    const uint8_t left = conf[static_cast<size_t>(4) * w + 4];    // 平坦内部
    const uint8_t right = conf[static_cast<size_t>(4) * w + 30];  // 强对比内部
    const uint8_t hole = conf[static_cast<size_t>(3) * w + 20];   // 无回波
    assert(hole == 0);                  // 无结构光信号 → 置信 0
    assert(left == 40);                 // 平坦 std=0 → min_conf 默认 40
    assert(right >= 200);               // 散斑强 → 高置信
    assert(right > left);
}

// IR 先验融合:稳定平坦满置信的像素,被低 IR 先验经 min 拉低;其余不受影响。
void prior_confidence_fuses_min() {
    P100R3TemporalFilterConfig cfg;
    cfg.spatial_denoise_enable = false;  // 隔离:不改 conf
    P100R3TemporalFilter filter(cfg);
    std::vector<uint16_t> fused;
    std::vector<uint8_t> conf;
    auto frame = flat_frame(500.0f);
    for (int i = 0; i < 6; ++i) filter.push(frame, kW, kH, &fused, &conf, nullptr, nullptr);
    assert(conf[0] == filter.config().full_confidence);  // 稳定平坦 → 满置信

    std::vector<uint8_t> prior(kPx, 255);
    prior[0] = 50;                       // 像素 0 IR 散斑弱
    filter.set_prior_confidence(prior);
    filter.push(frame, kW, kH, &fused, &conf, nullptr, nullptr);
    assert(conf[0] == 50);                                // min(255, 50)
    assert(conf[1] == filter.config().full_confidence);   // 其余先验=255,不降

    // reset 清空先验:不再融合
    filter.reset();
    for (int i = 0; i < 6; ++i) filter.push(frame, kW, kH, &fused, &conf, nullptr, nullptr);
    assert(conf[0] == filter.config().full_confidence);
}

}  // namespace

int main() {
    reduces_noise_without_bias();
    motion_resets_window();
    holds_estimate_on_dropout();
    reset_clears_state();
    resizes_safely();
    ir_speckle_confidence_maps_contrast();
    prior_confidence_fuses_min();
    std::cout << "berxel_temporal_filter_test PASS\n";
    return 0;
}
