// gomob native — Berxel P100R3 双流会话 JNI（Android 迁移 Step 3）
//
// 目标：把 Linux host 已实机验证的双流启动序列原样迁到 Android，复用 portable 层
//       （IUvcDevice 编排 + UvcRawFrameAssembler / UvcMjpegFrameAssembler + RgbdFramePairer），
//       不再用 Kotlin 侧弱化且把 depth 当 IR 的旧编排。
//
// 设备获取差异（与 Linux host 唯一不同点）：
//   - Linux：UsbContext::open(vid,pid) 走 libusb 枚举。
//   - Android：NO_DEVICE_DISCOVERY + libusb_wrap_sys_device 接管 Java 拿到的 usbfs fd。
//   两侧都把 fd/handle 包成 IUvcDevice，之后的 XU replay / depth controls / UVC 协商 / keepalive
//   全部走 portable 层同一份函数。
//
// 验证边界：编译进 gomob_native.so 通过即接口形状正确；真流 depth/同步必须在真机（vivo PD2324 +
//           带电 hub）跑出连续帧 + raw/8 mm 合理值才算通，rc=0 不代表成功。
//
// ★ M6.8b ④（2026-06-02 收尾）：生产唯一路径 = berxel_open_dual + berxel_snap_* core +
//   BerxelSessionAdapter/BerxelDriver。Kotlin BerxelNativeStack 经 cameraOpenByFds(0x0603:0x001f)
//   → BerxelDriver → berxel_open_dual 走这里，与 eYs3D 同 ICameraSession 契约。
//   历史 berxelDual* device-gated 回退 JNI 已在 color+depth 真机 PASS(2510DRK44C)后删除——
//   无并发分叉。见 docs/agent-memory/finding_p100r3_mix_color_depth_2026-06-02.md。

// ★ M6.8b ④ host 统一（2026-06-01）：本文件 host(Linux 开发服务器,libusb 枚举)与 Android(JNI,
//   wrap_sys_device fd)双目标编译。Android 专属区(jni.h / android-log / LOGI-LOGE)用 #ifdef __ANDROID__
//   守；core/adapter/driver/open_host 两端共用。host 经 BerxelDriver::open_host
//   → berxel_open_dual_host 对真机做 ICameraSession 统一验证(对原厂 oracle)。
#ifdef __ANDROID__
#include <jni.h>
#include <android/log.h>
#endif

#include <atomic>
#include <algorithm>
#include <array>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <libusb-1.0/libusb.h>

#include "gomob_berxel_portable.h"
#include "camera/camera_session.h"        // M6.8b ④：ICameraSession/ICameraDriver 抽象
#include "camera/host/usb_context.h"      // host 枚举打开(open_host)
#include "berxel/host/berxel_camera_adapter.h"  // MakeBerxelDriver() 工厂声明

#ifdef __ANDROID__
#define LOG_TAG "gomob_berxel_dual"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) do { std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGW(...) do { std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace {

using namespace gomob::berxel::host;

constexpr int kDefaultBulkXferCount = 48;
constexpr int kBerxelCfgBaseWords = 14;
constexpr int kBerxelCfgWords = 15;

// ---- AndroidUvcDevice：IUvcDevice over libusb handle，对应 Linux host 的 UsbDevice ----
class AndroidUvcDevice : public IUvcDevice {
public:
    explicit AndroidUvcDevice(libusb_device_handle* handle) : handle_(handle) {}

    int control_transfer(uint8_t bm_request_type,
                         uint8_t b_request,
                         uint16_t w_value,
                         uint16_t w_index,
                         uint8_t* data,
                         uint16_t length,
                         uint32_t timeout_ms) override {
        if (!handle_) return LIBUSB_ERROR_NO_DEVICE;
        return libusb_control_transfer(handle_, bm_request_type, b_request,
                                       w_value, w_index, data, length, timeout_ms);
    }
    int uvc_set_cur(uint16_t w_value, uint16_t w_index,
                    uint8_t* data, uint16_t length, uint32_t timeout_ms) override {
        return control_transfer(0x21, 0x01, w_value, w_index, data, length, timeout_ms);
    }
    int uvc_get_cur(uint16_t w_value, uint16_t w_index,
                    uint8_t* data, uint16_t length, uint32_t timeout_ms) override {
        return control_transfer(0xa1, 0x81, w_value, w_index, data, length, timeout_ms);
    }
    int uvc_get_def(uint16_t w_value, uint16_t w_index,
                    uint8_t* data, uint16_t length, uint32_t timeout_ms) override {
        return control_transfer(0xa1, 0x87, w_value, w_index, data, length, timeout_ms);
    }
    int bulk_in(uint8_t endpoint, uint8_t* data, int length,
                int* actual_length, uint32_t timeout_ms) override {
        if (!handle_) return LIBUSB_ERROR_NO_DEVICE;
        return libusb_bulk_transfer(handle_, endpoint, data, length, actual_length, timeout_ms);
    }

private:
    libusb_device_handle* handle_ = nullptr;
};

int claim_with_detach(libusb_device_handle* handle, int iface) {
    if (libusb_kernel_driver_active(handle, iface) == 1) {
        const int det = libusb_detach_kernel_driver(handle, iface);
        if (det != 0 && det != LIBUSB_ERROR_NOT_FOUND && det != LIBUSB_ERROR_NOT_SUPPORTED) {
            LOGE("detach iface %d rc=%d", iface, det);
        }
    }
    const int rc = libusb_claim_interface(handle, iface);
    if (rc != 0) LOGE("claim iface %d rc=%d (%s)", iface, rc, usb_error_name(rc));
    return rc;
}

struct DualSession {
    // 单 libusb context 包 master+companion（对齐 host SDK，host 单 UsbContext 跑 keepalive+color+depth 全绿）。
    libusb_context* ctx = nullptr;
    libusb_device_handle* master = nullptr;
    libusb_device_handle* companion = nullptr;
    std::unique_ptr<AndroidUvcDevice> master_dev;
    std::unique_ptr<AndroidUvcDevice> companion_dev;

    std::atomic<bool> running{false};
    std::thread keepalive_thread;   // 同步 set_cur 保活（异步化 bulk 后不再被 200ms 同步读饿）
    std::thread event_thread;       // 唯一 libusb 事件线程：只 reap + 立刻重提交，绝不在此做解析（吸干 USB）
    std::thread depth_parser_thread;// 解析线程：从队列取 chunk 跑 assembler + 整帧拷贝，与 reap 解耦
    std::thread log_thread;
    // 取数据/解析解耦队列（修：单事件线程边 reap 边解析+2MB 整帧拷贝 → reap 跟不上 → 内核 usbfs 堆积
    // → device 内部 buffer 填满 → ~6-9s NO_DEVICE。event 线程只入队 + 重提交，解析挪到 depth_parser_thread）。
    struct DepthChunk { std::vector<uint8_t> data; uint64_t ts_ns; };
    std::mutex depth_q_mu;
    std::condition_variable depth_q_cv;
    std::deque<DepthChunk> depth_q;
    int64_t depth_q_drops = 0;       // 队列满丢弃数（解析线程落后才会发生；正常应恒 0）
    size_t depth_q_hwm = 0;          // 队列高水位，诊断解析是否跟得上 reap
    // 组帧→滤波 二级解耦（M8.2/M8.3，对标官方 processDepthThread）：parser 只 chunk→整帧+marker 分流，
    // 整帧推此队列由 depth_filter_thread 独占跑 temporal_filter/空间降噪。滤波慢(Android ARM 数十~上百ms)
    // 不堵组帧/USB reap；队列有界 ≤kDepthPlaneQMax 满丢最旧帧 → 低延迟不雪崩(官方队列恒 ≤2 帧)。
    std::thread depth_filter_thread;
    struct DepthPlaneItem { bool is_depth = false; std::vector<uint8_t> bytes; UvcFrameInfo info; };
    std::mutex depth_plane_mu;
    std::condition_variable depth_plane_cv;
    std::deque<DepthPlaneItem> depth_plane_q;
    int64_t depth_plane_drops = 0;   // 整帧队列满丢弃数（滤波落后才发生）
    size_t depth_plane_hwm = 0;      // 整帧队列高水位，诊断滤波是否跟得上组帧
    // color 路同样解耦（双流时事件线程更不能被 MJPEG 组帧拖慢，否则 companion 又溢出）。
    std::thread color_parser_thread;
    std::mutex color_q_mu;
    std::condition_variable color_q_cv;
    std::deque<DepthChunk> color_q;  // 复用 DepthChunk{bytes,ts}
    int64_t color_q_drops = 0;
    size_t color_q_hwm = 0;
    // 异步 bulk 传输池（修 libusb 单 context 多线程同步竞争：bulk 改异步 + 单事件线程）
    std::unique_ptr<UvcRawFrameAssembler> depth_assembler;
    std::unique_ptr<UvcMjpegFrameAssembler> color_assembler;
    std::vector<libusb_transfer*> depth_xfers;
    std::vector<libusb_transfer*> color_xfers;
    std::vector<std::vector<uint8_t>> depth_bufs;
    std::vector<std::vector<uint8_t>> color_bufs;

    BulkStats keepalive_stats;
    BulkStats depth_stats;
    BulkStats color_stats;

    RgbdFramePairer pairer;
    std::mutex pairer_mu;

    // 最新 depth 帧（transport raw16），供 poll / 日志 sanity 用
    std::mutex frame_mu;
    std::vector<uint8_t> latest_depth_transport;
    UvcFrameInfo latest_depth_info;
    uint64_t depth_seq = 0;
    // 时域降噪：设备关 temporal_denoise 换稠密，代价是 ~38mm 逐像素相邻帧抖动；滑窗均值 N=8
    // 压到 ~10mm（3.7×）。只在解析线程（唯一消费者、按到达顺序每帧一次）调，无需额外锁。
    // 详见 tests/harness/depth_temporal_quality/、core/native-bridge 文档。
    bool depth_temporal_enable = true;
    P100R3TemporalFilter depth_temporal_filter;
    std::vector<uint16_t> latest_depth_fused;  // active raw16（融合后，仍 fixed-point /8）
    std::vector<uint8_t> latest_depth_conf;    // 逐像素 confidence（低值=不可靠，0=无效/飞点）
    std::vector<uint8_t> latest_depth_flying;  // 逐像素飞点 mask（1=飞点；仅诊断/可视化）
    // raw 预览遮罩状态：HUB 安全模式关闭 temporal 后，只做显示置信，不补/改真实 depth。
    std::vector<uint16_t> raw_preview_prev_depth;
    std::vector<uint8_t> raw_preview_stable_run;
    int raw_preview_w = 0;
    int raw_preview_h = 0;
    // companion 0x82 交织的 IR/phase 帧（非真深度）：不丢，存起来供「切 IR」预览。
    std::vector<uint8_t> latest_ir_transport;
    UvcFrameInfo latest_ir_info;
    uint64_t ir_skipped = 0;  // = IR/phase 帧计数
    uint64_t depth_bad_marker = 0;      // 组帧后找不到 0x0600/0x0500 状态行标记的帧
    uint64_t depth_aligned_planes = 0;  // 从带前缀/复合帧里按 marker 重对齐出的平面
    // master 0x81 MJPEG color 最新帧（供 UI poll；consume-once 语义）。
    std::mutex color_mu;
    std::vector<uint8_t> latest_color_mjpeg;
    std::vector<uint8_t> debug_latest_color_mjpeg;

