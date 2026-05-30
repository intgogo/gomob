#include "gomob_berxel_host_sdk.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>

namespace {

using gomob::berxel::host::P100R3VideoMode;
using gomob::berxel::host::P100R3DepthControls;
using gomob::berxel::host::XuPayload;

void assert_master_light_ir_pwm_matches_trace() {
    const XuPayload on =
        gomob::berxel::host::make_p100r3_master_force_internal_pwm_trigger_payload(true, 45);
    assert(on.selector == 1);
    assert(on.w_value == 0x0100);
    assert(on.w_index == 0x0500);
    assert(on.data.size() == 64);
    assert(on.data[0] == 0x42);
    assert(on.data[1] == 0x58);
    assert(on.data[2] == 0x04);
    assert(on.data[3] == 0x00);
    assert(on.data[4] == 0x05);
    assert(on.data[5] == 0x00);
    assert(on.data[8] == 0x30);
    assert(on.data[9] == 0x00);
    assert(on.data[10] == 0x01);
    assert(on.data[11] == 0x2d);

    const XuPayload off =
        gomob::berxel::host::make_p100r3_master_force_internal_pwm_trigger_payload(false, 45);
    assert(off.data[4] == 0x05);
    assert(off.data[5] == 0x00);
    assert(off.data[8] == 0x30);
    assert(off.data[9] == 0x00);
    assert(off.data[10] == 0x00);
    assert(off.data[11] == 0x00);
}

void assert_companion_light_ir_mode_matches_trace() {
    const P100R3VideoMode mode{1, 1280, 801, 45, 222222};
    const XuPayload light_ir =
        gomob::berxel::host::make_p100r3_companion_light_ir_open_stream_payload(mode);
    assert(light_ir.selector == 25);
    assert(light_ir.w_value == 0x1900);
    assert(light_ir.w_index == 0x0300);
    assert(light_ir.data.size() == 512);
    assert(light_ir.data[0] == 0x01);
    assert(light_ir.data[1] == 0x02);
    assert(light_ir.data[2] == 0x02);

    std::vector<XuPayload> payloads{
        gomob::berxel::host::make_p100r3_companion_depth_open_stream_payload(mode),
    };
    assert(payloads[0].data[2] == 0x03);

    std::string prefix;
    const int patched =
        gomob::berxel::host::patch_p100r3_companion_light_ir_open_stream_payloads(
            &payloads,
            mode,
            &prefix);
    assert(patched == 1);
    assert(payloads[0].data[0] == 0x01);
    assert(payloads[0].data[1] == 0x02);
    assert(payloads[0].data[2] == 0x02);
    assert(prefix.rfind("010202", 0) == 0);
}

void assert_light_ir_processing_matches_vendor_scale() {
    const P100R3VideoMode mode{1, 4, 2, 45, 222222};
    std::vector<uint16_t> transport = {
        static_cast<uint16_t>(49 << 6),
        static_cast<uint16_t>(57 << 6),
        static_cast<uint16_t>(104 << 6),
        static_cast<uint16_t>(1023 << 6),
        static_cast<uint16_t>(1 << 6),
        0,
        static_cast<uint16_t>(400 << 6),
        static_cast<uint16_t>(69 << 6),
    };
    std::vector<uint8_t> bytes(transport.size() * 2);
    for (size_t i = 0; i < transport.size(); ++i) {
        bytes[i * 2] = static_cast<uint8_t>(transport[i] & 0xff);
        bytes[i * 2 + 1] = static_cast<uint8_t>((transport[i] >> 8) & 0xff);
    }

    std::vector<uint16_t> active;
    const bool ok =
        gomob::berxel::host::process_p100r3_light_ir_frame(bytes.data(),
                                                           bytes.size(),
                                                           mode,
                                                           &active);
    assert(ok);
    assert(active.size() == 8);
    assert(active[0] == 49);
    assert(active[1] == 57);
    assert(active[2] == 104);
    assert(active[3] == 1023);
    assert(active[4] == 1);
    assert(active[5] == 0);
    assert(active[6] == 400);
    assert(active[7] == 69);
}

void assert_default_depth_controls_are_dense() {
    const P100R3DepthControls controls;
    assert(controls.enabled);
    assert(controls.set_auto_exposure);
    assert(controls.auto_exposure);
    assert(controls.set_confidence);
    assert(controls.confidence == 3);
    assert(controls.set_temporal_denoise);
    assert(!controls.temporal_denoise);
    assert(controls.set_spatial_denoise);
    assert(!controls.spatial_denoise);
}

}  // namespace

int main() {
    assert_master_light_ir_pwm_matches_trace();
    assert_companion_light_ir_mode_matches_trace();
    assert_light_ir_processing_matches_vendor_scale();
    assert_default_depth_controls_are_dense();
    std::cout << "berxel_host_payload_test PASS\n";
    return 0;
}
