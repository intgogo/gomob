// eYs3D / Etron RS-D550 自研 host RGBD 取流（M6.3 首光，零厂商 SDK）。
// 逐字复刻 usbmon 抓到的官方 eSPDI 真实开流序列（.dev/eys3d-sdk/sdk_stream_trace.txt）+ 自研
// 异步双流取帧 + FID 拆帧。详见 docs/agent-memory/finding_rsd550_open_sequence_decoded_2026-06-01.md。
//
// 五条硬约束（缺一不出帧）：
//  1. 开流前在 XU(unit4, wIndex=0x0400) 写 videoMode/ASIC 寄存器（`82 f0 14`/`20 f0 02`/`20 ed 00`），
//     每笔后跟 selector 0x0a 递增计数器握手；再走标准 VS PROBE→GET_CUR→COMMIT；无 SET_INTERFACE。
//  2. 异步多 URB(timeout=0)，回调只 memcpy 进 capture、不组装（否则掉到 53ms/payload 龟速）。
//  3. URB 大小 = maxPayloadTransferSize（一 URB 一 payload，保留 USB 短包帧边界）。
//  4. depth(IF2/0x82) 只在 color(IF1/0x81) 并发排空时才出流（共享 stereo→depth 流水线）。
//  5. 拆帧按 UVC payload 头 FID(bit0) 翻转切帧、剥 bHeaderLength 头；每真帧恰 1228800B。
// 会话开始先 libusb_reset_device 清上次残留（否则连续开关卡 immediate-STALL）。
//
// 用法：eys3d_replay_stream dual <secs> <dump_dir>          # color+depth 并发（推荐）
//       eys3d_replay_stream <ep_hex> <iface> <maxPayload> <frame_bytes> <secs> <dump_dir>  # 单流
#include "gomob_berxel_portable.h"
#include "eys3d/host/eys3d_usb_device.h"  // 共享 Eys3dUsbDevice:IUvcDevice

#include <libusb-1.0/libusb.h>

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <memory>
#include <string>
#include <vector>

using gomob::berxel::host::IUvcDevice;  // 仅复用 IUvcDevice 接口 + XuPayload 结构
using gomob::berxel::host::XuPayload;
using gomob::eys3d::host::Eys3dUsbDevice;  // 共享 libusb 设备实现

