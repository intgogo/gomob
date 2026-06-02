#include "gomob_berxel_host_sdk.h"

#include <libusb-1.0/libusb.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <limits>
#include <mutex>
#include <queue>
#include <regex>
#include <sstream>
#include <thread>
#include <utility>

namespace gomob::berxel::host {

using namespace detail;  // 复用 portable 共享底层 helper

namespace {

constexpr uint8_t kBmRequestTypeSetCur = 0x21;
constexpr uint8_t kBmRequestTypeGetCur = 0xa1;
constexpr uint8_t kBRequestSetCur = 0x01;
constexpr uint8_t kBRequestGetCur = 0x81;
constexpr uint8_t kBRequestGetDef = 0x87;
constexpr const char* kDepthMasterPayloads =
    "core/native-bridge/src/main/assets/berxel/iHawkP100R3_master_xu5_init.json";
constexpr const char* kColorMasterPayloads =
    "native/berxel/host/assets/iHawkP100R3_color_master_xu5_init.json";
constexpr const char* kCompanionInit =
    "core/native-bridge/src/main/assets/berxel/iHawkP100R3_init_sequence.json";
// 原厂 MIX 并发 color+depth 序列(berxel_mix_trace usbmon 抓 vendor SDK 的 definitive 配方):
//   master 21 条 + companion 8 条。color+depth 同开时用,depth-only/color-only 仍用上面的 SINGULAR。
constexpr const char* kMixMasterPayloads =
    "core/native-bridge/src/main/assets/berxel/iHawkP100R3_master_mix_init.json";
constexpr const char* kMixCompanionInit =
    "core/native-bridge/src/main/assets/berxel/iHawkP100R3_companion_mix_init.json";

std::string read_string_descriptor(libusb_device_handle* handle, uint8_t index) {
    if (!handle || index == 0) return {};
    uint8_t buf[256] = {};
    const int rc = libusb_get_string_descriptor_ascii(handle, index, buf, sizeof(buf));
    if (rc < 0) return {};
    std::string value(reinterpret_cast<char*>(buf), static_cast<size_t>(rc));
    const size_t nul = value.find('\0');
    if (nul != std::string::npos) value.resize(nul);
    return value;
}

bool contains_iface(const std::vector<int>& values, int iface) {
    return std::find(values.begin(), values.end(), iface) != values.end();
}

void ensure_parent_dir(const std::string& path) {
    if (path.empty()) return;
    const auto parent = std::filesystem::path(path).parent_path();
    if (!parent.empty()) std::filesystem::create_directories(parent);
}

bool write_binary_file(const std::string& path, const std::vector<uint8_t>& data) {
    if (path.empty()) return false;
    ensure_parent_dir(path);
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    out.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(data.size()));
    return static_cast<bool>(out);
}

bool save_jpeg_candidate(const std::vector<uint8_t>& frame, const std::string& path) {
    if (path.empty() || frame.empty()) return false;
    const size_t soi = find_marker(frame, 0xff, 0xd8, 0);
    const size_t eoi = rfind_marker(frame, 0xff, 0xd9);
    if (soi == std::string::npos || eoi == std::string::npos || eoi <= soi) return false;
    std::vector<uint8_t> jpeg(frame.begin() + static_cast<std::ptrdiff_t>(soi),
                              frame.begin() + static_cast<std::ptrdiff_t>(eoi + 2));
    return write_binary_file(path, jpeg);
}

}  // namespace


UsbDevice::UsbDevice(libusb_device_handle* handle) : handle_(handle) {
    if (handle_) libusb_set_auto_detach_kernel_driver(handle_, 1);
}

UsbDevice::~UsbDevice() {
    release_all();
    if (handle_) {
        libusb_close(handle_);
        handle_ = nullptr;
    }
}

bool UsbDevice::valid() const {
    return handle_ != nullptr;
}

bool UsbDevice::reset(LogFn log) {
    if (!handle_) return false;
    release_all();
    const int rc = libusb_reset_device(handle_);
    if (rc != 0) {
        log_line(log, "reset device rc=" + std::to_string(rc) + " " + usb_error_name(rc));
        return false;
    }
    return true;
}

bool UsbDevice::claim_interface(int iface, LogFn log) {
    if (!handle_) return false;
    if (contains_iface(claimed_interfaces_, iface)) return true;
    libusb_set_auto_detach_kernel_driver(handle_, 1);
    const int active = libusb_kernel_driver_active(handle_, iface);
    if (active == 1) {
        const int detach_rc = libusb_detach_kernel_driver(handle_, iface);
        if (detach_rc != 0 && detach_rc != LIBUSB_ERROR_NOT_SUPPORTED) {
            log_line(log, "detach iface " + std::to_string(iface) + " rc=" +
                          std::to_string(detach_rc) + " " + usb_error_name(detach_rc));
        }
    }
    const int rc = libusb_claim_interface(handle_, iface);
    if (rc != 0) {
        log_line(log, "claim iface " + std::to_string(iface) + " rc=" +
                      std::to_string(rc) + " " + usb_error_name(rc));
        return false;
    }
    claimed_interfaces_.push_back(iface);
    return true;
}

