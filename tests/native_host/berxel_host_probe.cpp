#include "gomob_berxel_host_sdk.h"

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <filesystem>
#include <functional>
#include <iomanip>
#include <iostream>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <utility>

using gomob::berxel::host::BulkStats;
using gomob::berxel::host::LogFn;
using gomob::berxel::host::P100R3DepthControls;
using gomob::berxel::host::P100R3DepthProcessingConfig;
using gomob::berxel::host::P100R3DepthProcessingStats;
using gomob::berxel::host::P100R3VideoMode;
using gomob::berxel::host::P100R3DualSession;
using gomob::berxel::host::P100R3DualSessionCallbacks;
using gomob::berxel::host::P100R3DualSessionConfig;
using gomob::berxel::host::P100R3DualSessionStats;
using gomob::berxel::host::RgbdFramePairer;
using gomob::berxel::host::RgbdFramePairInfo;
using gomob::berxel::host::RgbdPairingStats;
using gomob::berxel::host::UvcNegotiation;
using gomob::berxel::host::UvcFrameInfo;
using gomob::berxel::host::UvcStreamConfig;
using gomob::berxel::host::UsbContext;
using gomob::berxel::host::UsbDevice;
using gomob::berxel::host::XuPayload;

namespace {

constexpr const char* kDepthMasterPayloads =
    "core/native-bridge/src/main/assets/berxel/iHawkP100R3_master_xu5_init.json";
constexpr const char* kColorMasterPayloads =
    "native/berxel/host/assets/iHawkP100R3_color_master_xu5_init.json";

struct Args {
    bool list = false;
    bool reset = false;
    bool stop_only = false;
    bool send_master_stop = true;
    bool depth = false;
    bool color = false;
    bool light_ir = false;
    bool color_first = false;
    bool session_api = false;
    bool fresh_time_sync = true;
    int dur_ms = 3000;
    int ka_ms = 50;
    int master_limit = 20;
    int master_stop_stream = -1;
    int companion_warmup = 0;
    int color_format = 1;
    int color_frame = 3;
    int depth_frame = 2;
    int save_depth_frames = 0;
    int save_depth_skip = 0;
    bool no_depth_controls = false;
    bool depth_controls_vendor = false;
    bool depth_controls = false;
    int depth_ae = -1;
    int depth_confidence = -1;
    int depth_gain = -1;
    int depth_temporal_denoise = -1;
    int depth_spatial_denoise = -1;
    uint32_t color_interval_100ns = 333333;
    uint32_t depth_interval_100ns = 0x3640e;
    std::string master_payloads;
    std::string keepalive_payloads = kDepthMasterPayloads;
    std::string companion_init =
        "core/native-bridge/src/main/assets/berxel/iHawkP100R3_init_sequence.json";
    std::string out_dir = ".dev/berxel-host-sdk";
};

int64_t wall_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}

void log(const std::string& msg) {
    const int64_t t = wall_ms();
    std::cerr << "[" << (t / 1000) << "." << std::setw(3) << std::setfill('0')
              << (t % 1000) << std::setfill(' ') << "] " << msg << "\n";
}

std::string hex4(uint16_t v) {
    std::ostringstream ss;
    ss << std::hex << std::setw(4) << std::setfill('0') << v;
    return ss.str();
}

uint32_t fps_to_interval_100ns(int fps) {
    if (fps <= 0) return 0;
    return static_cast<uint32_t>(10000000u / static_cast<uint32_t>(fps));
}

uint16_t interval_to_fps(uint32_t interval_100ns) {
    if (interval_100ns == 0) return 0;
    return static_cast<uint16_t>((10000000u + interval_100ns / 2u) / interval_100ns);
}

void usage(const char* argv0) {
    std::cout
        << "usage: " << argv0 << " [--list] [--depth] [--color] [--dual]\n"
        << "       [--reset] [--stop-only] [--no-master-stop] [--master-stop-stream N]\n"
        << "       [--dur-ms N] [--ka-ms N] [--master-limit N|--master-all]\n"
        << "       [--companion-warmup N] [--no-fresh-time-sync]\n"
        << "       [--color-first] [--session-api]\n"
        << "       [--light-ir]\n"
        << "       [--color-format N] [--color-frame N] [--color-fps N] [--color-interval N]\n"
        << "       [--depth-frame N] [--depth-fps N] [--depth-interval N]\n"
        << "       [--save-depth-frames N] [--save-depth-skip N]\n"
        << "       [--no-depth-controls] [--depth-controls-vendor] [--depth-ae 0|1]\n"
        << "       [--depth-confidence N] [--depth-gain N]\n"
        << "       [--depth-temporal-denoise 0|1] [--depth-spatial-denoise 0|1]\n"
        << "       [--master-payloads PATH] [--keepalive-payloads PATH]\n"
        << "       [--companion-init PATH] [--out-dir DIR]\n\n"
        << "默认只枚举设备；拉流必须显式加 --depth / --color / --dual。\n";
}