namespace {

constexpr uint16_t kEtronVid = 0x3438;
constexpr uint16_t kRsD550Pid = 0x0206;

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

// XU 写（selector 3，wValue=0x0300，wIndex=0x0400）
XuPayload xu(std::vector<uint8_t> d) { return XuPayload{3, 0x0300, 0x0400, std::move(d)}; }
// selector 0x0a 计数器握手（1 字节）
XuPayload ctr(uint8_t c) { return XuPayload{0x0a, 0x0a00, 0x0400, {c}}; }
// VS PROBE/COMMIT：wValue=0x0100/0x0200，wIndex=接口号(0x0001/0x0002)，26 字节
XuPayload vs(uint16_t wval, uint16_t iface, std::vector<uint8_t> d) {
    return XuPayload{0, wval, iface, std::move(d)};
}

// 逐字复刻 sdk_stream_trace.txt 的开流序列。color_only=true 时在 COMMIT IF1 处截断，
// 紧接着立刻读 ep0x81（镜像工作 trace：COMMIT IF1 → 立即挂 bulk URB → 暖机 1.5s → 出数）。
std::vector<XuPayload> build_replay(bool color_only) {
    // 26 字节 PROBE/COMMIT 负载
    auto if1_zero = std::vector<uint8_t>{0x01,0x00,0x01,0x04, 0x80,0x84,0x1e,0x00,
        0,0,0,0, 0,0,0,0, 0,0, 0,0,0,0, 0,0, 0,0};
    auto if1_neg  = std::vector<uint8_t>{0x01,0x00,0x01,0x04, 0x80,0x84,0x1e,0x00,
        0,0,0,0, 0,0,0,0, 0,0, 0x00,0xc0,0x12,0x00, 0x00,0x08, 0,0};
    auto if2_zero = std::vector<uint8_t>{0x01,0x00,0x01,0x01, 0x80,0x84,0x1e,0x00,
        0,0,0,0, 0,0,0,0, 0,0, 0,0,0,0, 0,0, 0,0};
    auto if2_neg  = std::vector<uint8_t>{0x01,0x00,0x01,0x01, 0x80,0x84,0x1e,0x00,
        0,0,0,0, 0,0,0,0, 0,0, 0x00,0x80,0x25,0x00, 0x00,0x0c, 0,0};
    std::vector<XuPayload> s;
    // --- 预 XU：videoMode/ASIC 寄存器编程 + 计数器握手 ---
    s.push_back(xu({0x82,0xf0,0x14,0x00})); s.push_back(ctr(0x01));
    s.push_back(xu({0x82,0xf0,0x14,0x00})); s.push_back(ctr(0x02));
    s.push_back(xu({0x82,0xf0,0x14,0x00})); s.push_back(ctr(0x03));
    s.push_back(ctr(0x04));
    s.push_back(xu({0x82,0xf0,0x14,0x00})); s.push_back(ctr(0x05));
    s.push_back(xu({0x82,0xf0,0x14,0x00})); s.push_back(ctr(0x06));
    s.push_back(xu({0x82,0xf0,0x14,0x00})); s.push_back(ctr(0x07));
    s.push_back(ctr(0x08)); s.push_back(ctr(0x09)); s.push_back(ctr(0x0a));
    s.push_back(xu({0x20,0xf0,0x02,0x00})); s.push_back(ctr(0x0b));
    s.push_back(xu({0x20,0xed,0x00,0x00})); s.push_back(ctr(0x0c));
    // --- PROBE IF1（零负载探询）---
    s.push_back(vs(0x0100, 0x0001, if1_zero));
    s.push_back(vs(0x0100, 0x0001, if1_zero));
    s.push_back(ctr(0x0d)); s.push_back(ctr(0x0e));
    // flash/ZD 读(selector 0x0b)是标定，libusb 下 STALL 且非开流必需，跳过。
    s.push_back(ctr(0x0f));
    s.push_back(ctr(0x10)); s.push_back(ctr(0x11));
    // --- PROBE IF1（带协商 maxFrame/maxPayload）---
    s.push_back(vs(0x0100, 0x0001, if1_neg));
    s.push_back(vs(0x0100, 0x0001, if1_neg));
    s.push_back(ctr(0x12)); s.push_back(ctr(0x13));
    if (color_only) {
        // color 只到 COMMIT IF1 即截断，立刻读 ep0x81（工作 trace 中首帧色彩在 IF2 commit 前）
        s.push_back(vs(0x0200, 0x0001, if1_neg));
        return s;
    }
    // --- PROBE IF2 ---
    s.push_back(vs(0x0100, 0x0002, if2_zero));
    s.push_back(vs(0x0100, 0x0002, if2_zero));
    s.push_back(vs(0x0100, 0x0002, if2_neg));
    s.push_back(vs(0x0100, 0x0002, if2_neg));
    // --- COMMIT IF1 + IF2 ---
    s.push_back(vs(0x0200, 0x0001, if1_neg));
    s.push_back(vs(0x0200, 0x0002, if2_neg));
    // --- 后 XU：启动触发 0xf5=0 + 计数器 ---
    s.push_back(xu({0x20,0xf5,0x00,0x00}));
    s.push_back(ctr(0x14)); s.push_back(ctr(0x15)); s.push_back(ctr(0x16));
    return s;
}

// 单条流的异步取流状态：URB 回调只 memcpy 进 capture（µs 级），不解析；组装离线做。
struct StreamState {
    uint8_t ep = 0;
    int vs_iface = 0, frame_bytes = 0, max_payload = 0;
    std::vector<uint8_t> capture;
    std::vector<int> chunk_lens;
    size_t cap_off = 0, cap_cap = 0;
    int64_t chunks = 0, bytes = 0, errs = 0;
    bool stop = false;
    int inflight = 0;
    std::vector<libusb_transfer*> urbs;
    std::vector<std::vector<uint8_t>> bufs;
};

void LIBUSB_CALL stream_cb(libusb_transfer* t) {
    auto* s = static_cast<StreamState*>(t->user_data);
    if (t->status == LIBUSB_TRANSFER_COMPLETED && t->actual_length > 0) {
        ++s->chunks;
        s->bytes += t->actual_length;
        const size_t n = static_cast<size_t>(t->actual_length);
        if (s->cap_off + n <= s->cap_cap) {
            memcpy(s->capture.data() + s->cap_off, t->buffer, n);
            s->cap_off += n;
            s->chunk_lens.push_back(t->actual_length);
        } else {
            s->stop = true;
        }
    } else if (t->status != LIBUSB_TRANSFER_COMPLETED && t->status != LIBUSB_TRANSFER_TIMED_OUT) {
        ++s->errs;
    }
    if (!s->stop) {
        if (libusb_submit_transfer(t) != 0) --s->inflight;
    } else {
        --s->inflight;
    }
}

// Etron UVC-over-BULK 拆帧：每 payload 带 12B 标准 UVC 头(bHeaderLength,bmHeaderInfo)，
// 按 FID(bit0) 翻转切帧（非 Berxel 那种按 size 切）。剥头累积，每真帧恰好 frame_bytes。
int assemble_and_save(StreamState& s, int secs, const std::string& dump) {
    if (std::getenv("EYS3D_DUMPRAW")) {  // 落原始 capture + chunk 长度，离线分析 payload 结构
        std::ofstream r(dump + "/raw_if" + std::to_string(s.vs_iface) + ".bin", std::ios::binary);
        r.write(reinterpret_cast<const char*>(s.capture.data()),
                static_cast<std::streamsize>(s.cap_off));
        std::ofstream l(dump + "/lens_if" + std::to_string(s.vs_iface) + ".txt");
        for (int x : s.chunk_lens) l << x << "\n";
        printf("  [dump] raw_if%d.bin %zuB + lens_if%d.txt %zu chunks\n", s.vs_iface, s.cap_off,
               s.vs_iface, s.chunk_lens.size());
    }
    int frames = 0, saved = 0, good = 0;
    int prev_fid = -1;
    std::vector<uint8_t> cur;
    cur.reserve(static_cast<size_t>(s.frame_bytes) * 2);
    auto emit = [&]() {
        if (cur.empty()) return;
        ++frames;
        const bool ok = static_cast<int>(cur.size()) == s.frame_bytes;
        if (ok) ++good;
        if (ok && saved < 3) {
            const std::string p = dump + "/replay_if" + std::to_string(s.vs_iface) + "_" +
                                  std::to_string(saved) + ".bin";
            std::ofstream o(p, std::ios::binary);
            o.write(reinterpret_cast<const char*>(cur.data()),
                    static_cast<std::streamsize>(cur.size()));
            ++saved;
        }
        cur.clear();
    };
    size_t off = 0;
    for (int len : s.chunk_lens) {
        const uint8_t* p = s.capture.data() + off;
        off += static_cast<size_t>(len);
        if (len < 2) continue;
        int hl = p[0];
        if (hl < 2 || hl > len) hl = (len >= 12) ? 12 : len;  // bHeaderLength，兜底
        const int fid = p[1] & 1;
        if (prev_fid >= 0 && fid != prev_fid) emit();  // FID 翻转 = 帧边界
        prev_fid = fid;
        if (len > hl) cur.insert(cur.end(), p + hl, p + len);  // 剥头累积
    }
    emit();
    printf("  [ep0x%02x if%d] chunks=%lld bytes=%.2fMB(%.1fMB/s) FID切帧=%d 完整=%d(%dB) errs=%lld → %s\n",
           s.ep, s.vs_iface, (long long)s.chunks, s.bytes / 1048576.0,
           secs ? s.bytes / 1048576.0 / secs : 0.0, frames, good, s.frame_bytes,
           (long long)s.errs, good > 0 ? "✅出流" : "❌无帧");
    return good;
}

}  // namespace

