// 厂商 worker 生命周期屏障：Open 返回不代表 pthread 已进入，销毁前必须先观察到 started，再 Close/join。
#pragma once

#include <chrono>
#include <thread>

namespace gomob::eys3d {

enum class VendorWorkerCloseResult {
  kNotOpened,
  kClosed,
  kStartTimeout,
  kStillStarted,
};

class VendorWorkerLifecycle {
 public:
  void MarkOpenCalled() { open_called_ = true; }

  bool open_called() const { return open_called_; }

  template <typename IsStarted>
  bool WaitUntilStarted(IsStarted&& is_started, std::chrono::milliseconds timeout,
                        std::chrono::milliseconds poll_interval) {
    if (!open_called_) return false;
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    do {
      if (is_started()) {
        started_observed_ = true;
        return true;
      }
      std::this_thread::sleep_for(poll_interval);
    } while (std::chrono::steady_clock::now() < deadline);
    if (is_started()) {
      started_observed_ = true;
      return true;
    }
    return false;
  }

  template <typename IsStarted, typename Close>
  VendorWorkerCloseResult CloseAfterStarted(IsStarted&& is_started, Close&& close,
                                             std::chrono::milliseconds timeout,
                                             std::chrono::milliseconds poll_interval) {
    if (!open_called_) return VendorWorkerCloseResult::kNotOpened;
    if (!WaitUntilStarted(is_started, timeout, poll_interval)) {
      return VendorWorkerCloseResult::kStartTimeout;
    }
    close();
    if (is_started()) return VendorWorkerCloseResult::kStillStarted;
    open_called_ = false;
    close_completed_ = true;
    return VendorWorkerCloseResult::kClosed;
  }

  bool safe_to_destroy() const { return !open_called_ || close_completed_; }
  bool started_observed() const { return started_observed_; }

 private:
  bool open_called_ = false;
  bool started_observed_ = false;
  bool close_completed_ = false;
};

}  // namespace gomob::eys3d
