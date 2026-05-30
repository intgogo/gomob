#ifndef GOMOB_BERXEL_PORTABLE_H
#define GOMOB_BERXEL_PORTABLE_H

// P100R3 自研 SDK 可移植层：不依赖 Linux libusb / 文件系统，Android native 可直接编译复用。
// 包含数据契约、UVC 帧组装、RGBD 配对、depth/light-ir 解码、XU payload 生成与协议编排。

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <functional>
#include <limits>
#include <memory>
#include <string>
#include <vector>

namespace gomob::berxel::host {

struct UsbId {
    uint16_t vid = 0;
    uint16_t pid = 0;
};

struct XuPayload {
    uint8_t selector = 0;
    uint16_t w_value = 0;
    uint16_t w_index = 0;
    std::vector<uint8_t> data;
};

struct P100R3VideoMode {
    uint8_t frame_index = 0;
    uint16_t width = 0;
    uint16_t height = 0;
    uint16_t fps = 0;
    uint32_t interval_100ns = 0;
};

enum class P100R3DepthPixelFormat : uint8_t {
    k12I4D = 1,
    k13I3D = 2,
    k14I2D = 4,
};

struct P100R3DepthControls {
    bool enabled = true;
    bool set_auto_exposure = true;
    bool auto_exposure = true;
    bool set_confidence = true;
    uint8_t confidence = 3;
    bool set_depth_gain = false;
    uint8_t depth_gain = 1;
    bool set_temporal_denoise = true;
    bool temporal_denoise = false;
    bool set_spatial_denoise = true;
    bool spatial_denoise = false;
};

struct P100R3DepthProcessingConfig {
    P100R3DepthPixelFormat format = P100R3DepthPixelFormat::k13I3D;
    float min_depth_mm = 200.0f;
    float max_depth_mm = 2000.0f;
    int seed_support_radius_px = 2;
    int min_seed_support = 2;
    int max_fill_distance_px = 512;
    bool edge_aware_fill = true;
    int fill_consistency_radius_px = 1;
    float max_fill_depth_delta_mm = 80.0f;
    float target_valid_ratio = 0.99f;
    uint8_t raw_confidence = 255;
    uint8_t fill_confidence_near = 180;
    uint8_t fill_confidence_min = 32;
};

struct P100R3DepthProcessingStats {
    uint16_t active_width = 0;
    uint16_t active_height = 0;
    uint32_t active_pixels = 0;
    uint32_t raw_valid_pixels = 0;
    uint32_t rejected_out_of_range_pixels = 0;
    uint32_t seed_pixels = 0;
    uint32_t filled_pixels = 0;
    uint32_t edge_blocked_pixels = 0;
    uint32_t processed_valid_pixels = 0;
    int max_fill_distance_px = 0;
};

struct UvcStreamConfig {
    std::string name;
    int vs_interface = 1;
    uint8_t endpoint = 0;
    uint8_t format_index = 1;
    uint8_t frame_index = 1;
    uint32_t frame_interval_100ns = 333333;
    uint32_t max_video_frame_size = 0;
    uint32_t max_payload_transfer_size = 0;
    bool use_get_def = true;
};

struct UvcNegotiation {
    std::array<uint8_t, 26> probe = {};
    uint8_t format_index = 0;
    uint8_t frame_index = 0;
    uint32_t frame_interval_100ns = 0;
    uint32_t max_video_frame_size = 0;
    uint32_t max_payload_transfer_size = 0;
};

struct BulkStats {
    int64_t chunks = 0;
    int64_t bytes = 0;
    int64_t payload_bytes = 0;
    int64_t frames = 0;
    int64_t frame_drops = 0;
    int64_t uvc_headers = 0;
    int64_t fid_toggles = 0;
    int64_t completed_by_eof = 0;
    int64_t completed_by_size = 0;
    int64_t completed_by_fid = 0;
    int64_t completed_by_jpeg_eoi = 0;
    int64_t partial_frame_drops = 0;
    int64_t oversized_frame_drops = 0;
    int64_t timeouts = 0;
    int64_t errors = 0;
    int first_error = 0;
    int64_t duration_ms = 0;
};

using LogFn = std::function<void(const std::string&)>;

struct UvcFrameInfo {
    uint8_t endpoint = 0;
    P100R3VideoMode mode;
    uint64_t frame_number = 0;
    int64_t host_start_ns = 0;
    int64_t host_end_ns = 0;
    uint32_t transport_bytes = 0;
    uint32_t payload_bytes = 0;
    bool has_uvc_header = false;
    bool has_uvc_pts = false;
    uint32_t uvc_pts = 0;
    bool has_uvc_scr = false;
    uint32_t uvc_scr_stc = 0;
    uint16_t uvc_scr_sof = 0;
    uint8_t fid = 0;
    bool completed_by_eof = false;
    bool completed_by_size = false;
    bool completed_by_fid = false;
    bool completed_by_jpeg_eoi = false;
};

using UvcFrameCallback = std::function<bool(const UvcFrameInfo& info,
                                            const uint8_t* data,
                                            size_t size)>;

struct UvcMjpegFrame {
    UvcFrameInfo info;
    std::vector<uint8_t> jpeg;
};

struct UvcRawFrame {
    UvcFrameInfo info;
    std::vector<uint8_t> payload;
};

struct UvcRawFrameAssemblerConfig {
    uint8_t endpoint = 0;
    P100R3VideoMode mode;
    size_t frame_size = 0;
    bool drop_partial_on_uvc_header = true;
    size_t max_buffer_bytes = 0;
};

struct UvcRawFrameAssemblerStats {
    int64_t frames = 0;
    int64_t frame_drops = 0;
    int64_t partial_frame_drops = 0;
    int64_t uvc_headers = 0;
    int64_t completed_by_size = 0;
    int64_t oversized_frame_drops = 0;
    size_t buffered_bytes = 0;
};

class UvcRawFrameAssembler {
public:
    explicit UvcRawFrameAssembler(UvcRawFrameAssemblerConfig config = {});

