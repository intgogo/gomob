// eYs3D RS-D550 会话引擎(portable,传输无关)。纯 C++,无 libusb / 无 Android API。
//
// 设计:ICameraSession 的具体实现(host libusb / Android fd)是【传输壳】,只负责
//   1) claim 设备 + 回放开流序列(arming + PROBE/COMMIT)
//   2) 异步多 URB 取 BULK,组装出整帧(color IF1 / depth IF2)
//   3) 把组装好的整帧喂给本引擎 OnColorFrame / OnRawDepthFrame
// 本引擎承担所有【平台无关】职责:深度路由(IF2 视差→metric depthMm)、帧队列+背压、
// serial 配对序号、状态机、统计、arming 序列装配。host 与 Android 共用一份,可离线单测。
//
// 见 docs/architecture/12-camera-abstraction.md §会话分层、13-eys3d-driver.md §2bis。
#pragma once
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

#include "camera/camera_session.h"  // gomob::camera::{CameraFrame,SessionState,SessionStats,StreamProfile,DepthControls}
#include "eys3d_depth_router.h"      // DepthRouter
#include "eys3d_protocol.h"          // ArmConfig / BuildArmSequence / XuPayload

namespace gomob::eys3d {

struct SessionCoreConfig {
  gomob::camera::StreamProfile color;   // IF1 档位(passthrough,MJPG/YUYV 不在引擎解码)
  gomob::camera::StreamProfile depth;   // IF2 档位(决定路由 active 尺寸)
  DepthRouterConfig depth_router;       // 状态行 / 字节序
  bool want_color = true;
  bool want_depth = true;
  size_t max_queue = 8;                 // 背压上限:超过丢最旧帧(计 dropped)
};

// 传输无关会话引擎。
class Eys3dSessionCore {
 public:
  Eys3dSessionCore() = default;

  // ---- 配置(开流前)----
  bool Configure(const SessionCoreConfig& cfg);
  const SessionCoreConfig& config() const { return cfg_; }
  // 注入深度度量:ZD 表(优先)或几何 fx/B(无表退化)。
  void SetZdTable(ZdTable table);
  void SetGeometric(float fx_rect, float baseline_mm, float subpixel = kDefaultSubpixel);
  void SetCompensation(float scale, float bias);
  bool depth_ready() const;  // 深度路由终结器是否就绪

  // ---- 开流序列装配(供传输壳回放)----
  std::vector<gomob::camera::XuPayload> BuildArming(const ArmConfig& cfg) const;

  // ---- 状态机(传输壳驱动)----
  void MarkStreaming();
  void MarkError(const std::string& msg);
  void MarkStopped();
  void RequestStop();                 // 置停止请求标志(传输壳轮询)
  bool stop_requested() const;
  gomob::camera::SessionState state() const;
  gomob::camera::SessionStats stats() const;
  std::string last_error() const;

  // ---- 喂帧(传输线程)----
  // raw = IF2 组装好的整帧字节(11bit 视差),内部经 DepthRouter 转 metric depthMm 入队(硬件 ASIC 路径)。
  void OnRawDepthFrame(const uint8_t* raw, size_t size, int64_t host_ns);
  // 路径无关 metric 深度入口:已是 metric 的 depthMm(w*h 个 u16)直接入队。
  // 软件 stereo 路径在外部用 StereoDepthEngine 算好后经此喂入,与硬件路径汇同一队列。
  void OnDepthMmFrame(const uint16_t* mm, uint16_t width, uint16_t height, int64_t host_ns);
  // color = IF1 组装好的整帧(YUYV / MJPG),passthrough 入队。
  void OnColorFrame(const uint8_t* data, size_t size, int64_t host_ns);

  // ---- 取帧(消费线程)----
  // >0 拿到一帧(out 视图在下次 Poll 前有效)、0 超时空、<0 错误态。
  int Poll(gomob::camera::CameraFrame* out, uint32_t timeout_ms);

  // ---- 最新帧快照(供 JNI 按 kind 取最新、丢旧;与 FIFO Poll 并存)----
  // 把最新 depthMm 帧拷进 dst(最多 cap_px 个 u16)。返回写入字节数(px*2);无新帧 0;cap 不足 -1。
  // meta(非空,>=4)=[width,height,serial,host_ns]。consume-once:取后该帧标记已消费。
  int SnapshotLatestDepthMm(uint16_t* dst, size_t cap_px, int64_t* meta);
  // 把最新 color 帧拷进 out。无新帧返 false。consume-once。meta 同上。
  bool SnapshotLatestColor(std::vector<uint8_t>* out, int64_t* meta);

  // 回调式(可选):配置后每入队一帧同步触发。
  void SetOnFrame(std::function<void(const gomob::camera::CameraFrame&)> cb) { on_frame_ = std::move(cb); }

 private:
  struct OwnedFrame {
    gomob::camera::CameraStreamKind kind = gomob::camera::CameraStreamKind::kColor;
    uint16_t width = 0, height = 0;
    int64_t host_ns = 0;
    int serial = 0;
    std::vector<uint8_t> bytes;
  };
  void Enqueue(OwnedFrame f);  // 上锁入队 + 背压 + 通知 + 回调

  SessionCoreConfig cfg_;
  DepthRouter depth_router_;

  mutable std::mutex mu_;
  std::condition_variable cv_;
  std::deque<OwnedFrame> queue_;
  OwnedFrame current_;  // 上次 Poll 出的帧(持有 data 背存)
  gomob::camera::SessionState state_ = gomob::camera::SessionState::kIdle;
  gomob::camera::SessionStats stats_;
  std::string last_error_;
  bool stop_requested_ = false;
  int depth_serial_ = 0;
  int color_serial_ = 0;
  OwnedFrame latest_depth_, latest_color_;  // 最新帧快照(JNI consume-once)
  bool has_new_depth_ = false, has_new_color_ = false;

  std::vector<uint16_t> route_scratch_;  // OnRawDepthFrame 路由暂存(锁外用,单生产者)
  std::function<void(const gomob::camera::CameraFrame&)> on_frame_;
};

}  // namespace gomob::eys3d