void UsbDevice::release_interface(int iface) {
    if (!handle_) return;
    auto it = std::find(claimed_interfaces_.begin(), claimed_interfaces_.end(), iface);
    if (it == claimed_interfaces_.end()) return;
    libusb_release_interface(handle_, iface);
    claimed_interfaces_.erase(it);
}

void UsbDevice::release_all() {
    if (!handle_) return;
    while (!claimed_interfaces_.empty()) {
        const int iface = claimed_interfaces_.back();
        claimed_interfaces_.pop_back();
        libusb_release_interface(handle_, iface);
    }
}

int UsbDevice::control_transfer(uint8_t bm_request_type,
                                uint8_t b_request,
                                uint16_t w_value,
                                uint16_t w_index,
                                uint8_t* data,
                                uint16_t length,
                                uint32_t timeout_ms) {
    if (!handle_) return LIBUSB_ERROR_NO_DEVICE;
    return libusb_control_transfer(handle_,
                                   bm_request_type,
                                   b_request,
                                   w_value,
                                   w_index,
                                   data,
                                   length,
                                   timeout_ms);
}

int UsbDevice::uvc_set_cur(uint16_t w_value,
                           uint16_t w_index,
                           uint8_t* data,
                           uint16_t length,
                           uint32_t timeout_ms) {
    return control_transfer(kBmRequestTypeSetCur,
                            kBRequestSetCur,
                            w_value,
                            w_index,
                            data,
                            length,
                            timeout_ms);
}

int UsbDevice::uvc_get_cur(uint16_t w_value,
                           uint16_t w_index,
                           uint8_t* data,
                           uint16_t length,
                           uint32_t timeout_ms) {
    return control_transfer(kBmRequestTypeGetCur,
                            kBRequestGetCur,
                            w_value,
                            w_index,
                            data,
                            length,
                            timeout_ms);
}

int UsbDevice::uvc_get_def(uint16_t w_value,
                           uint16_t w_index,
                           uint8_t* data,
                           uint16_t length,
                           uint32_t timeout_ms) {
    return control_transfer(kBmRequestTypeGetCur,
                            kBRequestGetDef,
                            w_value,
                            w_index,
                            data,
                            length,
                            timeout_ms);
}

int UsbDevice::bulk_in(uint8_t endpoint,
                       uint8_t* data,
                       int length,
                       int* actual_length,
                       uint32_t timeout_ms) {
    if (!handle_) return LIBUSB_ERROR_NO_DEVICE;
    return libusb_bulk_transfer(handle_, endpoint, data, length, actual_length, timeout_ms);
}

UsbContext::UsbContext() {
    status_ = libusb_init(&context_);
}

UsbContext::~UsbContext() {
    if (context_) {
        libusb_exit(context_);
        context_ = nullptr;
    }
}

bool UsbContext::ok() const {
    return status_ == 0 && context_ != nullptr;
}

int UsbContext::status() const {
    return status_;
}

std::vector<UsbDeviceInfo> UsbContext::list_devices() const {
    std::vector<UsbDeviceInfo> out;
    if (!ok()) return out;

    libusb_device** list = nullptr;
    const ssize_t count = libusb_get_device_list(context_, &list);
    if (count < 0) return out;

    for (ssize_t i = 0; i < count; ++i) {
        libusb_device* dev = list[i];
        libusb_device_descriptor desc = {};
        if (libusb_get_device_descriptor(dev, &desc) != 0) continue;

        UsbDeviceInfo info;
        info.id = UsbId{desc.idVendor, desc.idProduct};
        info.bus = libusb_get_bus_number(dev);
        info.address = libusb_get_device_address(dev);
        info.bcd_usb = desc.bcdUSB;
        info.bcd_device = desc.bcdDevice;

        libusb_device_handle* handle = nullptr;
        if (libusb_open(dev, &handle) == 0 && handle) {
            info.manufacturer = read_string_descriptor(handle, desc.iManufacturer);
            info.product = read_string_descriptor(handle, desc.iProduct);
            info.serial = read_string_descriptor(handle, desc.iSerialNumber);
            libusb_close(handle);
        }
        out.push_back(std::move(info));
    }
    libusb_free_device_list(list, 1);
    return out;
}

std::unique_ptr<UsbDevice> UsbContext::open(UsbId id) const {
    if (!ok()) return nullptr;
    libusb_device_handle* handle = libusb_open_device_with_vid_pid(context_, id.vid, id.pid);
    if (!handle) return nullptr;
    return std::unique_ptr<UsbDevice>(new UsbDevice(handle));
}


std::vector<XuPayload> load_xu_payloads(const std::string& path,
                                        uint16_t default_w_value,
                                        uint16_t default_w_index,
                                        int limit) {
    std::ifstream in(path);
    if (!in) return {};
    std::stringstream ss;
    ss << in.rdbuf();
    return parse_xu_payloads(ss.str(), default_w_value, default_w_index, limit);
}


class P100R3DualSession::Impl {
public:
    explicit Impl(P100R3DualSessionConfig config)
        : config_(std::move(config)),
          pairer_(config_.pairer_config) {
    }

    ~Impl() {
        stop();
        join();
    }

