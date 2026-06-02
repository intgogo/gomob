// eYs3D / Etron RS-D550 自研 host 取流 probe（M6.2）。
// libusb 直驱 + 复用 native/berxel/portable 的通用 UVC 件（negotiate_uvc_stream /
// UvcRawFrameAssembler），不引入任何原厂 SDK。标准 UVC-over-BULK，无 keepalive。
//
// 目的：证明自研 libusb UVC 路径能从 RS-D550 取流，并 dump 帧用于判定
// IF2（0x82）到底是深度/视差还是 raw 第二目。
//
// 用法：eys3d_probe <vs_iface> <ep_hex> <format_index> <frame_index> <w> <h> <interval100ns> <secs> <dump_dir>
//   默认：IF1(0x81) YUY2(fmt1) 640x480(frame2) @10fps 3s → .dev/eys3d-probe/

#include "gomob_berxel_portable.h"  // 复用通用 UVC 件（命名空间 gomob::berxel::host）

#include <libusb-1.0/libusb.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>

using gomob::berxel::host::IUvcDevice;
using gomob::berxel::host::P100R3VideoMode;
using gomob::berxel::host::UvcNegotiation;
using gomob::berxel::host::UvcRawFrame;
using gomob::berxel::host::UvcRawFrameAssembler;
using gomob::berxel::host::UvcRawFrameAssemblerConfig;
using gomob::berxel::host::UvcStreamConfig;
using gomob::berxel::host::XuPayload;
using gomob::berxel::host::negotiate_uvc_stream;
using gomob::berxel::host::replay_xu_payloads;

namespace {

constexpr uint16_t kEtronVid = 0x3438;
constexpr uint16_t kRsD550Pid = 0x0206;

int64_t now_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}
int64_t now_ms() { return now_ns() / 1000000; }

std::string hex_bytes(const uint8_t* p, int n) {
    static const char* kHex = "0123456789abcdef";
    std::string s;
    for (int i = 0; i < n; ++i) {
        s += kHex[p[i] >> 4];
        s += kHex[p[i] & 0xf];
        s += ' ';
    }
    return s;
}

// libusb IUvcDevice 实现，镜像 native/berxel/host 的 UsbDevice：auto-detach + claim +
// 标准 UVC class control transfer + bulk_in。
class Eys3dUsbDevice : public IUvcDevice {
public:
    explicit Eys3dUsbDevice(libusb_device_handle* handle) : handle_(handle) {
        if (handle_) libusb_set_auto_detach_kernel_driver(handle_, 1);
    }
    ~Eys3dUsbDevice() override {
        for (int iface : claimed_) libusb_release_interface(handle_, iface);
        if (handle_) libusb_close(handle_);
    }

    bool claim(int iface) {
        if (!handle_) return false;
        libusb_set_auto_detach_kernel_driver(handle_, 1);
        if (libusb_kernel_driver_active(handle_, iface) == 1) {
            const int d = libusb_detach_kernel_driver(handle_, iface);
            if (d != 0 && d != LIBUSB_ERROR_NOT_SUPPORTED) {
                fprintf(stderr, "detach iface %d rc=%d %s\n", iface, d, libusb_error_name(d));
            }
        }
        const int rc = libusb_claim_interface(handle_, iface);
        if (rc != 0) {
            fprintf(stderr, "claim iface %d rc=%d %s\n", iface, rc, libusb_error_name(rc));
            return false;
        }
        claimed_.push_back(iface);
        return true;
    }

    int control_transfer(uint8_t t, uint8_t r, uint16_t v, uint16_t i, uint8_t* d, uint16_t l,
                         uint32_t to) override {
        if (!handle_) return LIBUSB_ERROR_NO_DEVICE;
        return libusb_control_transfer(handle_, t, r, v, i, d, l, to);
    }
    int uvc_set_cur(uint16_t v, uint16_t i, uint8_t* d, uint16_t l, uint32_t to) override {
        return control_transfer(0x21, 0x01, v, i, d, l, to);  // SET_CUR, class interface
    }
    int uvc_get_cur(uint16_t v, uint16_t i, uint8_t* d, uint16_t l, uint32_t to) override {
        return control_transfer(0xA1, 0x81, v, i, d, l, to);  // GET_CUR
    }
    int uvc_get_def(uint16_t v, uint16_t i, uint8_t* d, uint16_t l, uint32_t to) override {
        return control_transfer(0xA1, 0x87, v, i, d, l, to);  // GET_DEF
    }
    int bulk_in(uint8_t ep, uint8_t* d, int l, int* a, uint32_t to) override {
        if (!handle_) return LIBUSB_ERROR_NO_DEVICE;
        return libusb_bulk_transfer(handle_, ep, d, l, a, to);
    }

