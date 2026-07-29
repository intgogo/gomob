// HLSD8（Image+ / Sonix 0x0C45:0x6366）13MP RGB 相机 — 标准 UVC MJPEG 取流（color-only）。
//
// ★ 与深度模组 RS-D550(0x3438:0x0206) 是【两颗独立 USB 相机】：RS-D550 出 L'+depth（mode25），
//   HLSD8 出真彩 RGB（VINCreator 正射图的高分辨率来源，约 4160 宽）。gomob 之前只接了深度模组，
//   HLSD8 从未触及；本会话补齐 RGB 这一路。
//
// 对齐 VINCreator：HLSD8 走独立 libuvc1 + libusb1001，RS-D550 走 libuvc + libusb100，
// 两套 SONAME 物理隔离。HLSD8 是标准 Sonix UVC 摄像头：无 XU arming、无手动 depth bulk。
// 开流时严格协商 VINCreator 的 4160×832 MJPEG（native 实际固定 1..5fps），
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

// Android fd 会话：usbfs fd → libuvc1 标准 UVC MJPEG 流 → 最新帧快照。
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

  // libuvc 回调线程调用：存最新 MJPEG 帧；几何取已协商档，避免读取 vendor 私有 frame 尾字段。
  void OnColorFrame(const uint8_t* data, size_t bytes, int64_t ns);

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
  int stream_w_ = 0;
  int stream_h_ = 0;

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
