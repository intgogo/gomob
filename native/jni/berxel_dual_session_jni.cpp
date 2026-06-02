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
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
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
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGI(...) do { std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#define LOGE(...) do { std::fprintf(stderr, __VA_ARGS__); std::fprintf(stderr, "\n"); } while (0)
#endif

namespace {

using namespace gomob::berxel::host;

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
    std::vector<uint8_t> latest_depth_conf;    // 逐像素 temporal confidence（飞点处=0）
    std::vector<uint8_t> latest_depth_flying;  // 逐像素飞点 mask（1=飞点；仅诊断/可视化）
    // companion 0x82 交织的 IR/phase 帧（非真深度）：不丢，存起来供「切 IR」预览。
    std::vector<uint8_t> latest_ir_transport;
    UvcFrameInfo latest_ir_info;
    uint64_t ir_skipped = 0;  // = IR/phase 帧计数
    // master 0x81 MJPEG color 最新帧（供 UI poll；consume-once 语义）。
    std::mutex color_mu;
    std::vector<uint8_t> latest_color_mjpeg;

    bool enable_color = true;
    int read_len = 16384;
    int keepalive_ms = 50;
    P100R3VideoMode depth_mode{2, 640, 401, 45, 222222};
    P100R3VideoMode color_mode{2, 1280, 800, 30, 333333};
    int depth_frame_size = 0;
};

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

bool is_real_depth_frame(const std::vector<uint8_t>& f) {
    if (f.size() < 2) return true;  // 太小不判，按深度放行
    const uint16_t marker = static_cast<uint16_t>(f[0] | (static_cast<uint16_t>(f[1]) << 8));
    return marker == kP100R3DepthFrameMarker;
}

// 解析队列上限：解析线程跟得上时队列恒小；满了才丢（丢 chunk 废掉该帧，但优先保证 event 线程不阻塞、
// 持续吸干 USB，避免 device 内部 buffer 溢出掉线）。1024×64KB=64MB 上限，正常远到不了。
constexpr size_t kDepthQMaxChunks = 1024;

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
            bool notify = false;
            {
                std::lock_guard<std::mutex> lk(s->depth_q_mu);
                if (s->depth_q.size() < kDepthQMaxChunks) {
                    s->depth_q.push_back(DualSession::DepthChunk{
                        std::vector<uint8_t>(xfer->buffer, xfer->buffer + xfer->actual_length),
                        static_cast<uint64_t>(detail::now_ns())});
                    if (s->depth_q.size() > s->depth_q_hwm) s->depth_q_hwm = s->depth_q.size();
                    notify = true;
                } else {
                    s->depth_q_drops++;
                }
            }
            if (notify) s->depth_q_cv.notify_one();
        }
        break;
    case LIBUSB_TRANSFER_NO_DEVICE:
        LOGE("depth xfer NO_DEVICE → stop");
        s->depth_stats.errors++;
        s->running.store(false);
        s->depth_q_cv.notify_all();  // 唤醒解析线程退出
        return;
    case LIBUSB_TRANSFER_CANCELLED:
        return;  // close 流程取消，不重提交
    default:
        s->depth_stats.errors++;
        break;
    }
    if (s->running.load()) libusb_submit_transfer(xfer);
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
        for (UvcRawFrame& f : frames) {
            // 真深度帧进 latest_depth；IR/phase 帧转存 latest_ir（供「切 IR」预览），不混入 depth。
            if (!is_real_depth_frame(f.payload)) {
                {
                    std::lock_guard<std::mutex> lk(s->frame_mu);
                    s->latest_ir_transport = f.payload;
                    s->latest_ir_info = f.info;
                    s->ir_skipped++;
                }
                // IR 散斑局部对比度 → 单帧深度置信先验,喂时域滤波器(下一深度帧 push 融合 min)。
                // 散斑弱/无回波处深度不可信(实证 AUC 0.82)；本线程是 filter 唯一消费者,IR/depth
                // 顺序处理,set/consume 无并发,无需额外锁。在锁外算(积分图 ~256k px,不占 frame_mu)。
                if (s->depth_temporal_enable) {
                    const P100R3VideoMode active = p100r3_depth_active_mode(s->depth_mode);
                    const size_t aw = active.width, ah = active.height;
                    if (aw > 0 && ah > 0 && f.payload.size() >= aw * ah * 2) {
                        const auto* src = reinterpret_cast<const uint16_t*>(f.payload.data());
                        std::vector<uint16_t> active_ir(src, src + aw * ah);
                        s->depth_temporal_filter.set_prior_confidence(
                            p100r3_ir_speckle_confidence(active_ir,
                                static_cast<uint16_t>(aw), static_cast<uint16_t>(ah)));
                    }
                }
                continue;
            }
            // 时域融合：active raw16 = transport 前 aw×ah 个 uint16（状态行是最后一行）。
            // 在解析线程内（本线程是唯一消费者，按到达顺序每帧恰好一次），filter 状态无需额外锁。
            std::vector<uint16_t> fused;
            std::vector<uint8_t> conf;
            std::vector<uint8_t> flymask;
            bool have_fused = false;
            if (s->depth_temporal_enable) {
                const P100R3VideoMode active = p100r3_depth_active_mode(s->depth_mode);
                const size_t aw = active.width, ah = active.height;
                if (aw > 0 && ah > 0 && f.payload.size() >= aw * ah * 2) {
                    const auto* src = reinterpret_cast<const uint16_t*>(f.payload.data());
                    std::vector<uint16_t> active_raw(src, src + aw * ah);
                    // 传 &flymask 使飞点剔除在 live 生效：飞点处 conf 置 0（fused 原值保留）。
                    have_fused = s->depth_temporal_filter.push(
                        active_raw, static_cast<uint16_t>(aw), static_cast<uint16_t>(ah),
                        &fused, &conf, nullptr, &flymask);
                }
            }
            {
                std::lock_guard<std::mutex> lk(s->frame_mu);
                s->latest_depth_transport = f.payload;
                s->latest_depth_info = f.info;
                if (have_fused) {
                    s->latest_depth_fused = std::move(fused);
                    s->latest_depth_conf = std::move(conf);
                    s->latest_depth_flying = std::move(flymask);
                }
                s->depth_seq++;
            }
            std::lock_guard<std::mutex> lk(s->pairer_mu);
            RgbdFramePairInfo pair;
            s->pairer.push_depth(f.info, &pair);
        }
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
            bool notify = false;
            {
                std::lock_guard<std::mutex> lk(s->color_q_mu);
                if (s->color_q.size() < kDepthQMaxChunks) {
                    s->color_q.push_back(DualSession::DepthChunk{
                        std::vector<uint8_t>(xfer->buffer, xfer->buffer + xfer->actual_length),
                        static_cast<uint64_t>(detail::now_ns())});
                    if (s->color_q.size() > s->color_q_hwm) s->color_q_hwm = s->color_q.size();
                    notify = true;
                } else {
                    s->color_q_drops++;
                }
            }
            if (notify) s->color_q_cv.notify_one();
        }
        break;
    case LIBUSB_TRANSFER_NO_DEVICE:
        LOGE("color xfer NO_DEVICE → stop");
        s->color_stats.errors++;
        s->running.store(false);
        s->color_q_cv.notify_all();
        return;
    case LIBUSB_TRANSFER_CANCELLED:
        return;
    default:
        s->color_stats.errors++;
        break;
    }
    if (s->running.load()) libusb_submit_transfer(xfer);
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
        frames.clear();
        s->color_assembler->push_packet(chunk.data.data(), chunk.data.size(), chunk.ts_ns, &frames);
        for (UvcMjpegFrame& f : frames) {
            {
                std::lock_guard<std::mutex> lk(s->color_mu);
                s->latest_color_mjpeg = f.jpeg;
            }
            std::lock_guard<std::mutex> lk(s->pairer_mu);
            RgbdFramePairInfo pair;
            s->pairer.push_color(f.info, &pair);
        }
    }
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
                       std::vector<libusb_transfer*>& xfers,
                       std::vector<std::vector<uint8_t>>& bufs,
                       libusb_transfer_cb_fn cb) {
    xfers.assign(static_cast<size_t>(count), nullptr);
    bufs.assign(static_cast<size_t>(count), std::vector<uint8_t>(static_cast<size_t>(s->read_len), 0));
    for (int i = 0; i < count; ++i) {
        xfers[i] = libusb_alloc_transfer(0);
        if (!xfers[i]) { LOGE("alloc_transfer ep=0x%02x #%d failed", ep, i); return false; }
        libusb_fill_bulk_transfer(xfers[i], handle, ep, bufs[i].data(),
                                  s->read_len, cb, s, 0);
        const int rc = libusb_submit_transfer(xfers[i]);
        if (rc != 0) {
            LOGE("submit_transfer ep=0x%02x #%d rc=%d (%s)", ep, i, rc, usb_error_name(rc));
            libusb_free_transfer(xfers[i]);
            xfers[i] = nullptr;
            return false;
        }
    }
    LOGI("async bulk submitted ep=0x%02x count=%d size=%d", ep, count, s->read_len);
    return true;
}

