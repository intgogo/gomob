// eYs3D RS-D550 host(libusb)driver + 会话 — gomob::camera::ICameraDriver / ICameraSession 具体实现。
//
// 职责切分:
//   Eys3dDriver  — 工厂:capabilities()/match_usb_ids() 全可离线测;open_host 用 UsbContext 开设备出会话。
//   Eys3dHostSession — 传输壳:claim IF0/1/2 → 回放开流序列 → 异步多 URB 取 BULK → 单线程 FID 组装
//                      → 喂 Eys3dSessionCore(深度路由/队列/状态全在 core)。poll/state/stats 委托 core。
//
// 流机制逐字复用 eys3d_replay_stream 已实证的 4 条硬约束(异步多 URB / 回调只 memcpy / URB=maxPayload /
// 双端点并发),见 docs/agent-memory/finding_rsd550_open_sequence_decoded_2026-06-01.md。
//
// ★ 开流序列当前用 proven 计划(1280x480 错模式,出列恒定垃圾深度,仅证流通);depth ROUTING(core)
//   是对的,只是设备 ASIC 跑错模式。mode25(正确)的 videoMode 寄存器值 / PROBE 分辨率【待真机锁定】,
//   届时只换 Eys3dOpenPlan 值。终态见 docs/architecture/13-eys3d-driver.md §2bis。
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <thread>
#include <vector>

#include "camera/camera_session.h"
#include "eys3d/host/eys3d_stream_loop.h"         // Eys3dOpenPlan / ProvenWrongModePlan / RunEys3dStreamLoop
#include "eys3d/portable/eys3d_driver.h"          // kRsd550UsbId / DepthPath / BuildRsd550Capabilities
#include "eys3d/portable/eys3d_session_core.h"     // Eys3dSessionCore

namespace gomob::eys3d::host {

// host 会话:拥有设备 + core + 流线程。
class Eys3dHostSession : public gomob::camera::ICameraSession {
 public:
  // handle 必须是已 open 的 0x3438:0x0206;会话接管(析构 close)。
  Eys3dHostSession(void* libusb_device_handle, const Eys3dOpenPlan& plan,
                   const gomob::camera::SessionConfig& cfg);
  ~Eys3dHostSession() override;

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
  void StreamLoop();  // 流线程:开流 + URB + 组装 + 喂 core

  void* handle_ = nullptr;  // libusb_device_handle*(void* 避免头暴露 libusb)
  Eys3dOpenPlan plan_;
  gomob::camera::SessionConfig cfg_;
  Eys3dSessionCore core_;
  std::thread thread_;
  std::atomic<bool> running_{false};
};

// driver 工厂。
class Eys3dDriver : public gomob::camera::ICameraDriver {
 public:
  explicit Eys3dDriver(bool usb3 = false, DepthPath path = DepthPath::kHardwareAsic)
      : usb3_(usb3), path_(path) {}

  gomob::camera::CameraCapabilities capabilities() const override {
    return BuildRsd550Capabilities(usb3_, path_);
  }
  std::vector<gomob::camera::UsbId> match_usb_ids() const override { return {kRsd550UsbId}; }

  std::unique_ptr<gomob::camera::ICameraSession> open_host(
      gomob::camera::UsbContext& ctx, const gomob::camera::SessionConfig& cfg) override;
  std::unique_ptr<gomob::camera::ICameraSession> open_fd(
      const std::vector<int>& fds, const gomob::camera::SessionConfig& cfg) override;

 private:
  bool usb3_;
  DepthPath path_;
};

}  // namespace gomob::eys3d::host