    bool enable_color = true;
    int read_len = 16384;
    int depth_read_len = 16384;
    int color_read_len = 16384;
    int bulk_xfer_count = kDefaultBulkXferCount;
    int depth_payload_transfer_size = 0;
    int color_payload_unit = 0;
    int keepalive_ms = 50;
    P100R3VideoMode depth_mode{2, 640, 401, 45, 222222};
    P100R3VideoMode color_mode{2, 1280, 800, 30, 333333};
    int depth_frame_size = 0;
};

const char* xfer_status_name(libusb_transfer_status status) {
    switch (status) {
    case LIBUSB_TRANSFER_COMPLETED: return "COMPLETED";
    case LIBUSB_TRANSFER_ERROR: return "ERROR";
    case LIBUSB_TRANSFER_TIMED_OUT: return "TIMED_OUT";
    case LIBUSB_TRANSFER_CANCELLED: return "CANCELLED";
    case LIBUSB_TRANSFER_STALL: return "STALL";
    case LIBUSB_TRANSFER_NO_DEVICE: return "NO_DEVICE";
    case LIBUSB_TRANSFER_OVERFLOW: return "OVERFLOW";
    }
    return "UNKNOWN";
}

void notify_async_stop(DualSession* s) {
    s->running.store(false);
    s->depth_q_cv.notify_all();
    s->color_q_cv.notify_all();
    s->depth_plane_cv.notify_all();
}

void log_bulk_chunk_sample(const char* name, const BulkStats& stats,
                           const uint8_t* data, int actual_length) {
    if (actual_length <= 0) return;
    if (stats.chunks > 3 && (stats.chunks % 500) != 0) return;
    char head[3 * 8 + 1] = {};
    size_t used = 0;
    const int n = std::min(actual_length, 8);
    for (int i = 0; i < n && used + 3 < sizeof(head); ++i) {
        used += static_cast<size_t>(std::snprintf(
            head + used, sizeof(head) - used, "%02x%s", data[i], i + 1 == n ? "" : " "));
    }
    LOGI("%s bulk chunk#%lld len=%d head=%s",
         name, (long long)stats.chunks, actual_length, head);
}

void note_xfer_error(const char* name, BulkStats& stats, const libusb_transfer* xfer) {
    stats.errors++;
    const int status = static_cast<int>(xfer->status);
    if (stats.first_error == 0) stats.first_error = status;
    if (stats.errors <= 8 || (stats.errors % 100) == 0) {
        LOGE("%s xfer status=%s(%d) actual=%d err#%lld",
             name, xfer_status_name(xfer->status), status, xfer->actual_length,
             (long long)stats.errors);
    }
}

bool resubmit_or_stop(DualSession* s, libusb_transfer* xfer,
                      const char* name, BulkStats& stats) {
    const int rc = libusb_submit_transfer(xfer);
    if (rc == 0) return true;
    stats.errors++;
    if (stats.first_error == 0) stats.first_error = rc;
    LOGE("%s resubmit_transfer rc=%d (%s) err#%lld",
         name, rc, usb_error_name(rc), (long long)stats.errors);
    notify_async_stop(s);
    return false;
}

LogFn make_logfn(const char* tag) {
    return [tag](const std::string& msg) { LOGI("%s %s", tag, msg.c_str()); };
}

// active raw depth 中心 ROI 的 median mm + 有效率，用于真机日志 sanity（证明 raw/8 在 Android 成立）。
void depth_center_sanity(const std::vector<uint8_t>& transport,
                         const P100R3VideoMode& transport_mode,
                         float* out_median_mm, float* out_valid_ratio) {
    *out_median_mm = 0.0f;
    *out_valid_ratio = 0.0f;
    const P100R3VideoMode active = p100r3_depth_active_mode(transport_mode);
    const size_t aw = active.width;
    const size_t ah = active.height;
    if (aw == 0 || ah == 0 || transport.size() < aw * ah * 2) return;
    const auto* p = reinterpret_cast<const uint16_t*>(transport.data());
    const size_t x0 = aw / 4, x1 = aw - aw / 4;
    const size_t y0 = ah / 4, y1 = ah - ah / 4;
    std::vector<float> mm;
    size_t total = 0, valid = 0;
    for (size_t y = y0; y < y1; ++y) {
        for (size_t x = x0; x < x1; ++x) {
            ++total;
            const uint16_t raw = p[y * aw + x];
            const float d = p100r3_depth_raw_to_mm(raw, P100R3DepthPixelFormat::k13I3D);
            if (d > 0.0f) { ++valid; mm.push_back(d); }
        }
    }
    if (total > 0) *out_valid_ratio = static_cast<float>(valid) / static_cast<float>(total);
    if (!mm.empty()) {
        std::nth_element(mm.begin(), mm.begin() + mm.size() / 2, mm.end());
        *out_median_mm = mm[mm.size() / 2];
    }
}

// P100R3 companion 0x82 在同一条流上不规则交织【真深度帧】与【IR/phase 帧】（跟 color/keepalive 无关，
// 是此 depth 模式设备固有行为；原厂 SDK 也是收到两类帧后按状态行标记分流，不是靠控制关掉 IR）。
// 帧类型标记 = 状态行首像素 uint16 pixel[0]（30/30 dump 实测确定性可靠）：
//   0x0600(1536) = 真深度帧（13I_3D, raw/8=mm）
//   0x0500(1280) = IR/phase 帧（高字节 IR 灰度 + 低字节 phase code）
// 之前用低字节 distinct 内容启发式，现换成这个确定性标记。
constexpr uint16_t kP100R3DepthFrameMarker = 0x0600;
constexpr uint16_t kP100R3LightIrFrameMarker = 0x0500;

enum class P100R3MarkedPlaneKind {
    kUnknown,
    kDepth,
    kLightIr,
};

struct P100R3MarkedPlane {
    P100R3MarkedPlaneKind kind = P100R3MarkedPlaneKind::kUnknown;
    size_t offset = 0;
    std::vector<uint8_t> bytes;
};

P100R3MarkedPlaneKind p100r3_marker_kind(uint16_t marker) {
    if (marker == kP100R3DepthFrameMarker) return P100R3MarkedPlaneKind::kDepth;
    if (marker == kP100R3LightIrFrameMarker) return P100R3MarkedPlaneKind::kLightIr;
    return P100R3MarkedPlaneKind::kUnknown;
}

bool extract_p100r3_marked_plane(const std::vector<uint8_t>& frame,
                                 const P100R3VideoMode& mode,
                                 P100R3MarkedPlane* out) {
    if (!out || mode.width == 0 || mode.height == 0) return false;
    const size_t plane_bytes =
        static_cast<size_t>(mode.width) * static_cast<size_t>(mode.height) * 2;
    if (frame.size() < plane_bytes || plane_bytes < 2) return false;

    std::vector<size_t> candidates;
    const size_t max_offset = frame.size() - plane_bytes;
    const auto add_candidate = [&](size_t offset) {
        if (offset > max_offset) return;
        if ((offset & 1u) != 0) return;
        if (std::find(candidates.begin(), candidates.end(), offset) == candidates.end()) {
            candidates.push_back(offset);
        }
    };
    const auto scan_window = [&](size_t start, size_t end) {
        if (start > max_offset) return;
        end = std::min(end, max_offset);
        if ((start & 1u) != 0) ++start;
        for (size_t offset = start; offset <= end; offset += 2) add_candidate(offset);
    };

    // 正常裸 raw 从 0 开始；带 UVC/复合前缀时 marker 常落在 12/24 字节附近。
    add_candidate(0);
    add_candidate(12);
    add_candidate(24);
    scan_window(0, 64);
    // 某些 UVC descriptor 会报出约 2 个 raw plane + header 的 frameSize；第二个 plane
    // 通常在 plane_bytes 后的很小窗口里。只扫边界附近，避免把普通深度值误判成 marker。
    add_candidate(plane_bytes);
    add_candidate(plane_bytes + 12);
    add_candidate(plane_bytes + 24);
    scan_window(plane_bytes, plane_bytes + 96);

    P100R3MarkedPlane first_ir;
    bool have_ir = false;
    for (size_t offset : candidates) {
        if (offset + 2 > frame.size()) continue;
        const uint16_t marker = static_cast<uint16_t>(
            frame[offset] | (static_cast<uint16_t>(frame[offset + 1]) << 8));
        const P100R3MarkedPlaneKind kind = p100r3_marker_kind(marker);
        if (kind == P100R3MarkedPlaneKind::kUnknown) continue;
        P100R3MarkedPlane plane;
        plane.kind = kind;
        plane.offset = offset;
        plane.bytes.assign(frame.begin() + static_cast<std::ptrdiff_t>(offset),
                           frame.begin() + static_cast<std::ptrdiff_t>(offset + plane_bytes));
        if (kind == P100R3MarkedPlaneKind::kDepth) {
            *out = std::move(plane);
            return true;
        }
        if (!have_ir) {
            first_ir = std::move(plane);
            have_ir = true;
        }
    }
    if (have_ir) {
        *out = std::move(first_ir);
        return true;
    }
    return false;
}