int main(int argc, char** argv) {
    // dual 模式：argv[1]=="dual" → 同时取 color(IF1/0x81) + depth(IF2/0x82)，secs 见 argv[2]
    const bool dual = argc > 1 && strcmp(argv[1], "dual") == 0;
    uint8_t ep = argc > 1 && !dual ? static_cast<uint8_t>(strtol(argv[1], nullptr, 16)) : 0x81;
    int vs_iface = argc > 2 ? atoi(argv[2]) : 1;
    int max_payload = argc > 3 ? atoi(argv[3]) : 2048;
    int frame_bytes = argc > 4 ? atoi(argv[4]) : 1228800;
    int secs = dual ? (argc > 2 ? atoi(argv[2]) : 6) : (argc > 5 ? atoi(argv[5]) : 4);
    std::string dump = dual ? (argc > 3 ? argv[3] : ".dev/eys3d-probe")
                            : (argc > 6 ? argv[6] : ".dev/eys3d-probe");
    std::filesystem::create_directories(dump);

    if (dual)
        printf("RS-D550 双流取流: color(IF1/0x81)+depth(IF2/0x82) secs=%d\n", secs);
    else
        printf("RS-D550 逐字复刻取流: ep=0x%02x iface=%d maxPayload=%d frame=%d secs=%d\n",
               ep, vs_iface, max_payload, frame_bytes, secs);

    if (libusb_init(nullptr) != 0) { fprintf(stderr, "libusb_init 失败\n"); return 1; }
    libusb_device_handle* h = libusb_open_device_with_vid_pid(nullptr, kEtronVid, kRsD550Pid);
    if (!h) { fprintf(stderr, "打开 %04x:%04x 失败（在线？）\n", kEtronVid, kRsD550Pid);
              libusb_exit(nullptr); return 2; }
    // 会话开始先 USB reset 清掉上次未干净停流的残留状态（否则连续开关会卡 immediate-STALL）。
    if (!std::getenv("EYS3D_NORESET")) {
        int rr = libusb_reset_device(h);
        if (rr == LIBUSB_ERROR_NOT_FOUND) {  // 重枚举后需重开
            libusb_close(h);
            for (int i = 0; i < 20 && !h; ++i) {
                h = libusb_open_device_with_vid_pid(nullptr, kEtronVid, kRsD550Pid);
            }
            if (!h) { fprintf(stderr, "reset 后重开失败\n"); libusb_exit(nullptr); return 2; }
        }
        printf("USB reset rc=%d\n", rr);
    }

    {
        Eys3dUsbDevice dev(h);
        auto log = [](const std::string& s) { fprintf(stderr, "[replay] %s\n", s.c_str()); };
        // 占住 IF0(XU 通道) + IF1 + IF2，从 uvcvideo 抢过来
        if (!dev.claim(0)) { libusb_exit(nullptr); return 3; }
        dev.claim(1);
        dev.claim(2);

        printf("== 回放官方开流序列(SET_CUR) ==\n");
        const std::vector<XuPayload> seq = build_replay(!dual && vs_iface == 1);
        int ok = 0, stall = 0;
        for (size_t i = 0; i < seq.size(); ++i) {
            std::vector<uint8_t> d = seq[i].data;
            const int rc = dev.uvc_set_cur(seq[i].w_value, seq[i].w_index, d.data(),
                                           static_cast<uint16_t>(d.size()), 2000);
            if (rc < 0) {
                ++stall;
                fprintf(stderr, "[replay] #%zu wValue=0x%04x wIndex=0x%04x rc=%d %s (容忍继续)\n",
                        i, seq[i].w_value, seq[i].w_index, rc, libusb_error_name(rc));
            } else {
                ++ok;
            }
            // PROBE(0x0100) 后必须 GET_CUR 回读完成标准 UVC 协商握手，否则设备不 arm 流。
            if (seq[i].w_value == 0x0100) {
                std::vector<uint8_t> back(d.size());
                int gr = dev.uvc_get_cur(0x0100, seq[i].w_index, back.data(),
                                         static_cast<uint16_t>(back.size()), 2000);
                if (gr < 0)
                    fprintf(stderr, "[replay] GET_CUR(PROBE if%u) rc=%d %s\n", seq[i].w_index, gr,
                            libusb_error_name(gr));
            }
        }
        printf("回放完成: ok=%d stall=%d / %zu (COMMIT 后立刻读 ep0x%02x，不 clear_halt)\n",
               ok, stall, seq.size(), ep);
        (void)log;

        // ---- 组流：dual=color+depth 并发；否则单流 ----
        std::vector<std::unique_ptr<StreamState>> streams;
        auto add_stream = [&](uint8_t e, int ifc, int fb, int mp) {
            auto s = std::make_unique<StreamState>();
            s->ep = e; s->vs_iface = ifc; s->frame_bytes = fb; s->max_payload = mp;
            s->cap_cap = static_cast<size_t>(secs + 2) * 8u * 1024u * 1024u;
            s->capture.resize(s->cap_cap);
            streams.push_back(std::move(s));
        };
        if (dual) {
            add_stream(0x81, 1, 1228800, 2048);   // color/双目 1280×480 YUYV
            add_stream(0x82, 2, 1228800, 3072);   // depth 实测真帧=1228800B（非 maxVideoFrameSize）
        } else {
            add_stream(ep, vs_iface, frame_bytes, max_payload);
        }

        // 每条流 32 URB，URB=1 payload(maxPayloadTransferSize，512 对齐保留 USB 短包帧边界)。
        constexpr int kNumUrb = 32;
        for (auto& sp : streams) {
            StreamState& s = *sp;
            const int ulen = s.max_payload > 0 ? s.max_payload : 2048;
            s.urbs.resize(kNumUrb, nullptr);
            s.bufs.assign(kNumUrb, std::vector<uint8_t>(ulen));
            for (int i = 0; i < kNumUrb; ++i) {
                s.urbs[i] = libusb_alloc_transfer(0);
                libusb_fill_bulk_transfer(s.urbs[i], h, s.ep, s.bufs[i].data(), ulen, stream_cb,
                                          &s, 0 /*无超时*/);
                if (libusb_submit_transfer(s.urbs[i]) == 0) ++s.inflight;
            }
            printf("ep0x%02x: %d URB×%dB 挂起\n", s.ep, s.inflight, ulen);
        }

        // 单事件循环驱动所有流；任一流缓冲满或到时停止
        const int64_t deadline = now_ms() + secs * 1000;
        auto any_running = [&]() {
            for (auto& s : streams) if (!s->stop) return true;
            return false;
        };
        while (now_ms() < deadline && any_running()) {
            timeval tv{0, 50000};
            libusb_handle_events_timeout(nullptr, &tv);
        }
        for (auto& s : streams) s->stop = true;
        for (auto& s : streams) for (auto* t : s->urbs) libusb_cancel_transfer(t);
        for (int spin = 0; spin < 80; ++spin) {
            bool busy = false;
            for (auto& s : streams) if (s->inflight > 0) busy = true;
            if (!busy) break;
            timeval tv{0, 20000};
            libusb_handle_events_timeout(nullptr, &tv);
        }
        for (auto& s : streams) for (auto* t : s->urbs) libusb_free_transfer(t);

        printf("\n== 结果 ==\n");
        int total_frames = 0;
        for (auto& s : streams) total_frames += assemble_and_save(*s, secs, dump);
        printf("总帧=%d → %s\n", total_frames, total_frames > 0 ? "✅出流" : "❌否");
    }
    libusb_exit(nullptr);
    return 0;
}
