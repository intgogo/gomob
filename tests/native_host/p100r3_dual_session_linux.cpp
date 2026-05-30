// p100r3_dual_session_linux — Linux host 上 1:1 复现 Android BerxelNativeStack 的
// dual-session + master XU 5 keepalive + companion BULK sync_read 链路，目的：
// 排除 "Android USB BSP 杀掉 BULK 流" 跟 "我们协议复现实现有 bug" 两个可能。
//
// 设计：跟 core/native-bridge/src/main/kotlin/io/gomob/nativebridge/berxel/BerxelNativeStack.kt
// 字节对齐 — 所有 control transfer / endpoint / interface claim / 时序、payload 来源
// (master_xu5_init.json + companion init_sequence.json) 都跟 Android 完全一致。
// 唯一差别：Android 通过 libusb_wrap_sys_device(fd) 接管 Android UsbDeviceConnection，
// 这里直接 libusb_open_device_with_vid_pid，因为 Linux 直接挂内核驱动可以 detach。
//
// 用法：
//   sudo ./p100r3_dual_session_linux \
//     --ka-ms 50 --dur-ms 8000 --master-n 20 \
//     --master-payloads core/native-bridge/src/main/assets/berxel/iHawkP100R3_master_xu5_init.json \
//     --companion-init  core/native-bridge/src/main/assets/berxel/iHawkP100R3_init_sequence.json
//
// 关键判定：
// - "stack OK + ≥10s 持续 BULK 字节"   = 我们的协议 OK，问题是 Android BSP
// - "stack OK + ~100ms 内死"             = 协议正确但 firmware / 我们 keepalive 还是不对
// - "stack 起不来 / probe-commit 失败"   = 我们协议字节序列错

#include <libusb-1.0/libusb.h>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <regex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

// ── 跟 BerxelNativeStack 一致的硬编码常量 ──────────────────────────────────
constexpr uint16_t kMasterVid          = 0x0603;
constexpr uint16_t kMasterPid          = 0x001f;
constexpr uint16_t kCompanionVid       = 0x3558;
constexpr uint16_t kCompanionPid       = 0x1012;
constexpr int      kMasterVcIface      = 0;        // master XU 5 在 iface 0
constexpr int      kCompanionVcIface   = 0;        // companion XU 协议在 iface 0
constexpr int      kCompanionVsIface   = 1;        // companion BULK 在 iface 1
constexpr uint16_t kMasterXu5WIndex    = 0x0500;   // unit=5 iface=0
constexpr uint16_t kCompanionXuWIndex  = 0x0300;   // unit=3 iface=0
constexpr uint8_t  kCompanionBulkEp    = 0x82;
constexpr int      kBulkReadLen        = 16384;
constexpr int      kCompanionFormatIdx = 1;
constexpr int      kCompanionFrameIdx  = 2;
constexpr uint32_t kCompanionFrameInterval100ns = 0x3640E;  // ~45fps

struct Args {
    int      ka_ms        = 50;
    int      dur_ms       = 8000;
    int      master_n     = 20;
    std::string master_payloads = "core/native-bridge/src/main/assets/berxel/iHawkP100R3_master_xu5_init.json";
    std::string companion_init  = "core/native-bridge/src/main/assets/berxel/iHawkP100R3_init_sequence.json";
};

Args parse_args(int argc, char** argv) {
    Args a;
    for (int i = 1; i < argc; i++) {
        std::string k = argv[i];
        auto next = [&]() -> std::string { return (++i < argc) ? argv[i] : ""; };
        if      (k == "--ka-ms")            a.ka_ms = std::atoi(next().c_str());
        else if (k == "--dur-ms")           a.dur_ms = std::atoi(next().c_str());
        else if (k == "--master-n")         a.master_n = std::atoi(next().c_str());
        else if (k == "--master-payloads")  a.master_payloads = next();
        else if (k == "--companion-init")   a.companion_init = next();
        else if (k == "-h" || k == "--help") {
            std::cout << "usage: " << argv[0]
                      << " [--ka-ms N] [--dur-ms N] [--master-n N]"
                      << " [--master-payloads PATH] [--companion-init PATH]\n";
            std::exit(0);
        }
    }
    return a;
}