std::vector<uint8_t> make_raw_depth_preview_confidence(DualSession* s,
                                                       const std::vector<uint8_t>& transport,
                                                       const P100R3VideoMode& transport_mode) {
    if (!s) return {};
    const P100R3VideoMode active = p100r3_depth_active_mode(transport_mode);
    const int w = active.width;
    const int h = active.height;
    const size_t pixels = static_cast<size_t>(w) * static_cast<size_t>(h);
    if (w <= 0 || h <= 0 || transport.size() < pixels * 2) return {};
    const auto* raw = reinterpret_cast<const uint16_t*>(transport.data());
    std::vector<uint8_t> conf(pixels, 0);
    if (s->raw_preview_w != w || s->raw_preview_h != h ||
        s->raw_preview_prev_depth.size() != pixels ||
        s->raw_preview_stable_run.size() != pixels) {
        s->raw_preview_prev_depth.assign(pixels, 0);
        s->raw_preview_stable_run.assign(pixels, 0);
        s->raw_preview_w = w;
        s->raw_preview_h = h;
    }
    constexpr float kPreviewMinMm = 200.0f;
    constexpr float kPreviewMaxMm = 2500.0f;
    constexpr int kMinObservedNeighbors = 5;
    constexpr int kMinSimilarNeighbors = 4;
    constexpr uint8_t kMinStableRun = 3;
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            const size_t i = static_cast<size_t>(y) * w + x;
            const uint16_t v = raw[i];
            const float mm = p100r3_depth_raw_to_mm(v, P100R3DepthPixelFormat::k13I3D);
            if (mm < kPreviewMinMm || mm > kPreviewMaxMm) {
                s->raw_preview_prev_depth[i] = 0;
                s->raw_preview_stable_run[i] = 0;
                continue;
            }
            int observed = 0;
            int similar = 0;
            const int spatial_tolerance_raw = std::max(96, static_cast<int>(v) / 32); // max(12mm, ~3.1% depth)
            for (int dy = -1; dy <= 1; ++dy) {
                const int yy = y + dy;
                if (yy < 0 || yy >= h) continue;
                for (int dx = -1; dx <= 1; ++dx) {
                    const int xx = x + dx;
                    if ((dx == 0 && dy == 0) || xx < 0 || xx >= w) continue;
                    const uint16_t n = raw[static_cast<size_t>(yy) * w + xx];
                    if (n == 0) continue;
                    observed++;
                    if (std::abs(static_cast<int>(n) - static_cast<int>(v)) <= spatial_tolerance_raw) {
                        similar++;
                    }
                }
            }
            // 只做显示遮罩，不改 depth：弱支撑或跨帧跳动的点按不可靠处理，避免伪彩花点。
            if (observed < kMinObservedNeighbors || similar < kMinSimilarNeighbors) {
                s->raw_preview_prev_depth[i] = 0;
                s->raw_preview_stable_run[i] = 0;
                continue;
            }

            const uint16_t prev = s->raw_preview_prev_depth[i];
            uint8_t& stable_run = s->raw_preview_stable_run[i];
            const int temporal_tolerance_raw = std::max(120, static_cast<int>(v) / 32); // max(15mm, ~3.1% depth)
            if (prev != 0 &&
                std::abs(static_cast<int>(prev) - static_cast<int>(v)) <= temporal_tolerance_raw) {
                if (stable_run < 255) stable_run++;
            } else {
                stable_run = 1;
            }
            s->raw_preview_prev_depth[i] = v;
            if (stable_run >= kMinStableRun) conf[i] = 255;
        }
    }
    return conf;
}

// chunk 队列上限：组帧线程(M8.2 后只组帧不滤波,很快)跟得上时恒小；满了才丢最老 chunk(废一帧但保 event
// 线程不阻塞、持续吸 USB 防 device buffer 溢出掉线)。256×16KB=4MB,组帧线程轻量正常远到不了。
constexpr size_t kDepthQMaxChunks = 256;
// 整帧(组帧→滤波)队列上限：低延迟优先,滤波落后就丢最旧整帧(对标官方 ≤2 帧)。3 帧 ≈ 1.5MB。
constexpr size_t kDepthPlaneQMax = 3;

void sync_depth_assembler_stats(DualSession* s);
void sync_color_assembler_stats(DualSession* s);

// ── 异步 bulk 完成回调（跑在唯一 event_thread 上）──
// depth：companion 0x82。【只做最轻的事】：把 chunk 入队 + 立刻重提交，把 USB 管子持续吸干。
// assembler 解析 + 2MB 整帧拷贝挪到 depth_parser_thread（解耦取数据与解析，修 ~6-9s 掉线）。
void LIBUSB_CALL depth_xfer_cb(libusb_transfer* xfer) {
    DualSession* s = static_cast<DualSession*>(xfer->user_data);
    switch (xfer->status) {
    case LIBUSB_TRANSFER_COMPLETED:
        if (xfer->actual_length > 0) {
            s->depth_stats.chunks++;
            s->depth_stats.bytes += xfer->actual_length;
            log_bulk_chunk_sample("depth", s->depth_stats, xfer->buffer, xfer->actual_length);
            bool notify = false;
            {
                std::lock_guard<std::mutex> lk(s->depth_q_mu);
                if (s->depth_q.size() >= kDepthQMaxChunks) {
                    // 预览/扫描优先低延迟：解析线程落后时丢最老 chunk，保留最新 USB 数据。
                    s->depth_q.pop_front();
                    s->depth_q_drops++;
                }
                s->depth_q.push_back(DualSession::DepthChunk{
                    std::vector<uint8_t>(xfer->buffer, xfer->buffer + xfer->actual_length),
                    static_cast<uint64_t>(detail::now_ns())});
                if (s->depth_q.size() > s->depth_q_hwm) s->depth_q_hwm = s->depth_q.size();
                notify = true;
            }
            if (notify) s->depth_q_cv.notify_one();
        }
        break;
    case LIBUSB_TRANSFER_NO_DEVICE:
        note_xfer_error("depth", s->depth_stats, xfer);
        notify_async_stop(s);
        return;
    case LIBUSB_TRANSFER_CANCELLED:
        return;  // close 流程取消，不重提交
    default:
        note_xfer_error("depth", s->depth_stats, xfer);
        break;
    }
    if (s->running.load()) resubmit_or_stop(s, xfer, "depth", s->depth_stats);
}

// depth 解析线程：从队列取 chunk 跑 assembler，按 0x0600/0x0500 标记分流 depth/IR。
// 与 event 线程解耦——event 只管 reap，解析慢也不拖累 USB 吸取速度。
void depth_parser_loop(DualSession* s) {
    std::vector<UvcRawFrame> frames;
    while (true) {
        DualSession::DepthChunk chunk;
        {
            std::unique_lock<std::mutex> lk(s->depth_q_mu);
            s->depth_q_cv.wait(lk, [s] { return !s->depth_q.empty() || !s->running.load(); });
            if (s->depth_q.empty()) {
                if (!s->running.load()) break;  // 停止且排空 → 退出
                continue;
            }
            chunk = std::move(s->depth_q.front());
            s->depth_q.pop_front();
        }
        frames.clear();
        s->depth_assembler->push_packet(chunk.data.data(), chunk.data.size(), chunk.ts_ns, &frames);
        sync_depth_assembler_stats(s);
        for (UvcRawFrame& f : frames) {
            P100R3MarkedPlane plane;
            if (!extract_p100r3_marked_plane(f.payload, s->depth_mode, &plane)) {
                uint64_t bad_count = 0;
                {
                    std::lock_guard<std::mutex> lk(s->frame_mu);
                    bad_count = ++s->depth_bad_marker;
                }
                if (bad_count <= 3 || (bad_count % 100) == 0) {
                    char head[3 * 8 + 1] = {};
                    size_t used = 0;
                    const size_t n = std::min<size_t>(f.payload.size(), 8);
                    for (size_t i = 0; i < n && used + 3 < sizeof(head); ++i) {
                        used += static_cast<size_t>(std::snprintf(
                            head + used, sizeof(head) - used, "%02x%s",
                            f.payload[i], i + 1 == n ? "" : " "));
                    }
                    LOGW("depth bad marker#%llu payload=%zu head=%s",
                         (unsigned long long)bad_count, f.payload.size(), head);
                }
                continue;
            }
            if (plane.offset != 0 || plane.bytes.size() != f.payload.size()) {
                std::lock_guard<std::mutex> lk(s->frame_mu);
                s->depth_aligned_planes++;
            }
            UvcFrameInfo plane_info = f.info;
            plane_info.payload_bytes = static_cast<uint32_t>(
                std::min<size_t>(plane.bytes.size(), std::numeric_limits<uint32_t>::max()));

            // 组帧+分流完成 → 整帧推 filter 线程（有界,满丢最旧帧）。后处理(temporal/去噪)与组帧解耦,
            // 组帧线程不被滤波拖慢 → 不堆延迟(M8.2/M8.3,对标官方 processDepthThread)。
            const bool is_depth = (plane.kind == P100R3MarkedPlaneKind::kDepth);
            {
                std::lock_guard<std::mutex> lk(s->depth_plane_mu);
                if (s->depth_plane_q.size() >= kDepthPlaneQMax) {
                    s->depth_plane_q.pop_front();
                    s->depth_plane_drops++;
                }
                DualSession::DepthPlaneItem item;
                item.is_depth = is_depth;
                item.bytes = std::move(plane.bytes);
                item.info = plane_info;
                s->depth_plane_q.push_back(std::move(item));
                if (s->depth_plane_q.size() > s->depth_plane_hwm) s->depth_plane_hwm = s->depth_plane_q.size();
            }
            s->depth_plane_cv.notify_one();
        }
    }
}

