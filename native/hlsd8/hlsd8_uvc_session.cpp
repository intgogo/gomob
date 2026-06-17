// HLSD8 RGB 相机取流实现 — 见 hlsd8_uvc_session.h 头注。
#include "hlsd8/hlsd8_uvc_session.h"

#include "libuvc/libuvc.h"

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

namespace {

// libuvc_lusb100.so 用 dlopen(RTLD_LOCAL) 加载（同 eys3d_pupil_session 隔离策略）：其 libusb_* 走自己
// NEEDED 的 libusb100.so，不被 gomob_native 全局 libusb-1.0.so 遮蔽。dlsym 出 color-only 需要的 uvc_*。
struct LusbUvcApi {
  uvc_error_t (*init)(uvc_context_t**, struct libusb_context*) = nullptr;
  uvc_error_t (*get_device_with_fd)(uvc_context_t*, uvc_device_handle_t**, int, int, const char*, int, int, int) = nullptr;
  const uvc_format_desc_t* (*get_format_descs)(uvc_device_handle_t*) = nullptr;
  uvc_error_t (*get_stream_ctrl_format_size)(uvc_device_handle_t*, uvc_stream_ctrl_t*, enum uvc_frame_format, int, int, int) = nullptr;
  uvc_error_t (*start_streaming)(uvc_device_handle_t*, uvc_stream_ctrl_t*, uvc_frame_callback_t*, void*, uint8_t) = nullptr;
  void (*stop_streaming)(uvc_device_handle_t*) = nullptr;
  void (*close)(uvc_device_handle_t*) = nullptr;
  void (*exit)(uvc_context_t*) = nullptr;
  void* lib = nullptr;
};

bool LoadLusbUvc(LusbUvcApi* a) {
  a->lib = dlopen("libuvc_lusb100.so", RTLD_NOW | RTLD_LOCAL);
  if (!a->lib) { HLOG("dlopen libuvc_lusb100.so failed: %s", dlerror()); return false; }
#define USYM(f, n) a->f = reinterpret_cast<decltype(a->f)>(dlsym(a->lib, n))
  USYM(init, "uvc_init");
  USYM(get_device_with_fd, "uvc_get_device_with_fd");
  USYM(get_format_descs, "uvc_get_format_descs");
  USYM(get_stream_ctrl_format_size, "uvc_get_stream_ctrl_format_size");
  USYM(start_streaming, "uvc_start_streaming");
  USYM(stop_streaming, "uvc_stop_streaming");
  USYM(close, "uvc_close");
  USYM(exit, "uvc_exit");
#undef USYM
  const bool ok = a->init && a->get_device_with_fd && a->get_stream_ctrl_format_size &&
                  a->start_streaming && a->stop_streaming && a->close && a->exit;
  HLOG("libuvc_lusb100 dlopen ok=%d (get_format_descs=%p)", ok, (void*)a->get_format_descs);
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
void Hlsd8ColorCb(uvc_frame_t* frame, void* user) {
  auto* s = static_cast<Hlsd8UvcSession*>(user);
  if (!s || !frame || !frame->data || frame->data_bytes == 0) return;
  s->OnColorFrame(static_cast<const uint8_t*>(frame->data), frame->data_bytes,
                  static_cast<int>(frame->width), static_cast<int>(frame->height), NowNs());
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

void Hlsd8UvcSession::OnColorFrame(const uint8_t* data, size_t bytes, int w, int h, int64_t ns) {
  std::lock_guard<std::mutex> lk(mu_);
  latest_.assign(data, data + bytes);
  latest_w_ = w;
  latest_h_ = h;
  latest_ns_ = ns;
  ++latest_serial_;
  const int64_t n = ++color_frames_;
  if (n <= 3 || n % 30 == 0) HLOG("color(MJPEG) #%lld %zuB %dx%d", (long long)n, bytes, w, h);
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

  uvc_context_t* uctx = nullptr;
  if (uvc.init(&uctx, nullptr) != UVC_SUCCESS || !uctx) {
    HLOG("uvc_init failed");
    state_ = SS::kError; ++errors_; return;
  }

  int busnum = 0, devaddr = 0;
  ResolveBusDev(fd_, &busnum, &devaddr);
  uvc_device_handle_t* devh = nullptr;
  uvc_error_t er = uvc.get_device_with_fd(uctx, &devh, kHlsd8UsbId.vid, kHlsd8UsbId.pid, nullptr,
                                          fd_, busnum, devaddr);
  HLOG("uvc_get_device_with_fd(fd=%d bus=%d dev=%d) rc=%d", fd_, busnum, devaddr, er);
  if (er != UVC_SUCCESS || !devh) { state_ = SS::kError; ++errors_; uvc.exit(uctx); return; }

  // ① 枚举设备真实格式，自动选**最小** MJPEG 帧（≥640 宽，避免 tiny 无用档）。
  //    ★ 2026-06-15：HLSD8 与 eYs3D 同裸 OTG 并发取流，必须压低 HLSD8 带宽否则把 eYs3D 挤死（color/depth 0 帧）。
  //    预览只需小图；终态接带电 hub 后再恢复全分辨率（4160）。
  int best_w = 0, best_h = 0, best_fps = 0;
  long best_area = 0;
  if (uvc.get_format_descs) {
    for (const uvc_format_desc_t* f = uvc.get_format_descs(devh); f; f = f->next) {
      if (f->bDescriptorSubtype != UVC_VS_FORMAT_MJPEG) continue;
      for (const uvc_frame_desc_t* fr = f->frame_descs; fr; fr = fr->next) {
        if (fr->wWidth < 640) continue;  // 跳过过小档
        const long area = static_cast<long>(fr->wWidth) * fr->wHeight;
        if (best_area != 0 && area >= best_area) continue;  // 选最小面积
        uint32_t iv = fr->dwDefaultFrameInterval ? fr->dwDefaultFrameInterval : fr->dwMinFrameInterval;
        if (iv == 0 && fr->intervals && fr->intervals[0]) iv = fr->intervals[0];
        best_w = fr->wWidth; best_h = fr->wHeight; best_area = area;
        best_fps = iv ? static_cast<int>(10000000.0 / iv + 0.5) : 0;
      }
    }
    HLOG("auto-pick MJPEG(最小) %dx%d @%dfps", best_w, best_h, best_fps);
  }

  uvc_stream_ctrl_t ctrl;
  std::memset(&ctrl, 0, sizeof(ctrl));
  bool ok = false;
  if (best_w > 0 && best_fps > 0) {
    uvc_error_t rc = uvc.get_stream_ctrl_format_size(devh, &ctrl, UVC_FRAME_FORMAT_MJPEG,
                                                     best_w, best_h, best_fps);
    HLOG("nego auto %dx%d@%d rc=%d", best_w, best_h, best_fps, rc);
    ok = (rc == UVC_SUCCESS);
  }
  // 自动选失败时回退候选扫（仍是对真实描述符协商，匹配才成）。
  if (!ok) {
    struct Cand { int w, h; };
    static const Cand cands[] = {{4160, 832}, {4160, 3120}, {3840, 2160}, {1920, 1080},
                                 {1280, 960}, {1280, 720}, {1024, 768}, {800, 600}, {640, 480}};
    static const int fpss[] = {30, 25, 20, 15, 10, 5};
    for (const auto& c : cands) {
      for (int fps : fpss) {
        uvc_stream_ctrl_t t;
        std::memset(&t, 0, sizeof(t));
        if (uvc.get_stream_ctrl_format_size(devh, &t, UVC_FRAME_FORMAT_MJPEG, c.w, c.h, fps) == UVC_SUCCESS) {
          ctrl = t; best_w = c.w; best_h = c.h; best_fps = fps; ok = true; break;
        }
      }
      if (ok) break;
    }
    HLOG("nego sweep ok=%d %dx%d@%d", (int)ok, best_w, best_h, best_fps);
  }
  if (!ok) {
    state_ = SS::kError; ++errors_;
    HLOG("HLSD8 MJPEG 协商失败（无匹配格式）");
    uvc.close(devh); uvc.exit(uctx);
    return;
  }

  // ② start_streaming 起 libuvc 自有阻塞 handler 线程 + MJPEG 排空，回调存最新帧。
  uvc_error_t rc = uvc.start_streaming(devh, &ctrl, Hlsd8ColorCb, this, 0);
  HLOG("uvc_start_streaming(MJPEG %dx%d@%d) rc=%d", best_w, best_h, best_fps, rc);
  if (rc != UVC_SUCCESS) {
    state_ = SS::kError; ++errors_;
    uvc.close(devh); uvc.exit(uctx);
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
  // 真实分辨率开流时按描述符自动选（最大 MJPEG）；此处给 UI 占位档（已知约 4160 宽幅）。
  c.color_profiles.push_back(StreamProfile{4160, 832, 0, StreamProfile::Format::kMjpeg, "HLSD8 MJPEG(auto)"});
  return c;
}

std::unique_ptr<ICameraSession> Hlsd8Driver::open_fd(const std::vector<int>& fds,
                                                     const SessionConfig& cfg) {
  if (fds.empty() || fds[0] < 0) return nullptr;
  return std::make_unique<Hlsd8UvcSession>(fds[0], cfg);
}

}  // namespace gomob::hlsd8
