// HLSD8（Image+ / Sonix 0x0C45:0x6366）13MP RGB 相机 — 标准 UVC MJPEG 取流（color-only）。
//
// ★ 与深度模组 RS-D550(0x3438:0x0206) 是【两颗独立 USB 相机】：RS-D550 出 L'+depth（mode25），
//   HLSD8 出真彩 RGB（VINCreator 正射图的高分辨率来源，约 4160 宽）。gomob 之前只接了深度模组，
//   HLSD8 从未触及；本会话补齐 RGB 这一路。
//
// 实现走与 eYs3D 同一 libuvc_lusb100 后端（pupil 源解析 MJPEG + libusb100 能出流的后端），但因 HLSD8 是
// 标准 Sonix UVC 摄像头：无 XU arming、无手动 depth bulk —— 纯 libuvc 标准 PROBE/COMMIT。
// 开流时用 uvc_get_format_descs 枚举设备真实格式，自动选最大 MJPEG 帧（不猜分辨率），
// start_streaming 起 libuvc 自有阻塞 handler 线程排空 → 最新 MJPEG 帧快照给 snapshot_color。
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "camera/camera_session.h"

namespace gomob::hlsd8 {

// HLSD8 RGB 相机 USB ID（manufacturer "Image+"，VID 0x0C45=Sonix）。
inline constexpr gomob::camera::UsbId kHlsd8UsbId{0x0C45, 0x6366};

// Android fd 会话：wrap usbfs fd → libuvc_lusb100 标准 UVC MJPEG 流 → 最新帧快照。
class Hlsd8UvcSession : public gomob::camera::ICameraSession {
 public:
  Hlsd8UvcSession(int fd, const gomob::camera::SessionConfig& cfg);
  ~Hlsd8UvcSession() override;

  bool start(const gomob::camera::SessionCallbacks&) override;
  int poll(gomob::camera::CameraFrame* out, uint32_t timeout_ms) override;
  bool set_controls(const gomob::camera::DepthControls&) override { return false; }
  void stop() override;
  void join() override;
  gomob::camera::SessionState state() const override { return state_.load(); }
  gomob::camera::SessionStats stats() const override;
  // 最新 MJPEG 帧（consume-once，Kotlin 端 BitmapFactory 解码 → RGB24）。
  bool snapshot_color(std::vector<uint8_t>* out, int64_t* meta) override;
  int dump_raw_color(const char* path) override;

  // libuvc 回调线程调用：存最新 MJPEG 帧。
  void OnColorFrame(const uint8_t* data, size_t bytes, int w, int h, int64_t ns);

 private:
  void Run();

  int fd_ = -1;
  gomob::camera::SessionConfig cfg_;
  std::thread thread_;
  std::atomic<bool> running_{false};
  std::atomic<bool> stop_{false};
  std::atomic<gomob::camera::SessionState> state_{gomob::camera::SessionState::kIdle};

  std::mutex mu_;
  std::vector<uint8_t> latest_;
  int latest_w_ = 0;
  int latest_h_ = 0;
  int64_t latest_serial_ = 0;
  int64_t latest_ns_ = 0;
  int64_t returned_serial_ = -1;

  std::atomic<int64_t> color_frames_{0};
  std::atomic<int64_t> errors_{0};
};

// HLSD8 driver（color-only）。注册进 CameraRegistry，按 0x0C45:0x6366 认领。
class Hlsd8Driver : public gomob::camera::ICameraDriver {
 public:
  gomob::camera::CameraCapabilities capabilities() const override;
  std::vector<gomob::camera::UsbId> match_usb_ids() const override { return {kHlsd8UsbId}; }

  // host 路径暂不提供（HLSD8 只在 Android fd 路径接入；host 抓包另走 VINCreator/v4l2）。
  std::unique_ptr<gomob::camera::ICameraSession> open_host(
      gomob::camera::UsbContext&, const gomob::camera::SessionConfig&) override {
    return nullptr;
  }
  // fds[0] = HLSD8 单节点 usbfs fd。
  std::unique_ptr<gomob::camera::ICameraSession> open_fd(
      const std::vector<int>& fds, const gomob::camera::SessionConfig& cfg) override;
};

}  // namespace gomob::hlsd8