// 每秒打一条运行态日志：真机一眼看出 depth/color 帧是否持续、pair delta、depth mm sanity。
void log_loop(DualSession* s) {
    while (s->running.load()) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        float median_mm = 0.0f, valid_ratio = 0.0f;
        uint64_t seq = 0, ir = 0;
        {
            std::lock_guard<std::mutex> lk(s->frame_mu);
            if (!s->latest_depth_transport.empty()) {
                depth_center_sanity(s->latest_depth_transport, s->depth_mode, &median_mm, &valid_ratio);
            }
            seq = s->depth_seq;
            ir = s->ir_skipped;
        }
        RgbdPairingStats ps;
        {
            std::lock_guard<std::mutex> lk(s->pairer_mu);
            ps = s->pairer.stats();
        }
        size_t q_now = 0, q_hwm = 0, cq_hwm = 0; int64_t q_drops = 0, cq_drops = 0;
        {
            std::lock_guard<std::mutex> lk(s->depth_q_mu);
            q_now = s->depth_q.size(); q_hwm = s->depth_q_hwm; q_drops = s->depth_q_drops;
        }
        {
            std::lock_guard<std::mutex> lk(s->color_q_mu);
            cq_hwm = s->color_q_hwm; cq_drops = s->color_q_drops;
        }
        LOGI("RUN depth_seq=%llu ir_skipped=%llu pairs=%lld "
             "center_median=%.1fmm valid=%.3f pair_p50_delta_ns=%lld "
             "depth_chunks=%lld depth_err=%lld color_chunks=%lld color_bytes=%lld color_err=%lld "
             "ka_ok=%lld ka_err=%lld q_now=%zu q_hwm=%zu q_drops=%lld cq_hwm=%zu cq_drops=%lld",
             (unsigned long long)seq, (unsigned long long)ir,
             (long long)ps.pairs, median_mm, valid_ratio,
             (long long)ps.last_host_delta_ns,
             (long long)s->depth_stats.chunks, (long long)s->depth_stats.errors,
             (long long)s->color_stats.chunks, (long long)s->color_stats.bytes,
             (long long)s->color_stats.errors,
             (long long)s->keepalive_stats.chunks, (long long)s->keepalive_stats.errors,
             q_now, q_hwm, (long long)q_drops, cq_hwm, (long long)cq_drops);
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
    UvcNegotiation depth_neg;
    if (!negotiate_uvc_stream(*s->companion_dev, depth_cfg, &depth_neg, log)) {
        LOGE("depth UVC commit failed");
        return false;
    }
    return true;
}