    void reset();
    bool push_packet(const uint8_t* data,
                     int actual,
                     int64_t packet_ns,
                     std::vector<UvcRawFrame>* out_frames);
    UvcRawFrameAssemblerStats stats() const;

private:
    UvcRawFrameAssemblerConfig config_;
    UvcRawFrameAssemblerStats stats_;
    std::vector<uint8_t> frame_;
    UvcFrameInfo current_;
    bool have_current_ = false;
};

struct UvcMjpegFrameAssemblerConfig {
    uint8_t endpoint = 0;
    P100R3VideoMode mode;
    size_t max_frame_bytes = 8 * 1024 * 1024;
};

struct UvcMjpegFrameAssemblerStats {
    int64_t frames = 0;
    int64_t frame_drops = 0;
    int64_t uvc_headers = 0;
    int64_t fid_toggles = 0;
    int64_t completed_by_eof = 0;
    int64_t completed_by_fid = 0;
    int64_t completed_by_jpeg_eoi = 0;
    int64_t oversized_frame_drops = 0;
    size_t buffered_bytes = 0;
};

class UvcMjpegFrameAssembler {
public:
    explicit UvcMjpegFrameAssembler(UvcMjpegFrameAssemblerConfig config = {});

    void reset();
    bool push_packet(const uint8_t* data,
                     int actual,
                     int64_t packet_ns,
                     std::vector<UvcMjpegFrame>* out_frames);
    UvcMjpegFrameAssemblerStats stats() const;

private:
    UvcMjpegFrameAssemblerConfig config_;
    UvcMjpegFrameAssemblerStats stats_;
    std::vector<uint8_t> frame_;
    UvcFrameInfo current_;
    bool have_current_ = false;
    bool have_fid_ = false;
    bool waiting_for_next_fid_ = false;
    uint8_t current_fid_ = 0;
};

struct RgbdFramePairInfo {
    uint64_t pair_number = 0;
    UvcFrameInfo color;
    UvcFrameInfo depth;
    int64_t host_delta_ns = 0;  // depth 中点减 color 中点
    bool within_tolerance = false;
};

struct RgbdFramePairerConfig {
    int64_t max_delta_ns = 50 * 1000 * 1000;
    size_t max_color_queue = 64;
    size_t max_depth_queue = 128;
};

struct RgbdPairingStats {
    int64_t pairs = 0;
    int64_t dropped_color_frames = 0;
    int64_t dropped_depth_frames = 0;
    size_t queued_color_frames = 0;
    size_t queued_depth_frames = 0;
    int64_t last_host_delta_ns = 0;
    int64_t mean_abs_host_delta_ns = 0;
    int64_t max_abs_host_delta_ns = 0;
    uint64_t last_color_frame_number = 0;
    uint64_t last_depth_frame_number = 0;
};

int64_t uvc_frame_midpoint_ns(const UvcFrameInfo& info);

class RgbdFramePairer {
public:
    explicit RgbdFramePairer(RgbdFramePairerConfig config = {});