// ── 简易 JSON 抽取：只要 "data_hex":"..." 字符串值，按出现顺序返 ──────────
std::vector<std::vector<uint8_t>> load_hex_array(const std::string& path, int limit = -1) {
    std::ifstream f(path);
    if (!f) { std::cerr << "无法打开 " << path << "\n"; return {}; }
    std::stringstream ss; ss << f.rdbuf();
    std::string s = ss.str();
    std::regex re(R"X("data_hex"\s*:\s*"([0-9a-fA-F]+)")X");
    std::vector<std::vector<uint8_t>> out;
    auto begin = std::sregex_iterator(s.begin(), s.end(), re);
    auto end = std::sregex_iterator();
    for (auto it = begin; it != end; ++it) {
        if (limit > 0 && (int)out.size() >= limit) break;
        const std::string& hex = (*it)[1].str();
        std::vector<uint8_t> b(hex.size() / 2);
        for (size_t i = 0; i < b.size(); i++) {
            unsigned v;
            std::sscanf(hex.data() + i * 2, "%2x", &v);
            b[i] = (uint8_t)v;
        }
        out.push_back(std::move(b));
    }
    return out;
}

// ── 从 companion init JSON 拿 selector 字段（同 data_hex 顺序对齐） ──────
std::vector<int> load_selectors(const std::string& path) {
    std::ifstream f(path);
    std::stringstream ss; ss << f.rdbuf();
    std::string s = ss.str();
    std::regex re(R"X("selector"\s*:\s*(\d+))X");
    std::vector<int> out;
    for (auto it = std::sregex_iterator(s.begin(), s.end(), re);
         it != std::sregex_iterator(); ++it) {
        out.push_back(std::atoi((*it)[1].str().c_str()));
    }
    return out;
}

// ── libusb wrap ───────────────────────────────────────────────────────────
int ctrl_out(libusb_device_handle* h, uint16_t wValue, uint16_t wIndex,
             uint8_t* data, uint16_t wLen, uint32_t timeout = 2000) {
    return libusb_control_transfer(h, 0x21, 0x01, wValue, wIndex, data, wLen, timeout);
}

int ctrl_in(libusb_device_handle* h, uint16_t wValue, uint16_t wIndex,
            uint8_t* data, uint16_t wLen, uint32_t timeout = 2000) {
    return libusb_control_transfer(h, 0xa1, 0x81, wValue, wIndex, data, wLen, timeout);
}

bool claim(libusb_device_handle* h, int iface) {
    libusb_set_auto_detach_kernel_driver(h, 1);
    int rc = libusb_kernel_driver_active(h, iface);
    if (rc == 1) {
        rc = libusb_detach_kernel_driver(h, iface);
        if (rc != 0) std::cerr << "  detach iface " << iface << " rc=" << rc << "\n";
    }
    rc = libusb_claim_interface(h, iface);
    if (rc != 0) {
        std::cerr << "  claim iface " << iface << " rc=" << rc
                  << " " << libusb_error_name(rc) << "\n";
        return false;
    }
    return true;
}

void release(libusb_device_handle* h, int iface) {
    libusb_release_interface(h, iface);
}

int64_t now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// ── 主流程 ─────────────────────────────────────────────────────────────────
struct Stats {
    std::atomic<int64_t> ka_count{0};
    std::atomic<int64_t> data_reads{0};
    std::atomic<int64_t> data_bytes{0};
    std::atomic<int>     first_err{0};
    std::atomic<int>     err_count{0};
};

void log(const std::string& msg) {
    auto t = now_ms();
    std::cerr << "[" << t / 1000 << "." << (t % 1000) << "] " << msg << "\n";
    std::cerr.flush();
}

bool replay_master_init(libusb_device_handle* h,
                        const std::vector<std::vector<uint8_t>>& payloads) {
    for (size_t i = 0; i < payloads.size(); i++) {
        auto p = payloads[i];  // 拷贝因为 libusb 可能写
        int rc = ctrl_out(h, 0x0100, kMasterXu5WIndex, p.data(), p.size());
        if (rc < 0) {
            log("master init#" + std::to_string(i) + " SET_CUR rc=" + std::to_string(rc) +
                " " + libusb_error_name(rc));
            return false;
        }
        std::vector<uint8_t> buf(p.size());
        ctrl_in(h, 0x0100, kMasterXu5WIndex, buf.data(), buf.size());  // ignore rc
    }
    log("master init " + std::to_string(payloads.size()) + " payloads done");
    return true;
}

void keepalive_thread(libusb_device_handle* h, std::vector<uint8_t> seed,
                      int interval_ms, std::atomic<bool>* running, Stats* stats) {
    uint32_t cnt = (uint32_t)seed[10] | ((uint32_t)seed[11] << 8) |
                   ((uint32_t)seed[12] << 16) | ((uint32_t)seed[13] << 24);
    while (running->load()) {
        cnt += 0x36;
        seed[10] = (uint8_t)(cnt);
        seed[11] = (uint8_t)(cnt >> 8);
        seed[12] = (uint8_t)(cnt >> 16);
        seed[13] = (uint8_t)(cnt >> 24);
        ctrl_out(h, 0x0100, kMasterXu5WIndex, seed.data(), seed.size(), 500);
        std::vector<uint8_t> buf(seed.size());
        ctrl_in(h, 0x0100, kMasterXu5WIndex, buf.data(), buf.size(), 500);
        stats->ka_count++;
        std::this_thread::sleep_for(std::chrono::milliseconds(interval_ms));
    }
}