// depth 滤波线程：从整帧队列取已分流的 plane，跑时域融合/空间降噪/IR 先验，更新 latest_depth/ir。
// 与组帧解耦——滤波慢(Android ARM 上数十~上百ms)也不堵组帧/USB reap(对标官方 processDepthThread)。
// 本线程是 temporal_filter 唯一消费者，IR/depth 按到达顺序逐帧处理，filter 内部状态无需额外锁。
void depth_filter_loop(DualSession* s) {
    while (true) {
        DualSession::DepthPlaneItem item;
        {
            std::unique_lock<std::mutex> lk(s->depth_plane_mu);
            s->depth_plane_cv.wait(lk, [s] { return !s->depth_plane_q.empty() || !s->running.load(); });
            if (s->depth_plane_q.empty()) {
                if (!s->running.load()) break;  // 停止且排空 → 退出
                continue;
            }
            item = std::move(s->depth_plane_q.front());
            s->depth_plane_q.pop_front();
        }
        const UvcFrameInfo plane_info = item.info;
        // IR/phase 帧：转存 latest_ir（供「切 IR」预览）+ 喂 temporal_filter 单帧深度置信先验，不混入 depth。
        // 散斑弱/无回波处深度不可信(实证 AUC 0.82)；本线程唯一消费者，IR/depth 顺序处理无并发。
        if (!item.is_depth) {
            {
                std::lock_guard<std::mutex> lk(s->frame_mu);
                s->latest_ir_transport = item.bytes;
                s->latest_ir_info = plane_info;
                s->ir_skipped++;
            }
            if (s->depth_temporal_enable) {
                const P100R3VideoMode active = p100r3_depth_active_mode(s->depth_mode);
                const size_t aw = active.width, ah = active.height;
                if (aw > 0 && ah > 0 && item.bytes.size() >= aw * ah * 2) {
                    const auto* src = reinterpret_cast<const uint16_t*>(item.bytes.data());
                    std::vector<uint16_t> active_ir(src, src + aw * ah);
                    s->depth_temporal_filter.set_prior_confidence(
                        p100r3_ir_speckle_confidence(active_ir,
                            static_cast<uint16_t>(aw), static_cast<uint16_t>(ah)));
                }
            }
            continue;
        }
        // 真深度帧时域融合：active raw16 = transport 前 aw×ah 个 uint16（状态行是最后一行）。
        std::vector<uint16_t> fused;
        std::vector<uint8_t> conf;
        std::vector<uint8_t> flymask;
        bool have_fused = false;
        std::vector<uint8_t> raw_conf;
        if (s->depth_temporal_enable) {
            const P100R3VideoMode active = p100r3_depth_active_mode(s->depth_mode);
            const size_t aw = active.width, ah = active.height;
            if (aw > 0 && ah > 0 && item.bytes.size() >= aw * ah * 2) {
                const auto* src = reinterpret_cast<const uint16_t*>(item.bytes.data());
                std::vector<uint16_t> active_raw(src, src + aw * ah);
                // 传 &flymask 使飞点剔除在 live 生效：飞点处 conf 置 0（fused 原值保留）。
                have_fused = s->depth_temporal_filter.push(
                    active_raw, static_cast<uint16_t>(aw), static_cast<uint16_t>(ah),
                    &fused, &conf, nullptr, &flymask);
            }
        } else {
            raw_conf = make_raw_depth_preview_confidence(s, item.bytes, s->depth_mode);
        }
        {
            std::lock_guard<std::mutex> lk(s->frame_mu);
            s->latest_depth_transport = std::move(item.bytes);
            s->latest_depth_info = plane_info;
            if (have_fused) {
                s->latest_depth_fused = std::move(fused);
                s->latest_depth_conf = std::move(conf);
                s->latest_depth_flying = std::move(flymask);
            } else if (!raw_conf.empty()) {
                s->latest_depth_fused.clear();
                s->latest_depth_conf = std::move(raw_conf);
                s->latest_depth_flying.clear();
            } else {
                s->latest_depth_fused.clear();
                s->latest_depth_conf.clear();
                s->latest_depth_flying.clear();
            }
            s->depth_seq++;
        }
        std::lock_guard<std::mutex> lk(s->pairer_mu);
        RgbdFramePairInfo pair;
        s->pairer.push_depth(plane_info, &pair);
    }
}

// color：master 0x81 MJPEG。同 depth：回调只入队 + 重提交，MJPEG 组帧挪到 color_parser_thread。
void LIBUSB_CALL color_xfer_cb(libusb_transfer* xfer) {
    DualSession* s = static_cast<DualSession*>(xfer->user_data);
    switch (xfer->status) {
    case LIBUSB_TRANSFER_COMPLETED:
        if (xfer->actual_length > 0) {
            s->color_stats.chunks++;
            s->color_stats.bytes += xfer->actual_length;
            log_bulk_chunk_sample("color", s->color_stats, xfer->buffer, xfer->actual_length);
            bool notify = false;
            {
                std::lock_guard<std::mutex> lk(s->color_q_mu);
                if (s->color_q.size() >= kDepthQMaxChunks) {
                    // COLOR 只做预览：积压时保新丢旧，避免 UI 看到过期 MJPEG。
                    s->color_q.pop_front();
                    s->color_q_drops++;
                }
                s->color_q.push_back(DualSession::DepthChunk{
                    std::vector<uint8_t>(xfer->buffer, xfer->buffer + xfer->actual_length),
                    static_cast<uint64_t>(detail::now_ns())});
                if (s->color_q.size() > s->color_q_hwm) s->color_q_hwm = s->color_q.size();
                notify = true;
            }
            if (notify) s->color_q_cv.notify_one();
        }
        break;
    case LIBUSB_TRANSFER_NO_DEVICE:
        note_xfer_error("color", s->color_stats, xfer);
        notify_async_stop(s);
        return;
    case LIBUSB_TRANSFER_CANCELLED:
        return;
    default:
        note_xfer_error("color", s->color_stats, xfer);
        break;
    }
    if (s->running.load()) resubmit_or_stop(s, xfer, "color", s->color_stats);
}

bool looks_like_uvc_payload_header_at(const std::vector<uint8_t>& data, size_t offset) {
    if (offset >= data.size()) return false;
    const size_t remaining = data.size() - offset;
    if (remaining > static_cast<size_t>(std::numeric_limits<int>::max())) return false;
    const auto payload = gomob::berxel::host::detail::parse_uvc_payload(
        data.data() + offset,
        static_cast<int>(remaining));
    return payload.valid;
}

size_t find_uvc_payload_header_near(const std::vector<uint8_t>& data,
                                    size_t start,
                                    size_t end) {
    if (data.size() < 2 || start >= data.size()) return std::string::npos;
    end = std::min(end, data.size() - 2);
    for (size_t offset = start; offset <= end; ++offset) {
        if (looks_like_uvc_payload_header_at(data, offset)) return offset;
    }
    return std::string::npos;
}

struct ColorPayloadSlice {
    size_t offset = 0;
    int length = 0;
};

std::vector<ColorPayloadSlice> split_color_payloads(const std::vector<uint8_t>& data,
                                                    int payload_unit) {
    std::vector<ColorPayloadSlice> slices;
    if (data.empty()) return slices;
    if (payload_unit <= 0 || data.size() <= static_cast<size_t>(payload_unit) ||
        !looks_like_uvc_payload_header_at(data, 0)) {
        slices.push_back(ColorPayloadSlice{0, static_cast<int>(data.size())});
        return slices;
    }

    constexpr size_t kHeaderSearchWindow = 512;
    size_t offset = 0;
    while (offset < data.size()) {
        const size_t remaining = data.size() - offset;
        size_t next = data.size();
        if (remaining > static_cast<size_t>(payload_unit)) {
            const size_t expected = offset + static_cast<size_t>(payload_unit);
            if (looks_like_uvc_payload_header_at(data, expected)) {
                next = expected;
            } else {
                const size_t search_start = std::max(offset + 2, expected > kHeaderSearchWindow
                    ? expected - kHeaderSearchWindow
                    : offset + 2);
                const size_t search_end = std::min(data.size() - 2, expected + kHeaderSearchWindow);
                const size_t found = find_uvc_payload_header_near(data, search_start, search_end);
                if (found != std::string::npos) {
                    next = found;
                } else {
                    // 找不到下一段合法 UVC 头时，把剩余 chunk 当一个 payload 交给 assembler；
                    // 这样不会在 JPEG 数据中间盲切，最多少吐一帧，不再吐半帧花屏。
                    next = data.size();
                }
            }
        }
        if (next <= offset) next = data.size();
        slices.push_back(ColorPayloadSlice{
            offset,
            static_cast<int>(std::min<size_t>(
                next - offset,
                static_cast<size_t>(std::numeric_limits<int>::max()))),
        });
        offset = next;
    }
    return slices;
}

// color 解析线程：从队列取 chunk 跑 MJPEG assembler，存 latest_color_mjpeg。
void color_parser_loop(DualSession* s) {
    std::vector<UvcMjpegFrame> frames;
    while (true) {
        DualSession::DepthChunk chunk;
        {
            std::unique_lock<std::mutex> lk(s->color_q_mu);
            s->color_q_cv.wait(lk, [s] { return !s->color_q.empty() || !s->running.load(); });
            if (s->color_q.empty()) {
                if (!s->running.load()) break;
                continue;
            }
            chunk = std::move(s->color_q.front());
            s->color_q.pop_front();
        }
        const std::vector<ColorPayloadSlice> slices = split_color_payloads(chunk.data, s->color_payload_unit);
        for (const ColorPayloadSlice& slice : slices) {
            if (slice.length <= 0) continue;
            frames.clear();
            s->color_assembler->push_packet(
                chunk.data.data() + slice.offset,
                slice.length,
                chunk.ts_ns,
                &frames);
            sync_color_assembler_stats(s);
            for (UvcMjpegFrame& f : frames) {
                if (f.info.frame_number <= 3 || (f.info.frame_number % 100) == 0) {
                    LOGI("dual color assembled#%lld jpeg=%zu payload=%u transport=%u eof=%d fid=%d eoi=%d",
                         (long long)f.info.frame_number, f.jpeg.size(), f.info.payload_bytes,
                         f.info.transport_bytes, (int)f.info.completed_by_eof,
                         (int)f.info.completed_by_fid, (int)f.info.completed_by_jpeg_eoi);
                }
                {
                    std::lock_guard<std::mutex> lk(s->color_mu);
                    s->latest_color_mjpeg = f.jpeg;
                    s->debug_latest_color_mjpeg = f.jpeg;
                }
                std::lock_guard<std::mutex> lk(s->pairer_mu);
                RgbdFramePairInfo pair;
                s->pairer.push_color(f.info, &pair);
            }
        }
    }
}

void sync_depth_assembler_stats(DualSession* s) {
    if (!s || !s->depth_assembler) return;
    const UvcRawFrameAssemblerStats st = s->depth_assembler->stats();
    s->depth_stats.frames = st.frames;
    s->depth_stats.frame_drops = st.frame_drops;
    s->depth_stats.uvc_headers = st.uvc_headers;
    s->depth_stats.completed_by_size = st.completed_by_size;
    s->depth_stats.partial_frame_drops = st.partial_frame_drops;
    s->depth_stats.oversized_frame_drops = st.oversized_frame_drops;
}

void sync_color_assembler_stats(DualSession* s) {
    if (!s || !s->color_assembler) return;
    const UvcMjpegFrameAssemblerStats st = s->color_assembler->stats();
    s->color_stats.frames = st.frames;
    s->color_stats.frame_drops = st.frame_drops;
    s->color_stats.uvc_headers = st.uvc_headers;
    s->color_stats.fid_toggles = st.fid_toggles;
    s->color_stats.completed_by_eof = st.completed_by_eof;
    s->color_stats.completed_by_fid = st.completed_by_fid;
    s->color_stats.completed_by_jpeg_eoi = st.completed_by_jpeg_eoi;
    s->color_stats.oversized_frame_drops = st.oversized_frame_drops;
}