    bool start(P100R3DualSessionCallbacks callbacks) {
        {
            std::lock_guard<std::mutex> lock(mu_);
            const P100R3SessionState state = stats_.state;
            if (state == P100R3SessionState::kOpening ||
                state == P100R3SessionState::kStreaming ||
                state == P100R3SessionState::kStopping) {
                return false;
            }
            callbacks_ = std::move(callbacks);
            stats_ = P100R3DualSessionStats{};
            stats_.state = P100R3SessionState::kOpening;
            cleanup_done_ = false;
        }
        pairer_.reset();
        keepalive_stats_ = BulkStats{};
        running_.store(true);

        if (!config_.enable_color && !config_.enable_depth) {
            return fail_setup("P100R3 session 至少需要启用一路流");
        }

        context_ = std::make_unique<UsbContext>();
        if (!context_->ok()) {
            return fail_setup("libusb 初始化失败: " + std::to_string(context_->status()));
        }

        std::vector<XuPayload> master_payloads;
        if (!prepare_master(&master_payloads)) return false;
        start_keepalive(master_payloads);
        std::this_thread::sleep_for(std::chrono::milliseconds(200));

        const bool controls_ok = config_.color_first
            ? (setup_color() && setup_depth())
            : (setup_depth() && setup_color());
        if (!controls_ok) return false;

        bool enter_streaming = false;
        {
            std::lock_guard<std::mutex> lock(mu_);
            // 并发 stop() 可能在 setup 期间把状态翻到 kStopping 并清了 running_。
            // 仅当仍处于 kOpening 时才升 kStreaming 并启动拉流线程，避免把 kStopping 覆盖回 kStreaming、
            // 又 spawn 立即空转退出的线程，造成 state/stop_reason/running_ 自相矛盾。
            if (stats_.state == P100R3SessionState::kOpening) {
                stats_.state = P100R3SessionState::kStreaming;
                enter_streaming = true;
            }
        }
        if (!enter_streaming) {
            // 已被并发 stop 抢先：保持 kStopping，交给 stop()/join() 收尾，不启动拉流线程。
            return false;
        }

        if (config_.enable_depth) {
            depth_thread_ = std::thread(&Impl::depth_loop, this);
        }
        if (config_.enable_color) {
            color_thread_ = std::thread(&Impl::color_loop, this);
        }
        return true;
    }

    void stop() {
        request_stop(P100R3SessionStopReason::kUserStop);
    }

    void join() {
        if (depth_thread_.joinable()) depth_thread_.join();
        if (color_thread_.joinable()) color_thread_.join();

        const P100R3SessionState state_before = state();
        if (state_before == P100R3SessionState::kOpening ||
            state_before == P100R3SessionState::kStreaming ||
            state_before == P100R3SessionState::kStopping) {
            if (config_.duration_ms > 0 && stop_reason() == P100R3SessionStopReason::kNone) {
                set_stop_reason(P100R3SessionStopReason::kDurationReached);
            }
        }

        cleanup_controls();

        {
            std::lock_guard<std::mutex> lock(mu_);
            if (stats_.state != P100R3SessionState::kFailed &&
                stats_.state != P100R3SessionState::kIdle) {
                stats_.state = P100R3SessionState::kStopped;
            }
        }
    }

    P100R3SessionState state() const {
        std::lock_guard<std::mutex> lock(mu_);
        return stats_.state;
    }

    P100R3DualSessionStats stats() const {
        P100R3DualSessionStats out;
        {
            std::lock_guard<std::mutex> lock(mu_);
            out = stats_;
        }
        {
            std::lock_guard<std::mutex> lock(pairer_mu_);
            out.rgbd_pairing = pairer_.stats();
            out.rgbd_pairs = out.rgbd_pairing.pairs;
            out.dropped_color_pairs = out.rgbd_pairing.dropped_color_frames;
            out.dropped_depth_pairs = out.rgbd_pairing.dropped_depth_frames;
            out.queued_color_pairs = out.rgbd_pairing.queued_color_frames;
            out.queued_depth_pairs = out.rgbd_pairing.queued_depth_frames;
        }
        return out;
    }

private:
    void emit_log(const std::string& msg) const {
        if (callbacks_.log) callbacks_.log(msg);
    }

    P100R3SessionStopReason stop_reason() const {
        std::lock_guard<std::mutex> lock(mu_);
        return stats_.stop_reason;
    }

    void set_stop_reason(P100R3SessionStopReason reason) {
        std::lock_guard<std::mutex> lock(mu_);
        if (stats_.stop_reason == P100R3SessionStopReason::kNone) {
            stats_.stop_reason = reason;
        }
    }

    void request_stop(P100R3SessionStopReason reason) {
        bool should_stop = false;
        std::lock_guard<std::mutex> lock(mu_);
        if (stats_.state == P100R3SessionState::kOpening ||
            stats_.state == P100R3SessionState::kStreaming) {
            stats_.state = P100R3SessionState::kStopping;
            should_stop = true;
        } else if (stats_.state == P100R3SessionState::kStopping) {
            should_stop = true;
        }
        if (should_stop && stats_.stop_reason == P100R3SessionStopReason::kNone) {
            stats_.stop_reason = reason;
        }
        if (should_stop) {
            running_.store(false);
        }
    }