Args parse_args(int argc, char** argv) {
    Args args;
    for (int i = 1; i < argc; ++i) {
        const std::string key = argv[i];
        auto next = [&]() -> std::string {
            if (++i >= argc) return {};
            return argv[i];
        };
        if (key == "--list") {
            args.list = true;
        } else if (key == "--reset") {
            args.reset = true;
        } else if (key == "--stop-only") {
            args.stop_only = true;
        } else if (key == "--no-master-stop") {
            args.send_master_stop = false;
        } else if (key == "--master-stop-stream") {
            args.master_stop_stream = std::atoi(next().c_str());
        } else if (key == "--depth") {
            args.depth = true;
        } else if (key == "--light-ir") {
            args.light_ir = true;
            args.depth = true;
            args.depth_frame = 1;
            args.depth_interval_100ns = 222222;
        } else if (key == "--color") {
            args.color = true;
        } else if (key == "--dual") {
            args.depth = true;
            args.color = true;
        } else if (key == "--color-first") {
            args.color_first = true;
        } else if (key == "--session-api") {
            args.session_api = true;
        } else if (key == "--no-fresh-time-sync") {
            args.fresh_time_sync = false;
        } else if (key == "--dur-ms") {
            args.dur_ms = std::atoi(next().c_str());
        } else if (key == "--ka-ms") {
            args.ka_ms = std::atoi(next().c_str());
        } else if (key == "--master-limit") {
            args.master_limit = std::atoi(next().c_str());
        } else if (key == "--master-all") {
            args.master_limit = 0;
        } else if (key == "--companion-warmup") {
            args.companion_warmup = std::atoi(next().c_str());
        } else if (key == "--color-format") {
            args.color_format = std::atoi(next().c_str());
        } else if (key == "--color-frame") {
            args.color_frame = std::atoi(next().c_str());
        } else if (key == "--color-fps") {
            args.color_interval_100ns = fps_to_interval_100ns(std::atoi(next().c_str()));
        } else if (key == "--color-interval") {
            args.color_interval_100ns = static_cast<uint32_t>(std::strtoul(next().c_str(), nullptr, 0));
        } else if (key == "--depth-frame") {
            args.depth_frame = std::atoi(next().c_str());
        } else if (key == "--save-depth-frames") {
            args.save_depth_frames = std::atoi(next().c_str());
        } else if (key == "--save-depth-skip") {
            args.save_depth_skip = std::atoi(next().c_str());
        } else if (key == "--depth-fps") {
            args.depth_interval_100ns = fps_to_interval_100ns(std::atoi(next().c_str()));
        } else if (key == "--depth-interval") {
            args.depth_interval_100ns = static_cast<uint32_t>(std::strtoul(next().c_str(), nullptr, 0));
        } else if (key == "--no-depth-controls") {
            args.no_depth_controls = true;
            args.depth_controls = false;
        } else if (key == "--depth-controls-vendor") {
            args.depth_controls_vendor = true;
            args.depth_controls = true;
        } else if (key == "--depth-controls") {
            args.depth_controls = true;
        } else if (key == "--depth-ae") {
            args.depth_ae = std::atoi(next().c_str());
            args.depth_controls = true;
        } else if (key == "--depth-confidence") {
            args.depth_confidence = std::atoi(next().c_str());
            args.depth_controls = true;
        } else if (key == "--depth-gain") {
            args.depth_gain = std::atoi(next().c_str());
            args.depth_controls = true;
        } else if (key == "--depth-temporal-denoise") {
            args.depth_temporal_denoise = std::atoi(next().c_str());
            args.depth_controls = true;
        } else if (key == "--depth-spatial-denoise") {
            args.depth_spatial_denoise = std::atoi(next().c_str());
            args.depth_controls = true;
        } else if (key == "--master-payloads") {
            args.master_payloads = next();
        } else if (key == "--keepalive-payloads") {
            args.keepalive_payloads = next();
        } else if (key == "--companion-init") {
            args.companion_init = next();
        } else if (key == "--out-dir") {
            args.out_dir = next();
        } else if (key == "-h" || key == "--help") {
            usage(argv[0]);
            std::exit(0);
        } else {
            std::cerr << "unknown arg: " << key << "\n";
            usage(argv[0]);
            std::exit(2);
        }
    }
    if (!args.list && !args.reset && !args.stop_only && !args.depth && !args.color) args.list = true;
    if (args.master_payloads.empty()) {
        args.master_payloads = args.color && !args.depth ? kColorMasterPayloads : kDepthMasterPayloads;
    }
    args.save_depth_frames = std::max(0, args.save_depth_frames);
    args.save_depth_skip = std::max(0, args.save_depth_skip);
    return args;
}

bool is_known_berxel(uint16_t vid, uint16_t pid) {
    return (vid == gomob::berxel::host::kP100R3MasterId.vid &&
            pid == gomob::berxel::host::kP100R3MasterId.pid) ||
           (vid == gomob::berxel::host::kP100R3CompanionId.vid &&
            pid == gomob::berxel::host::kP100R3CompanionId.pid);
}

void print_devices(const UsbContext& ctx) {
    const auto devices = ctx.list_devices();
    std::cout << "USB devices:\n";
    for (const auto& d : devices) {
        const bool known = is_known_berxel(d.id.vid, d.id.pid);
        std::cout << "  Bus " << std::setw(3) << std::setfill('0') << static_cast<int>(d.bus)
                  << " Dev " << std::setw(3) << static_cast<int>(d.address)
                  << std::setfill(' ') << "  "
                  << hex4(d.id.vid) << ":" << hex4(d.id.pid)
                  << (known ? "  [Berxel P100R3]" : "")
                  << "  " << d.manufacturer << " " << d.product;
        if (!d.serial.empty()) std::cout << "  SN=" << d.serial;
        std::cout << "\n";
    }
}

void print_stats(const std::string& name, const BulkStats& s) {
    std::cout << name << ":\n"
              << "  chunks      = " << s.chunks << "\n"
              << "  bytes       = " << s.bytes << "\n"
              << "  payload     = " << s.payload_bytes << "\n"
              << "  frames      = " << s.frames << "\n"
              << "  frame_drops = " << s.frame_drops << "\n"
              << "  uvc_headers = " << s.uvc_headers << "\n"
              << "  fid_toggles = " << s.fid_toggles << "\n"
              << "  by_eof      = " << s.completed_by_eof << "\n"
              << "  by_size     = " << s.completed_by_size << "\n"
              << "  by_fid      = " << s.completed_by_fid << "\n"
              << "  by_eoi      = " << s.completed_by_jpeg_eoi << "\n"
              << "  partial_drop= " << s.partial_frame_drops << "\n"
              << "  over_drop   = " << s.oversized_frame_drops << "\n"
              << "  timeouts    = " << s.timeouts << "\n"
              << "  errors      = " << s.errors << "\n"
              << "  first_error = " << s.first_error << " ("
              << gomob::berxel::host::usb_error_name(s.first_error) << ")\n"
              << "  duration_ms = " << s.duration_ms << "\n";
}

std::string out_path(const Args& args, const std::string& leaf) {
    return (std::filesystem::path(args.out_dir) / leaf).string();
}

std::string indexed_leaf(const std::string& prefix, int index, const std::string& suffix) {
    std::ostringstream ss;
    ss << prefix << '-' << std::setw(3) << std::setfill('0') << index << suffix;
    return ss.str();
}

bool write_binary_file_local(const std::string& path, const uint8_t* data, size_t size) {
    if (path.empty() || !data || size == 0) return false;
    const auto parent = std::filesystem::path(path).parent_path();
    if (!parent.empty()) std::filesystem::create_directories(parent);
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    out.write(reinterpret_cast<const char*>(data), static_cast<std::streamsize>(size));
    return static_cast<bool>(out);
}

uint32_t count_nonzero_u16(const uint8_t* data, size_t size) {
    if (!data || size < 2) return 0;
    uint32_t count = 0;
    const size_t pixels = size / 2;
    for (size_t i = 0; i < pixels; ++i) {
        const uint16_t v = static_cast<uint16_t>(data[i * 2]) |
                           static_cast<uint16_t>(static_cast<uint16_t>(data[i * 2 + 1]) << 8);
        if (v != 0) count++;
    }
    return count;
}