// 唯一 libusb 事件线程：处理所有异步 bulk 完成回调。100ms 超时让 close 时能及时退出。
void dual_event_loop(DualSession* s) {
    timeval tv{0, 100 * 1000};
    while (s->running.load()) {
        int rc = libusb_handle_events_timeout_completed(s->ctx, &tv, nullptr);
        if (rc != 0 && rc != LIBUSB_ERROR_INTERRUPTED) {
            LOGE("dual handle_events rc=%d", rc);
        }
    }
}

// 分配 + 提交 count 个异步 bulk transfer（timeout=0 无限等，由 close 时 cancel 回收）。
bool submit_async_bulk(DualSession* s, libusb_device_handle* handle, uint8_t ep, int count,
                       int transfer_size,
                       std::vector<libusb_transfer*>& xfers,
                       std::vector<std::vector<uint8_t>>& bufs,
                       libusb_transfer_cb_fn cb) {
    if (transfer_size <= 0) transfer_size = s->read_len;
    xfers.assign(static_cast<size_t>(count), nullptr);
    bufs.assign(static_cast<size_t>(count), std::vector<uint8_t>(static_cast<size_t>(transfer_size), 0));
    for (int i = 0; i < count; ++i) {
        xfers[i] = libusb_alloc_transfer(0);
        if (!xfers[i]) { LOGE("alloc_transfer ep=0x%02x #%d failed", ep, i); return false; }
        libusb_fill_bulk_transfer(xfers[i], handle, ep, bufs[i].data(),
                                  transfer_size, cb, s, 0);
        const int rc = libusb_submit_transfer(xfers[i]);
        if (rc != 0) {
            LOGE("submit_transfer ep=0x%02x #%d rc=%d (%s)", ep, i, rc, usb_error_name(rc));
            libusb_free_transfer(xfers[i]);
            xfers[i] = nullptr;
            return false;
        }
    }
    LOGI("async bulk submitted ep=0x%02x count=%d size=%d", ep, count, transfer_size);
    return true;
}

// 每秒打一条运行态日志：真机一眼看出 depth/color 帧是否持续、pair delta、depth mm sanity。
void log_loop(DualSession* s) {
    while (s->running.load()) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        float median_mm = 0.0f, valid_ratio = 0.0f;
        uint64_t seq = 0, ir = 0, bad_marker = 0, aligned_planes = 0;
        {
            std::lock_guard<std::mutex> lk(s->frame_mu);
            if (!s->latest_depth_transport.empty()) {
                depth_center_sanity(s->latest_depth_transport, s->depth_mode, &median_mm, &valid_ratio);
            }
            seq = s->depth_seq;
            ir = s->ir_skipped;
            bad_marker = s->depth_bad_marker;
            aligned_planes = s->depth_aligned_planes;
        }
        RgbdPairingStats ps;
        {
            std::lock_guard<std::mutex> lk(s->pairer_mu);
            ps = s->pairer.stats();
        }
        size_t q_now = 0, q_hwm = 0, cq_hwm = 0, fq_hwm = 0; int64_t q_drops = 0, cq_drops = 0, fq_drops = 0;
        {
            std::lock_guard<std::mutex> lk(s->depth_q_mu);
            q_now = s->depth_q.size(); q_hwm = s->depth_q_hwm; q_drops = s->depth_q_drops;
        }
        {
            std::lock_guard<std::mutex> lk(s->depth_plane_mu);
            fq_hwm = s->depth_plane_hwm; fq_drops = s->depth_plane_drops;
        }
        {
            std::lock_guard<std::mutex> lk(s->color_q_mu);
            cq_hwm = s->color_q_hwm; cq_drops = s->color_q_drops;
        }
        LOGI("RUN depth_seq=%llu ir_skipped=%llu depth_bad_marker=%llu depth_aligned=%llu pairs=%lld "
             "center_median=%.1fmm valid=%.3f pair_p50_delta_ns=%lld "
             "depth_frames=%lld depth_chunks=%lld depth_drops=%lld depth_partial=%lld depth_err=%lld "
             "color_frames=%lld color_chunks=%lld color_drops=%lld color_eof=%lld color_fid=%lld color_eoi=%lld "
             "color_bytes=%lld color_err=%lld "
             "ka_ok=%lld ka_err=%lld depth_first_err=%d color_first_err=%d "
             "q_now=%zu q_hwm=%zu q_drops=%lld cq_hwm=%zu cq_drops=%lld fq_hwm=%zu fq_drops=%lld",
             (unsigned long long)seq, (unsigned long long)ir,
             (unsigned long long)bad_marker, (unsigned long long)aligned_planes,
             (long long)ps.pairs, median_mm, valid_ratio,
             (long long)ps.last_host_delta_ns,
             (long long)s->depth_stats.frames,
             (long long)s->depth_stats.chunks,
             (long long)s->depth_stats.frame_drops,
             (long long)s->depth_stats.partial_frame_drops,
             (long long)s->depth_stats.errors,
             (long long)s->color_stats.frames,
             (long long)s->color_stats.chunks,
             (long long)s->color_stats.frame_drops,
             (long long)s->color_stats.completed_by_eof,
             (long long)s->color_stats.completed_by_fid,
             (long long)s->color_stats.completed_by_jpeg_eoi,
             (long long)s->color_stats.bytes,
             (long long)s->color_stats.errors,
             (long long)s->keepalive_stats.chunks, (long long)s->keepalive_stats.errors,
             s->depth_stats.first_error, s->color_stats.first_error,
             q_now, q_hwm, (long long)q_drops, cq_hwm, (long long)cq_drops,
             fq_hwm, (long long)fq_drops);
    }
}

