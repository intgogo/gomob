#include "eys3d/android/eys3d_fd_session.h"

#include <libusb-1.0/libusb.h>

#include <cstdio>

namespace gomob::eys3d::android {

using gomob::camera::CameraFrame;
using gomob::camera::DepthControls;
using gomob::camera::SessionCallbacks;
using gomob::camera::SessionConfig;

Eys3dFdSession::Eys3dFdSession(int fd, const host::Eys3dOpenPlan& plan, const SessionConfig& cfg)
    : fd_(fd), plan_(plan), cfg_(cfg) {
  SessionCoreConfig cc;
  cc.color = cfg.color;
  cc.depth = cfg.depth;
  cc.depth_router = DepthRouterConfig{cfg.depth.width, cfg.depth.height, 0, false};
  cc.want_color = cfg.want_color;
  cc.want_depth = cfg.want_depth;
  core_.Configure(cc);
}

Eys3dFdSession::~Eys3dFdSession() {
  stop();
  join();
}

bool Eys3dFdSession::start(const SessionCallbacks& cb) {
  if (running_.exchange(true)) return false;
  if (cb.on_frame) core_.SetOnFrame(cb.on_frame);
  thread_ = std::thread(&Eys3dFdSession::Run, this);
  return true;
}

void Eys3dFdSession::Run() {
  if (fd_ < 0) { core_.MarkError("invalid fd"); return; }
  // NO_DEVICE_DISCOVERY 下不枚举,只 wrap Java 拿到的 usbfs fd(与 Berxel Android 路径一致)。
  if (libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY) != 0) {
    core_.MarkError("set_option(NO_DEVICE_DISCOVERY) failed");
    return;
  }
  libusb_context* ctx = nullptr;
  if (libusb_init(&ctx) != 0 || ctx == nullptr) { core_.MarkError("libusb_init failed"); return; }
  ctx_ = ctx;
  libusb_device_handle* handle = nullptr;
  if (libusb_wrap_sys_device(ctx, static_cast<intptr_t>(fd_), &handle) != 0 || handle == nullptr) {
    core_.MarkError("libusb_wrap_sys_device failed");
    libusb_exit(ctx); ctx_ = nullptr;
    return;
  }
  handle_ = handle;

  // 流逻辑全在传输无关 RunEys3dStreamLoop;具名 context 必须传进去做 handle_events。
  RunEys3dStreamLoop(ctx, handle, plan_, cfg_, core_);

  libusb_close(handle); handle_ = nullptr;
  libusb_exit(ctx); ctx_ = nullptr;
}

int Eys3dFdSession::poll(CameraFrame* out, uint32_t timeout_ms) {
  return core_.Poll(out, timeout_ms);
}

bool Eys3dFdSession::set_controls(const DepthControls& c) {
  // TODO(device-gated M6.5):语义控制 → XU 写(IR current 已有;AE/denoise 待锁定)。
  (void)c;
  return false;
}

void Eys3dFdSession::stop() {
  if (running_.load()) core_.RequestStop();
}

void Eys3dFdSession::join() {
  if (thread_.joinable()) thread_.join();
  running_.store(false);
}

std::unique_ptr<gomob::camera::ICameraSession> Eys3dFdDriver::open_fd(const std::vector<int>& fds,
                                                                     const SessionConfig& cfg) {
  if (fds.empty() || fds[0] < 0) return nullptr;
  SessionConfig c = cfg;
  auto caps = capabilities();
  if (c.color.width == 0 && !caps.color_profiles.empty()) c.color = caps.color_profiles[0];
  if (c.depth.width == 0 && !caps.depth_profiles.empty()) c.depth = caps.depth_profiles[0];
  auto sess = std::make_unique<Eys3dFdSession>(fds[0], host::Mode25Usb2Plan(), c);  // videoMode=36 正确 arming
  // 注入几何度量默认(无 per-device ZD 表时兜底,出 metric mm)。终态 ZD 表见 TODO M6.5。
  sess->SetGeometric(kRsd550RectifiedFx, kRsd550BaselineMm);
  return sess;
}

}  // namespace gomob::eys3d::android
