// eYs3D RS-D550 Android fd 会话 —— 纯 native 直驱厂商 C++ 引擎（零 Java 编排）。
//
// 与 Eys3dFdSession（自研 libuvc/libusb100 取流）并列的第三条会话实现：复用厂商 libUVCCamera 的
// UVCCamera/UVCPreview/FrameGrabber C++ 类做取流（厂商已验证起流链，规避自研 mode25 -EPROTO），
// 但帧经自建 FrameGrabber trampoline 全 native 进 Eys3dSessionCore，不经任何 _jobject。
// 仅保留 Java UsbManager.openDevice 拿 fd（与 Berxel/所有 USB 相机一致，不可避免）。
//
// 见 vendor_uvc_abi.h（ABI 契约/帧出线路）、docs/architecture/13-eys3d-driver.md。
// ★ spike 阶段：经 Eys3dFdDriver::open_fd 的 vendor_cpp 分支构造；验收=真机 ourCb 收 mode25 深度帧。
#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

#include "camera/camera_session.h"
#include "eys3d/android/vendor_uvc_abi.h"
#include "eys3d/portable/eys3d_session_core.h"
#include "eys3d/portable/vendor_worker_lifecycle.h"

namespace gomob::eys3d::android {

struct FrameCallbackContext;

class Eys3dVendorCppSession : public gomob::camera::ICameraSession {
 public:
  Eys3dVendorCppSession(int fd, const gomob::camera::SessionConfig& cfg);
  ~Eys3dVendorCppSession() override;

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

  // FrameGrabber worker 线程回调入口（trampoline 转发到此）。
  void OnVendorFrame(void* depth_vec, int dW, int dH, void* color_vec, int cW, int cH, int serial);

 private:
  void Run();              // dlopen→连接→arming→起流→保活
  void Teardown();         // 停流 + 释放厂商对象
  bool WaitForFrameGrabberStarted();
  void QuarantineVendorObjects(const char* reason);

  int fd_ = -1;
  gomob::camera::SessionConfig cfg_;
  Eys3dSessionCore core_;
  VendorUvcAbi abi_{};
  std::thread thread_;
  std::atomic<bool> running_{false};

  void* cam_ = nullptr;  // UVCCamera 对象（我方 over-alloc，厂商 ctor 初始化）
  void* fg_ = nullptr;   // FrameGrabber 对象（厂商所有，仅借指针 repoint 回调）
  void* old_fg_cb_ = nullptr;
  void* old_fg_ctx_ = nullptr;
  FrameCallbackContext* fg_callback_ctx_ = nullptr;
  VendorWorkerLifecycle fg_lifecycle_;
  bool cam_connected_ = false;
  // 离屏窗口：startPreview 硬要非空 ANativeWindow(门控)，但 FrameGrabber 路径旁路绘制故不真渲染。AImageReader 纯 native。
  void* color_reader_ = nullptr;  // AImageReader*
  void* depth_reader_ = nullptr;
  void* color_win_ = nullptr;     // ANativeWindow*（reader 所有，随 reader 释放）
  void* depth_win_ = nullptr;

  std::atomic<int64_t> cb_frames_{0};
  std::atomic<bool> first_frame_ready_{false};
};

}  // namespace gomob::eys3d::android