    bool fail_setup(const std::string& message) {
        emit_log(message);
        running_.store(false);
        {
            std::lock_guard<std::mutex> lock(mu_);
            stats_.state = P100R3SessionState::kFailed;
            stats_.stop_reason = P100R3SessionStopReason::kSetupFailed;
            stats_.error_message = message;
        }
        cleanup_controls();
        return false;
    }

    std::string master_payload_path() const {
        if (!config_.master_payloads.empty()) return config_.master_payloads;
        // color+depth 并发 = MIX 模式,用原厂 MIX master 序列(host 实测 color 1003/depth 146/0 错)。
        if (config_.enable_color && config_.enable_depth) return kMixMasterPayloads;
        if (config_.enable_color && !config_.enable_depth) return kColorMasterPayloads;
        return kDepthMasterPayloads;
    }

    std::string keepalive_payload_path() const {
        return config_.keepalive_payloads.empty() ? kDepthMasterPayloads : config_.keepalive_payloads;
    }

    std::string companion_init_path() const {
        if (!config_.companion_init.empty()) return config_.companion_init;
        // MIX 模式 companion 序列(reg0x19=04 + 末尾 0102 00),配 kMixMasterPayloads。
        if (config_.enable_color && config_.enable_depth) return kMixCompanionInit;
        return kCompanionInit;
    }

    bool prepare_master(std::vector<XuPayload>* master_payloads) {
        const std::string payload_path = master_payload_path();
        emit_log("加载 master XU5 payload: " + payload_path);
        *master_payloads = load_xu_payloads(payload_path,
                                            0x0100,
                                            kP100R3MasterXu5WIndex,
                                            config_.master_limit);
        if (master_payloads->empty()) {
            return fail_setup("master payload 加载失败");
        }
        if (config_.fresh_time_sync) {
            const int patched = refresh_master_time_sync_payloads(master_payloads);
            if (patched > 0) {
                emit_log("master time-sync payload refreshed: " + std::to_string(patched));
            }
        }
        if (config_.enable_color && config_.color_format == 1) {
            std::string payload_hex;
            const int patched = patch_p100r3_master_color_open_stream_payloads(
                master_payloads,
                config_.color_mode,
                &payload_hex);
            emit_log("master COLOR OpenStream patched: " + std::to_string(patched) +
                     " -> " + std::to_string(config_.color_mode.width) + "x" +
                     std::to_string(config_.color_mode.height) + "@" +
                     std::to_string(config_.color_mode.fps));
            emit_log("master COLOR OpenStream payload: " + payload_hex);
        }

        emit_log("打开 master " + usb_id_string(kP100R3MasterId));
        master_ = context_->open(kP100R3MasterId);
        if (!master_) return fail_setup("master 0603:001f 未发现或无法打开");
        if (!master_->claim_interface(0, callbacks_.log)) return fail_setup("master interface 0 claim 失败");
        if (config_.enable_color && !master_->claim_interface(1, callbacks_.log)) {
            return fail_setup("master interface 1 claim 失败");
        }
        if (!replay_xu_payloads(*master_, *master_payloads, true, "master", callbacks_.log)) {
            return fail_setup("master XU5 replay 失败");
        }
        if (config_.depth_as_light_ir) {
            const uint8_t fps = static_cast<uint8_t>(
                std::clamp<int>(config_.depth_mode.fps, 1, 255));
            const XuPayload payload =
                make_p100r3_master_force_internal_pwm_trigger_payload(true, fps);
            emit_log("master force internal PWM trigger enabled, fps=" + std::to_string(fps));
            if (!replay_xu_payloads(*master_, {payload}, true, "master-light-ir-pwm", callbacks_.log)) {
                return fail_setup("master light-ir PWM trigger 失败");
            }
        }
        return true;
    }

    void start_keepalive(const std::vector<XuPayload>& master_payloads) {
        if (config_.keepalive_interval_ms <= 0 || !master_) {
            emit_log("master keepalive disabled");
            return;
        }
        XuPayload seed;
        if (!find_keepalive_seed(master_payloads, &seed)) {
            const std::string path = keepalive_payload_path();
            const auto payloads = load_xu_payloads(path, 0x0100, kP100R3MasterXu5WIndex);
            if (find_keepalive_seed(payloads, &seed)) {
                emit_log("keepalive seed loaded: " + path);
            } else if (!master_payloads.empty()) {
                emit_log("keepalive seed fallback to last master payload");
                seed = master_payloads.back();
            }
        }
        keepalive_thread_ = std::thread(master_keepalive_loop,
                                        std::ref(*master_),
                                        seed,
                                        config_.keepalive_interval_ms,
                                        std::ref(running_),
                                        std::ref(keepalive_stats_),
                                        callbacks_.log);
        emit_log("master keepalive started, interval=" +
                 std::to_string(config_.keepalive_interval_ms) + "ms");
    }