bool write_u16_pgm_local(const std::string& path,
                         const uint8_t* data,
                         size_t size,
                         const P100R3VideoMode& mode) {
    if (path.empty() || !data || size < 2 || mode.width == 0 || mode.height == 0) return false;
    const uint16_t active_height = gomob::berxel::host::p100r3_depth_active_height(mode);
    const size_t pixels = static_cast<size_t>(mode.width) * active_height;
    if (size < pixels * 2) return false;
    std::vector<uint16_t> values(pixels);
    for (size_t i = 0; i < pixels; ++i) {
        values[i] = static_cast<uint16_t>(data[i * 2]) |
                    static_cast<uint16_t>(static_cast<uint16_t>(data[i * 2 + 1]) << 8);
    }
    std::vector<uint16_t> nonzero;
    nonzero.reserve(values.size());
    for (uint16_t v : values) {
        if (v != 0) nonzero.push_back(v);
    }
    if (nonzero.empty()) return false;
    std::sort(nonzero.begin(), nonzero.end());
    auto quantile = [&](double q) -> uint16_t {
        const size_t index = std::min(nonzero.size() - 1,
                                      static_cast<size_t>(q * static_cast<double>(nonzero.size() - 1)));
        return nonzero[index];
    };
    uint16_t low = quantile(0.01);
    uint16_t high = quantile(0.99);
    if (high <= low) high = static_cast<uint16_t>(low + 1);

    const auto parent = std::filesystem::path(path).parent_path();
    if (!parent.empty()) std::filesystem::create_directories(parent);
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    out << "P5\n" << mode.width << " " << active_height << "\n255\n";
    for (uint16_t v : values) {
        int gray = 0;
        if (v > low) {
            gray = static_cast<int>(
                (static_cast<uint32_t>(std::min<uint16_t>(v, high) - low) * 255u) /
                static_cast<uint32_t>(high - low));
        }
        const uint8_t byte = static_cast<uint8_t>(std::clamp(gray, 0, 255));
        out.write(reinterpret_cast<const char*>(&byte), 1);
    }
    return static_cast<bool>(out);
}

class FrameCsv {
public:
    explicit FrameCsv(const std::string& path) {
        if (path.empty()) return;
        const auto parent = std::filesystem::path(path).parent_path();
        if (!parent.empty()) std::filesystem::create_directories(parent);
        out_.open(path);
        if (out_) {
            out_ << "stream,frame,host_start_ns,host_end_ns,endpoint,width,height,fps,"
                 << "payload_bytes,transport_bytes,has_uvc_pts,uvc_pts,"
                 << "has_uvc_scr,uvc_scr_stc,uvc_scr_sof,fid,completed_by_eof,"
                 << "completed_by_size,completed_by_fid,completed_by_jpeg_eoi\n";
        }
    }

    void write(const std::string& stream, const UvcFrameInfo& info) {
        std::lock_guard<std::mutex> lock(mu_);
        if (!out_) return;
        out_ << stream << ','
             << info.frame_number << ','
             << info.host_start_ns << ','
             << info.host_end_ns << ','
             << static_cast<int>(info.endpoint) << ','
             << info.mode.width << ','
             << info.mode.height << ','
             << info.mode.fps << ','
             << info.payload_bytes << ','
             << info.transport_bytes << ','
             << (info.has_uvc_pts ? 1 : 0) << ','
             << info.uvc_pts << ','
             << (info.has_uvc_scr ? 1 : 0) << ','
             << info.uvc_scr_stc << ','
             << info.uvc_scr_sof << ','
             << static_cast<int>(info.fid) << ','
             << (info.completed_by_eof ? 1 : 0) << ','
             << (info.completed_by_size ? 1 : 0) << ','
             << (info.completed_by_fid ? 1 : 0) << ','
             << (info.completed_by_jpeg_eoi ? 1 : 0) << '\n';
    }

private:
    std::mutex mu_;
    std::ofstream out_;
};

class PairCsv {
public:
    explicit PairCsv(const std::string& path) {
        if (path.empty()) return;
        const auto parent = std::filesystem::path(path).parent_path();
        if (!parent.empty()) std::filesystem::create_directories(parent);
        out_.open(path);
        if (out_) {
            out_ << "pair,color_frame,depth_frame,color_start_ns,color_end_ns,"
                 << "depth_start_ns,depth_end_ns,color_mid_ns,depth_mid_ns,delta_ns,delta_ms,"
                 << "within_tolerance,color_has_pts,depth_has_pts,color_pts,depth_pts,"
                 << "color_has_scr,depth_has_scr,color_scr_stc,depth_scr_stc,"
                 << "color_scr_sof,depth_scr_sof,color_fid,depth_fid,"
                 << "color_payload_bytes,depth_payload_bytes\n";
        }
    }

    void write(const RgbdFramePairInfo& pair) {
        std::lock_guard<std::mutex> lock(mu_);
        if (!out_) return;
        const int64_t color_mid = gomob::berxel::host::uvc_frame_midpoint_ns(pair.color);
        const int64_t depth_mid = gomob::berxel::host::uvc_frame_midpoint_ns(pair.depth);
        out_ << pair.pair_number << ','
             << pair.color.frame_number << ','
             << pair.depth.frame_number << ','
             << pair.color.host_start_ns << ','
             << pair.color.host_end_ns << ','
             << pair.depth.host_start_ns << ','
             << pair.depth.host_end_ns << ','
             << color_mid << ','
             << depth_mid << ','
             << pair.host_delta_ns << ','
             << (static_cast<double>(pair.host_delta_ns) / 1000000.0) << ','
             << (pair.within_tolerance ? 1 : 0) << ','
             << (pair.color.has_uvc_pts ? 1 : 0) << ','
             << (pair.depth.has_uvc_pts ? 1 : 0) << ','
             << pair.color.uvc_pts << ','
             << pair.depth.uvc_pts << ','
             << (pair.color.has_uvc_scr ? 1 : 0) << ','
             << (pair.depth.has_uvc_scr ? 1 : 0) << ','
             << pair.color.uvc_scr_stc << ','
             << pair.depth.uvc_scr_stc << ','
             << pair.color.uvc_scr_sof << ','
             << pair.depth.uvc_scr_sof << ','
             << static_cast<int>(pair.color.fid) << ','
             << static_cast<int>(pair.depth.fid) << ','
             << pair.color.payload_bytes << ','
             << pair.depth.payload_bytes << '\n';
    }

private:
    std::mutex mu_;
    std::ofstream out_;
};

int master_yuy2_frame_size(int frame_index) {
    switch (frame_index) {
        case 1: return 512 * 496 * 2;
        case 2:
        case 3: return 320 * 200 * 2;
        case 4:
        case 5: return 640 * 300 * 2;
        case 6:
        case 7:
        case 8:
        case 9: return 640 * 400 * 2;
        case 10: return 640 * 460 * 2;
        case 11:
        case 12:
        case 13:
        case 14: return 640 * 600 * 2;
        case 15: return 1280 * 800 * 2;
        case 16: return 1280 * 960 * 2;
        default: return 0;
    }
}

