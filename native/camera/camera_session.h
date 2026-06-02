// 厂商无关相机会话抽象(native/camera)。
//
// 设计见 docs/architecture/12-camera-abstraction.md §1。切面:I/O(IUvcDevice,见 camera_device.h)
// 与会话编排(此文件)两层之间。各 driver 内部负责"深度怎么来"(P100R3 ASIC直出 / eYs3D ASIC视差+ZD
// 或软件stereo),统一从 poll() 出 metric depth16(+可选 confidence/IR)。上层永不见寄存器。
#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "camera_device.h"  // gomob::camera::UsbId

namespace gomob::camera {

// 流档位(分辨率/帧率/格式)。
struct StreamProfile {
  enum class Format { kYuyv, kMjpeg, kDepthU16 };
  uint16_t width = 0;
  uint16_t height = 0;
  uint16_t fps = 0;
  Format format = Format::kYuyv;
  std::string label;
};

// 帧类型。depthMm 统一为 uint16 metric(mm)。
enum class CameraStreamKind { kColor, kDepthMm, kConfidence, kIr };

// 单帧(零拷贝视图,data 由 session 拥有,poll 返回后下次 poll 前有效)。
struct CameraFrame {
  CameraStreamKind kind = CameraStreamKind::kColor;
  uint16_t width = 0;
  uint16_t height = 0;
  int64_t host_ns = 0;
  int64_t device_ns = 0;
  const uint8_t* data = nullptr;
  size_t size = 0;
  int serial = 0;  // color/depth 同步序号
};

// 设备能力(上层据此定 UI 档位/是否有 conf/IR/是否端侧 metric)。
struct CameraCapabilities {
  std::string vendor;
  std::string model;
  bool has_color = true;
  bool has_depth = true;
  bool has_confidence = false;
  bool has_ir = false;
  // P100R3=true(ASIC 直出 metric);eYs3D 硬件路径=true(ASIC 视差+ZD 表查 mm);软件 stereo fallback=false。
  bool depth_is_metric_onchip = true;
  std::vector<StreamProfile> color_profiles;
  std::vector<StreamProfile> depth_profiles;
};

// 语义控制(去厂商化,各 driver 内部翻译成 XU/寄存器)。负值/-1 = 不改。
struct DepthControls {
  float confidence_threshold = -1.0f;  // 0..1
  int temporal_denoise = -1;           // 0 off / 1 on
  int spatial_denoise = -1;
  int auto_exposure = -1;              // 0 off / 1 on
  int gain = -1;
  int ir_current = -1;                 // eYs3D IR 投射器电流 0..6
};

enum class SessionState { kIdle, kStarting, kStreaming, kError, kStopped };

struct SessionStats {
  int64_t color_frames = 0;
  int64_t depth_frames = 0;
  int64_t dropped = 0;
  int64_t errors = 0;
};

struct SessionConfig {
  StreamProfile color;
  StreamProfile depth;
  DepthControls controls;
  bool want_color = true;
  bool want_depth = true;
  std::string options_json;  // driver 特定覆盖(arming/mode 等)
};

struct SessionCallbacks {
  std::function<void(const CameraFrame&)> on_frame;
  std::function<void(SessionState, const std::string&)> on_state;
};

// 开流后的运行态会话。
class ICameraSession {
 public:
  virtual ~ICameraSession() = default;
  virtual bool start(const SessionCallbacks&) = 0;
  // 拉一帧:>0 拿到、0 超时、<0 错误。回调式用 start 的 on_frame;轮询式用 poll。
  virtual int poll(CameraFrame* out, uint32_t timeout_ms) = 0;
  virtual bool set_controls(const DepthControls&) = 0;
  virtual void stop() = 0;
  virtual void join() = 0;
  virtual SessionState state() const = 0;
  virtual SessionStats stats() const = 0;

  // 最新帧快照(消费型,丢旧):JNI / 预览按 kind 取最新,两相机统一契约。
  // depthMm 写 dst(cap_px 个 u16);返回写入字节数,无新帧 0,cap 不足 -1。meta(>=4)=[w,h,serial,host_ns]。
  // 默认空实现:只支持 poll() 的会话不必实现快照。
  virtual int snapshot_depth_mm(uint16_t* /*dst*/, size_t /*cap_px*/, int64_t* /*meta*/) { return 0; }
  virtual bool snapshot_color(std::vector<uint8_t>* /*out*/, int64_t* /*meta*/) { return false; }

  // 逐像素 confidence 快照(uint8,与 depth 同尺寸 W*H,0=飞点/无效)。返回字节数 / 0 无 / -1 cap 不足。
  // meta(>=4)=[w,h,serial,host_ns]。CameraStreamKind::kConfidence 的快照形态;无 conf 的会话默认 0。
  virtual int snapshot_confidence(uint8_t* /*dst*/, size_t /*cap*/, int64_t* /*meta*/) { return 0; }
  // IR/phase 灰度快照(uint8,W*H)。返回字节数 / 0 无 / -1 cap 不足。CameraStreamKind::kIr 形态。
  virtual int snapshot_ir(uint8_t* /*dst*/, size_t /*cap*/, int64_t* /*meta*/) { return 0; }

  // 厂商扩展诊断统计(driver 自定义语义的 int64 序列;Berxel=16 项 keepalive/配对/seq 等)。
  // 写 out(<=cap 项),返回实际写入项数。无扩展统计的会话默认 0。SessionStats 之外的富诊断面用此承载。
  virtual int extended_stats(int64_t* /*out*/, size_t /*cap*/) const { return 0; }

  // 调试:把最新 depth transport 原始字节 dump 到 path。返回写入字节数。无 dump 能力默认 0。
  virtual int dump_raw_depth(const char* /*path*/) { return 0; }
};

// host(libusb)USB 上下文,由 native/*/host 层定义;Android 走 open_fd 不需要。
class UsbContext;

// 无设备态的工厂 + 枚举。
class ICameraDriver {
 public:
  virtual ~ICameraDriver() = default;
  virtual CameraCapabilities capabilities() const = 0;
  virtual std::vector<UsbId> match_usb_ids() const = 0;
  // host(libusb):P100R3 内部用 2 设备、eYs3D 1 设备,封在 driver 内。
  virtual std::unique_ptr<ICameraSession> open_host(UsbContext& ctx, const SessionConfig& cfg) = 0;
  // Android fd:P100R3 传 2 个 fd(master+companion),eYs3D 传 1 个。
  virtual std::unique_ptr<ICameraSession> open_fd(const std::vector<int>& fds, const SessionConfig& cfg) = 0;
};

}  // namespace gomob::camera