    bool setup_depth() {
        if (!config_.enable_depth) return true;
        const std::string path = companion_init_path();
        emit_log("加载 companion init: " + path);
        auto payloads = load_xu_payloads(path, 0, kP100R3CompanionXu3WIndex);
        if (payloads.empty()) return fail_setup("companion init 加载失败");

        std::string payload_prefix_hex;
        const int patched = config_.depth_as_light_ir
            ? patch_p100r3_companion_light_ir_open_stream_payloads(
                  &payloads,
                  config_.depth_mode,
                  &payload_prefix_hex)
            : patch_p100r3_companion_depth_open_stream_payloads(
                  &payloads,
                  config_.depth_mode,
                  &payload_prefix_hex);
        const uint8_t stream_mode_code = config_.depth_as_light_ir
            ? 0x02
            : p100r3_depth_mode_code(config_.depth_mode);
        std::ostringstream mode_code;
        mode_code << "0x" << std::hex << std::setw(2) << std::setfill('0')
                  << static_cast<int>(stream_mode_code);
        const std::string stream_label = config_.depth_as_light_ir ? "LIGHT_IR" : "DEPTH";
        emit_log("companion " + stream_label + " OpenStream patched: " + std::to_string(patched) +
                 " -> " + std::to_string(config_.depth_mode.width) + "x" +
                 std::to_string(config_.depth_mode.height) + "@" +
                 std::to_string(config_.depth_mode.fps) + " code=" + mode_code.str());
        emit_log("companion " + stream_label + " OpenStream payload prefix: " + payload_prefix_hex);

        emit_log("打开 companion " + usb_id_string(kP100R3CompanionId));
        companion_ = context_->open(kP100R3CompanionId);
        if (!companion_) return fail_setup("companion 3558:1012 未发现或无法打开");
        if (!companion_->claim_interface(0, callbacks_.log) ||
            !companion_->claim_interface(1, callbacks_.log)) {
            return fail_setup("companion interface claim 失败");
        }
        if (!replay_xu_payloads(*companion_, payloads, true, "companion", callbacks_.log)) {
            return fail_setup("companion XU3 replay 失败");
        }
        if (!config_.depth_as_light_ir &&
            !apply_p100r3_depth_controls(*companion_, config_.depth_controls, callbacks_.log)) {
            return fail_setup("companion depth controls apply 失败");
        }

        UvcStreamConfig depth_config;
        depth_config.name = config_.depth_as_light_ir ? "companion-light-ir" : "companion-depth";
        depth_config.vs_interface = 1;
        depth_config.endpoint = 0x82;
        depth_config.format_index = 1;
        depth_config.frame_index = config_.depth_mode.frame_index;
        depth_config.frame_interval_100ns = config_.depth_mode.interval_100ns;
        UvcNegotiation depth_negotiation;
        if (!negotiate_uvc_stream(*companion_, depth_config, &depth_negotiation, callbacks_.log)) {
            return fail_setup("depth UVC commit 失败");
        }
        return true;
    }

    bool setup_color() {
        if (!config_.enable_color) return true;
        if (!master_) return fail_setup("master 尚未打开");
        UvcStreamConfig color_config;
        color_config.name = "master-color";
        color_config.vs_interface = 1;
        color_config.endpoint = 0x81;
        color_config.format_index = static_cast<uint8_t>(config_.color_format);
        color_config.frame_index = config_.color_mode.frame_index;
        color_config.frame_interval_100ns = config_.color_mode.interval_100ns;
        UvcNegotiation color_negotiation;
        if (!negotiate_uvc_stream(*master_, color_config, &color_negotiation, callbacks_.log)) {
            return fail_setup("color UVC commit 失败");
        }
        return true;
    }

    void depth_loop() {
        const int frame_size = config_.depth_frame_size > 0
            ? config_.depth_frame_size
            : depth_frame_size(config_.depth_mode);
        auto callback = [&](const UvcFrameInfo& info, const uint8_t* data, size_t size) {
            push_depth_pair(info);
            if (callbacks_.depth_frame && !callbacks_.depth_frame(info, data, size)) {
                request_stop(P100R3SessionStopReason::kCallbackStop);
                return false;
            }
            return running_.load();
        };
        const BulkStats stats = pull_raw_frames_until(*companion_,
                                                      0x82,
                                                      running_,
                                                      config_.duration_ms,
                                                      config_.read_len,
                                                      frame_size,
                                                      config_.depth_mode,
                                                      callback,
                                                      callbacks_.log);
        {
            std::lock_guard<std::mutex> lock(mu_);
            stats_.depth = stats;
        }
        note_stream_end(stats);
    }

    void color_loop() {
        BulkStats stats;
        if (config_.color_format == 1) {
            auto callback = [&](const UvcFrameInfo& info, const uint8_t* data, size_t size) {
                push_color_pair(info);
                if (callbacks_.color_frame && !callbacks_.color_frame(info, data, size)) {
                    request_stop(P100R3SessionStopReason::kCallbackStop);
                    return false;
                }
                return running_.load();
            };
            stats = pull_mjpeg_frames_until(*master_,
                                            0x81,
                                            running_,
                                            config_.duration_ms,
                                            config_.read_len,
                                            config_.color_mode,
                                            config_.color_bulk_sample_path,
                                            callback,
                                            callbacks_.log);
        } else {
            const int frame_size = config_.color_raw_frame_size > 0
                ? config_.color_raw_frame_size
                : color_yuy2_frame_size(config_.color_mode);
            auto callback = [&](const UvcFrameInfo& info, const uint8_t* data, size_t size) {
                push_color_pair(info);
                if (callbacks_.color_frame && !callbacks_.color_frame(info, data, size)) {
                    request_stop(P100R3SessionStopReason::kCallbackStop);
                    return false;
                }
                return running_.load();
            };
            stats = pull_raw_frames_until(*master_,
                                          0x81,
                                          running_,
                                          config_.duration_ms,
                                          config_.read_len,
                                          frame_size,
                                          config_.color_mode,
                                          callback,
                                          callbacks_.log);
        }
        {
            std::lock_guard<std::mutex> lock(mu_);
            stats_.color = stats;
        }
        note_stream_end(stats);
    }