    void reset();
    bool push_depth(const UvcFrameInfo& depth, RgbdFramePairInfo* out);
    bool push_color(const UvcFrameInfo& color, RgbdFramePairInfo* out);

    int64_t pair_count() const;
    int64_t dropped_color_frames() const;
    int64_t dropped_depth_frames() const;
    size_t queued_color_frames() const;
    size_t queued_depth_frames() const;
    RgbdPairingStats stats() const;

private:
    bool try_pair(RgbdFramePairInfo* out);

    RgbdFramePairerConfig config_;
    std::deque<UvcFrameInfo> color_queue_;
    std::deque<UvcFrameInfo> depth_queue_;
    int64_t pair_count_ = 0;
    int64_t dropped_color_frames_ = 0;
    int64_t dropped_depth_frames_ = 0;
    int64_t last_host_delta_ns_ = 0;
    int64_t sum_abs_host_delta_ns_ = 0;
    int64_t max_abs_host_delta_ns_ = 0;
    uint64_t last_color_frame_number_ = 0;
    uint64_t last_depth_frame_number_ = 0;
};

enum class P100R3SessionState {
    kIdle,
    kOpening,
    kStreaming,
    kStopping,
    kStopped,
    kFailed,
};

enum class P100R3SessionStopReason {
    kNone,
    kUserStop,
    kDurationReached,
    kCallbackStop,
    kBulkError,
    kSetupFailed,
};

const char* p100r3_session_state_name(P100R3SessionState state);
const char* p100r3_session_stop_reason_name(P100R3SessionStopReason reason);

constexpr UsbId kP100R3MasterId{0x0603, 0x001f};
constexpr UsbId kP100R3CompanionId{0x3558, 0x1012};
constexpr uint16_t kP100R3MasterXu5WIndex = 0x0500;
constexpr uint16_t kP100R3CompanionXu3WIndex = 0x0300;

// 与 libusb 错误码对齐，portable 层不直接依赖 libusb.h。
constexpr int kUvcErrorTimeout = -7;    // == LIBUSB_ERROR_TIMEOUT
constexpr int kUvcErrorNoDevice = -4;   // == LIBUSB_ERROR_NO_DEVICE

// USB UVC 设备抽象。host 层 UsbDevice(libusb) 与 Android 层各实现一份，
// 让 XU replay / depth controls / UVC 协商 / keepalive 等编排逻辑跨平台复用。
class IUvcDevice {
public:
    virtual ~IUvcDevice() = default;

    virtual int control_transfer(uint8_t bm_request_type,
                                 uint8_t b_request,
                                 uint16_t w_value,
                                 uint16_t w_index,
                                 uint8_t* data,
                                 uint16_t length,
                                 uint32_t timeout_ms) = 0;

    virtual int uvc_set_cur(uint16_t w_value,
                            uint16_t w_index,
                            uint8_t* data,
                            uint16_t length,
                            uint32_t timeout_ms = 2000) = 0;

    virtual int uvc_get_cur(uint16_t w_value,
                            uint16_t w_index,
                            uint8_t* data,
                            uint16_t length,
                            uint32_t timeout_ms = 2000) = 0;

    virtual int uvc_get_def(uint16_t w_value,
                            uint16_t w_index,
                            uint8_t* data,
                            uint16_t length,
                            uint32_t timeout_ms = 2000) = 0;