bool setup_dual(DualSession* s,
                const std::vector<uint8_t>& master_xu_json,
                const std::vector<uint8_t>& companion_init_json) {
    LogFn log = make_logfn("[dual]");

    // ---- master ----
    std::vector<XuPayload> master_payloads = parse_xu_payloads(
        std::string(master_xu_json.begin(), master_xu_json.end()),
        0x0100, kP100R3MasterXu5WIndex);
    if (master_payloads.empty()) { LOGE("master xu payloads empty"); return false; }
    refresh_master_time_sync_payloads(&master_payloads);
    if (s->enable_color) {
        // ★ 并发 color+depth = MIX 模式：master_xu_json 已是【原厂 MIX 序列】(Kotlin 按 enableColor
        //   选 iHawkP100R3_master_mix_init.json)。MIX 序列里 StreamFlagMode(0x0030)=0x0000 写两次 +
        //   cmd0x0007=01 + COLOR OpenStream(640x400@30) 中段，都是原厂抓包逐位还原——不再 patch
        //   StreamFlagMode(旧 0x02 是反编译错推断,host MIX trace 证实线上值是 0x0000)。
        //   COLOR OpenStream 仍按 color_mode 重写一遍保证与 UVC commit 参数一致(host 实测重写 == 原厂
        //   hex 42580c00...011e00,no-op);color_mode 不同步时这里自动跟上。
        std::string color_os_hex;
        patch_p100r3_master_color_open_stream_payloads(&master_payloads, s->color_mode, &color_os_hex);
        LOGI("master COLOR OpenStream(MIX 中段就位) %dx%d@%d hex=%s",
             s->color_mode.width, s->color_mode.height, s->color_mode.fps, color_os_hex.c_str());
    }
    if (claim_with_detach(s->master, 0) != 0) return false;
    if (s->enable_color && claim_with_detach(s->master, 1) != 0) return false;
    if (!replay_xu_payloads(*s->master_dev, master_payloads, true, "master", log)) {
        LOGE("master XU5 replay failed");
        return false;
    }

    // ---- master color 先起（在 companion depth 之前 commit + 起 color_pump bulk 实读）----
    // 为什么 master 先起：vivo OTG 上 master 控制端点【只靠 keepalive 维持不住】，必须有【活跃的
    // master 视频 bulk 流】维持主控。实测：companion depth 一先开流就饿死 master 控制（keepalive set_cur
    // 全 LIBUSB_ERROR_TIMEOUT），轮到 color commit 时 master 已死（SET_PROBE timeout → setup_dual failed）。
    // 旧 enableMasterStream=true 路径在 vivo+hub 实测 14310 reads 0 错误，靠的就是 master 视频流先活。
    // commit 放在 keepalive 启动【之前】：master 刚 replay 完控制通道干净，避开 keepalive set_cur 与
    // color SET_PROBE/COMMIT 在 master ep0 上的竞争。
    // 小米回归注意：小米 keepalive-only 即可维持 master，提前 color 不应破坏 depth；若交织变多，
    // depth_pump 的确定性 0x0600/0x0500 标记分流仍能正确抽 depth（密度待小米实测复核）。
    if (s->enable_color) {
        UvcStreamConfig color_cfg;
        color_cfg.name = "master-color";
        color_cfg.vs_interface = 1;
        color_cfg.endpoint = 0x81;
        color_cfg.format_index = 1;
        color_cfg.frame_index = s->color_mode.frame_index;
        color_cfg.frame_interval_100ns = s->color_mode.interval_100ns;
        UvcNegotiation color_neg;
        if (!negotiate_uvc_stream(*s->master_dev, color_cfg, &color_neg, log)) {
            LOGE("master color UVC commit failed");
            return false;
        }
        if (color_neg.max_payload_transfer_size > 0) {
            s->color_payload_unit = static_cast<int>(color_neg.max_payload_transfer_size);
            LOGI("master color UVC payload split unit=%d async xfer size=%d",
                 s->color_payload_unit, s->read_len);
        }
        // color 异步 bulk transfer 由 berxel_setup_and_launch 在 setup 成功后统一提交（与 depth 共用单事件线程）。
        std::this_thread::sleep_for(std::chrono::milliseconds(200));  // 等 master color commit 落定
    }

    // master XU5 keepalive：必须【总是】跑——companion depth 依赖它维持（HONOR 实测：跳 keepalive
    // companion 1s 内 LIBUSB_ERROR_NO_DEVICE 掉线）。keepalive 也维持设备 depth-only 模式。
    // 已知问题（待异步化修）：keepalive set_cur 与 color/depth bulk 同步传输共用单 libusb context，
    // 被 200ms bulk 读卡在事件锁后 >500ms → set_cur LIBUSB_ERROR_TIMEOUT → companion 连着但不出帧。
    XuPayload seed;
    if (s->keepalive_ms > 0 && detail::find_keepalive_seed(master_payloads, &seed)) {
        s->keepalive_thread = std::thread(master_keepalive_loop, std::ref(*s->master_dev), seed,
                                          s->keepalive_ms, std::ref(s->running),
                                          std::ref(s->keepalive_stats), make_logfn("[ka]"));
    } else {
        LOGI("keepalive disabled or seed not found");
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    // ---- companion depth ----
    std::vector<XuPayload> comp_payloads = parse_xu_payloads(
        std::string(companion_init_json.begin(), companion_init_json.end()),
        0, kP100R3CompanionXu3WIndex);
    if (comp_payloads.empty()) { LOGE("companion init payloads empty"); return false; }
    patch_p100r3_companion_depth_open_stream_payloads(&comp_payloads, s->depth_mode);
    if (claim_with_detach(s->companion, 0) != 0) return false;
    if (claim_with_detach(s->companion, 1) != 0) return false;
    if (!replay_xu_payloads(*s->companion_dev, comp_payloads, true, "companion", log)) {
        LOGE("companion XU3 replay failed");
        return false;
    }
    // dense depth controls：默认即 AE=1, confidence=3, temporal=0, spatial=0（原厂 dense 路径）
    P100R3DepthControls controls;
    if (!apply_p100r3_depth_controls(*s->companion_dev, controls, log)) {
        LOGE("apply depth controls failed");
        return false;
    }
    UvcStreamConfig depth_cfg;
    depth_cfg.name = "companion-depth";
    depth_cfg.vs_interface = 1;
    depth_cfg.endpoint = 0x82;
    depth_cfg.format_index = 1;
    depth_cfg.frame_index = s->depth_mode.frame_index;
    depth_cfg.frame_interval_100ns = s->depth_mode.interval_100ns;
    depth_cfg.max_payload_transfer_size = static_cast<uint32_t>(s->depth_payload_transfer_size);
    if (s->depth_payload_transfer_size > 0) {
        LOGI("companion-depth override dwMaxPayloadTransferSize=%d", s->depth_payload_transfer_size);
    }
    UvcNegotiation depth_neg;
    if (!negotiate_uvc_stream(*s->companion_dev, depth_cfg, &depth_neg, log)) {
        LOGE("depth UVC commit failed");
        return false;
    }
    s->depth_read_len = s->read_len;
    return true;
}

void close_session(DualSession* s) {
    if (!s) return;
    s->running.store(false);
    s->depth_q_cv.notify_all();  // 唤醒解析线程（排空残余后退出）
    s->color_q_cv.notify_all();
    s->depth_plane_cv.notify_all();  // 唤醒滤波线程
    // 取消在途异步 bulk（回调因 running=false 不重提交；CANCELLED 由 event_thread/排干处理）
    for (auto* x : s->depth_xfers) if (x) libusb_cancel_transfer(x);
    for (auto* x : s->color_xfers) if (x) libusb_cancel_transfer(x);
    if (s->keepalive_thread.joinable()) s->keepalive_thread.join();
    if (s->event_thread.joinable()) s->event_thread.join();
    if (s->depth_parser_thread.joinable()) s->depth_parser_thread.join();  // 先停组帧(不再产 plane)
    if (s->depth_filter_thread.joinable()) s->depth_filter_thread.join();  // 再停滤波(排空 plane 队列)
    if (s->color_parser_thread.joinable()) s->color_parser_thread.join();
    if (s->log_thread.joinable()) s->log_thread.join();
    // event_thread 已退出；再排干 ≤500ms 确保取消回调跑完，transfer 不在途再 free（防 use-after-free）。
    // s->ctx 为 nullptr 时即默认 context（host open_host 路径），handle_events 仍排默认 context。
    {
        timeval tv{0, 50 * 1000};
        for (int i = 0; i < 10; ++i) libusb_handle_events_timeout_completed(s->ctx, &tv, nullptr);
    }
    for (auto* x : s->depth_xfers) if (x) libusb_free_transfer(x);
    for (auto* x : s->color_xfers) if (x) libusb_free_transfer(x);
    s->depth_xfers.clear();
    s->color_xfers.clear();
#ifndef __ANDROID__
    // host(Linux)：释放接口 + 重附内核驱动，让原厂 SDK / kernel uvc 下次能正常枚举（best-effort，
    // 忽略返回；不附回去会让 vendor SDK 在我们 detach 过的设备上枚举失败）。Android fd 无此问题，#ifndef 守。
    for (auto* h : {s->master, s->companion}) {
        if (!h) continue;
        for (int i = 0; i <= 1; ++i) { libusb_release_interface(h, i); libusb_attach_kernel_driver(h, i); }
    }
#endif
    if (s->master) { libusb_close(s->master); s->master = nullptr; }
    if (s->companion) { libusb_close(s->companion); s->companion = nullptr; }
    // s->ctx 仅 fd 路径自有（libusb_init(&ctx)）才 exit；host 默认 context 由 UsbContext 释放。
    if (s->ctx) { libusb_exit(s->ctx); s->ctx = nullptr; }
    delete s;
}

// ─────────────────────────────────────────────────────────────────────────────
// M6.8b ④：Berxel → ICameraSession 适配。
// 下面的 open/snapshot core 是双流取流逻辑的唯一真理源(纯函数化:JNI buffer 换裸指针),经
// BerxelSessionAdapter/BerxelDriver 接 camera* 生产路径。历史 berxelDual* device-gated 回退 JNI
// 已在 color+depth 真机 PASS 后删除,此 core 即生产唯一实现,无分叉。
// ─────────────────────────────────────────────────────────────────────────────

// 从 cfg 填 DualSession 的流模式/控制字段（fd 与 host 路径共用）。
void berxel_set_modes_from_cfg(DualSession* s, const int32_t cfg[kBerxelCfgWords]) {
    s->depth_mode = P100R3VideoMode{static_cast<uint8_t>(cfg[3]),
                                    static_cast<uint16_t>(cfg[0]), static_cast<uint16_t>(cfg[1]),
                                    static_cast<uint16_t>(cfg[2]), static_cast<uint32_t>(cfg[4])};
    s->color_mode = P100R3VideoMode{static_cast<uint8_t>(cfg[8]),
                                    static_cast<uint16_t>(cfg[5]), static_cast<uint16_t>(cfg[6]),
                                    static_cast<uint16_t>(cfg[7]), static_cast<uint32_t>(cfg[9])};
    s->keepalive_ms = cfg[10] >= 0 ? cfg[10] : 50;
    s->read_len = cfg[11] > 0 ? cfg[11] : 16384;
    s->depth_read_len = s->read_len;
    s->color_read_len = s->read_len;
    s->color_payload_unit = 0;
    s->enable_color = cfg[12] != 0;
    s->depth_temporal_enable = cfg[13] >= 0;
    const int cfg13_abs = cfg[13] < 0 ? -cfg[13] : cfg[13];
    s->bulk_xfer_count = (cfg[13] == -9999)
        ? 0
        : ((cfg13_abs > 1 || cfg[13] == 1)
        ? std::min(std::max(cfg13_abs, 1), 128)
        : kDefaultBulkXferCount);
    s->depth_payload_transfer_size = cfg[14] > 0
        ? std::min(std::max(cfg[14], 1024), 4 * 1024 * 1024)
        : 0;
    s->depth_frame_size = static_cast<int>(s->depth_mode.width) * static_cast<int>(s->depth_mode.height) * 2;
    LOGI("config depth=%dx%d@%d color=%dx%d@%d enable_color=%d read_len=%d bulk_count=%d temporal=%d depth_payload=%d",
         s->depth_mode.width, s->depth_mode.height, s->depth_mode.fps,
         s->color_mode.width, s->color_mode.height, s->color_mode.fps,
         (int)s->enable_color, s->read_len, s->bulk_xfer_count, (int)s->depth_temporal_enable,
         s->depth_payload_transfer_size);
}

// 公共尾：s->master/companion(+ctx) 已就绪 → setup_dual + assembler + 线程 + bulk pump。
// fd(Android cameraOpenByFds)与 host(open_host)两开法共用此尾。失败返 false（调用方 close_session）。
bool berxel_setup_and_launch(DualSession* s,
                             const std::vector<uint8_t>& master_xu,
                             const std::vector<uint8_t>& comp_init) {
    s->master_dev = std::make_unique<AndroidUvcDevice>(s->master);
    s->companion_dev = std::make_unique<AndroidUvcDevice>(s->companion);

    s->running.store(true);
    if (!setup_dual(s, master_xu, comp_init)) {
        LOGE("setup_dual failed");
        return false;
    }
    // 注意：0x82 必须解析 UVC payload header；按裸 RAW 固定 size 切会把 header/续包拼进像素平面，
    // 状态行 marker 找不到，表现为 depth_seq=0 和持续花屏。
    s->depth_assembler = std::make_unique<UvcRawFrameAssembler>(UvcRawFrameAssemblerConfig{
        0x82, s->depth_mode, static_cast<size_t>(s->depth_frame_size), true,
        static_cast<size_t>(s->depth_frame_size) * 3, /*parse_uvc_payload_header=*/true});
    if (s->enable_color) {
        s->color_assembler = std::make_unique<UvcMjpegFrameAssembler>(
            UvcMjpegFrameAssemblerConfig{0x81, s->color_mode, 8 * 1024 * 1024, false});
    }
    s->depth_parser_thread = std::thread(depth_parser_loop, s);
    s->depth_filter_thread = std::thread(depth_filter_loop, s);  // M8.2：后处理独立线程，不堵组帧/reap
    if (s->enable_color) s->color_parser_thread = std::thread(color_parser_loop, s);
    s->event_thread = std::thread(dual_event_loop, s);
    // USB3/dock/手机组合上 depth-only 可能一提交大量 URB 就 NO_DEVICE；debug 路径允许降压 A/B。
    if (s->bulk_xfer_count > 0) {
        if (!submit_async_bulk(s, s->companion, 0x82, s->bulk_xfer_count, s->depth_read_len,
                               s->depth_xfers, s->depth_bufs, depth_xfer_cb)) {
            LOGE("submit depth async bulk failed");
            return false;
        }
        if (s->enable_color &&
            !submit_async_bulk(s, s->master, 0x81, s->bulk_xfer_count, s->read_len,
                               s->color_xfers, s->color_bufs, color_xfer_cb)) {
            LOGE("submit color async bulk failed");
            return false;
        }
    } else {
        LOGW("async bulk skipped by debug config; UVC stream committed without IN transfers");
    }
    s->log_thread = std::thread(log_loop, s);
    LOGI("berxel_setup_and_launch ok ptr=%p enable_color=%d temporal=%d depth_raw_parse=1 depth=%dx%d@%d read_len=%d bulk_count=%d",
         (void*)s, (int)s->enable_color, (int)s->depth_temporal_enable,
         s->depth_mode.width, s->depth_mode.height, s->depth_mode.fps,
         s->read_len, s->bulk_xfer_count);
    return true;
}

// Android fd 路径：NO_DEVICE_DISCOVERY + 自有 ctx + wrap_sys_device 接管 usbfs fd。失败返 nullptr。
DualSession* berxel_open_dual(int masterFd, int companionFd,
                              const std::vector<uint8_t>& master_xu,
                              const std::vector<uint8_t>& comp_init,
                              const int32_t cfg[kBerxelCfgWords]) {
    auto* s = new (std::nothrow) DualSession();
    if (!s) return nullptr;
    berxel_set_modes_from_cfg(s, cfg);
    if (libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY) != 0) {
        LOGE("set_option(NO_DEVICE_DISCOVERY) failed");
        delete s; return nullptr;
    }
    if (libusb_init(&s->ctx) != 0) { LOGE("libusb_init failed"); delete s; return nullptr; }
    if (libusb_wrap_sys_device(s->ctx, static_cast<intptr_t>(masterFd), &s->master) != 0 || !s->master) {
        LOGE("wrap master fd failed"); close_session(s); return nullptr;
    }
    if (libusb_wrap_sys_device(s->ctx, static_cast<intptr_t>(companionFd), &s->companion) != 0 || !s->companion) {
        LOGE("wrap companion fd failed"); close_session(s); return nullptr;
    }
    if (!berxel_setup_and_launch(s, master_xu, comp_init)) { close_session(s); return nullptr; }
    return s;
}

