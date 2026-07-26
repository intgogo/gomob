#include "eys3d/portable/vendor_worker_lifecycle.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>

using gomob::eys3d::VendorWorkerCloseResult;
using gomob::eys3d::VendorWorkerLifecycle;

namespace {

int g_fail = 0;

#define CHECK_TRUE(expr)                                                                    \
  do {                                                                                      \
    if (!(expr)) {                                                                          \
      std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #expr);                       \
      ++g_fail;                                                                             \
    }                                                                                       \
  } while (0)

bool WaitUntil(const std::atomic<int>& value, int minimum, std::chrono::milliseconds timeout) {
  const auto deadline = std::chrono::steady_clock::now() + timeout;
  while (std::chrono::steady_clock::now() < deadline) {
    if (value.load() >= minimum) return true;
    std::this_thread::sleep_for(std::chrono::milliseconds(1));
  }
  return value.load() >= minimum;
}

void TestTeardownWaitsForDelayedWorkerAndJoinsBeforeDestroy() {
  VendorWorkerLifecycle lifecycle;
  lifecycle.MarkOpenCalled();

  std::mutex gate_mutex;
  std::condition_variable gate_cv;
  bool release_worker = false;
  std::atomic<bool> started{false};
  std::atomic<bool> stop_worker{false};
  std::atomic<bool> worker_exited{false};
  std::atomic<int> started_checks{0};
  std::atomic<int> close_calls{0};
  std::atomic<bool> close_saw_started{false};
  std::atomic<bool> close_joined{false};
  std::atomic<bool> destroyed{false};

  std::thread worker([&] {
    {
      std::unique_lock<std::mutex> lock(gate_mutex);
      gate_cv.wait(lock, [&] { return release_worker; });
    }
    started.store(true);
    while (!stop_worker.load()) std::this_thread::yield();
    worker_exited.store(true);
  });

  VendorWorkerCloseResult result = VendorWorkerCloseResult::kStartTimeout;
  std::thread teardown([&] {
    result = lifecycle.CloseAfterStarted(
        [&] {
          started_checks.fetch_add(1);
          return started.load();
        },
        [&] {
          close_calls.fetch_add(1);
          close_saw_started.store(started.load());
          started.store(false);
          stop_worker.store(true);
          worker.join();
          close_joined.store(true);
        },
        std::chrono::milliseconds(500), std::chrono::milliseconds(1));
    if (lifecycle.safe_to_destroy()) destroyed.store(close_joined.load());
  });

  CHECK_TRUE(WaitUntil(started_checks, 5, std::chrono::milliseconds(100)));
  CHECK_TRUE(close_calls.load() == 0);
  CHECK_TRUE(!destroyed.load());
  {
    std::lock_guard<std::mutex> lock(gate_mutex);
    release_worker = true;
  }
  gate_cv.notify_one();
  teardown.join();

  CHECK_TRUE(result == VendorWorkerCloseResult::kClosed);
  CHECK_TRUE(close_calls.load() == 1);
  CHECK_TRUE(close_saw_started.load());
  CHECK_TRUE(worker_exited.load());
  CHECK_TRUE(close_joined.load());
  CHECK_TRUE(destroyed.load());
  CHECK_TRUE(lifecycle.safe_to_destroy());
}

void TestTimeoutNeverClosesOrAllowsDestroy() {
  VendorWorkerLifecycle lifecycle;
  lifecycle.MarkOpenCalled();
  std::atomic<int> close_calls{0};

  const auto result = lifecycle.CloseAfterStarted(
      [] { return false; }, [&] { close_calls.fetch_add(1); },
      std::chrono::milliseconds(10), std::chrono::milliseconds(1));

  CHECK_TRUE(result == VendorWorkerCloseResult::kStartTimeout);
  CHECK_TRUE(close_calls.load() == 0);
  CHECK_TRUE(!lifecycle.safe_to_destroy());
}

void TestCloseReturningWhileStartedNeverAllowsDestroy() {
  VendorWorkerLifecycle lifecycle;
  lifecycle.MarkOpenCalled();
  std::atomic<int> close_calls{0};

  const auto result = lifecycle.CloseAfterStarted(
      [] { return true; }, [&] { close_calls.fetch_add(1); },
      std::chrono::milliseconds(10), std::chrono::milliseconds(1));

  CHECK_TRUE(result == VendorWorkerCloseResult::kStillStarted);
  CHECK_TRUE(close_calls.load() == 1);
  CHECK_TRUE(!lifecycle.safe_to_destroy());
}

}  // namespace

int main() {
  TestTeardownWaitsForDelayedWorkerAndJoinsBeforeDestroy();
  TestTimeoutNeverClosesOrAllowsDestroy();
  TestCloseReturningWhileStartedNeverAllowsDestroy();
  std::printf("eys3d_vendor_worker_lifecycle_test: %s (fails=%d)\n",
              g_fail == 0 ? "PASS" : "FAIL", g_fail);
  return g_fail == 0 ? 0 : 1;
}