int companion_depth_frame_size(int frame_index) {
    switch (frame_index) {
        case 1: return 1280 * 801 * 2;
        case 2: return 640 * 401 * 2;
        case 3: return 320 * 201 * 2;
        case 4: return 1280 * 800 * 2;
        default: return 0;
    }
}

P100R3VideoMode color_mode_for(const Args& args) {
    P100R3VideoMode mode;
    mode.frame_index = static_cast<uint8_t>(args.color_frame);
    mode.fps = interval_to_fps(args.color_interval_100ns);
    mode.interval_100ns = args.color_interval_100ns;
    switch (args.color_frame) {
        case 1:
            mode.width = 1920;
            mode.height = 1080;
            break;
        case 2:
            mode.width = 1280;
            mode.height = 800;
            break;
        case 3:
            mode.width = 640;
            mode.height = 400;
            break;
        default:
            break;
    }
    return mode;
}

P100R3VideoMode depth_mode_for(const Args& args) {
    P100R3VideoMode mode;
    mode.frame_index = static_cast<uint8_t>(args.depth_frame);
    mode.fps = interval_to_fps(args.depth_interval_100ns);
    mode.interval_100ns = args.depth_interval_100ns;
    switch (args.depth_frame) {
        case 1:
            mode.width = 1280;
            mode.height = 801;
            break;
        case 2:
            mode.width = 640;
            mode.height = 401;
            break;
        case 3:
            mode.width = 320;
            mode.height = 201;
            break;
        case 4:
            mode.width = 1280;
            mode.height = 800;
            break;
        default:
            break;
    }
    return mode;
}

P100R3DepthControls depth_controls_for(const Args& args) {
    P100R3DepthControls controls;
    if (args.no_depth_controls) {
        controls.enabled = false;
        return controls;
    }
    if (!args.depth_controls) return controls;

    const bool has_explicit = args.depth_ae >= 0 ||
                              args.depth_confidence >= 0 ||
                              args.depth_gain >= 0 ||
                              args.depth_temporal_denoise >= 0 ||
                              args.depth_spatial_denoise >= 0;
    controls.enabled = true;
    controls.set_auto_exposure = args.depth_controls_vendor || !has_explicit || args.depth_ae >= 0;
    controls.auto_exposure = args.depth_ae < 0 || args.depth_ae != 0;
    controls.set_confidence = args.depth_controls_vendor || !has_explicit || args.depth_confidence >= 0;
    controls.confidence = static_cast<uint8_t>(
        std::clamp(args.depth_confidence < 0 ? 3 : args.depth_confidence, 1, 5));
    controls.set_depth_gain = args.depth_gain >= 0;
    controls.depth_gain = static_cast<uint8_t>(
        std::clamp(args.depth_gain < 0 ? 1 : args.depth_gain, 1, 4));
    controls.set_temporal_denoise =
        args.depth_controls_vendor || !has_explicit || args.depth_temporal_denoise >= 0;
    controls.temporal_denoise = args.depth_temporal_denoise > 0;
    controls.set_spatial_denoise =
        args.depth_controls_vendor || !has_explicit || args.depth_spatial_denoise >= 0;
    controls.spatial_denoise = args.depth_spatial_denoise > 0;
    return controls;
}

bool is_master_keepalive_payload(const XuPayload& payload) {
    static constexpr uint8_t kPrefix[] = {
        0x42, 0x58, 0x0a, 0x00, 0x0d, 0x00, 0x00, 0x00,
    };
    return payload.data.size() >= 14 &&
           std::equal(kPrefix, kPrefix + sizeof(kPrefix), payload.data.begin());
}

bool find_keepalive_seed(const std::vector<XuPayload>& payloads, XuPayload* out) {
    for (auto it = payloads.rbegin(); it != payloads.rend(); ++it) {
        if (!is_master_keepalive_payload(*it)) continue;
        if (out) *out = *it;
        return true;
    }
    return false;
}

std::vector<uint8_t> stop_stream_types_for(const Args& args) {
    if (args.master_stop_stream >= 0) {
        return {static_cast<uint8_t>(args.master_stop_stream & 0xff)};
    }
    if (args.stop_only) return {1, 2, 5};
    std::vector<uint8_t> out;
    if (args.color) out.push_back(1);
    if (args.depth) {
        out.push_back(2);
        out.push_back(5);
    }
    return out;
}

bool send_master_stop(UsbDevice& master, const std::vector<uint8_t>& stream_types, LogFn log) {
    if (stream_types.empty()) return true;
    std::vector<XuPayload> payloads;
    payloads.reserve(stream_types.size());
    for (const uint8_t stream_type : stream_types) {
        payloads.push_back(gomob::berxel::host::make_p100r3_master_close_stream_payload(stream_type));
    }
    return gomob::berxel::host::replay_xu_payloads(master, payloads, true, "master-stop", log);
}

void print_session_stats(const P100R3DualSessionStats& s) {
    std::cout << "session:\n"
              << "  state       = "
              << gomob::berxel::host::p100r3_session_state_name(s.state) << "\n"
              << "  stop_reason = "
              << gomob::berxel::host::p100r3_session_stop_reason_name(s.stop_reason) << "\n";
    if (!s.error_message.empty()) {
        std::cout << "  error       = " << s.error_message << "\n";
    }
}

void print_pairing_stats(const RgbdPairingStats& s) {
    std::cout << "rgbd_pairs:\n"
              << "  pairs          = " << s.pairs << "\n"
              << "  drop_color     = " << s.dropped_color_frames << "\n"
              << "  drop_depth     = " << s.dropped_depth_frames << "\n"
              << "  queued_color   = " << s.queued_color_frames << "\n"
              << "  queued_depth   = " << s.queued_depth_frames << "\n"
              << "  last_delta_ms  = "
              << (static_cast<double>(s.last_host_delta_ns) / 1000000.0) << "\n"
              << "  mean_abs_ms    = "
              << (static_cast<double>(s.mean_abs_host_delta_ns) / 1000000.0) << "\n"
              << "  max_abs_ms     = "
              << (static_cast<double>(s.max_abs_host_delta_ns) / 1000000.0) << "\n"
              << "  last_color     = " << s.last_color_frame_number << "\n"
              << "  last_depth     = " << s.last_depth_frame_number << "\n";
}