// host(Linux 服务器)路径：libusb 默认 context 枚举打开 master 0x0603:0x001f + companion 0x3558:0x1012。
// s->ctx 留 nullptr（默认 context 由 UsbContext 持有/释放），其余 setup 与 fd 路径完全一致。失败返 nullptr。
DualSession* berxel_open_dual_host(gomob::camera::UsbContext& ctx,
                                   const std::vector<uint8_t>& master_xu,
                                   const std::vector<uint8_t>& comp_init,
                                   const int32_t cfg[kBerxelCfgWords]) {
    auto* s = new (std::nothrow) DualSession();
    if (!s) return nullptr;
    berxel_set_modes_from_cfg(s, cfg);
    s->ctx = nullptr;  // 默认 context（UsbContext 已 libusb_init(nullptr)）
    s->master = ctx.open(0x0603, 0x001f);
    if (!s->master) { LOGE("host open master 0603:001f 失败"); close_session(s); return nullptr; }
    s->companion = ctx.open(0x3558, 0x1012);
    if (!s->companion) { LOGE("host open companion 3558:1012 失败"); close_session(s); return nullptr; }
    if (!berxel_setup_and_launch(s, master_xu, comp_init)) { close_session(s); return nullptr; }
    return s;
}

// depth → active 16bit mm 写 dst(cap_px 个 u16)，可同时拷贝同帧 confidence 给上层缓存。
// 返回字节数 / 0 无帧 / -1 cap 不足。meta=[aw,ah,frameNo,midNs]。
int berxel_snap_depth_mm_bundle(DualSession* s,
                                uint16_t* dst,
                                size_t cap_px,
                                int64_t meta[4],
                                std::vector<uint8_t>* out_conf) {
    if (!s) return 0;
    std::vector<uint8_t> transport;
    std::vector<uint16_t> fused;
    std::vector<uint8_t> conf;
    UvcFrameInfo info;
    uint64_t seq = 0;
    {
        std::lock_guard<std::mutex> lk(s->frame_mu);
        if (s->latest_depth_transport.empty()) return 0;
        if (s->depth_temporal_enable && !s->latest_depth_fused.empty()) {
            fused = s->latest_depth_fused;
        } else {
            transport = s->latest_depth_transport;
        }
        if (out_conf) conf = s->latest_depth_conf;
        info = s->latest_depth_info;
        seq = s->depth_seq;
    }
    const P100R3VideoMode active = p100r3_depth_active_mode(s->depth_mode);
    const size_t aw = active.width, ah = active.height;
    const size_t need = aw * ah * 2;
    if (!dst || cap_px * 2 < need) return -1;
    const uint16_t* src = nullptr;
    if (!fused.empty()) {
        if (fused.size() < aw * ah) return 0;
        src = fused.data();
    } else {
        if (transport.size() < need) return 0;
        src = reinterpret_cast<const uint16_t*>(transport.data());
    }
    for (size_t i = 0; i < aw * ah; ++i) {
        const float mm = p100r3_depth_raw_to_mm(src[i], P100R3DepthPixelFormat::k13I3D);
        dst[i] = static_cast<uint16_t>(mm < 0.0f ? 0.0f : (mm > 65535.0f ? 65535.0f : mm));
    }
    if (meta) {
        meta[0] = static_cast<int64_t>(aw); meta[1] = static_cast<int64_t>(ah);
        meta[2] = static_cast<int64_t>(seq); meta[3] = uvc_frame_midpoint_ns(info);
    }
    if (out_conf) {
        const size_t conf_need = aw * ah;
        if (conf.size() >= conf_need) {
            out_conf->assign(conf.begin(), conf.begin() + static_cast<std::ptrdiff_t>(conf_need));
        } else {
            out_conf->clear();
        }
    }
    return static_cast<int>(need);
}

// 逐像素 confidence(uint8) 写 dst。返回字节数 / 0 无 / -1 cap 不足。
int berxel_snap_conf(DualSession* s, uint8_t* dst, size_t cap, int64_t meta[4]) {
    if (!s) return 0;
    std::vector<uint8_t> conf;
    UvcFrameInfo info;
    uint64_t seq = 0;
    {
        std::lock_guard<std::mutex> lk(s->frame_mu);
        if (s->latest_depth_conf.empty()) return 0;
        conf = s->latest_depth_conf;
        info = s->latest_depth_info;
        seq = s->depth_seq;
    }
    const P100R3VideoMode active = p100r3_depth_active_mode(s->depth_mode);
    const size_t need = static_cast<size_t>(active.width) * active.height;
    if (conf.size() < need) return 0;
    if (!dst || cap < need) return -1;
    std::memcpy(dst, conf.data(), need);
    if (meta) {
        meta[0] = static_cast<int64_t>(active.width); meta[1] = static_cast<int64_t>(active.height);
        meta[2] = static_cast<int64_t>(seq); meta[3] = uvc_frame_midpoint_ns(info);
    }
    return static_cast<int>(need);
}

// IR/phase 高字节 → 8bit 灰度写 dst。返回字节数 / 0 无 / -1 cap 不足。
int berxel_snap_ir(DualSession* s, uint8_t* dst, size_t cap, int64_t meta[4]) {
    if (!s) return 0;
    std::vector<uint8_t> transport;
    UvcFrameInfo info;
    uint64_t seq = 0;
    {
        std::lock_guard<std::mutex> lk(s->frame_mu);
        if (s->latest_ir_transport.empty()) return 0;
        transport = s->latest_ir_transport;
        info = s->latest_ir_info;
        seq = s->ir_skipped;
    }
    const P100R3VideoMode active = p100r3_depth_active_mode(s->depth_mode);
    const size_t npix = static_cast<size_t>(active.width) * active.height;
    if (transport.size() < npix * 2) return 0;
    if (!dst || cap < npix) return -1;
    for (size_t i = 0; i < npix; ++i) dst[i] = transport[i * 2 + 1];
    if (meta) {
        meta[0] = static_cast<int64_t>(active.width); meta[1] = static_cast<int64_t>(active.height);
        meta[2] = static_cast<int64_t>(seq); meta[3] = uvc_frame_midpoint_ns(info);
    }
    return static_cast<int>(npix);
}

// 取走最新 master MJPEG color（consume-once，取走即清）。返回是否有帧。
bool berxel_take_color(DualSession* s, std::vector<uint8_t>* out) {
    if (!s || !out) return false;
    std::lock_guard<std::mutex> lk(s->color_mu);
    if (s->latest_color_mjpeg.empty()) return false;
    out->swap(s->latest_color_mjpeg);
    return true;
}

// 富诊断 16-long 统计。字段顺序见 ICameraSession::extended_stats / NativeBridge.cameraExtendedStats:
//   [0..3]depth frames/chunks/bytes/errors [4..7]color frames/chunks/bytes/errors
//   [8..11]pairs/lastDeltaNs/meanAbsDeltaNs/maxAbsDeltaNs [12..13]lastColor/lastDepthFrameNo
//   [14]keepaliveChunks [15]depthSeq。
void berxel_get_stats(DualSession* s, int64_t out[16]) {
    for (int i = 0; i < 16; ++i) out[i] = 0;
    if (!s) return;
    RgbdPairingStats ps;
    { std::lock_guard<std::mutex> lk(s->pairer_mu); ps = s->pairer.stats(); }
    uint64_t seq = 0;
    { std::lock_guard<std::mutex> lk(s->frame_mu); seq = s->depth_seq; }
    out[0] = s->depth_stats.frames;  out[1] = s->depth_stats.chunks;
    out[2] = s->depth_stats.bytes;   out[3] = s->depth_stats.errors;
    out[4] = s->color_stats.frames;  out[5] = s->color_stats.chunks;
    out[6] = s->color_stats.bytes;   out[7] = s->color_stats.errors;
    out[8] = ps.pairs;               out[9] = ps.last_host_delta_ns;
    out[10] = ps.mean_abs_host_delta_ns; out[11] = ps.max_abs_host_delta_ns;
    out[12] = static_cast<int64_t>(ps.last_color_frame_number);
    out[13] = static_cast<int64_t>(ps.last_depth_frame_number);
    out[14] = s->keepalive_stats.chunks;
    out[15] = static_cast<int64_t>(seq);
}

// dump 最新 depth transport 原始字节到 path。返回写入字节数。
int berxel_dump_depth(DualSession* s, const char* path) {
    if (!s || !path) return 0;
    std::vector<uint8_t> transport;
    uint64_t frame_no = 0;
    {
        std::lock_guard<std::mutex> lk(s->frame_mu);
        if (s->latest_depth_transport.empty()) return 0;
        transport = s->latest_depth_transport;
        frame_no = s->latest_depth_info.frame_number;
    }
    int written = 0;
    FILE* f = std::fopen(path, "wb");
    if (f) {
        written = static_cast<int>(std::fwrite(transport.data(), 1, transport.size(), f));
        std::fclose(f);
        LOGI("dump raw depth frame#%llu size=%zu -> %s",
             (unsigned long long)frame_no, transport.size(), path);
    } else {
        LOGE("dump fopen failed: %s", path);
    }
    return written;
}

