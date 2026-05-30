#include "gomob_berxel_host_sdk.h"

#include <cassert>
#include <cstdint>
#include <iostream>
#include <vector>

namespace {

void write_le32(std::vector<uint8_t>* bytes, size_t offset, uint32_t value) {
    (*bytes)[offset + 0] = static_cast<uint8_t>(value & 0xff);
    (*bytes)[offset + 1] = static_cast<uint8_t>((value >> 8) & 0xff);
    (*bytes)[offset + 2] = static_cast<uint8_t>((value >> 16) & 0xff);
    (*bytes)[offset + 3] = static_cast<uint8_t>((value >> 24) & 0xff);
}

std::vector<uint8_t> uvc_packet(uint8_t fid,
                                const std::vector<uint8_t>& payload,
                                bool with_clock = true) {
    const uint8_t header_len = with_clock ? 12 : 2;
    uint8_t flags = static_cast<uint8_t>(0x80 | (fid & 0x01));
    if (with_clock) flags = static_cast<uint8_t>(flags | 0x04 | 0x08);

    std::vector<uint8_t> out(header_len, 0);
    out[0] = header_len;
    out[1] = flags;
    if (with_clock) {
        write_le32(&out, 2, 0x01020304);
        write_le32(&out, 6, 0x05060708);
        out[10] = 0x33;
        out[11] = 0x44;
    }
    out.reserve(out.size() + payload.size());
    for (uint8_t byte : payload) {
        out.push_back(byte);
    }
    return out;
}

gomob::berxel::host::UvcRawFrameAssembler make_assembler() {
    gomob::berxel::host::UvcRawFrameAssemblerConfig config;
    config.endpoint = 0x82;
    config.mode = gomob::berxel::host::P100R3VideoMode{2, 4, 2, 45, 222222};
    config.frame_size = 8;
    return gomob::berxel::host::UvcRawFrameAssembler(config);
}

void joins_header_and_continuation() {
    auto assembler = make_assembler();
    std::vector<gomob::berxel::host::UvcRawFrame> frames;
    std::vector<uint8_t> packet = uvc_packet(1, {1, 2, 3, 4});
    assert(assembler.push_packet(packet.data(), static_cast<int>(packet.size()), 1000, &frames));
    assert(frames.empty());

    packet = {5, 6, 7, 8};
    assert(assembler.push_packet(packet.data(), static_cast<int>(packet.size()), 2000, &frames));
    assert(frames.size() == 1);
    assert((frames[0].payload == std::vector<uint8_t>{1, 2, 3, 4, 5, 6, 7, 8}));
    assert(frames[0].info.endpoint == 0x82);
    assert(frames[0].info.frame_number == 1);
    assert(frames[0].info.host_start_ns == 1000);
    assert(frames[0].info.host_end_ns == 2000);
    assert(frames[0].info.completed_by_size);
    assert(frames[0].info.has_uvc_pts);
    assert(frames[0].info.uvc_pts == 0x01020304);
    assert(frames[0].info.has_uvc_scr);
    assert(frames[0].info.uvc_scr_stc == 0x05060708);
    assert(frames[0].info.uvc_scr_sof == 0x4433);

    const auto stats = assembler.stats();
    assert(stats.frames == 1);
    assert(stats.completed_by_size == 1);
    assert(stats.frame_drops == 0);
}

void drops_partial_on_new_uvc_header() {
    auto assembler = make_assembler();
    std::vector<gomob::berxel::host::UvcRawFrame> frames;
    std::vector<uint8_t> packet = uvc_packet(0, {1, 2, 3, 4});
    assert(assembler.push_packet(packet.data(), static_cast<int>(packet.size()), 1000, &frames));
    assert(frames.empty());

    packet = uvc_packet(1, {9, 10, 11, 12, 13, 14, 15, 16});
    assert(assembler.push_packet(packet.data(), static_cast<int>(packet.size()), 2000, &frames));
    assert(frames.size() == 1);
    assert((frames[0].payload == std::vector<uint8_t>{9, 10, 11, 12, 13, 14, 15, 16}));

    const auto stats = assembler.stats();
    assert(stats.frames == 1);
    assert(stats.frame_drops == 1);
    assert(stats.partial_frame_drops == 1);
    assert(stats.uvc_headers == 2);
}

void oversized_recovery_resets_frame_info() {
    // frame_size=8, max_buffer 默认 = frame_size*3 = 24。
    auto assembler = make_assembler();
    std::vector<gomob::berxel::host::UvcRawFrame> frames;

    // 先送一个带 UVC header 的半帧（4 字节，ts=1000，带 pts/scr）。
    std::vector<uint8_t> head = uvc_packet(0, {1, 2, 3, 4});
    assert(assembler.push_packet(head.data(), static_cast<int>(head.size()), 1000, &frames));
    assert(frames.empty());

    // 再送一个无 header 的超长续传（30 字节，ts=5000），累计 34 > max_buffer(24)，触发 oversized。
    std::vector<uint8_t> cont(30, 0);
    for (size_t i = 0; i < cont.size(); ++i) cont[i] = static_cast<uint8_t>(0x40 + i);
    assert(assembler.push_packet(cont.data(), static_cast<int>(cont.size()), 5000, &frames));

    // oversized 丢弃后保留尾段 frame_size 字节并出 1 帧，payload 为续传的最后 8 字节。
    assert(frames.size() == 1);
    assert(frames[0].payload.size() == 8);
    assert(frames[0].payload.front() == 0x56);  // cont[22]
    assert(frames[0].payload.back() == 0x5d);    // cont[29]
    // 关键回归：出帧元数据必须来自触发 oversized 的当前包(ts=5000)，
    // 不能带被丢弃半帧的 stale host_start_ns(=1000)/pts，否则污染 RGBD 配对 host_delta。
    assert(frames[0].info.host_start_ns == 5000);
    assert(frames[0].info.host_end_ns == 5000);
    assert(!frames[0].info.has_uvc_pts);
    assert(!frames[0].info.has_uvc_scr);
    assert(frames[0].info.completed_by_size);

    const auto stats = assembler.stats();
    assert(stats.oversized_frame_drops == 1);
    assert(stats.frame_drops >= 1);
    assert(stats.frames == 1);
}

}  // namespace

int main() {
    joins_header_and_continuation();
    drops_partial_on_new_uvc_header();
    oversized_recovery_resets_frame_info();
    std::cout << "berxel_raw_assembler_test PASS\n";
    return 0;
}