    virtual int bulk_in(uint8_t endpoint,
                        uint8_t* data,
                        int length,
                        int* actual_length,
                        uint32_t timeout_ms) = 0;
};

const char* usb_error_name(int rc);
std::string usb_id_string(UsbId id);


// 共享底层 helper：libusb IO 层与纯逻辑层都要用，放 detail 子命名空间，
// 不进入公共 API 表面，但跨 TU 可链接。定义在 gomob_berxel_portable.cpp。
namespace detail {

struct UvcPayloadView {
    int offset = 0;
    int length = 0;
    bool valid = false;
    bool eof = false;
    bool error = false;
    bool has_pts = false;
    bool has_scr = false;
    uint32_t pts = 0;
    uint32_t scr_stc = 0;
    uint16_t scr_sof = 0;
    uint8_t fid = 0;
};

int64_t now_ms();
int64_t now_ns();
void log_line(LogFn log, const std::string& msg);
uint32_t read_le32(const uint8_t* p);
uint16_t read_le16(const std::vector<uint8_t>& data, size_t offset);
void write_le16(std::vector<uint8_t>& data, size_t offset, uint16_t v);
void write_le32(uint8_t* p, uint32_t v);
bool is_master_time_sync_payload(const std::vector<uint8_t>& data);
bool is_p100r3_color_open_stream_payload(const XuPayload& payload);
bool is_p100r3_depth_open_stream_payload(const XuPayload& payload);
std::string hex16(uint16_t v);
int hex_digit(char c);
std::vector<uint8_t> hex_to_bytes(const std::string& hex);
bool extract_int(const std::string& text, const std::string& key, int* out);
bool extract_hex(const std::string& text, const std::string& key, std::string* out);
size_t find_marker(const std::vector<uint8_t>& data, uint8_t a, uint8_t b, size_t start);
size_t rfind_marker(const std::vector<uint8_t>& data, uint8_t a, uint8_t b);
bool find_jpeg_bounds(const std::vector<uint8_t>& frame, size_t* begin, size_t* end);
std::string hex_bytes(const uint8_t* data, int length);
UvcPayloadView parse_uvc_payload(const uint8_t* data, int actual);
void begin_frame_info(UvcFrameInfo* info, uint8_t endpoint, const P100R3VideoMode& mode,
                      uint64_t frame_number, int64_t host_ns, const UvcPayloadView& payload);
void update_frame_info(UvcFrameInfo* info, int actual, int payload_length,
                       int64_t host_ns, const UvcPayloadView& payload);
void note_bulk_error(BulkStats& stats, int rc);
bool is_master_keepalive_payload(const XuPayload& payload);
bool find_keepalive_seed(const std::vector<XuPayload>& payloads, XuPayload* out);
int depth_frame_size(const P100R3VideoMode& mode);
int color_yuy2_frame_size(const P100R3VideoMode& mode);

}  // namespace detail

int refresh_master_time_sync_payloads(std::vector<XuPayload>* payloads);

std::string hex_bytes_compact(const std::vector<uint8_t>& data, size_t max_bytes = 0);

uint8_t p100r3_depth_mode_code(const P100R3VideoMode& mode);
uint8_t p100r3_depth_fraction_bits(P100R3DepthPixelFormat format);
uint16_t p100r3_depth_active_height(const P100R3VideoMode& transport_mode);
P100R3VideoMode p100r3_depth_active_mode(const P100R3VideoMode& transport_mode);
float p100r3_depth_raw_to_mm(uint16_t raw,
                             P100R3DepthPixelFormat format = P100R3DepthPixelFormat::k13I3D);
bool process_p100r3_depth_frame(const uint8_t* transport_frame,
                                size_t transport_size,
                                const P100R3VideoMode& transport_mode,
                                const P100R3DepthProcessingConfig& config,
                                std::vector<uint16_t>* processed_active_raw16,
                                std::vector<uint8_t>* confidence,
                                P100R3DepthProcessingStats* stats = nullptr);
bool process_p100r3_light_ir_frame(const uint8_t* transport_frame,
                                   size_t transport_size,
                                   const P100R3VideoMode& transport_mode,
                                   std::vector<uint16_t>* active_ir10);

// 深度时域降噪：设备 temporal_denoise 关掉换来稠密(valid≈1.0)，代价是逐像素相邻帧
// 抖动 ~38mm（远超 ≤1%@1-2m 规格）。grounding 仿真证实有界滑窗均值 N=8 能把抖动压到
// ~11mm（3.5×）且密度不掉、无偏移；朴素小阈值 EMA 失效（噪声>阈值→每帧 reset→透传），
// 故运动门限必须按噪声底/深度缩放。详见
// `tests/harness/depth_temporal_quality/`、`.dev/depth-temporal-analysis/CONCLUSION.md`。
// 在 raw valid 像素上做（量测真值），融合后再可选补洞；processed 仍只作 VIN/分割/弱置信。
// 飞点（flying pixel）= 结构光在前景/背景断崖之间插值出的悬浮假点，污染点云、是相邻帧
// 抖动 p95 的主来源。grounding 证：纯单帧检测过杀 24%（把真实斜面/边缘当飞点）；真飞点需
// "空间断崖 ∧ 时域不稳"联合判定，时域信号取自 P100R3TemporalFilter 窗口（断崖里 91.9% 时域不稳、
// 真稳定边缘仅 2.4%）。下面是【单帧空间几何】判据配置（无状态，可单独 host-test）。
// 详见 tests/harness/depth_flying_pixel/、.dev/flying-pixel-analysis/SYNTHESIS.md。
struct P100R3FlyingPixelConfig {
    P100R3DepthPixelFormat format = P100R3DepthPixelFormat::k13I3D;
    // 视线掠射角度上界：真实斜面坡度 ≤ grazing_max，阶跃必落 step_max(Z)=tan(grazing)·Z/f·Δpx 内。
    // 把固定 mm 阈值换成物理坡度上界，从根因消除单帧过杀（不分远近/坡度）。fx/fy 暂用 FOV 反推，
    // 标定 blob 到位后用真内参 + 畸变覆盖。
    float fx_px = 440.4f;                  // (W/2)/tan(36°)，72° 水平 FOV；W 变化按比例重算
    float fy_px = 424.1f;                  // (H/2)/tan(25.25°)，50.5° 垂直 FOV
    float grazing_angle_max_deg = 88.0f;   // 真实表面坡度上界；留余量防 FOV 反推误差，漏判=保守
    int sandwich_radius_px = 3;            // 夹心邻域外探半径：抓 1-3px 飞点带（中间列看不到直邻 fg/bg）
    // 共面支撑护盾：斜面/曲面恒有近共面邻居 → 否决删除；真悬空飞点 support≈0。
    float support_band_mm = 30.0f;         // 共面容差绝对底（fused 降噪后同面差 <~10mm，留斜面余量）
    float support_band_pct = 0.04f;        // 共面容差深度自适应（远距同面差变大）
    int min_coplanar_support = 2;          // 共面邻居 ≥ 此值则强豁免（判真表面不删）
};

struct P100R3FlyingSpatialEvidence {
    bool sandwich = false;   // 双侧角度超界夹心（被更近的崖和更远的崖同时夹住=悬浮）
    int support = 0;         // 8 邻域共面邻居数
    bool no_support = false; // support < min_coplanar_support
};

// 无状态单帧空间几何判据：在【fused】depth(0=无效) 上算像素 idx 是否几何上像飞点。
// angle_scale>1 收紧角度阈（降级路径用），1.0=正常。返回 false 表示像素无效/越界。
bool p100r3_flying_spatial_evidence(const std::vector<uint16_t>& fused_raw16,
                                    uint16_t width,
                                    uint16_t height,
                                    size_t idx,
                                    const P100R3FlyingPixelConfig& config,
                                    float angle_scale,
                                    P100R3FlyingSpatialEvidence* out);

struct P100R3TemporalFilterConfig {
    P100R3DepthPixelFormat format = P100R3DepthPixelFormat::k13I3D;
    int window = 8;                      // 滑窗深度（每像素最多保留的有效样本数）
    // 运动门限必须 ≥ 噪声底，否则退化成"每帧 reset"的 EMA 陷阱（见 CONCLUSION.md）。
    // 噪声底随场景/距离/SDK 变（实测 vendor 干净 ~38mm、host live ~63mm），固定阈值不泛化 →
    // 门限【自适应】：每帧用 robust median(|cur-est|) 估噪声底，门限 = max(绝对底, k×噪声估计, percent×深度)。
    // 真硬件验证：自适应后 vendor 38mm 与 live 63mm 两场景在同一 k 上增益几乎相同（归一化），
    // k=2.0（门限=2×噪声底，运动/离群门限标准裕度）两场景都拿 ~4.1× 增益、零偏移、密度不掉。
    float motion_reset_mm = 45.0f;       // 运动门限绝对底（最干净场景的下限）
    float motion_reset_noise_k = 2.0f;   // 门限 = k × 自适应噪声底估计（核心，泛化跨场景/距离）
    float motion_reset_percent = 0.03f;  // 运动门限随深度缩放（结构光噪声随深度增大）
    int min_samples_full_conf = 4;       // 达到此样本数即给满 temporal confidence
    uint8_t full_confidence = 255;
    uint8_t single_sample_confidence = 96;  // 仅 1 个样本（未降噪）的置信
    // 飞点剔除（在 fuse 之后做：用窗口时域信号 + fused 降噪后梯度）。push 的 flying_mask 出参非空才启用。
    bool flying_enable = true;
    P100R3FlyingPixelConfig flying;
    float flying_tstd_floor_mm = 60.0f;            // 时域不稳绝对底（窗口 span，抓不触发 reset 的抖动）
    float flying_tstd_percent = 0.03f;             // 时域门深度自适应
    // 时域稳定 = 连续 ≥ min_stable 帧未发生运动 reset。慢性飞点频繁 reset → stable_run 小 → 不稳；
    // 真表面 stable_run 大 → 稳。暖机（总观测 frames_seen < min_stable）只降权不删（信号不足，宁漏勿杀）。
    int flying_min_stable_samples = 3;
    float flying_single_frame_angle_scale = 1.8f;  // 暖机/降级路径角度收紧倍数（宁漏勿杀）
    uint8_t flying_weak_confidence = 32;           // 降级弱置信
    // ── 真置信（温度稳定性派生）──
    // 设备 confidence 饱和无效（实测 98.7% 都是 255，噪声像素也标满置信），改用窗口 span（跳幅）派生：
    // span 越大（越抖）→ 置信越低，即使窗口攒满。fused_raw16 原值不动（保稠密），下游按 conf 掩码/加权。
    // 真帧验证：~74% 像素时域不稳（相邻帧跳 >85mm 中位），密度优先把真表面埋在噪声里。详见 .dev/denoise-proto。
    bool confidence_from_stability = true;
    float conf_stable_span_mm = 20.0f;    // 窗口 span ≤ 此 → 满稳定（conf 不降）
    float conf_unstable_span_mm = 80.0f;  // 窗口 span ≥ 此 → 不可信（稳定因子→0）
    float conf_span_percent = 0.02f;      // 两带随深度缩放（远处结构光噪声更大）
    uint8_t conf_min_valid = 8;           // 有效像素稳定性置信下限（0 留给飞点/无效，便于区分）
    // ── 空间降噪（median3 去脉冲 + bilateral5 保边）──
    // 设备 spatial_denoise 关掉换稠密，结构光原始深度逐像素 speckle ~40%。在 fuse 后、飞点前作用 fused_raw16。
    // 真帧实测 noise_p50 27→9mm、edge_keep 0.89、density 不掉。flying 合成 harness 需关掉以隔离测试。
    bool spatial_denoise_enable = true;
    float spatial_sigma_r_mm = 40.0f;     // bilateral range σ（取 noise 谷底）
    float spatial_sigma_s = 2.0f;         // bilateral 空间 σ（px）
};

struct P100R3TemporalFilterStats {
    uint32_t fused_pixels = 0;          // 本帧有融合输出（count>0）的像素
    uint32_t motion_resets = 0;         // 本帧触发运动重置的像素
    uint32_t single_sample_pixels = 0;  // 本帧仍只有 1 个样本的像素
    float mean_window_fill = 0.0f;      // 有效像素的平均窗口填充度
    uint32_t flying_pixels = 0;         // 判为飞点（置 conf=0）的像素
    uint32_t flying_spatial_hits = 0;   // 空间证据命中（sandwich）的像素
    uint32_t flying_temporal_gated = 0; // 时域不稳门命中的像素
    uint32_t flying_blocked_by_support = 0;  // 被共面支撑豁免（救回）的像素
    float noise_floor_mm = 0.0f;        // 自适应噪声底估计（mm），= 运动门限/k 的来源
};

// 有状态多帧融合器：跨平台（host + Android）复用。新 burst/pose 调 reset()。
class P100R3TemporalFilter {
public:
    explicit P100R3TemporalFilter(P100R3TemporalFilterConfig config = {});

