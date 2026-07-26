// HLSD8 RGB 相机取流实现 — 见 hlsd8_uvc_session.h 头注。
#include "hlsd8/hlsd8_uvc_session.h"

#include "eys3d/android/eys3d_libuvc.h"

#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstring>

#define HLOG(...) __android_log_print(ANDROID_LOG_INFO, "hlsd8_uvc", __VA_ARGS__)

namespace gomob::hlsd8 {

using gomob::camera::CameraCapabilities;
using gomob::camera::CameraFrame;
using gomob::camera::ICameraSession;
using gomob::camera::SessionCallbacks;
using gomob::camera::SessionConfig;
using gomob::camera::SessionStats;
using gomob::camera::StreamProfile;
using SS = gomob::camera::SessionState;
using gomob::eys3d::android::uvc_context;
using gomob::eys3d::android::uvc_device;
using gomob::eys3d::android::uvc_device_handle;
using gomob::eys3d::android::uvc_frame;
using gomob::eys3d::android::uvc_frame_callback_t;
using gomob::eys3d::android::uvc_stream_ctrl;

namespace {

// 对齐 VINCreator 的双栈隔离：HLSD8 使用 libuvc1.so → libusb1001.so，RS-D550 厂商引擎使用
// libuvc.so → libusb100.so。两套不同 SONAME 保证各自拥有独立 libusb 全局状态和事件线程。
// HLSD8 只需标准 color-only UVC API，不需要加载 libUVCCamera1.so；但协商与起流参数必须逐项
// 对齐其 UVCPreview 实现，否则 10fps 会把同一 USB2 hub 上的 RS-D550 mode25 挤到全 transfer timeout。
struct LusbUvcApi {
  int (*init2)(uvc_context**, libusb_context*, const char*) = nullptr;
  int (*get_device_with_fd)(uvc_context*, uvc_device**, int, int, const char*, int, int, int) = nullptr;
  int (*open)(uvc_device*, uvc_device_handle**) = nullptr;
  int (*get_stream_ctrl_format_size_fps)(
      uvc_device_handle*, uvc_stream_ctrl*, int, int, int, int, int) = nullptr;
  int (*start_streaming_bandwidth)(
      uvc_device_handle*, uvc_stream_ctrl*, uvc_frame_callback_t*, void*, uint8_t, float) = nullptr;
  void (*stop_streaming)(uvc_device_handle*) = nullptr;
  void (*close)(uvc_device_handle*) = nullptr;
  void (*unref_device)(uvc_device*) = nullptr;
  void (*exit)(uvc_context*) = nullptr;
  void* lib = nullptr;
};

// libuvc1 是 VINCreator 新栈，frame-format 枚举与旧 libuvc.so 不同。
// 反汇编 uvc_frame_format_matches_guid 的表项可见 9 → "MJPG"；旧头里的 MJPEG=7 会恒返 -51。
constexpr int kUvc1FrameFormatMjpeg = 9;

bool LoadLusbUvc(LusbUvcApi* a) {
  a->lib = dlopen("libuvc1.so", RTLD_NOW | RTLD_LOCAL);
  if (!a->lib) { HLOG("dlopen libuvc1.so failed: %s", dlerror()); return false; }
#define USYM(f, n) a->f = reinterpret_cast<decltype(a->f)>(dlsym(a->lib, n))
  USYM(init2, "uvc_init2");
  USYM(get_device_with_fd, "uvc_get_device_with_fd");
  USYM(open, "uvc_open");
  USYM(get_stream_ctrl_format_size_fps, "uvc_get_stream_ctrl_format_size_fps");
  USYM(start_streaming_bandwidth, "uvc_start_streaming_bandwidth");
  USYM(stop_streaming, "uvc_stop_streaming");
  USYM(close, "uvc_close");
  USYM(unref_device, "uvc_unref_device");
  USYM(exit, "uvc_exit");
#undef USYM
  const bool ok = a->init2 && a->get_device_with_fd && a->open &&
                  a->get_stream_ctrl_format_size_fps && a->start_streaming_bandwidth &&
                  a->stop_streaming && a->close && a->unref_device && a->exit;
  HLOG("libuvc1/libusb1001 dlopen ok=%d", ok);
  return ok;
}

int64_t NowNs() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

// usbfs fd → busnum/devaddr（uvc_get_device_with_fd 需要）。/proc/self/fd/<fd> → /dev/bus/usb/BBB/DDD。
void ResolveBusDev(int fd, int* busnum, int* devaddr) {
  char path[64], link[256];
  std::snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
  ssize_t n = readlink(path, link, sizeof(link) - 1);
  if (n <= 0) return;
  link[n] = '\0';
  const char* base = "/dev/bus/usb/";
  const char* p = std::strstr(link, base);
  if (!p) return;
  p += std::strlen(base);
  *busnum = std::atoi(p);
  const char* slash = std::strchr(p, '/');
  if (slash) *devaddr = std::atoi(slash + 1);
}

// libuvc 回调（color-only）→ 转交会话存最新帧。
void Hlsd8ColorCb(uvc_frame* frame, void* user) {
  auto* s = static_cast<Hlsd8UvcSession*>(user);
  if (!s || !frame || !frame->data || frame->data_bytes == 0) return;
  // vendor uvc_frame 在 data_bytes 后有私有字段，不能用 pupil 头读取 width/height；几何取已协商档。
  s->OnColorFrame(static_cast<const uint8_t*>(frame->data), frame->data_bytes, NowNs());
}

}  // namespace

Hlsd8UvcSession::Hlsd8UvcSession(int fd, const SessionConfig& cfg) : fd_(fd), cfg_(cfg) {}

Hlsd8UvcSession::~Hlsd8UvcSession() {
  stop();
  join();
}

bool Hlsd8UvcSession::start(const SessionCallbacks&) {
  if (running_.exchange(true)) return false;
  stop_ = false;
  thread_ = std::thread(&Hlsd8UvcSession::Run, this);
  return true;
}

void Hlsd8UvcSession::OnColorFrame(const uint8_t* data, size_t bytes, int64_t ns) {
  std::lock_guard<std::mutex> lk(mu_);
  latest_.assign(data, data + bytes);
  latest_w_ = stream_w_;
  latest_h_ = stream_h_;
  latest_ns_ = ns;
  ++latest_serial_;
  const int64_t n = ++color_frames_;
  if (n <= 3 || n % 30 == 0)
    HLOG("color(MJPEG) #%lld %zuB %dx%d", (long long)n, bytes, stream_w_, stream_h_);
}

bool Hlsd8UvcSession::snapshot_color(std::vector<uint8_t>* out, int64_t* meta) {
  if (!out) return false;
  std::lock_guard<std::mutex> lk(mu_);
  if (latest_.empty() || latest_serial_ == returned_serial_) return false;  // consume-once
  *out = latest_;
  returned_serial_ = latest_serial_;
  if (meta) {
    meta[0] = latest_w_;
    meta[1] = latest_h_;
    meta[2] = latest_serial_;
    meta[3] = latest_ns_;
  }
  return true;
}

int Hlsd8UvcSession::dump_raw_color(const char* path) {
  std::lock_guard<std::mutex> lk(mu_);
  if (latest_.empty() || !path) return 0;
  FILE* f = std::fopen(path, "wb");
  if (!f) return 0;
  const size_t wrote = std::fwrite(latest_.data(), 1, latest_.size(), f);
  std::fclose(f);
  return static_cast<int>(wrote);
}

int Hlsd8UvcSession::poll(CameraFrame*, uint32_t) { return 0; }  // 快照式，不走 poll

SessionStats Hlsd8UvcSession::stats() const {
  SessionStats s;
  s.color_frames = color_frames_.load();
  s.errors = errors_.load();
  return s;
}

void Hlsd8UvcSession::Run() {
  state_ = SS::kStarting;
  if (fd_ < 0) { state_ = SS::kError; ++errors_; return; }

  static LusbUvcApi uvc;  // HLSD8 单设备，进程内单会话；dlopen 句柄复用
  if (!uvc.lib && !LoadLusbUvc(&uvc)) { state_ = SS::kError; ++errors_; return; }

  uvc_context* uctx = nullptr;
  if (uvc.init2(&uctx, nullptr, "/dev/bus/usb") != 0 || !uctx) {
    HLOG("uvc_init2(libuvc1) failed");
    state_ = SS::kError; ++errors_; return;
  }

  int busnum = 0, devaddr = 0;
  ResolveBusDev(fd_, &busnum, &devaddr);
  uvc_device* dev = nullptr;
  int er = uvc.get_device_with_fd(uctx, &dev, kHlsd8UsbId.vid, kHlsd8UsbId.pid, nullptr,
                                  fd_, busnum, devaddr);
  HLOG("uvc_get_device_with_fd(fd=%d bus=%d dev=%d) rc=%d", fd_, busnum, devaddr, er);
  if (er != 0 || !dev) { state_ = SS::kError; ++errors_; uvc.exit(uctx); return; }

  uvc_device_handle* devh = nullptr;
  er = uvc.open(dev, &devh);
  HLOG("uvc_open(HLSD8/libuvc1) rc=%d", er);
  if (er != 0 || !devh) {
    state_ = SS::kError; ++errors_; uvc.unref_device(dev); uvc.exit(uctx); return;
  }

  // ① 只协商 VINCreator 使用的 4160x832 MJPEG。vendor 结构体与 pupil 不同，按已知原厂档直接协商，
  //    不跨 ABI 遍历 format descriptor；低分辨率或其它画幅不属于同一标定像素域。
  constexpr int kWidth = 4160;
  constexpr int kHeight = 832;
  constexpr int kMinFps = 1;
  constexpr int kMaxFps = 5;
  constexpr float kBandwidthFactor = 1.0f;
  uvc_stream_ctrl ctrl;
  std::memset(&ctrl, 0, sizeof(ctrl));
  // VINCreator libUVCCamera1::UVCPreview::setPreviewSize 反汇编实证：即使 Java 传 max_fps=30，
  // native 也固定调用 uvc_get_stream_ctrl_format_size_fps(..., min=1, max=5)。不能先试 10fps。
  const int negotiate_rc = uvc.get_stream_ctrl_format_size_fps(
      devh, &ctrl, kUvc1FrameFormatMjpeg, kWidth, kHeight, kMinFps, kMaxFps);
  HLOG("nego VINCreator %dx%d fps=%d..%d rc=%d fmt=%u frame=%u interval=%u", kWidth, kHeight,
       kMinFps, kMaxFps, negotiate_rc, ctrl.bFormatIndex, ctrl.bFrameIndex, ctrl.dwFrameInterval);
  if (negotiate_rc != 0) {
    state_ = SS::kError; ++errors_;
    HLOG("HLSD8 MJPEG 协商失败（无匹配格式）");
    uvc.close(devh); uvc.unref_device(dev); uvc.exit(uctx);
    return;
  }
  stream_w_ = kWidth;
  stream_h_ = kHeight;

  // ② VINCreator UVCPreview::do_preview 使用 bandwidth 变体；参数 1.0f 也必须一致。
  int rc = uvc.start_streaming_bandwidth(
      devh, &ctrl, Hlsd8ColorCb, this, 0, kBandwidthFactor);
  HLOG("uvc_start_streaming_bandwidth(MJPEG %dx%d fps=%d..%d bw=%.1f) rc=%d", kWidth, kHeight,
       kMinFps, kMaxFps, kBandwidthFactor, rc);
  if (rc != 0) {
    state_ = SS::kError; ++errors_;
    uvc.close(devh); uvc.unref_device(dev); uvc.exit(uctx);
    return;
  }
  state_ = SS::kStreaming;

  // ③ 监控循环（libuvc handler 线程出帧，这里只等停止）。
  int tick = 0;
  while (!stop_.load()) {
    usleep(200000);
    if (++tick % 25 == 0) HLOG("tick color=%lld 帧", (long long)color_frames_.load());
  }

  // ④ 收尾。
  uvc.stop_streaming(devh);
  uvc.close(devh);
  uvc.unref_device(dev);
  uvc.exit(uctx);
  state_ = SS::kStopped;
}

void Hlsd8UvcSession::stop() {
  stop_ = true;
  running_ = false;
}

void Hlsd8UvcSession::join() {
  if (thread_.joinable()) thread_.join();
}

CameraCapabilities Hlsd8Driver::capabilities() const {
  CameraCapabilities c;
  c.vendor = "Image+";
  c.model = "HLSD8";
  c.has_color = true;
  c.has_depth = false;
  c.has_confidence = false;
  c.has_ir = false;
  c.depth_is_metric_onchip = false;
  // 真实分辨率开流时按描述符优先选 5:1 最大 MJPEG 帧；预览降采样在 Kotlin 层完成。
  // 此处给 UI 声明原厂采集档，实际协商仍以设备描述符为准。
  c.color_profiles.push_back(StreamProfile{4160, 832, 0, StreamProfile::Format::kMjpeg, "HLSD8 MJPEG(auto)"});
  return c;
}

std::unique_ptr<ICameraSession> Hlsd8Driver::open_fd(const std::vector<int>& fds,
                                                     const SessionConfig& cfg) {
  if (fds.empty() || fds[0] < 0) return nullptr;
  return std::make_unique<Hlsd8UvcSession>(fds[0], cfg);
}

}  // namespace gomob::hlsd8
