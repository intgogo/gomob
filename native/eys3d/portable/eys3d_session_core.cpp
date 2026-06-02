#include "eys3d_session_core.h"

#include <chrono>
#include <cstring>

namespace gomob::eys3d {

using gomob::camera::CameraFrame;
using gomob::camera::CameraStreamKind;
using gomob::camera::SessionState;
using gomob::camera::SessionStats;
using gomob::camera::XuPayload;

bool Eys3dSessionCore::Configure(const SessionCoreConfig& cfg) {
  std::lock_guard<std::mutex> lk(mu_);
  cfg_ = cfg;
  // 路由 active 尺寸取 depth 档位;状态行/字节序来自 depth_router 配置。
  DepthRouterConfig rc = cfg.depth_router;
  if (rc.width == 0) rc.width = cfg.depth.width;
  if (rc.height == 0) rc.height = cfg.depth.height;
  depth_router_.Configure(rc);
  return true;
}

void Eys3dSessionCore::SetZdTable(ZdTable table) { depth_router_.finalizer().SetZdTable(std::move(table)); }
void Eys3dSessionCore::SetGeometric(float fx_rect, float baseline_mm, float subpixel) {
  depth_router_.finalizer().SetGeometric(fx_rect, baseline_mm, subpixel);
}
void Eys3dSessionCore::SetCompensation(float scale, float bias) {
  depth_router_.finalizer().SetCompensation(scale, bias);
}
bool Eys3dSessionCore::depth_ready() const { return depth_router_.finalizer().ready(); }

std::vector<XuPayload> Eys3dSessionCore::BuildArming(const ArmConfig& cfg) const {
  return BuildArmSequence(cfg);
}

void Eys3dSessionCore::MarkStreaming() {
  std::lock_guard<std::mutex> lk(mu_);
  state_ = SessionState::kStreaming;
}
void Eys3dSessionCore::MarkError(const std::string& msg) {
  std::lock_guard<std::mutex> lk(mu_);
  state_ = SessionState::kError;
  last_error_ = msg;
  ++stats_.errors;
  cv_.notify_all();
}
void Eys3dSessionCore::MarkStopped() {
  std::lock_guard<std::mutex> lk(mu_);
  state_ = SessionState::kStopped;
  cv_.notify_all();
}
void Eys3dSessionCore::RequestStop() {
  std::lock_guard<std::mutex> lk(mu_);
  stop_requested_ = true;
  cv_.notify_all();
}
bool Eys3dSessionCore::stop_requested() const {
  std::lock_guard<std::mutex> lk(mu_);
  return stop_requested_;
}
SessionState Eys3dSessionCore::state() const {
  std::lock_guard<std::mutex> lk(mu_);
  return state_;
}
SessionStats Eys3dSessionCore::stats() const {
  std::lock_guard<std::mutex> lk(mu_);
  return stats_;
}
std::string Eys3dSessionCore::last_error() const {
  std::lock_guard<std::mutex> lk(mu_);
  return last_error_;
}

void Eys3dSessionCore::Enqueue(OwnedFrame f) {
  CameraFrame view;
  bool fire = false;
  {
    std::lock_guard<std::mutex> lk(mu_);
    // 背压:超上限丢最旧(保最新,扫描更看重新帧)。
    while (queue_.size() >= cfg_.max_queue && !queue_.empty()) {
      queue_.pop_front();
      ++stats_.dropped;
    }
    if (f.kind == CameraStreamKind::kDepthMm) {
      ++stats_.depth_frames;
      latest_depth_ = f;  // 快照最新(JNI 用),拷贝后再 move 入 FIFO
      has_new_depth_ = true;
    } else if (f.kind == CameraStreamKind::kColor) {
      ++stats_.color_frames;
      latest_color_ = f;
      has_new_color_ = true;
    }
    queue_.push_back(std::move(f));
    cv_.notify_one();
    if (on_frame_) {
      const OwnedFrame& b = queue_.back();
      view.kind = b.kind; view.width = b.width; view.height = b.height;
      view.host_ns = b.host_ns; view.serial = b.serial;
      view.data = b.bytes.data(); view.size = b.bytes.size();
      fire = true;
    }
  }
  if (fire && on_frame_) on_frame_(view);  // 锁外触发回调,避免重入死锁
}

void Eys3dSessionCore::OnRawDepthFrame(const uint8_t* raw, size_t size, int64_t host_ns) {
  if (!cfg_.want_depth) return;
  if (!depth_router_.Route(raw, size, &route_scratch_)) {
    std::lock_guard<std::mutex> lk(mu_);
    ++stats_.dropped;
    return;
  }
  OwnedFrame f;
  f.kind = CameraStreamKind::kDepthMm;
  f.width = depth_router_.active_width();
  f.height = depth_router_.active_height();
  f.host_ns = host_ns;
  f.serial = depth_serial_++;
  const uint8_t* p = reinterpret_cast<const uint8_t*>(route_scratch_.data());
  f.bytes.assign(p, p + route_scratch_.size() * sizeof(uint16_t));
  Enqueue(std::move(f));
}

void Eys3dSessionCore::OnDepthMmFrame(const uint16_t* mm, uint16_t width, uint16_t height,
                                      int64_t host_ns) {
  if (!cfg_.want_depth || mm == nullptr || width == 0 || height == 0) return;
  OwnedFrame f;
  f.kind = CameraStreamKind::kDepthMm;
  f.width = width;
  f.height = height;
  f.host_ns = host_ns;
  f.serial = depth_serial_++;
  const uint8_t* p = reinterpret_cast<const uint8_t*>(mm);
  f.bytes.assign(p, p + static_cast<size_t>(width) * height * sizeof(uint16_t));
  Enqueue(std::move(f));
}

void Eys3dSessionCore::OnColorFrame(const uint8_t* data, size_t size, int64_t host_ns) {
  if (!cfg_.want_color || data == nullptr || size == 0) return;
  OwnedFrame f;
  f.kind = CameraStreamKind::kColor;
  f.width = cfg_.color.width;
  f.height = cfg_.color.height;
  f.host_ns = host_ns;
  f.serial = color_serial_++;
  f.bytes.assign(data, data + size);
  Enqueue(std::move(f));
}

int Eys3dSessionCore::SnapshotLatestDepthMm(uint16_t* dst, size_t cap_px, int64_t* meta) {
  std::lock_guard<std::mutex> lk(mu_);
  if (!has_new_depth_ || latest_depth_.bytes.empty()) return 0;
  const size_t px = static_cast<size_t>(latest_depth_.width) * latest_depth_.height;
  if (cap_px < px) return -1;
  std::memcpy(dst, latest_depth_.bytes.data(), px * sizeof(uint16_t));
  if (meta) {
    meta[0] = latest_depth_.width; meta[1] = latest_depth_.height;
    meta[2] = latest_depth_.serial; meta[3] = latest_depth_.host_ns;
  }
  has_new_depth_ = false;  // consume-once
  return static_cast<int>(px * sizeof(uint16_t));
}

bool Eys3dSessionCore::SnapshotLatestColor(std::vector<uint8_t>* out, int64_t* meta) {
  std::lock_guard<std::mutex> lk(mu_);
  if (!has_new_color_ || latest_color_.bytes.empty() || out == nullptr) return false;
  *out = latest_color_.bytes;
  if (meta) {
    meta[0] = latest_color_.width; meta[1] = latest_color_.height;
    meta[2] = latest_color_.serial; meta[3] = latest_color_.host_ns;
  }
  has_new_color_ = false;
  return true;
}

int Eys3dSessionCore::Poll(CameraFrame* out, uint32_t timeout_ms) {
  std::unique_lock<std::mutex> lk(mu_);
  if (queue_.empty()) {
    if (state_ == SessionState::kError) return -1;
    if (timeout_ms == 0) return 0;
    cv_.wait_for(lk, std::chrono::milliseconds(timeout_ms), [&] {
      return !queue_.empty() || state_ == SessionState::kError || stop_requested_;
    });
    if (queue_.empty()) return state_ == SessionState::kError ? -1 : 0;
  }
  current_ = std::move(queue_.front());
  queue_.pop_front();
  if (out) {
    out->kind = current_.kind;
    out->width = current_.width;
    out->height = current_.height;
    out->host_ns = current_.host_ns;
    out->device_ns = 0;
    out->serial = current_.serial;
    out->data = current_.bytes.data();
    out->size = current_.bytes.size();
  }
  return 1;
}

}  // namespace gomob::eys3d