bool replay_companion_init(libusb_device_handle* h,
                           const std::vector<std::vector<uint8_t>>& payloads,
                           const std::vector<int>& selectors) {
    if (payloads.size() != selectors.size()) {
        log("companion init: payload/selector count mismatch");
        return false;
    }
    for (size_t i = 0; i < payloads.size(); i++) {
        auto p = payloads[i];  // 拷贝
        uint16_t wValue = (uint16_t)selectors[i] << 8;
        int rc = ctrl_out(h, wValue, kCompanionXuWIndex, p.data(), p.size());
        if (rc < 0) {
            log("companion init#" + std::to_string(i) + " sel=" + std::to_string(selectors[i]) +
                " SET_CUR rc=" + std::to_string(rc) + " " + libusb_error_name(rc));
            return false;
        }
        std::vector<uint8_t> buf(p.size());
        ctrl_in(h, wValue, kCompanionXuWIndex, buf.data(), buf.size());
    }
    log("companion init " + std::to_string(payloads.size()) + " sequences done");
    return true;
}

bool negotiate_uvc(libusb_device_handle* h) {
    uint8_t ctrl[26] = {0};
    ctrl[0] = 0x01; ctrl[1] = 0x00;
    ctrl[2] = (uint8_t)kCompanionFormatIdx;
    ctrl[3] = (uint8_t)kCompanionFrameIdx;
    uint32_t ivl = kCompanionFrameInterval100ns;
    ctrl[4] = (uint8_t)(ivl);
    ctrl[5] = (uint8_t)(ivl >> 8);
    ctrl[6] = (uint8_t)(ivl >> 16);
    ctrl[7] = (uint8_t)(ivl >> 24);
    int rc;
    rc = ctrl_out(h, 0x0100, kCompanionVsIface, ctrl, 26);
    if (rc < 0) { log(std::string("UVC SET_PROBE rc=") + libusb_error_name(rc)); return false; }
    uint8_t got[26] = {0};
    rc = ctrl_in(h, 0x0100, kCompanionVsIface, got, 26);
    if (rc < 0) { log(std::string("UVC GET_PROBE rc=") + libusb_error_name(rc)); return false; }
    log("UVC probe negotiated: fmt=" + std::to_string(got[2]) +
        " frm=" + std::to_string(got[3]) +
        " dwMaxPayloadTransferSize=" +
        std::to_string((uint32_t)got[22] | ((uint32_t)got[23] << 8) |
                       ((uint32_t)got[24] << 16) | ((uint32_t)got[25] << 24)));
    rc = ctrl_out(h, 0x0200, kCompanionVsIface, got, 26);
    if (rc < 0) { log(std::string("UVC COMMIT rc=") + libusb_error_name(rc)); return false; }
    return true;
}

void bulk_pull_loop(libusb_device_handle* h, int dur_ms,
                    std::atomic<bool>* running, Stats* stats) {
    std::vector<uint8_t> buf(kBulkReadLen);
    int64_t deadline = now_ms() + dur_ms;
    int consecutive_err = 0;
    while (running->load() && now_ms() < deadline) {
        int actual = 0;
        int rc = libusb_bulk_transfer(h, kCompanionBulkEp, buf.data(),
                                       buf.size(), &actual, 200);
        if (rc == 0 && actual > 0) {
            stats->data_reads++;
            stats->data_bytes += actual;
            consecutive_err = 0;
        } else {
            if (stats->first_err.load() == 0 && rc != 0) stats->first_err.store(rc);
            stats->err_count++;
            consecutive_err++;
            if (consecutive_err >= 20) {
                log("bulk loop: 20 consecutive timeout/err rc=" +
                    std::string(libusb_error_name(rc)) + " — stream dead, exit");
                break;
            }
        }
    }
}

}  // namespace