// dump 最新 color MJPEG 到 path。返回写入字节数。
int berxel_dump_color(DualSession* s, const char* path) {
    if (!s || !path) return 0;
    std::vector<uint8_t> jpeg;
    {
        std::lock_guard<std::mutex> lk(s->color_mu);
        if (s->debug_latest_color_mjpeg.empty()) return 0;
        jpeg = s->debug_latest_color_mjpeg;
    }
    int written = 0;
    FILE* f = std::fopen(path, "wb");
    if (f) {
        written = static_cast<int>(std::fwrite(jpeg.data(), 1, jpeg.size(), f));
        std::fclose(f);
        LOGI("dump raw color mjpeg size=%zu -> %s", jpeg.size(), path);
    } else {
        LOGE("dump color fopen failed: %s", path);
    }
    return written;
}

// options_json 二进制布局(小端): [u32 xuLen][xu][u32 initLen][init][cfg]。
bool unpack_berxel_options(const std::string& blob,
                           std::vector<uint8_t>* xu,
                           std::vector<uint8_t>* init,
                           int32_t cfg[kBerxelCfgWords]) {
    const uint8_t* p = reinterpret_cast<const uint8_t*>(blob.data());
    const size_t n = blob.size();
    for (int i = 0; i < kBerxelCfgWords; ++i) cfg[i] = 0;
    size_t off = 0;
    auto rd_u32 = [&](uint32_t* v) -> bool {
        if (off + 4 > n) return false;
        std::memcpy(v, p + off, 4); off += 4; return true;
    };
    uint32_t xuLen = 0;
    if (!rd_u32(&xuLen)) return false;
    if (off + xuLen > n) return false;
    xu->assign(p + off, p + off + xuLen);
    off += xuLen;
    uint32_t initLen = 0;
    if (!rd_u32(&initLen)) return false;
    if (off + initLen > n) return false;
    init->assign(p + off, p + off + initLen);
    off += initLen;
    if (off + kBerxelCfgBaseWords * 4 > n) return false;
    for (int i = 0; i < kBerxelCfgBaseWords; ++i) {
        int32_t v;
        std::memcpy(&v, p + off, 4);
        cfg[i] = v;
        off += 4;
    }
    if (off + 4 <= n) {
        uint32_t next = 0;
        std::memcpy(&next, p + off, 4);
        const bool oldTailLooksLikeKeepalive = (off + 4 + static_cast<size_t>(next) == n);
        if (!oldTailLooksLikeKeepalive) {
            int32_t v;
            std::memcpy(&v, p + off, 4);
            cfg[14] = v;
            off += 4;
        }
    }
    return true;
}

// ICameraSession 适配：包一个已开流的 DualSession，snapshot/stats/dump 全走上面的 core。
class BerxelSessionAdapter : public gomob::camera::ICameraSession {
 public:
    explicit BerxelSessionAdapter(DualSession* s) : s_(s) {}
    ~BerxelSessionAdapter() override { if (s_) { close_session(s_); s_ = nullptr; } }

    bool start(const gomob::camera::SessionCallbacks&) override {
        // open_fd 里 berxel_open_dual 已起全部线程 + bulk;此处会话已在流。
        state_ = s_ ? gomob::camera::SessionState::kStreaming : gomob::camera::SessionState::kError;
        return s_ != nullptr;
    }
    int poll(gomob::camera::CameraFrame*, uint32_t) override { return 0; }  // Berxel 走 snapshot,不用 poll
    bool set_controls(const gomob::camera::DepthControls&) override { return true; }  // dual 控制是 start-config
    void stop() override {
        if (s_) { close_session(s_); s_ = nullptr; }
        state_ = gomob::camera::SessionState::kStopped;
    }
    void join() override {}  // close_session 已 join 线程
    gomob::camera::SessionState state() const override { return state_; }
    gomob::camera::SessionStats stats() const override {
        gomob::camera::SessionStats st;
        if (s_) {
            int64_t e[16]; berxel_get_stats(s_, e);
            st.depth_frames = e[0]; st.color_frames = e[4]; st.errors = e[3] + e[7];
        }
        return st;
    }
    int snapshot_depth_mm(uint16_t* dst, size_t cap_px, int64_t* meta) override {
        int64_t m[4] = {0, 0, 0, 0};
        std::vector<uint8_t> conf;
        const int r = berxel_snap_depth_mm_bundle(s_, dst, cap_px, m, &conf);
        if (r > 0) {
            std::lock_guard<std::mutex> lk(snapshot_mu_);
            cached_depth_conf_ = std::move(conf);
            for (int i = 0; i < 4; ++i) cached_depth_meta_[i] = m[i];
        }
        if (meta) for (int i = 0; i < 4; ++i) meta[i] = m[i];
        return r;
    }
    bool snapshot_color(std::vector<uint8_t>* out, int64_t* meta) override {
        const bool ok = berxel_take_color(s_, out);
        if (meta) for (int i = 0; i < 4; ++i) meta[i] = 0;  // master MJPEG 无 per-frame meta
        return ok;
    }
    int snapshot_confidence(uint8_t* dst, size_t cap, int64_t* meta) override {
        {
            std::lock_guard<std::mutex> lk(snapshot_mu_);
            if (!cached_depth_conf_.empty()) {
                const size_t need = cached_depth_conf_.size();
                if (!dst || cap < need) return -1;
                std::memcpy(dst, cached_depth_conf_.data(), need);
                if (meta) for (int i = 0; i < 4; ++i) meta[i] = cached_depth_meta_[i];
                cached_depth_conf_.clear();
                return static_cast<int>(need);
            }
        }
        int64_t m[4] = {0, 0, 0, 0};
        const int r = berxel_snap_conf(s_, dst, cap, m);
        if (meta) for (int i = 0; i < 4; ++i) meta[i] = m[i];
        return r;
    }
    int snapshot_ir(uint8_t* dst, size_t cap, int64_t* meta) override {
        int64_t m[4] = {0, 0, 0, 0};
        const int r = berxel_snap_ir(s_, dst, cap, m);
        if (meta) for (int i = 0; i < 4; ++i) meta[i] = m[i];
        return r;
    }
    int extended_stats(int64_t* out, size_t cap) const override {
        if (!s_ || !out) return 0;
        int64_t e[16]; berxel_get_stats(s_, e);
        const size_t n = cap < 16 ? cap : 16;
        for (size_t i = 0; i < n; ++i) out[i] = e[i];
        return static_cast<int>(n);
    }
    int dump_raw_depth(const char* path) override { return berxel_dump_depth(s_, path); }
    int dump_raw_color(const char* path) override { return berxel_dump_color(s_, path); }

 private:
    DualSession* s_ = nullptr;
    gomob::camera::SessionState state_ = gomob::camera::SessionState::kStarting;
    std::mutex snapshot_mu_;
    std::vector<uint8_t> cached_depth_conf_;
    int64_t cached_depth_meta_[4] = {0, 0, 0, 0};
};

// ICameraDriver：无设备态工厂 + 枚举。open_fd 收 [masterFd, companionFd] + 打包 options_json。
class BerxelDriver : public gomob::camera::ICameraDriver {
 public:
    gomob::camera::CameraCapabilities capabilities() const override {
        gomob::camera::CameraCapabilities c;
        c.vendor = "Berxel"; c.model = "iHawk P100R3";
        c.has_color = true; c.has_depth = true; c.has_confidence = true; c.has_ir = true;
        c.depth_is_metric_onchip = true;
        c.depth_profiles.push_back(gomob::camera::StreamProfile{
            1280, 800, 45, gomob::camera::StreamProfile::Format::kDepthU16, "1280x800@45"});
        return c;
    }
    std::vector<gomob::camera::UsbId> match_usb_ids() const override {
        return { gomob::camera::UsbId{0x0603, 0x001f} };  // master 节点;companion 0x3558 内部
    }
    std::unique_ptr<gomob::camera::ICameraSession> open_host(
            gomob::camera::UsbContext& ctx, const gomob::camera::SessionConfig& cfg) override {
        // host(Linux 服务器)统一路径：libusb 枚举打开 master+companion → berxel_open_dual_host。
        // options_json 同 open_fd 打包 [masterXu|companionInit|cfg]，与 fd 路径同一双流序列。
        std::vector<uint8_t> master_xu, comp_init;
        int32_t c15[kBerxelCfgWords] = {0};
        if (!unpack_berxel_options(cfg.options_json, &master_xu, &comp_init, c15)) {
            LOGE("BerxelDriver open_host: options_json 解析失败 len=%zu", cfg.options_json.size());
            return nullptr;
        }
        DualSession* s = berxel_open_dual_host(ctx, master_xu, comp_init, c15);
        if (!s) { LOGE("BerxelDriver open_host: berxel_open_dual_host 失败"); return nullptr; }
        return std::make_unique<BerxelSessionAdapter>(s);
    }
    std::unique_ptr<gomob::camera::ICameraSession> open_fd(
            const std::vector<int>& fds, const gomob::camera::SessionConfig& cfg) override {
        if (fds.size() < 2) {
            LOGE("BerxelDriver open_fd 需 2 fd(master+companion),给了 %zu", fds.size());
            return nullptr;
        }
        std::vector<uint8_t> master_xu, comp_init;
        int32_t c15[kBerxelCfgWords] = {0};
        if (!unpack_berxel_options(cfg.options_json, &master_xu, &comp_init, c15)) {
            LOGE("BerxelDriver open_fd: options_json 解析失败 len=%zu", cfg.options_json.size());
            return nullptr;
        }
        DualSession* s = berxel_open_dual(fds[0], fds[1], master_xu, comp_init, c15);
        if (!s) { LOGE("BerxelDriver open_fd: berxel_open_dual 失败"); return nullptr; }
        return std::make_unique<BerxelSessionAdapter>(s);
    }
};

}  // namespace

// MakeBerxelDriver：在 camera_session_jni 注册。定义在 anon namespace 外,引用其中的 BerxelDriver
// （同 TU 内可见）。
namespace gomob::berxel::host {
std::shared_ptr<gomob::camera::ICameraDriver> MakeBerxelDriver() {
    return std::make_shared<BerxelDriver>();
}
}  // namespace gomob::berxel::host