void close_session(DualSession* s) {
    if (!s) return;
    s->running.store(false);
    s->depth_q_cv.notify_all();  // 唤醒解析线程（排空残余后退出）
    s->color_q_cv.notify_all();
    // 取消在途异步 bulk（回调因 running=false 不重提交；CANCELLED 由 event_thread/排干处理）
    for (auto* x : s->depth_xfers) if (x) libusb_cancel_transfer(x);
    for (auto* x : s->color_xfers) if (x) libusb_cancel_transfer(x);
    if (s->keepalive_thread.joinable()) s->keepalive_thread.join();
    if (s->event_thread.joinable()) s->event_thread.join();
    if (s->depth_parser_thread.joinable()) s->depth_parser_thread.join();
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

// 从 14-int cfg 填 DualSession 的流模式/控制字段（fd 与 host 路径共用）。
void berxel_set_modes_from_cfg(DualSession* s, const int32_t cfg[14]) {
    s->depth_mode = P100R3VideoMode{static_cast<uint8_t>(cfg[3]),
                                    static_cast<uint16_t>(cfg[0]), static_cast<uint16_t>(cfg[1]),
                                    static_cast<uint16_t>(cfg[2]), static_cast<uint32_t>(cfg[4])};
    s->color_mode = P100R3VideoMode{static_cast<uint8_t>(cfg[8]),
                                    static_cast<uint16_t>(cfg[5]), static_cast<uint16_t>(cfg[6]),
                                    static_cast<uint16_t>(cfg[7]), static_cast<uint32_t>(cfg[9])};
    s->keepalive_ms = cfg[10] >= 0 ? cfg[10] : 50;
    s->read_len = cfg[11] > 0 ? cfg[11] : 16384;
    s->enable_color = cfg[12] != 0;
    s->depth_temporal_enable = cfg[13] >= 0;
    s->depth_frame_size = static_cast<int>(s->depth_mode.width) * static_cast<int>(s->depth_mode.height) * 2;
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
    s->depth_assembler = std::make_unique<UvcRawFrameAssembler>(UvcRawFrameAssemblerConfig{
        0x82, s->depth_mode, static_cast<size_t>(s->depth_frame_size), true,
        static_cast<size_t>(s->depth_frame_size) * 3});
    if (s->enable_color) {
        s->color_assembler = std::make_unique<UvcMjpegFrameAssembler>(
            UvcMjpegFrameAssemblerConfig{0x81, s->color_mode, 8 * 1024 * 1024});
    }
    s->depth_parser_thread = std::thread(depth_parser_loop, s);
    if (s->enable_color) s->color_parser_thread = std::thread(color_parser_loop, s);
    s->event_thread = std::thread(dual_event_loop, s);
    // 2026-06-02 真机证伪 rank2:master color 0x81 URB 48→8 不解决 2510DRK44C 上 color-on 整机死
    //   (8 URB 照样 depth_chunks=0)。⇒ 不是 URB 数量,是【并发 master color 流本身】与 companion
    //   depth 在该机 USB stack 上互斥(depth-only 稳出 302 帧,一开 color 整机 0 帧)。
    //   真解走 04b 时间复用(depth/color 错峰单流,不并发),非 URB 调参。故此处保持对称 48。
    constexpr int kBulkXferCount = 48;
    if (!submit_async_bulk(s, s->companion, 0x82, kBulkXferCount, s->depth_xfers, s->depth_bufs, depth_xfer_cb)) {
        LOGE("submit depth async bulk failed");
        return false;
    }
    if (s->enable_color &&
        !submit_async_bulk(s, s->master, 0x81, kBulkXferCount, s->color_xfers, s->color_bufs, color_xfer_cb)) {
        LOGE("submit color async bulk failed");
        return false;
    }
    s->log_thread = std::thread(log_loop, s);
    LOGI("berxel_setup_and_launch ok ptr=%p enable_color=%d temporal=%d depth=%dx%d@%d",
         (void*)s, (int)s->enable_color, (int)s->depth_temporal_enable,
         s->depth_mode.width, s->depth_mode.height, s->depth_mode.fps);
    return true;
}

// Android fd 路径：NO_DEVICE_DISCOVERY + 自有 ctx + wrap_sys_device 接管 usbfs fd。失败返 nullptr。
DualSession* berxel_open_dual(int masterFd, int companionFd,
                              const std::vector<uint8_t>& master_xu,
                              const std::vector<uint8_t>& comp_init,
                              const int32_t cfg[14]) {
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
                                   const int32_t cfg[14]) {
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

// depth → active 16bit mm 写 dst(cap_px 个 u16)。返回字节数 / 0 无帧 / -1 cap 不足。meta=[aw,ah,frameNo,midNs]。
int berxel_snap_depth_mm(DualSession* s, uint16_t* dst, size_t cap_px, int64_t meta[4]) {
    if (!s) return 0;
    std::vector<uint8_t> transport;
    std::vector<uint16_t> fused;
    UvcFrameInfo info;
    {
        std::lock_guard<std::mutex> lk(s->frame_mu);
        if (s->latest_depth_transport.empty()) return 0;
        if (s->depth_temporal_enable && !s->latest_depth_fused.empty()) {
            fused = s->latest_depth_fused;
        } else {
            transport = s->latest_depth_transport;
        }
        info = s->latest_depth_info;
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
        meta[2] = static_cast<int64_t>(info.frame_number); meta[3] = uvc_frame_midpoint_ns(info);
    }
    return static_cast<int>(need);
}

// 逐像素 confidence(uint8) 写 dst。返回字节数 / 0 无 / -1 cap 不足。
int berxel_snap_conf(DualSession* s, uint8_t* dst, size_t cap, int64_t meta[4]) {
    if (!s) return 0;
    std::vector<uint8_t> conf;
    UvcFrameInfo info;
    {
        std::lock_guard<std::mutex> lk(s->frame_mu);
        if (s->latest_depth_conf.empty()) return 0;
        conf = s->latest_depth_conf;
        info = s->latest_depth_info;
    }
    const P100R3VideoMode active = p100r3_depth_active_mode(s->depth_mode);
    const size_t need = static_cast<size_t>(active.width) * active.height;
    if (conf.size() < need) return 0;
    if (!dst || cap < need) return -1;
    std::memcpy(dst, conf.data(), need);
    if (meta) {
        meta[0] = static_cast<int64_t>(active.width); meta[1] = static_cast<int64_t>(active.height);
        meta[2] = static_cast<int64_t>(info.frame_number); meta[3] = uvc_frame_midpoint_ns(info);
    }
    return static_cast<int>(need);
}

// IR/phase 高字节 → 8bit 灰度写 dst。返回字节数 / 0 无 / -1 cap 不足。
int berxel_snap_ir(DualSession* s, uint8_t* dst, size_t cap, int64_t meta[4]) {
    if (!s) return 0;
    std::vector<uint8_t> transport;
    UvcFrameInfo info;
    {
        std::lock_guard<std::mutex> lk(s->frame_mu);
        if (s->latest_ir_transport.empty()) return 0;
        transport = s->latest_ir_transport;
        info = s->latest_ir_info;
    }
    const P100R3VideoMode active = p100r3_depth_active_mode(s->depth_mode);
    const size_t npix = static_cast<size_t>(active.width) * active.height;
    if (transport.size() < npix * 2) return 0;
    if (!dst || cap < npix) return -1;
    for (size_t i = 0; i < npix; ++i) dst[i] = transport[i * 2 + 1];
    if (meta) {
        meta[0] = static_cast<int64_t>(active.width); meta[1] = static_cast<int64_t>(active.height);
        meta[2] = static_cast<int64_t>(info.frame_number); meta[3] = uvc_frame_midpoint_ns(info);
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

// options_json 二进制布局(小端): [u32 xuLen][xu][u32 initLen][init][14×i32 cfg]。
bool unpack_berxel_options(const std::string& blob,
                           std::vector<uint8_t>* xu, std::vector<uint8_t>* init, int32_t cfg[14]) {
    const uint8_t* p = reinterpret_cast<const uint8_t*>(blob.data());
    const size_t n = blob.size();
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
    if (off + 14 * 4 > n) return false;
    for (int i = 0; i < 14; ++i) { int32_t v; std::memcpy(&v, p + off, 4); cfg[i] = v; off += 4; }
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
        const int r = berxel_snap_depth_mm(s_, dst, cap_px, m);
        if (meta) for (int i = 0; i < 4; ++i) meta[i] = m[i];
        return r;
    }
    bool snapshot_color(std::vector<uint8_t>* out, int64_t* meta) override {
        const bool ok = berxel_take_color(s_, out);
        if (meta) for (int i = 0; i < 4; ++i) meta[i] = 0;  // master MJPEG 无 per-frame meta
        return ok;
    }
    int snapshot_confidence(uint8_t* dst, size_t cap, int64_t* meta) override {
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

 private:
    DualSession* s_ = nullptr;
    gomob::camera::SessionState state_ = gomob::camera::SessionState::kStarting;
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
        // options_json 同 open_fd 打包 [masterXu|companionInit|14-int cfg]，与 fd 路径同一双流序列。
        std::vector<uint8_t> master_xu, comp_init;
        int32_t c14[14] = {0};
        if (!unpack_berxel_options(cfg.options_json, &master_xu, &comp_init, c14)) {
            LOGE("BerxelDriver open_host: options_json 解析失败 len=%zu", cfg.options_json.size());
            return nullptr;
        }
        DualSession* s = berxel_open_dual_host(ctx, master_xu, comp_init, c14);
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
        int32_t c14[14] = {0};
        if (!unpack_berxel_options(cfg.options_json, &master_xu, &comp_init, c14)) {
            LOGE("BerxelDriver open_fd: options_json 解析失败 len=%zu", cfg.options_json.size());
            return nullptr;
        }
        DualSession* s = berxel_open_dual(fds[0], fds[1], master_xu, comp_init, c14);
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