int main(int argc, char** argv) {
    Args args = parse_args(argc, argv);
    std::cout << "params: kaMs=" << args.ka_ms
              << " durMs=" << args.dur_ms
              << " masterN=" << args.master_n
              << "\n  master_payloads=" << args.master_payloads
              << "\n  companion_init =" << args.companion_init << "\n";

    auto master_payloads = load_hex_array(args.master_payloads, args.master_n);
    if ((int)master_payloads.size() < 1) { std::cerr << "master payloads 加载失败\n"; return 1; }
    log("loaded " + std::to_string(master_payloads.size()) + " master payloads");

    auto companion_payloads = load_hex_array(args.companion_init);
    auto companion_selectors = load_selectors(args.companion_init);
    if (companion_payloads.empty()) { std::cerr << "companion init 加载失败\n"; return 1; }
    log("loaded " + std::to_string(companion_payloads.size()) + " companion init payloads");

    // libusb init
    if (libusb_init(nullptr) != 0) { std::cerr << "libusb_init 失败\n"; return 1; }

    // open master
    auto* master = libusb_open_device_with_vid_pid(nullptr, kMasterVid, kMasterPid);
    if (!master) { std::cerr << "master 0603:001f 未发现 — 检查相机插好没\n"; libusb_exit(nullptr); return 2; }
    log("master opened");
    if (!claim(master, kMasterVcIface)) { libusb_close(master); libusb_exit(nullptr); return 2; }
    log("master iface " + std::to_string(kMasterVcIface) + " claimed");

    // open companion
    auto* companion = libusb_open_device_with_vid_pid(nullptr, kCompanionVid, kCompanionPid);
    if (!companion) {
        std::cerr << "companion 3558:1012 未发现\n";
        release(master, kMasterVcIface); libusb_close(master); libusb_exit(nullptr); return 2;
    }
    log("companion opened");
    if (!claim(companion, kCompanionVcIface) || !claim(companion, kCompanionVsIface)) {
        release(companion, kCompanionVcIface); libusb_close(companion);
        release(master, kMasterVcIface);       libusb_close(master);
        libusb_exit(nullptr); return 2;
    }
    log("companion iface " + std::to_string(kCompanionVcIface) +
        " + " + std::to_string(kCompanionVsIface) + " claimed");

    Stats stats;

    // master init
    if (!replay_master_init(master, master_payloads)) {
        std::cerr << "master init seq 失败\n";
        return 3;
    }

    // keepalive thread
    std::atomic<bool> ka_running{true};
    std::thread ka_thread(keepalive_thread, master, master_payloads.back(),
                          args.ka_ms, &ka_running, &stats);
    log("keepalive thread started (interval=" + std::to_string(args.ka_ms) + "ms)");

    // warmup
    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    // companion init
    if (!replay_companion_init(companion, companion_payloads, companion_selectors)) {
        ka_running = false; ka_thread.join();
        return 4;
    }

    // UVC probe/commit
    if (!negotiate_uvc(companion)) {
        ka_running = false; ka_thread.join();
        return 5;
    }

    // BULK pull
    log("=== BULK pull loop start (dur=" + std::to_string(args.dur_ms) + "ms) ===");
    int64_t pull_start = now_ms();
    std::atomic<bool> pull_running{true};
    bulk_pull_loop(companion, args.dur_ms, &pull_running, &stats);
    int64_t pull_dur = now_ms() - pull_start;
    log("=== BULK pull loop end (actual=" + std::to_string(pull_dur) + "ms) ===");

    // stop
    ka_running = false;
    ka_thread.join();

    release(companion, kCompanionVsIface);
    release(companion, kCompanionVcIface);
    libusb_close(companion);
    release(master, kMasterVcIface);
    libusb_close(master);
    libusb_exit(nullptr);

    // summary
    std::cout << "\n=== RESULT ===\n"
              << "  ka_count    = " << stats.ka_count.load() << "\n"
              << "  data_reads  = " << stats.data_reads.load() << "\n"
              << "  data_bytes  = " << stats.data_bytes.load() << "\n"
              << "  first_err   = " << stats.first_err.load()
              << " (" << libusb_error_name(stats.first_err.load()) << ")\n"
              << "  err_count   = " << stats.err_count.load() << "\n"
              << "  pull_dur_ms = " << pull_dur << "\n";

    const auto bytes = stats.data_bytes.load();
    const auto dur = pull_dur;
    if (bytes >= 320 * 1024 && dur >= args.dur_ms - 500) {
        std::cout << "★ OK — 持续 " << dur << "ms 拿 " << bytes << " B → 协议正确\n";
        return 0;
    } else if (bytes >= 100 * 1024) {
        std::cout << "△ WARN — 拿 " << bytes << " B 但 " << dur << "ms 内挂\n";
        return 0;
    } else {
        std::cout << "✗ FAIL — 几乎没数据 (" << bytes << " B) — 检查协议 / firmware\n";
        return 6;
    }
}
