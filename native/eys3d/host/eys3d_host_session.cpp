#include "eys3d/host/eys3d_host_session.h"

#include <libusb-1.0/libusb.h>

#include "camera/host/usb_context.h"

namespace gomob::eys3d::host {

using gomob::camera::CameraFrame;
using gomob::camera::DepthControls;
using gomob::camera::SessionCallbacks;
using gomob::camera::SessionConfig;
using gomob::camera::SessionState;
using gomob::camera::UsbContext;

Eys3dHostSession::Eys3dHostSession(void* handle, const Eys3dOpenPlan& plan, const SessionConfig& cfg)
    : handle_(handle), plan_(plan), cfg_(cfg) {
  // core 配置:depth 档位决定路由 active 尺寸。沿用 cfg 的 color/depth profile。
  SessionCoreConfig cc;
  cc.color = cfg.color;
  cc.depth = cfg.depth;
  cc.depth_router = DepthRouterConfig{cfg.depth.width, cfg.depth.height, 0, false};
  cc.want_color = cfg.want_color;
  cc.want_depth = cfg.want_depth;
  core_.Configure(cc);
}

Eys3dHostSession::~Eys3dHostSession() {
  stop();
  join();
}

bool Eys3dHostSession::start(const SessionCallbacks& cb) {
  if (running_.exchange(true)) return false;
  if (cb.on_frame) core_.SetOnFrame(cb.on_frame);
  thread_ = std::thread(&Eys3dHostSession::StreamLoop, this);
  return true;
}

void Eys3dHostSession::StreamLoop() {
  // 流逻辑全在传输无关的 RunEys3dStreamLoop(host 与 Android-fd 共调,零重复)。
  // host 走 UsbContext 默认 context → handle_events 传 nullptr。
  RunEys3dStreamLoop(nullptr, static_cast<libusb_device_handle*>(handle_), plan_, cfg_, core_);
}

int Eys3dHostSession::poll(CameraFrame* out, uint32_t timeout_ms) {
  return core_.Poll(out, timeout_ms);
}

bool Eys3dHostSession::set_controls(const DepthControls& c) {
  // TODO(M6.8 device-gated):把语义控制翻成 XU 写(IR current 已有 MakeSetIrCurrent;AE/denoise 待锁定)。
  (void)c;
  return false;
}

void Eys3dHostSession::stop() {
  if (running_.load()) core_.RequestStop();
}

void Eys3dHostSession::join() {
  if (thread_.joinable()) thread_.join();
  running_.store(false);
}

std::unique_ptr<gomob::camera::ICameraSession> Eys3dDriver::open_host(UsbContext& ctx,
                                                                      const SessionConfig& cfg) {
  if (!ctx.valid()) return nullptr;
  libusb_device_handle* h = ctx.open(kRsd550UsbId.vid, kRsd550UsbId.pid);
  if (!h) return nullptr;
  SessionConfig c = cfg;
  // 调用方未给档位 → 用能力默认(mode25/USB3)。
  auto caps = capabilities();
  if (c.color.width == 0 && !caps.color_profiles.empty()) c.color = caps.color_profiles[0];
  if (c.depth.width == 0 && !caps.depth_profiles.empty()) c.depth = caps.depth_profiles[0];
  return std::make_unique<Eys3dHostSession>(h, Mode25Usb2Plan(), c);  // videoMode=36 正确 arming
}

std::unique_ptr<gomob::camera::ICameraSession> Eys3dDriver::open_fd(const std::vector<int>& fds,
                                                                    const SessionConfig& cfg) {
  // Android fd 路径在 native/eys3d/android(后续 M6.8);host build 不支持。
  (void)fds; (void)cfg;
  return nullptr;
}

}  // namespace gomob::eys3d::host