    // 标准 UVC：COMMIT 后用 SET_INTERFACE 选 alt 启动 streaming（很多规范 UVC 设备依赖此触发）。
    int set_alt(int iface, int alt) {
        return handle_ ? libusb_set_interface_alt_setting(handle_, iface, alt)
                       : LIBUSB_ERROR_NO_DEVICE;
    }
    int clear_halt(uint8_t ep) {
        return handle_ ? libusb_clear_halt(handle_, ep) : LIBUSB_ERROR_NO_DEVICE;
    }

private:
    libusb_device_handle* handle_ = nullptr;
    std::vector<int> claimed_;
};

// 把一帧 payload 当 uint16 LE 解，打印能区分「深度/视差 vs 图像」的统计。
void frame_stats(const UvcRawFrame& f, int w, int h) {
    const uint8_t* p = f.payload.data();
    const size_t n = f.payload.size();
    const size_t px = n / 2;
    uint16_t vmin = 0xffff, vmax = 0;
    uint64_t vsum = 0;
    bool lo_seen[256] = {false};
    int lo_distinct = 0;
    for (size_t i = 0; i + 1 < n; i += 2) {
        const uint16_t v = static_cast<uint16_t>(p[i] | (p[i + 1] << 8));
        vmin = std::min(vmin, v);
        vmax = std::max(vmax, v);
        vsum += v;
        if (!lo_seen[p[i]]) { lo_seen[p[i]] = true; ++lo_distinct; }
    }
    uint16_t center = 0;
    if (w > 0 && h > 0) {
        const size_t ci = (static_cast<size_t>(h / 2) * w + w / 2) * 2;
        if (ci + 1 < n) center = static_cast<uint16_t>(p[ci] | (p[ci + 1] << 8));
    }
    printf("    u16: min=%u max=%u mean=%.1f center=%u lowByteDistinct=%d/256  head=%s\n", vmin,
           vmax, px ? static_cast<double>(vsum) / px : 0.0, center, lo_distinct,
           hex_bytes(p, 24).c_str());
}

}  // namespace

