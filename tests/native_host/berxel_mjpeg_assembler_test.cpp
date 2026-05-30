#include "gomob_berxel_host_sdk.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>

namespace {

std::vector<uint8_t> jpeg_bytes() {
    return {0xff, 0xd8, 0x11, 0x22, 0x33, 0xff, 0xd9};
}

void write_le32(std::vector<uint8_t>* bytes, size_t offset, uint32_t value) {
    (*bytes)[offset + 0] = static_cast<uint8_t>(value & 0xff);
    (*bytes)[offset + 1] = static_cast<uint8_t>((value >> 8) & 0xff);
    (*bytes)[offset + 2] = static_cast<uint8_t>((value >> 16) & 0xff);
    (*bytes)[offset + 3] = static_cast<uint8_t>((value >> 24) & 0xff);
}

std::vector<uint8_t> uvc_packet(uint8_t fid,
                                bool eof,
                                const std::vector<uint8_t>& payload,
                                bool with_clock = false) {
    const uint8_t header_len = with_clock ? 12 : 2;
    uint8_t flags = static_cast<uint8_t>(0x80 | (fid & 0x01));
    if (eof) flags = static_cast<uint8_t>(flags | 0x02);
    if (with_clock) flags = static_cast<uint8_t>(flags | 0x04 | 0x08);

    std::vector<uint8_t> out(header_len, 0);
    out[0] = header_len;
    out[1] = flags;
    if (with_clock) {
        write_le32(&out, 2, 0x11223344);
        write_le32(&out, 6, 0x55667788);
        out[10] = 0xaa;
        out[11] = 0xbb;
    }
    out.reserve(out.size() + payload.size());
    for (uint8_t byte : payload) {
        out.push_back(byte);
    }
    return out;
}

gomob::berxel::host::UvcMjpegFrameAssembler make_assembler() {
    gomob::berxel::host::UvcMjpegFrameAssemblerConfig config;
    config.endpoint = 0x81;
    config.mode = gomob::berxel::host::P100R3VideoMode{3, 640, 400, 30, 333333};
    return gomob::berxel::host::UvcMjpegFrameAssembler(config);
}

void emits_on_uvc_eof() {
    auto assembler = make_assembler();
    std::vector<gomob::berxel::host::UvcMjpegFrame> frames;
    const std::vector<uint8_t> jpeg = jpeg_bytes();
    const std::vector<uint8_t> first_payload(jpeg.begin(), jpeg.begin() + 3);
    const std::vector<uint8_t> second_payload(jpeg.begin() + 3, jpeg.end());

    std::vector<uint8_t> packet = uvc_packet(0, false, first_payload);
    assert(assembler.push_packet(packet.data(), static_cast<int>(packet.size()), 1000, &frames));
    assert(frames.empty());

    packet = uvc_packet(0, true, second_payload, true);
    assert(assembler.push_packet(packet.data(), static_cast<int>(packet.size()), 2000, &frames));
    assert(frames.size() == 1);
    assert(frames[0].jpeg == jpeg);
    assert(frames[0].info.frame_number == 1);
    assert(frames[0].info.endpoint == 0x81);
    assert(frames[0].info.mode.width == 640);
    assert(frames[0].info.host_start_ns == 1000);
    assert(frames[0].info.host_end_ns == 2000);
    assert(frames[0].info.completed_by_eof);
    assert(!frames[0].info.completed_by_jpeg_eoi);
    assert(frames[0].info.has_uvc_pts);
    assert(frames[0].info.uvc_pts == 0x11223344);
    assert(frames[0].info.has_uvc_scr);
    assert(frames[0].info.uvc_scr_stc == 0x55667788);
    assert(frames[0].info.uvc_scr_sof == 0xbbaa);

    const auto stats = assembler.stats();
    assert(stats.frames == 1);
    assert(stats.completed_by_eof == 1);
    assert(stats.frame_drops == 0);
}

void emits_on_jpeg_eoi_without_eof() {
    auto assembler = make_assembler();
    std::vector<gomob::berxel::host::UvcMjpegFrame> frames;
    const std::vector<uint8_t> jpeg = jpeg_bytes();
    std::vector<uint8_t> packet = uvc_packet(1, false, jpeg);

    assert(assembler.push_packet(packet.data(), static_cast<int>(packet.size()), 3000, &frames));
    assert(frames.size() == 1);
    assert(frames[0].jpeg == jpeg);
    assert(frames[0].info.fid == 1);
    assert(frames[0].info.completed_by_jpeg_eoi);
    assert(!frames[0].info.completed_by_eof);

    const auto stats = assembler.stats();
    assert(stats.frames == 1);
    assert(stats.completed_by_jpeg_eoi == 1);

    packet = uvc_packet(1, false, {0x00, 0x00, 0x00});
    assert(assembler.push_packet(packet.data(), static_cast<int>(packet.size()), 3100, &frames));
    assert(frames.empty());

    packet = uvc_packet(1, true, {});
    assert(assembler.push_packet(packet.data(), static_cast<int>(packet.size()), 3200, &frames));
    assert(frames.empty());
    assert(assembler.stats().frame_drops == 0);
}

void drops_partial_on_fid_toggle_and_recovers() {
    auto assembler = make_assembler();
    std::vector<gomob::berxel::host::UvcMjpegFrame> frames;
    const std::vector<uint8_t> partial = {0xff, 0xd8, 0x44, 0x55};
    std::vector<uint8_t> packet = uvc_packet(0, false, partial);
    assert(assembler.push_packet(packet.data(), static_cast<int>(packet.size()), 4000, &frames));
    assert(frames.empty());

    const std::vector<uint8_t> jpeg = jpeg_bytes();
    packet = uvc_packet(1, false, jpeg);
    assert(assembler.push_packet(packet.data(), static_cast<int>(packet.size()), 5000, &frames));
    assert(frames.size() == 1);
    assert(frames[0].jpeg == jpeg);

    const auto stats = assembler.stats();
    assert(stats.fid_toggles == 1);
    assert(stats.frame_drops == 1);
    assert(stats.frames == 1);
}

}  // namespace

int main() {
    emits_on_uvc_eof();
    emits_on_jpeg_eoi_without_eof();
    drops_partial_on_fid_toggle_and_recovers();
    std::cout << "berxel_mjpeg_assembler_test PASS\n";
    return 0;
}