    void push_depth_pair(const UvcFrameInfo& info) {
        if (!config_.enable_color) return;
        RgbdFramePairInfo pair;
        bool have_pair = false;
        {
            std::lock_guard<std::mutex> lock(pairer_mu_);
            have_pair = pairer_.push_depth(info, &pair);
        }
        if (have_pair && callbacks_.rgbd_pair) callbacks_.rgbd_pair(pair);
    }

    void push_color_pair(const UvcFrameInfo& info) {
        if (!config_.enable_depth) return;
        RgbdFramePairInfo pair;
        bool have_pair = false;
        {
            std::lock_guard<std::mutex> lock(pairer_mu_);
            have_pair = pairer_.push_color(info, &pair);
        }
        if (have_pair && callbacks_.rgbd_pair) callbacks_.rgbd_pair(pair);
    }

    void note_stream_end(const BulkStats& stats) {
        if (config_.duration_ms > 0 &&
            stats.duration_ms + 500 >= config_.duration_ms &&
            stop_reason() == P100R3SessionStopReason::kNone) {
            return;
        }
        if (!running_.load() && stop_reason() == P100R3SessionStopReason::kNone) {
            request_stop(P100R3SessionStopReason::kBulkError);
        }
    }

    std::vector<uint8_t> stop_stream_types() const {
        std::vector<uint8_t> out;
        if (config_.enable_color) out.push_back(1);
        if (config_.enable_depth && !config_.depth_as_light_ir) {
            out.push_back(2);
            out.push_back(5);
        }
        if (out.empty() && !config_.depth_as_light_ir) {
            out.push_back(1);
            out.push_back(2);
            out.push_back(5);
        }
        return out;
    }

