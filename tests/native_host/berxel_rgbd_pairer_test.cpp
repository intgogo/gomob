#include "gomob_berxel_host_sdk.h"

#include <cassert>
#include <cstdint>
#include <iostream>

namespace {

constexpr int64_t kMs = 1000 * 1000;

gomob::berxel::host::UvcFrameInfo make_frame(uint8_t endpoint,
                                             uint64_t frame_number,
                                             int64_t start_ns,
                                             int64_t end_ns) {
    gomob::berxel::host::UvcFrameInfo info;
    info.endpoint = endpoint;
    info.frame_number = frame_number;
    info.host_start_ns = start_ns;
    info.host_end_ns = end_ns;
    info.payload_bytes = 128;
    info.transport_bytes = 256;
    return info;
}

void records_pairing_delta_summary() {
    gomob::berxel::host::RgbdFramePairerConfig config;
    config.max_delta_ns = 20 * kMs;
    gomob::berxel::host::RgbdFramePairer pairer(config);

    gomob::berxel::host::RgbdFramePairInfo pair;
    assert(!pairer.push_color(make_frame(0x81, 20, 3 * kMs, 13 * kMs), &pair));
    assert(!pairer.push_depth(make_frame(0x82, 10, 0, 10 * kMs), &pair));
    assert(pairer.push_depth(make_frame(0x82, 11, 30 * kMs, 40 * kMs), &pair));
    assert(pair.pair_number == 1);
    assert(pair.color.frame_number == 20);
    assert(pair.depth.frame_number == 10);
    assert(pair.host_delta_ns == -3 * kMs);
    assert(pair.within_tolerance);

    assert(pairer.push_color(make_frame(0x81, 21, 28 * kMs, 38 * kMs), &pair));
    assert(pair.host_delta_ns == 2 * kMs);

    const auto stats = pairer.stats();
    assert(stats.pairs == 2);
    assert(stats.dropped_color_frames == 0);
    assert(stats.dropped_depth_frames == 0);
    assert(stats.queued_color_frames == 0);
    assert(stats.queued_depth_frames == 0);
    assert(stats.last_host_delta_ns == 2 * kMs);
    assert(stats.mean_abs_host_delta_ns == 2500 * 1000);
    assert(stats.max_abs_host_delta_ns == 3 * kMs);
    assert(stats.last_color_frame_number == 21);
    assert(stats.last_depth_frame_number == 11);
}

void drops_out_of_window_frames() {
    gomob::berxel::host::RgbdFramePairerConfig config;
    config.max_delta_ns = 5 * kMs;
    gomob::berxel::host::RgbdFramePairer pairer(config);

    gomob::berxel::host::RgbdFramePairInfo pair;
    assert(!pairer.push_depth(make_frame(0x82, 1, 0, 2 * kMs), &pair));
    assert(!pairer.push_color(make_frame(0x81, 1, 20 * kMs, 22 * kMs), &pair));
    assert(!pairer.push_depth(make_frame(0x82, 2, 40 * kMs, 42 * kMs), &pair));

    const auto stats = pairer.stats();
    assert(stats.pairs == 0);
    assert(stats.dropped_color_frames == 1);
    assert(stats.dropped_depth_frames == 1);
    assert(stats.queued_color_frames == 0);
    assert(stats.queued_depth_frames == 1);
    assert(stats.mean_abs_host_delta_ns == 0);
    assert(stats.max_abs_host_delta_ns == 0);
}

}  // namespace

int main() {
    records_pairing_delta_summary();
    drops_out_of_window_frames();
    std::cout << "berxel_rgbd_pairer_test PASS\n";
    return 0;
}