int run_session_probe(const Args& args) {
    log("使用 P100R3DualSession SDK API");
    P100R3DualSessionConfig config;
    config.enable_color = args.color;
    config.enable_depth = args.depth;
    config.depth_as_light_ir = args.light_ir;
    config.color_first = args.color_first;
    config.fresh_time_sync = args.fresh_time_sync;
    config.send_master_stop = args.send_master_stop;
    config.duration_ms = args.dur_ms;
    config.keepalive_interval_ms = args.ka_ms;
    config.master_limit = args.master_limit;
    config.read_len = 16384;
    config.color_format = args.color_format;
    config.color_mode = color_mode_for(args);
    config.depth_mode = depth_mode_for(args);
    config.depth_controls = depth_controls_for(args);
    config.color_raw_frame_size = master_yuy2_frame_size(args.color_frame);
    config.depth_frame_size = companion_depth_frame_size(args.depth_frame);
    config.master_payloads = args.master_payloads;
    config.keepalive_payloads = args.keepalive_payloads;
    config.companion_init = args.companion_init;
    config.color_bulk_sample_path = out_path(args, "color-bulk-sample.bin");

    FrameCsv frame_csv(out_path(args, "frames.csv"));
    PairCsv pair_csv(out_path(args, "pairs.csv"));
    bool first_depth_saved = false;
    bool first_color_saved = false;
    std::vector<uint8_t> best_depth_frame;
    UvcFrameInfo best_depth_info;
    uint32_t best_depth_nonzero = 0;
    std::mutex saved_depth_mu;
    int saved_depth_seen = 0;
    int saved_depth_count = 0;
    std::ofstream saved_depth_csv;
    if (args.save_depth_frames > 0 && args.depth && !args.light_ir) {
        const std::string path = out_path(args, "saved_depth_frames.csv");
        const auto parent = std::filesystem::path(path).parent_path();
        if (!parent.empty()) std::filesystem::create_directories(parent);
        saved_depth_csv.open(path);
        if (saved_depth_csv) {
            saved_depth_csv
                << "saved,uvc_frame,width,height,active_height,fps,payload_bytes,"
                << "transport_bytes,raw_active_valid,processed_valid,filled,edge_blocked,"
                << "transport_path,active_path,processed_path,confidence_path\n";
        }
    }

    P100R3DualSessionCallbacks callbacks;
    callbacks.log = log;
    callbacks.depth_frame = [&](const UvcFrameInfo& info, const uint8_t* data, size_t size) {
        const std::string stream_name = args.light_ir ? "light-ir" : "depth";
        frame_csv.write(stream_name, info);

        const uint32_t nonzero = count_nonzero_u16(data, size);
        const bool should_update_best = args.light_ir
            ? best_depth_frame.empty()
            : nonzero > best_depth_nonzero;
        if (should_update_best && data && size > 0) {
            std::vector<uint16_t> light_ir_active;
            P100R3VideoMode saved_mode = info.mode;
            const uint8_t* saved_data = data;
            size_t saved_size = size;
            if (args.light_ir &&
                gomob::berxel::host::process_p100r3_light_ir_frame(data,
                                                                   size,
                                                                   info.mode,
                                                                   &light_ir_active)) {
                saved_mode = gomob::berxel::host::p100r3_depth_active_mode(info.mode);
                saved_data = reinterpret_cast<const uint8_t*>(light_ir_active.data());
                saved_size = light_ir_active.size() * sizeof(uint16_t);
            }
            best_depth_nonzero = count_nonzero_u16(saved_data, saved_size);
            best_depth_info = info;
            best_depth_info.mode = saved_mode;
            best_depth_info.payload_bytes = static_cast<uint32_t>(saved_size);
            best_depth_frame.assign(saved_data, saved_data + saved_size);
        }
        if (!first_depth_saved) {
            const std::string leaf = args.light_ir ? "light-ir-first.raw" : "depth-first.raw";
            std::vector<uint16_t> light_ir_active;
            P100R3VideoMode saved_mode = info.mode;
            const uint8_t* saved_data = data;
            size_t saved_size = size;
            if (args.light_ir &&
                gomob::berxel::host::process_p100r3_light_ir_frame(data,
                                                                   size,
                                                                   info.mode,
                                                                   &light_ir_active)) {
                saved_mode = gomob::berxel::host::p100r3_depth_active_mode(info.mode);
                saved_data = reinterpret_cast<const uint8_t*>(light_ir_active.data());
                saved_size = light_ir_active.size() * sizeof(uint16_t);
            }
            if (args.light_ir) {
                write_binary_file_local(out_path(args, "light-ir-first-transport.raw"), data, size);
            }
            first_depth_saved = write_binary_file_local(out_path(args, leaf), saved_data, saved_size);
            if (first_depth_saved) {
                log("raw first frame saved: " + out_path(args, leaf));
                if (args.light_ir) {
                    const std::string pgm_path = out_path(args, "light-ir-first.pgm");
                    if (write_u16_pgm_local(pgm_path, saved_data, saved_size, saved_mode)) {
                        log("light-ir first preview saved: " + pgm_path);
                    }
                }
            }
        }
        if (!args.light_ir && args.save_depth_frames > 0 && data && size > 0) {
            int save_index = -1;
            {
                std::lock_guard<std::mutex> lock(saved_depth_mu);
                const int seen_index = saved_depth_seen++;
                if (seen_index >= args.save_depth_skip &&
                    saved_depth_count < args.save_depth_frames) {
                    save_index = saved_depth_count++;
                }
            }
            if (save_index >= 0) {
                const P100R3VideoMode active_mode =
                    gomob::berxel::host::p100r3_depth_active_mode(info.mode);
                const size_t active_size = static_cast<size_t>(active_mode.width) *
                                           static_cast<size_t>(active_mode.height) *
                                           sizeof(uint16_t);
                std::string transport_leaf;
                std::string active_leaf;
                std::string processed_leaf;
                std::string confidence_leaf;
                uint32_t raw_active_valid = 0;
                uint32_t processed_valid = 0;
                uint32_t filled = 0;
                uint32_t edge_blocked = 0;
                if (size >= active_size) {
                    transport_leaf = indexed_leaf("depth-frame", save_index, "-transport.raw");
                    active_leaf = indexed_leaf("depth-frame", save_index, "-active.raw");
                    write_binary_file_local(out_path(args, transport_leaf), data, size);
                    write_binary_file_local(out_path(args, active_leaf), data, active_size);
                    raw_active_valid = count_nonzero_u16(data, active_size);

                    P100R3DepthProcessingConfig processing;
                    std::vector<uint16_t> processed_depth;
                    std::vector<uint8_t> processed_confidence;
                    P100R3DepthProcessingStats processing_stats;
                    if (gomob::berxel::host::process_p100r3_depth_frame(data,
                                                                        size,
                                                                        info.mode,
                                                                        processing,
                                                                        &processed_depth,
                                                                        &processed_confidence,
                                                                        &processing_stats)) {
                        processed_leaf = indexed_leaf("depth-frame", save_index, "-processed.raw");
                        confidence_leaf = indexed_leaf("depth-frame", save_index, "-confidence.raw");
                        write_binary_file_local(
                            out_path(args, processed_leaf),
                            reinterpret_cast<const uint8_t*>(processed_depth.data()),
                            processed_depth.size() * sizeof(uint16_t));
                        write_binary_file_local(out_path(args, confidence_leaf),
                                                processed_confidence.data(),
                                                processed_confidence.size());
                        processed_valid = processing_stats.processed_valid_pixels;
                        filled = processing_stats.filled_pixels;
                        edge_blocked = processing_stats.edge_blocked_pixels;
                    }
                }
                {
                    std::lock_guard<std::mutex> lock(saved_depth_mu);
                    if (saved_depth_csv) {
                        saved_depth_csv << save_index << ','
                                        << info.frame_number << ','
                                        << info.mode.width << ','
                                        << info.mode.height << ','
                                        << active_mode.height << ','
                                        << info.mode.fps << ','
                                        << info.payload_bytes << ','
                                        << info.transport_bytes << ','
                                        << raw_active_valid << ','
                                        << processed_valid << ','
                                        << filled << ','
                                        << edge_blocked << ','
                                        << transport_leaf << ','
                                        << active_leaf << ','
                                        << processed_leaf << ','
                                        << confidence_leaf << '\n';
                    }
                }
                if (save_index == 0 || save_index + 1 == args.save_depth_frames) {
                    log("depth sample saved: index=" + std::to_string(save_index) +
                        " frame=" + std::to_string(info.frame_number) +
                        " raw_valid=" + std::to_string(raw_active_valid) +
                        " processed_valid=" + std::to_string(processed_valid));
                }
            }
        }
        return true;
    };
    callbacks.color_frame = [&](const UvcFrameInfo& info, const uint8_t* data, size_t size) {
        frame_csv.write("color", info);
        if (!first_color_saved) {
            const std::string leaf = args.color_format == 1 ? "color-first.jpg" : "color-first.raw";
            first_color_saved = write_binary_file_local(out_path(args, leaf), data, size);
            if (first_color_saved) {
                log(std::string(args.color_format == 1 ? "mjpeg" : "raw") +
                    " first frame saved: " + out_path(args, leaf));
            }
        }
        return true;
    };
    callbacks.rgbd_pair = [&](const RgbdFramePairInfo& pair) {
        pair_csv.write(pair);
    };

    P100R3DualSession session(config);
    if (!session.start(std::move(callbacks))) {
        const P100R3DualSessionStats s = session.stats();
        std::cout << "\n=== RESULT ===\n";
        print_session_stats(s);
        return 7;
    }
    session.join();

    if (!best_depth_frame.empty()) {
        const std::string path = out_path(args, args.light_ir ? "light-ir-best.raw" : "depth-best.raw");
        if (write_binary_file_local(path, best_depth_frame.data(), best_depth_frame.size())) {
            log("raw best depth frame saved: " + path +
                " frame=" + std::to_string(best_depth_info.frame_number) +
                " nonzero=" + std::to_string(best_depth_nonzero));
            if (args.light_ir) {
                const std::string pgm_path = out_path(args, "light-ir-best.pgm");
                if (write_u16_pgm_local(pgm_path,
                                        best_depth_frame.data(),
                                        best_depth_frame.size(),
                                        best_depth_info.mode)) {
                    log("light-ir best preview saved: " + pgm_path);
                }
            }
        }
        if (args.light_ir) {
            const P100R3DualSessionStats s = session.stats();
            std::cout << "\n=== RESULT ===\n";
            print_session_stats(s);
            print_stats("keepalive", s.keepalive);
            print_stats("light-ir", s.depth);
            return s.state == gomob::berxel::host::P100R3SessionState::kStopped ? 0 : 8;
        }
        P100R3DepthProcessingConfig processing;
        std::vector<uint16_t> processed_depth;
        std::vector<uint8_t> processed_confidence;
        P100R3DepthProcessingStats processing_stats;
        if (gomob::berxel::host::process_p100r3_depth_frame(best_depth_frame.data(),
                                                            best_depth_frame.size(),
                                                            best_depth_info.mode,
                                                            processing,
                                                            &processed_depth,
                                                            &processed_confidence,
                                                            &processing_stats)) {
            const std::string processed_path = out_path(args, "depth-best-processed.raw");
            if (write_binary_file_local(
                    processed_path,
                    reinterpret_cast<const uint8_t*>(processed_depth.data()),
                    processed_depth.size() * sizeof(uint16_t))) {
                log("processed depth saved: " + processed_path +
                    " raw_valid=" + std::to_string(processing_stats.raw_valid_pixels) +
                    " processed_valid=" + std::to_string(processing_stats.processed_valid_pixels) +
                    " filled=" + std::to_string(processing_stats.filled_pixels) +
                    " edge_blocked=" + std::to_string(processing_stats.edge_blocked_pixels));
            }
            const std::string confidence_path = out_path(args, "depth-best-confidence.raw");
            if (write_binary_file_local(confidence_path,
                                        processed_confidence.data(),
                                        processed_confidence.size())) {
                log("processed confidence saved: " + confidence_path);
            }
        }
    }

    const P100R3DualSessionStats s = session.stats();
    std::cout << "\n=== RESULT ===\n";
    print_session_stats(s);
    print_stats("keepalive", s.keepalive);
    if (args.depth) print_stats("depth", s.depth);
    if (args.color) print_stats("color", s.color);
    if (args.depth && args.color) {
        print_pairing_stats(s.rgbd_pairing);
    }

    if ((args.depth && s.depth.bytes <= 0) || (args.color && s.color.bytes <= 0)) {
        return 9;
    }
    return s.state == gomob::berxel::host::P100R3SessionState::kStopped ? 0 : 8;
}

}  // namespace