    void cleanup_controls() {
        bool should_cleanup = false;
        {
            std::lock_guard<std::mutex> lock(mu_);
            should_cleanup = !cleanup_done_;
            cleanup_done_ = true;
        }
        if (!should_cleanup) return;

        running_.store(false);
        if (keepalive_thread_.joinable()) keepalive_thread_.join();
        {
            std::lock_guard<std::mutex> lock(mu_);
            stats_.keepalive = keepalive_stats_;
        }
        if (config_.send_master_stop && master_) {
            std::vector<XuPayload> payloads;
            if (config_.depth_as_light_ir) {
                payloads.push_back(make_p100r3_master_force_internal_pwm_trigger_payload(false, 0));
            }
            for (const uint8_t stream_type : stop_stream_types()) {
                payloads.push_back(make_p100r3_master_close_stream_payload(stream_type));
            }
            replay_xu_payloads(*master_, payloads, true, "master-stop", callbacks_.log);
        }
        if (config_.depth_as_light_ir && companion_) {
            const XuPayload payload = make_p100r3_companion_hv3_command_payload({0x01, 0x02, 0x00});
            replay_xu_payloads(*companion_, {payload}, true, "companion-light-ir-stop", callbacks_.log);
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        companion_.reset();
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        master_.reset();
        context_.reset();
    }

    P100R3DualSessionConfig config_;
    P100R3DualSessionCallbacks callbacks_;
    mutable std::mutex mu_;
    mutable std::mutex pairer_mu_;
    P100R3DualSessionStats stats_;
    BulkStats keepalive_stats_;
    RgbdFramePairer pairer_;
    std::atomic<bool> running_{false};
    std::unique_ptr<UsbContext> context_;
    std::unique_ptr<UsbDevice> master_;
    std::unique_ptr<UsbDevice> companion_;
    std::thread keepalive_thread_;
    std::thread depth_thread_;
    std::thread color_thread_;
    bool cleanup_done_ = true;
};

P100R3DualSession::P100R3DualSession(P100R3DualSessionConfig config)
    : impl_(std::make_unique<Impl>(std::move(config))) {
}

P100R3DualSession::~P100R3DualSession() = default;

bool P100R3DualSession::start(P100R3DualSessionCallbacks callbacks) {
    return impl_->start(std::move(callbacks));
}

void P100R3DualSession::stop() {
    impl_->stop();
}

void P100R3DualSession::join() {
    impl_->join();
}

P100R3SessionState P100R3DualSession::state() const {
    return impl_->state();
}

P100R3DualSessionStats P100R3DualSession::stats() const {
    return impl_->stats();
}

BulkStats pull_raw_frames_until(UsbDevice& device,
                                uint8_t endpoint,
                                std::atomic<bool>& running,
                                int duration_ms,
                                int read_len,
                                int frame_size,
                                const P100R3VideoMode& mode,
                                UvcFrameCallback callback,
                                LogFn log) {
    BulkStats stats;
    const int64_t start = now_ms();
    const int64_t deadline = start + duration_ms;
    std::vector<uint8_t> buffer(static_cast<size_t>(read_len));
    UvcRawFrameAssembler assembler(UvcRawFrameAssemblerConfig{
        endpoint,
        mode,
        static_cast<size_t>(std::max(0, frame_size)),
        true,
        frame_size > 0 ? static_cast<size_t>(frame_size) * 3 : 0,
    });

    bool keep_running = true;
    int consecutive_errors = 0;
    int trace_chunks = 0;
    if (const char* env = std::getenv("GOMOB_BERXEL_TRACE_BULK")) {
        trace_chunks = std::atoi(env);
    }

    auto before_deadline = [&]() {
        return duration_ms <= 0 || now_ms() < deadline;
    };

    while (keep_running && running.load() && before_deadline()) {
        int actual = 0;
        const int rc = device.bulk_in(endpoint, buffer.data(), read_len, &actual, 200);
        if (rc == 0 && actual > 0) {
            const int64_t packet_ns = now_ns();
            stats.chunks++;
            stats.bytes += actual;
            consecutive_errors = 0;
            if (trace_chunks > 0 && stats.chunks <= trace_chunks) {
                const int n = std::min(actual, 16);
                log_line(log, "raw chunk " + std::to_string(stats.chunks) +
                              " actual=" + std::to_string(actual) +
                              " head=" + hex_bytes(buffer.data(), n));
            }

            const UvcPayloadView payload = parse_uvc_payload(buffer.data(), actual);
            if (payload.error) {
                continue;
            }
            const int payload_offset = payload.valid ? payload.offset : 0;
            const int payload_length = payload.valid ? payload.length : actual;
            if (payload_length <= 0) {
                continue;
            }
            stats.payload_bytes += payload_length;

            if (frame_size > 0) {
                std::vector<UvcRawFrame> frames;
                assembler.push_packet(buffer.data(), actual, packet_ns, &frames);
                for (const UvcRawFrame& frame : frames) {
                    if (callback && !callback(frame.info, frame.payload.data(), frame.payload.size())) {
                        keep_running = false;
                        running.store(false);
                        break;
                    }
                }
            } else {
                (void)payload_offset;
            }
            continue;
        }

        note_bulk_error(stats, rc);
        consecutive_errors++;
        if (consecutive_errors >= 20) {
            log_line(log, "raw bulk stopped after 20 consecutive errors, last=" +
                      std::string(usb_error_name(rc)));
            running.store(false);
            break;
        }
    }

    const UvcRawFrameAssemblerStats assembler_stats = assembler.stats();
    stats.frames = assembler_stats.frames;
    stats.frame_drops += assembler_stats.frame_drops;
    if (assembler_stats.buffered_bytes > 0) stats.frame_drops++;
    stats.uvc_headers += assembler_stats.uvc_headers;
    stats.completed_by_size += assembler_stats.completed_by_size;
    stats.partial_frame_drops += assembler_stats.partial_frame_drops;
    stats.oversized_frame_drops += assembler_stats.oversized_frame_drops;
    stats.duration_ms = now_ms() - start;
    return stats;
}

BulkStats pull_raw_frames(UsbDevice& device,
                          uint8_t endpoint,
                          int duration_ms,
                          int read_len,
                          int frame_size,
                          const P100R3VideoMode& mode,
                          UvcFrameCallback callback,
                          LogFn log) {
    std::atomic<bool> running{true};
    return pull_raw_frames_until(device,
                                 endpoint,
                                 running,
                                 duration_ms,
                                 read_len,
                                 frame_size,
                                 mode,
                                 callback,
                                 log);
}

BulkStats pull_raw_bulk(UsbDevice& device,
                        uint8_t endpoint,
                        int duration_ms,
                        int read_len,
                        int frame_size,
                        const std::string& first_frame_path,
                        LogFn log) {
    bool first_frame_saved = false;
    UvcFrameCallback callback = [&](const UvcFrameInfo&, const uint8_t* data, size_t size) {
        if (!first_frame_saved && !first_frame_path.empty() && data && size > 0) {
            std::vector<uint8_t> frame(data, data + size);
            first_frame_saved = write_binary_file(first_frame_path, frame);
            if (first_frame_saved) log_line(log, "raw first frame saved: " + first_frame_path);
        }
        return true;
    };
    return pull_raw_frames(device,
                           endpoint,
                           duration_ms,
                           read_len,
                           frame_size,
                           P100R3VideoMode{},
                           callback,
                           log);
}

BulkStats pull_mjpeg_frames_until(UsbDevice& device,
                                  uint8_t endpoint,
                                  std::atomic<bool>& running,
                                  int duration_ms,
                                  int read_len,
                                  const P100R3VideoMode& mode,
                                  const std::string& bulk_sample_path,
                                  UvcFrameCallback callback,
                                  LogFn log) {
    BulkStats stats;
    const int64_t start = now_ms();
    const int64_t deadline = start + duration_ms;
    std::vector<uint8_t> buffer(static_cast<size_t>(read_len));
    UvcMjpegFrameAssembler assembler(UvcMjpegFrameAssemblerConfig{
        endpoint,
        mode,
        8 * 1024 * 1024,
    });
    std::vector<uint8_t> bulk_sample;
    if (!bulk_sample_path.empty()) bulk_sample.reserve(1024 * 1024);
    bool bulk_sample_saved = false;

    bool keep_running = true;
    int consecutive_errors = 0;

    auto before_deadline = [&]() {
        return duration_ms <= 0 || now_ms() < deadline;
    };

    while (keep_running && running.load() && before_deadline()) {
        int actual = 0;
        const int rc = device.bulk_in(endpoint, buffer.data(), read_len, &actual, 200);
        if (rc == 0 && actual > 0) {
            const int64_t packet_ns = now_ns();
            stats.chunks++;
            stats.bytes += actual;
            consecutive_errors = 0;

            if (!bulk_sample_path.empty() && !bulk_sample_saved && bulk_sample.size() < 1024 * 1024) {
                const size_t need = 1024 * 1024 - bulk_sample.size();
                const size_t take = std::min(need, static_cast<size_t>(actual));
                bulk_sample.insert(bulk_sample.end(), buffer.begin(),
                                   buffer.begin() + static_cast<std::ptrdiff_t>(take));
                if (bulk_sample.size() >= 1024 * 1024) {
                    if (write_binary_file(bulk_sample_path, bulk_sample)) {
                        log_line(log, "mjpeg bulk sample saved: " + bulk_sample_path);
                    }
                    bulk_sample_saved = true;
                }
            }

            const UvcPayloadView payload = parse_uvc_payload(buffer.data(), actual);
            if (payload.error) {
                continue;
            }
            const int payload_len = payload.valid ? payload.length : actual;
            stats.payload_bytes += std::max(0, payload_len);

            std::vector<UvcMjpegFrame> frames;
            assembler.push_packet(buffer.data(), actual, packet_ns, &frames);
            for (const UvcMjpegFrame& frame : frames) {
                if (callback && !callback(frame.info, frame.jpeg.data(), frame.jpeg.size())) {
                    keep_running = false;
                    running.store(false);
                    break;
                }
            }
            continue;
        }

        note_bulk_error(stats, rc);
        consecutive_errors++;
        if (consecutive_errors >= 20) {
            log_line(log, "mjpeg bulk stopped after 20 consecutive errors, last=" +
                      std::string(usb_error_name(rc)));
            running.store(false);
            break;
        }
    }

    const UvcMjpegFrameAssemblerStats assembler_stats = assembler.stats();
    stats.frames = assembler_stats.frames;
    stats.frame_drops += assembler_stats.frame_drops;
    if (assembler_stats.buffered_bytes > 0) stats.frame_drops++;
    stats.uvc_headers += assembler_stats.uvc_headers;
    stats.fid_toggles += assembler_stats.fid_toggles;
    stats.completed_by_eof += assembler_stats.completed_by_eof;
    stats.completed_by_fid += assembler_stats.completed_by_fid;
    stats.completed_by_jpeg_eoi += assembler_stats.completed_by_jpeg_eoi;
    stats.oversized_frame_drops += assembler_stats.oversized_frame_drops;
    if (!bulk_sample_path.empty() && !bulk_sample_saved && !bulk_sample.empty()) {
        if (write_binary_file(bulk_sample_path, bulk_sample)) {
            log_line(log, "mjpeg bulk sample saved at tail: " + bulk_sample_path);
        }
    }
    stats.duration_ms = now_ms() - start;
    return stats;
}

BulkStats pull_mjpeg_frames(UsbDevice& device,
                            uint8_t endpoint,
                            int duration_ms,
                            int read_len,
                            const P100R3VideoMode& mode,
                            const std::string& bulk_sample_path,
                            UvcFrameCallback callback,
                            LogFn log) {
    std::atomic<bool> running{true};
    return pull_mjpeg_frames_until(device,
                                   endpoint,
                                   running,
                                   duration_ms,
                                   read_len,
                                   mode,
                                   bulk_sample_path,
                                   callback,
                                   log);
}

BulkStats pull_mjpeg_bulk(UsbDevice& device,
                          uint8_t endpoint,
                          int duration_ms,
                          int read_len,
                          const std::string& first_jpeg_path,
                          const std::string& bulk_sample_path,
                          LogFn log) {
    bool first_saved = false;
    UvcFrameCallback callback = [&](const UvcFrameInfo&, const uint8_t* data, size_t size) {
        if (!first_saved && !first_jpeg_path.empty() && data && size > 0) {
            std::vector<uint8_t> frame(data, data + size);
            first_saved = save_jpeg_candidate(frame, first_jpeg_path);
            if (first_saved) log_line(log, "mjpeg first frame saved: " + first_jpeg_path);
        }
        return true;
    };
    return pull_mjpeg_frames(device,
                             endpoint,
                             duration_ms,
                             read_len,
                             P100R3VideoMode{},
                             bulk_sample_path,
                             callback,
                             log);
}

}  // namespace gomob::berxel::host