int main(int argc, char** argv) {
    int vs_iface = argc > 1 ? std::atoi(argv[1]) : 1;
    uint8_t endpoint = argc > 2 ? static_cast<uint8_t>(std::strtol(argv[2], nullptr, 16)) : 0x81;
    uint8_t format_index = argc > 3 ? static_cast<uint8_t>(std::atoi(argv[3])) : 1;
    uint8_t frame_index = argc > 4 ? static_cast<uint8_t>(std::atoi(argv[4])) : 2;
    int w = argc > 5 ? std::atoi(argv[5]) : 640;
    int h = argc > 6 ? std::atoi(argv[6]) : 480;
    uint32_t interval = argc > 7 ? static_cast<uint32_t>(std::strtoul(argv[7], nullptr, 10)) : 1000000;
    int secs = argc > 8 ? std::atoi(argv[8]) : 3;
    std::string dump = argc > 9 ? argv[9] : ".dev/eys3d-probe";

    const int frame_size = w * h * 2;  // YUY2 16bpp
    std::filesystem::create_directories(dump);

    printf("eYs3D RS-D550 probe: IF%d ep=0x%02x fmt=%d frame=%d %dx%d interval=%u secs=%d\n",
           vs_iface, endpoint, format_index, frame_index, w, h, interval, secs);

    if (libusb_init(nullptr) != 0) {
        fprintf(stderr, "libusb_init 失败\n");
        return 1;
    }
    libusb_device_handle* handle = libusb_open_device_with_vid_pid(nullptr, kEtronVid, kRsD550Pid);
    if (!handle) {
        fprintf(stderr, "打开 %04x:%04x 失败（设备在线？权限？）\n", kEtronVid, kRsD550Pid);
        libusb_exit(nullptr);
        return 2;
    }

    {
        Eys3dUsbDevice dev(handle);
        auto log = [](const std::string& s) { fprintf(stderr, "[uvc] %s\n", s.c_str()); };

        // M6.3 开流激活（usbmon 抓 eYs3D 官方 test_x86 真实序列复刻）：claim IF0 拿 XU 通道，
        // 写 FW 寄存器 videoMode(0xF0)=4 + 0xE0=3 + 0xE3=0x63；可选 CT(0x0200/0x0100=01)。
        // EYS3D_ACTIVATE_MODE 给 0xF0 值（默认 4），EYS3D_CT_ENABLE 追加 CT 命令。
        dev.claim(0);  // IF0 承载 Etron XU（wIndex=0x0400）
        if (const char* m = std::getenv("EYS3D_ACTIVATE_MODE")) {
            const uint8_t vmode = static_cast<uint8_t>(std::strtol(m, nullptr, 0));
            std::vector<XuPayload> act = {
                XuPayload{3, 0x0300, 0x0400, {0x20, 0xF0, vmode, 0x00}},  // videoMode
                XuPayload{3, 0x0300, 0x0400, {0x20, 0xE0, 0x03, 0x00}},   // FW 0xE0=3
                XuPayload{3, 0x0300, 0x0400, {0x20, 0xE3, 0x63, 0x00}},   // FW 0xE3=0x63
            };
            if (std::getenv("EYS3D_CT_ENABLE")) act.push_back(XuPayload{2, 0x0200, 0x0100, {0x01}});
            replay_xu_payloads(dev, act, false, "eys3d-activate", log);
            printf("激活: videoMode(0xF0)=%u +0xE0=3 +0xE3=0x63%s\n", vmode,
                   std::getenv("EYS3D_CT_ENABLE") ? " +CT(0200/0100=01)" : "");
        } else {
            printf("（未设 EYS3D_ACTIVATE_MODE → 跳过激活）\n");
        }

        if (!dev.claim(vs_iface)) {
            libusb_exit(nullptr);
            return 3;
        }

        UvcStreamConfig cfg;
        cfg.name = "eys3d-if" + std::to_string(vs_iface);
        cfg.vs_interface = vs_iface;
        cfg.endpoint = endpoint;
        cfg.format_index = format_index;
        cfg.frame_index = frame_index;
        cfg.frame_interval_100ns = interval;
        UvcNegotiation neg;
        if (!negotiate_uvc_stream(dev, cfg, &neg, log)) {
            fprintf(stderr, "UVC probe/commit 失败\n");
            libusb_exit(nullptr);
            return 4;
        }
        printf("协商成功: fmt=%u frame=%u interval=%u maxFrame=%u maxPayload=%u\n", neg.format_index,
               neg.frame_index, neg.frame_interval_100ns, neg.max_video_frame_size,
               neg.max_payload_transfer_size);

        // COMMIT 后启动 streaming：SET_INTERFACE(alt0) + 清端点 halt。
        const int alt_rc = dev.set_alt(vs_iface, 0);
        printf("SET_INTERFACE(if%d,alt0) rc=%d %s\n", vs_iface, alt_rc,
               alt_rc ? libusb_error_name(alt_rc) : "OK");
        dev.clear_halt(endpoint);

        P100R3VideoMode mode;
        mode.frame_index = frame_index;
        mode.width = static_cast<uint16_t>(w);
        mode.height = static_cast<uint16_t>(h);
        mode.fps = interval ? static_cast<uint16_t>(10000000u / interval) : 0;
        mode.interval_100ns = interval;

        UvcRawFrameAssembler assembler(UvcRawFrameAssemblerConfig{
            endpoint, mode, static_cast<size_t>(frame_size), true,
            static_cast<size_t>(frame_size) * 3});

        std::vector<uint8_t> buf(16384);
        const int64_t deadline = now_ms() + secs * 1000;
        int64_t chunks = 0, total_bytes = 0, errs = 0, consec = 0;
        int saved = 0, frames = 0;
        int first_err = 0;

        while (now_ms() < deadline) {
            int actual = 0;
            const int rc = dev.bulk_in(endpoint, buf.data(), static_cast<int>(buf.size()), &actual, 200);
            if (rc == 0 && actual > 0) {
                consec = 0;
                ++chunks;
                total_bytes += actual;
                std::vector<UvcRawFrame> out;
                assembler.push_packet(buf.data(), actual, now_ns(), &out);
                for (const UvcRawFrame& f : out) {
                    ++frames;
                    if (saved < 5) {
                        const std::string path =
                            dump + "/if" + std::to_string(vs_iface) + "_frame_" +
                            std::to_string(saved) + ".bin";
                        std::ofstream o(path, std::ios::binary);
                        o.write(reinterpret_cast<const char*>(f.payload.data()),
                                static_cast<std::streamsize>(f.payload.size()));
                        printf("  frame#%d size=%zu (期望 %d) → %s\n", frames, f.payload.size(),
                               frame_size, path.c_str());
                        frame_stats(f, w, h);
                        ++saved;
                    }
                }
                continue;
            }
            if (rc != 0) {
                if (first_err == 0) first_err = rc;
                ++errs;
                if (++consec >= 40) {
                    fprintf(stderr, "连续 40 次 bulk 错误，停止 last=%s\n", libusb_error_name(rc));
                    break;
                }
            }
        }

        const auto st = assembler.stats();
        printf("\n== 结果 ==\n");
        printf("chunks=%lld bytes=%lld frames(assembled)=%lld drops=%lld errs=%lld firstErr=%d(%s)\n",
               static_cast<long long>(chunks), static_cast<long long>(total_bytes),
               static_cast<long long>(st.frames), static_cast<long long>(st.frame_drops),
               static_cast<long long>(errs), first_err,
               first_err ? libusb_error_name(first_err) : "-");
        printf("帧率≈ %.1f fps（%d 帧 / %d 秒）\n", secs ? static_cast<double>(frames) / secs : 0.0,
               frames, secs);
    }

    libusb_exit(nullptr);
    return 0;
}