    void reset();
    const P100R3TemporalFilterConfig& config() const { return config_; }

    // 输入当前帧 active raw16（0=无效，长度 = width*height）。
    // 输出 fused raw16（0=仍无效）+ 可选 temporal confidence。尺寸变化自动 reset。
    // flying_mask 非空且 config.flying_enable 时，跑飞点剔除：飞点处 flying_mask=1 且
    // confidence 该位清 0（fused_raw16 原值不动，保"raw 是测量真值"）。
    bool push(const std::vector<uint16_t>& active_raw16,
              uint16_t width,
              uint16_t height,
              std::vector<uint16_t>* fused_raw16,
              std::vector<uint8_t>* confidence = nullptr,
              P100R3TemporalFilterStats* stats = nullptr,
              std::vector<uint8_t>* flying_mask = nullptr);

private:
    P100R3TemporalFilterConfig config_;
    uint16_t width_ = 0;
    uint16_t height_ = 0;
    int window_ = 1;
    std::vector<uint16_t> samples_;  // window_ × pixels（环形缓冲，行优先按像素分组）
    std::vector<uint8_t> count_;     // 每像素有效样本数 [0..window_]
    std::vector<uint8_t> cursor_;    // 每像素环形写指针 [0..window_)
    std::vector<uint16_t> window_span_raw_;  // 当前窗口 max-min（飞点时域门：抓不触发 reset 的抖动）
    std::vector<uint8_t> stable_run_;        // 连续未 reset 的帧数（reset→0，否则 ++ 饱和）；大=时域稳
    std::vector<uint8_t> frames_seen_;       // 该像素总有效观测数（饱和）；区分暖机 vs 慢性飞点
    float noise_est_raw_ = 0.0f;             // 自适应噪声底估计（raw 单位，EMA）；运动门限随它走
    std::vector<int> diff_scratch_;          // 每帧 |cur-est| 暂存（求 median 估噪声底，复用免重分配）
    std::vector<uint16_t> denoise_scratch_;  // 空间降噪 ping-pong 暂存（复用免重分配）

