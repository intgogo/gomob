// eYs3D RS-D550 Android fd 会话 + 驱动 — gomob::camera::ICameraDriver / ICameraSession 的 Android 实现。
//
// 与 host 路径对称:唯一差异是【拿 handle 的方式】——Android 从 UsbDeviceConnection.getFileDescriptor()
// 拿 usbfs fd,经 NO_DEVICE_DISCOVERY + libusb_wrap_sys_device(fd) 接管;流逻辑完全复用
// 传输无关的 RunEys3dStreamLoop(native/eys3d/host/eys3d_stream_loop),零重复零退化。
//
// 进 libgomob_native.so(Android)。eys3d/host 的 Eys3dHostSession(UsbContext 枚举)是 host-only 不进 .so。
// ★ 深度正确性仍受 mode25 真值门控(ProvenWrongModePlan 占位),见 TODO M6.5。
#pragma once

#include <atomic>
#include <memory>
#include <thread>
#include <vector>

#include "camera/camera_session.h"
#include "eys3d/host/eys3d_stream_loop.h"        // Eys3dOpenPlan / ProvenWrongModePlan / RunEys3dStreamLoop
#include "eys3d/portable/eys3d_driver.h"         // kRsd550UsbId / DepthPath / BuildRsd550Capabilities
#include "eys3d/portable/eys3d_session_core.h"   // Eys3dSessionCore

namespace gomob::eys3d::android {

// Android fd 会话:wrap fd → 共享流循环 → core。
class Eys3dFdSession : public gomob::camera::ICameraSession {
 public:
  // fd = Android UsbDeviceConnection.getFileDescriptor();会话接管(析构释放 libusb)。
  Eys3dFdSession(int fd, const host::Eys3dOpenPlan& plan, const gomob::camera::SessionConfig& cfg);
  ~Eys3dFdSession() override;

  // 注入深度度量(ZD 表 / 几何)。start 前调。
  void SetZdTable(ZdTable table) { core_.SetZdTable(std::move(table)); }
  void SetGeometric(float fx, float B, float subpixel = kDefaultSubpixel) {
    core_.SetGeometric(fx, B, subpixel);
  }

  bool start(const gomob::camera::SessionCallbacks&) override;
  int poll(gomob::camera::CameraFrame* out, uint32_t timeout_ms) override;
  bool set_controls(const gomob::camera::DepthControls&) override;
  void stop() override;
  void join() override;
  gomob::camera::SessionState state() const override { return core_.state(); }
  gomob::camera::SessionStats stats() const override { return core_.stats(); }
  int snapshot_depth_mm(uint16_t* dst, size_t cap_px, int64_t* meta) override {
    return core_.SnapshotLatestDepthMm(dst, cap_px, meta);
  }
  bool snapshot_color(std::vector<uint8_t>* out, int64_t* meta) override {
    return core_.SnapshotLatestColor(out, meta);
  }

 private:
  void Run();  // wrap fd → RunEys3dStreamLoop → cleanup

  int fd_ = -1;
  host::Eys3dOpenPlan plan_;
  gomob::camera::SessionConfig cfg_;
  Eys3dSessionCore core_;
  std::thread thread_;
  std::atomic<bool> running_{false};
  void* ctx_ = nullptr;     // libusb_context*(具名,fd 路径专属)
  void* handle_ = nullptr;  // libusb_device_handle*
};

// Android fd 驱动。
class Eys3dFdDriver : public gomob::camera::ICameraDriver {
 public:
  explicit Eys3dFdDriver(bool usb3 = false, DepthPath path = DepthPath::kHardwareAsic)
      : usb3_(usb3), path_(path) {}

  gomob::camera::CameraCapabilities capabilities() const override {
    return BuildRsd550Capabilities(usb3_, path_);
  }
  std::vector<gomob::camera::UsbId> match_usb_ids() const override { return {kRsd550UsbId}; }

  // Android 无 host UsbContext 枚举路径。
  std::unique_ptr<gomob::camera::ICameraSession> open_host(
      gomob::camera::UsbContext&, const gomob::camera::SessionConfig&) override {
    return nullptr;
  }
  // fds[0] = RS-D550 单节点 usbfs fd。
  std::unique_ptr<gomob::camera::ICameraSession> open_fd(
      const std::vector<int>& fds, const gomob::camera::SessionConfig& cfg) override;

 private:
  bool usb3_;
  DepthPath path_;
};

}  // namespace gomob::eys3d::android
