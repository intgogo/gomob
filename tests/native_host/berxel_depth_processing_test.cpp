#include "gomob_berxel_host_sdk.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>

namespace {

std::vector<uint8_t> to_bytes(const std::vector<uint16_t>& pixels) {
    std::vector<uint8_t> out(pixels.size() * 2);
    for (size_t i = 0; i < pixels.size(); ++i) {
        out[i * 2] = static_cast<uint8_t>(pixels[i] & 0xff);
        out[i * 2 + 1] = static_cast<uint8_t>((pixels[i] >> 8) & 0xff);
    }
    return out;
}

gomob::berxel::host::P100R3DepthProcessingConfig test_config() {
    gomob::berxel::host::P100R3DepthProcessingConfig config;
    config.seed_support_radius_px = 1;
    config.min_seed_support = 1;
    config.max_fill_distance_px = 16;
    config.target_valid_ratio = 1.0f;
    config.max_fill_depth_delta_mm = 80.0f;
    return config;
}

void fills_flat_holes() {
    constexpr int width = 8;
    constexpr int height = 4;
    constexpr int active_height = height;
    constexpr uint16_t depth_raw = 200 * 8;
    std::vector<uint16_t> pixels(width * height, 0);
    for (int y = 0; y < active_height; ++y) {
        pixels[y * width + 0] = depth_raw;
        pixels[y * width + 1] = depth_raw;
        pixels[y * width + 6] = depth_raw;
        pixels[y * width + 7] = depth_raw;
    }

    const std::vector<uint8_t> bytes = to_bytes(pixels);
    std::vector<uint16_t> processed;
    std::vector<uint8_t> confidence;
    gomob::berxel::host::P100R3DepthProcessingStats stats;
    const bool ok = gomob::berxel::host::process_p100r3_depth_frame(
        bytes.data(),
        bytes.size(),
        gomob::berxel::host::P100R3VideoMode{0, width, height, 0, 0},
        test_config(),
        &processed,
        &confidence,
        &stats);

    assert(ok);
    assert(processed.size() == width * active_height);
    assert(stats.raw_valid_pixels == 16);
    assert(stats.processed_valid_pixels == width * active_height);
    assert(stats.edge_blocked_pixels == 0);
    for (uint16_t v : processed) {
        assert(v == depth_raw);
    }
}

void does_not_cross_depth_edge() {
    constexpr int width = 8;
    constexpr int height = 4;
    constexpr int active_height = height;
    constexpr uint16_t near_raw = 200 * 8;
    constexpr uint16_t far_raw = 600 * 8;
    std::vector<uint16_t> pixels(width * height, 0);
    for (int y = 0; y < active_height; ++y) {
        pixels[y * width + 0] = near_raw;
        pixels[y * width + 1] = near_raw;
        pixels[y * width + 6] = far_raw;
        pixels[y * width + 7] = far_raw;
    }

    const std::vector<uint8_t> bytes = to_bytes(pixels);
    std::vector<uint16_t> processed;
    std::vector<uint8_t> confidence;
    gomob::berxel::host::P100R3DepthProcessingStats stats;
    const bool ok = gomob::berxel::host::process_p100r3_depth_frame(
        bytes.data(),
        bytes.size(),
        gomob::berxel::host::P100R3VideoMode{0, width, height, 0, 0},
        test_config(),
        &processed,
        &confidence,
        &stats);

    assert(ok);
    assert(processed.size() == width * active_height);
    assert(stats.edge_blocked_pixels > 0);
    for (int y = 0; y < active_height; ++y) {
        bool has_empty_boundary = false;
        for (int x = 2; x <= 5; ++x) {
            const uint16_t v = processed[y * width + x];
            if (v == 0) has_empty_boundary = true;
            if (x >= 5) assert(v != near_raw);
            if (x <= 2) assert(v != far_raw);
        }
        assert(has_empty_boundary);
    }
}

}  // namespace

int main() {
    fills_flat_holes();
    does_not_cross_depth_edge();
    std::cout << "berxel_depth_processing_test PASS\n";
    return 0;
}