    // median3（去脉冲尖峰）→ bilateral5（保边平滑），原地作用于 fused（0 保持 0，不新填）。
    void apply_spatial_denoise(std::vector<uint16_t>* fused);
};

XuPayload make_p100r3_master_color_open_stream_payload(const P100R3VideoMode& mode);
XuPayload make_p100r3_master_force_internal_pwm_trigger_payload(bool enabled, uint8_t fps);
XuPayload make_p100r3_companion_depth_open_stream_payload(const P100R3VideoMode& mode);
XuPayload make_p100r3_companion_light_ir_open_stream_payload(const P100R3VideoMode& mode);
XuPayload make_p100r3_companion_hv3_command_payload(const std::vector<uint8_t>& prefix);
XuPayload make_p100r3_depth_auto_exposure_payload(bool enabled);
XuPayload make_p100r3_depth_confidence_payload(uint8_t confidence);
XuPayload make_p100r3_depth_gain_payload(uint8_t gain);
XuPayload make_p100r3_depth_temporal_denoise_payload(bool enabled);
XuPayload make_p100r3_depth_spatial_denoise_payload(bool enabled);
XuPayload make_p100r3_master_close_stream_payload(uint8_t stream_type);

int patch_p100r3_master_color_open_stream_payloads(std::vector<XuPayload>* payloads,
                                                   const P100R3VideoMode& mode,
                                                   std::string* payload_hex = nullptr);

int patch_p100r3_companion_depth_open_stream_payloads(std::vector<XuPayload>* payloads,
                                                      const P100R3VideoMode& mode,
                                                      std::string* payload_prefix_hex = nullptr);

int patch_p100r3_companion_light_ir_open_stream_payloads(std::vector<XuPayload>* payloads,
                                                         const P100R3VideoMode& mode,
                                                         std::string* payload_prefix_hex = nullptr);

bool replay_xu_payloads(IUvcDevice& device,
                        const std::vector<XuPayload>& payloads,
                        bool read_back,
                        const std::string& label,
                        LogFn log = {});

bool apply_p100r3_depth_controls(IUvcDevice& device,
                                 const P100R3DepthControls& controls,
                                 LogFn log = {});

bool negotiate_uvc_stream(IUvcDevice& device,
                          const UvcStreamConfig& config,
                          UvcNegotiation* out,
                          LogFn log = {});

void master_keepalive_loop(IUvcDevice& master,
                           XuPayload seed,
                           int interval_ms,
                           std::atomic<bool>& running,
                           BulkStats& stats,
                           LogFn log = {});

// 解析 XU payload JSON 文本（不读文件，Android 可传 asset bytes）。
std::vector<XuPayload> parse_xu_payloads(const std::string& text,
                                         uint16_t default_w_value,
                                         uint16_t default_w_index,
                                         int limit = -1);

}  // namespace gomob::berxel::host

#endif  // GOMOB_BERXEL_PORTABLE_H