int main(int argc, char** argv) {
    const Args args = parse_args(argc, argv);
    std::filesystem::create_directories(args.out_dir);

    UsbContext ctx;
    if (!ctx.ok()) {
        std::cerr << "libusb_init failed: " << ctx.status() << "\n";
        return 1;
    }

    if (args.list) {
        print_devices(ctx);
        if (!args.reset && !args.stop_only && !args.depth && !args.color) return 0;
    }

    if (args.reset) {
        log("reset master/companion");
        bool ok = true;
        if (auto master = ctx.open(gomob::berxel::host::kP100R3MasterId)) {
            ok = master->reset(log) && ok;
        } else {
            log("master reset skipped: device not open");
            ok = false;
        }
        if (auto companion = ctx.open(gomob::berxel::host::kP100R3CompanionId)) {
            ok = companion->reset(log) && ok;
        } else {
            log("companion reset skipped: device not open");
            ok = false;
        }
        if (!args.stop_only && !args.depth && !args.color) return ok ? 0 : 10;
        std::this_thread::sleep_for(std::chrono::milliseconds(1500));
    }

    if (args.stop_only) {
        log("打开 master 发送 stop");
        std::unique_ptr<UsbDevice> master = ctx.open(gomob::berxel::host::kP100R3MasterId);
        if (!master) {
            std::cerr << "master 0603:001f 未发现或无法打开\n";
            return 3;
        }
        if (!master->claim_interface(0, log)) return 3;
        const bool ok = send_master_stop(*master, stop_stream_types_for(args), log);
        return ok ? 0 : 11;
    }

    if (args.session_api && (args.depth || args.color)) {
        return run_session_probe(args);
    }

    std::unique_ptr<UsbDevice> companion;
    if (args.companion_warmup > 0) {
        log("companion warmup init: " + args.companion_init +
            " limit=" + std::to_string(args.companion_warmup));
        const auto companion_payloads = gomob::berxel::host::load_xu_payloads(
            args.companion_init,
            0,
            gomob::berxel::host::kP100R3CompanionXu3WIndex,
            args.companion_warmup);
        if (companion_payloads.empty()) {
            std::cerr << "companion warmup 加载失败\n";
            return 5;
        }
        companion = ctx.open(gomob::berxel::host::kP100R3CompanionId);
        if (!companion) {
            std::cerr << "companion 3558:1012 未发现或无法打开\n";
            return 5;
        }
        if (!companion->claim_interface(0, log)) return 5;
        if (!gomob::berxel::host::replay_xu_payloads(*companion,
                                                     companion_payloads,
                                                     true,
                                                     "companion-warmup",
                                                     log)) {
            return 6;
        }
    }

    log("加载 master XU5 payload: " + args.master_payloads);
    auto master_payloads = gomob::berxel::host::load_xu_payloads(
        args.master_payloads,
        0x0100,
        gomob::berxel::host::kP100R3MasterXu5WIndex,
        args.master_limit);
    if (master_payloads.empty()) {
        std::cerr << "master payload 加载失败\n";
        return 2;
    }
    if (args.fresh_time_sync) {
        const int patched = gomob::berxel::host::refresh_master_time_sync_payloads(&master_payloads);
        if (patched > 0) {
            log("master time-sync payload refreshed: " + std::to_string(patched));
        }
    }
    if (args.color && args.color_format == 1) {
        const P100R3VideoMode color_mode = color_mode_for(args);
        std::string payload_hex;
        const int patched = gomob::berxel::host::patch_p100r3_master_color_open_stream_payloads(
            &master_payloads,
            color_mode,
            &payload_hex);
        log("master COLOR OpenStream patched: " + std::to_string(patched) +
            " -> " + std::to_string(color_mode.width) + "x" +
            std::to_string(color_mode.height) + "@" + std::to_string(color_mode.fps));
        log("master COLOR OpenStream payload: " + payload_hex);
    }

    log("打开 master " + gomob::berxel::host::usb_id_string(gomob::berxel::host::kP100R3MasterId));
    std::unique_ptr<UsbDevice> master = ctx.open(gomob::berxel::host::kP100R3MasterId);
    if (!master) {
        std::cerr << "master 0603:001f 未发现或无法打开\n";
        return 3;
    }
    if (!master->claim_interface(0, log)) return 3;
    if (args.color && !master->claim_interface(1, log)) return 3;

    if (!gomob::berxel::host::replay_xu_payloads(*master, master_payloads, true, "master", log)) {
        return 4;
    }

    std::atomic<bool> keepalive_running{true};
    BulkStats keepalive_stats;
    std::thread keepalive_thread;
    if (args.ka_ms > 0) {
        XuPayload keepalive_seed;
        if (!find_keepalive_seed(master_payloads, &keepalive_seed)) {
            const auto keepalive_payloads = gomob::berxel::host::load_xu_payloads(
                args.keepalive_payloads,
                0x0100,
                gomob::berxel::host::kP100R3MasterXu5WIndex);
            if (!find_keepalive_seed(keepalive_payloads, &keepalive_seed)) {
                log("keepalive seed fallback to last master payload");
                keepalive_seed = master_payloads.back();
            } else {
                log("keepalive seed loaded: " + args.keepalive_payloads);
            }
        }
        keepalive_thread = std::thread(gomob::berxel::host::master_keepalive_loop,
                                       std::ref(*master),
                                       keepalive_seed,
                                       args.ka_ms,
                                       std::ref(keepalive_running),
                                       std::ref(keepalive_stats),
                                       log);
        log("master keepalive started, interval=" + std::to_string(args.ka_ms) + "ms");
    } else {
        log("master keepalive disabled");
    }
    auto stop_keepalive = [&]() {
        keepalive_running = false;
        if (keepalive_thread.joinable()) keepalive_thread.join();
    };
    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    auto negotiate_depth = [&]() -> bool {
        if (!args.depth) return true;
        log("加载 companion init: " + args.companion_init);
        auto companion_payloads = gomob::berxel::host::load_xu_payloads(
            args.companion_init,
            0,
            gomob::berxel::host::kP100R3CompanionXu3WIndex);
        if (companion_payloads.empty()) {
            std::cerr << "companion init 加载失败\n";
            return false;
        }
        const P100R3VideoMode depth_mode = depth_mode_for(args);
        std::string payload_prefix_hex;
        const int patched = gomob::berxel::host::patch_p100r3_companion_depth_open_stream_payloads(
            &companion_payloads,
            depth_mode,
            &payload_prefix_hex);
        std::ostringstream mode_code;
        mode_code << "0x" << std::hex << std::setw(2) << std::setfill('0')
                  << static_cast<int>(gomob::berxel::host::p100r3_depth_mode_code(depth_mode));
        log("companion DEPTH OpenStream patched: " + std::to_string(patched) +
            " -> " + std::to_string(depth_mode.width) + "x" +
            std::to_string(depth_mode.height) + "@" + std::to_string(depth_mode.fps) +
            " code=" + mode_code.str());
        log("companion DEPTH OpenStream payload prefix: " + payload_prefix_hex);

        if (!companion) {
            log("打开 companion " +
                gomob::berxel::host::usb_id_string(gomob::berxel::host::kP100R3CompanionId));
            companion = ctx.open(gomob::berxel::host::kP100R3CompanionId);
            if (!companion) {
                std::cerr << "companion 3558:1012 未发现或无法打开\n";
                return false;
            }
        }
        if (!companion->claim_interface(0, log) || !companion->claim_interface(1, log)) {
            return false;
        }
        if (!gomob::berxel::host::replay_xu_payloads(*companion,
                                                     companion_payloads,
                                                     true,
                                                     "companion",
                                                     log)) {
            return false;
        }
        if (!gomob::berxel::host::apply_p100r3_depth_controls(*companion,
                                                              depth_controls_for(args),
                                                              log)) {
            return false;
        }

        UvcStreamConfig depth_config;
        depth_config.name = "companion-depth";
        depth_config.vs_interface = 1;
        depth_config.endpoint = 0x82;
        depth_config.format_index = 1;
        depth_config.frame_index = static_cast<uint8_t>(args.depth_frame);
        depth_config.frame_interval_100ns = args.depth_interval_100ns;
        UvcNegotiation depth_negotiation;
        if (!gomob::berxel::host::negotiate_uvc_stream(*companion,
                                                       depth_config,
                                                       &depth_negotiation,
                                                       log)) {
            return false;
        }
        return true;
    };

    auto negotiate_color = [&]() -> bool {
        if (!args.color) return true;
        UvcStreamConfig color_config;
        color_config.name = "master-color";
        color_config.vs_interface = 1;
        color_config.endpoint = 0x81;
        color_config.format_index = static_cast<uint8_t>(args.color_format);
        color_config.frame_index = static_cast<uint8_t>(args.color_frame);
        color_config.frame_interval_100ns = args.color_interval_100ns;
        UvcNegotiation color_negotiation;
        if (!gomob::berxel::host::negotiate_uvc_stream(*master,
                                                       color_config,
                                                       &color_negotiation,
                                                       log)) {
            return false;
        }
        return true;
    };

    bool controls_ok = false;
    if (args.color_first) {
        controls_ok = negotiate_color() && negotiate_depth();
    } else {
        controls_ok = negotiate_depth() && negotiate_color();
    }
    if (!controls_ok) {
        stop_keepalive();
        return 7;
    }

    BulkStats depth_stats;
    BulkStats color_stats;
    std::thread depth_thread;
    std::thread color_thread;
    FrameCsv frame_csv(out_path(args, "frames.csv"));
    PairCsv pair_csv(out_path(args, "pairs.csv"));
    RgbdFramePairer pairer;
    std::mutex pairer_mu;

    log("开始拉流 durMs=" + std::to_string(args.dur_ms));
    if (args.depth && companion) {
        depth_thread = std::thread([&]() {
            const P100R3VideoMode mode = depth_mode_for(args);
            bool first_frame_saved = false;
            auto callback = [&](const UvcFrameInfo& info, const uint8_t* data, size_t size) {
                frame_csv.write("depth", info);
                {
                    std::lock_guard<std::mutex> lock(pairer_mu);
                    RgbdFramePairInfo pair;
                    if (pairer.push_depth(info, &pair)) {
                        pair_csv.write(pair);
                    }
                }
                if (!first_frame_saved) {
                    first_frame_saved = write_binary_file_local(out_path(args, "depth-first.raw"), data, size);
                    if (first_frame_saved) {
                        log("raw first frame saved: " + out_path(args, "depth-first.raw"));
                    }
                }
                return true;
            };
            depth_stats = gomob::berxel::host::pull_raw_frames(
                *companion,
                0x82,
                args.dur_ms,
                16384,
                companion_depth_frame_size(args.depth_frame),
                mode,
                callback,
                log);
        });
    }
    if (args.color) {
        color_thread = std::thread([&]() {
            if (args.color_format == 1) {
                const P100R3VideoMode mode = color_mode_for(args);
                bool first_frame_saved = false;
                auto callback = [&](const UvcFrameInfo& info, const uint8_t* data, size_t size) {
                    frame_csv.write("color", info);
                    {
                        std::lock_guard<std::mutex> lock(pairer_mu);
                        RgbdFramePairInfo pair;
                        if (pairer.push_color(info, &pair)) {
                            pair_csv.write(pair);
                        }
                    }
                    if (!first_frame_saved) {
                        first_frame_saved = write_binary_file_local(out_path(args, "color-first.jpg"), data, size);
                        if (first_frame_saved) {
                            log("mjpeg first frame saved: " + out_path(args, "color-first.jpg"));
                        }
                    }
                    return true;
                };
                color_stats = gomob::berxel::host::pull_mjpeg_frames(
                    *master,
                    0x81,
                    args.dur_ms,
                    16384,
                    mode,
                    out_path(args, "color-bulk-sample.bin"),
                    callback,
                    log);
            } else {
                P100R3VideoMode mode;
                mode.frame_index = static_cast<uint8_t>(args.color_frame);
                mode.fps = interval_to_fps(args.color_interval_100ns);
                mode.interval_100ns = args.color_interval_100ns;
                bool first_frame_saved = false;
                auto callback = [&](const UvcFrameInfo& info, const uint8_t* data, size_t size) {
                    frame_csv.write("color", info);
                    {
                        std::lock_guard<std::mutex> lock(pairer_mu);
                        RgbdFramePairInfo pair;
                        if (pairer.push_color(info, &pair)) {
                            pair_csv.write(pair);
                        }
                    }
                    if (!first_frame_saved) {
                        first_frame_saved = write_binary_file_local(out_path(args, "color-first.raw"), data, size);
                        if (first_frame_saved) {
                            log("raw first frame saved: " + out_path(args, "color-first.raw"));
                        }
                    }
                    return true;
                };
                color_stats = gomob::berxel::host::pull_raw_frames(
                    *master,
                    0x81,
                    args.dur_ms,
                    16384,
                    master_yuy2_frame_size(args.color_frame),
                    mode,
                    callback,
                    log);
            }
        });
    }

    if (depth_thread.joinable()) depth_thread.join();
    if (color_thread.joinable()) color_thread.join();
    log("拉流结束，停止 keepalive");

    stop_keepalive();
    if (args.send_master_stop) {
        send_master_stop(*master, stop_stream_types_for(args), log);
    }

    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    companion.reset();
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    master.reset();

    std::cout << "\n=== RESULT ===\n";
    print_stats("keepalive", keepalive_stats);
    if (args.depth) print_stats("depth", depth_stats);
    if (args.color) print_stats("color", color_stats);
    if (args.depth && args.color) {
        std::lock_guard<std::mutex> lock(pairer_mu);
        print_pairing_stats(pairer.stats());
    }

    if ((args.depth && depth_stats.bytes <= 0) || (args.color && color_stats.bytes <= 0)) {
        return 9;
    }
    return 0;
}
