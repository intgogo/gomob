// eYs3D Android fd 会话/驱动 smoke(链 libusb)— 验编译自洽 + 工厂离线面 + 无效 fd 优雅降级。
// 真机出帧设备门控,不在此;此处证 Eys3dFdDriver/Eys3dFdSession 结构端到端可构造、坏 fd 进 kError。
#include "eys3d/android/eys3d_fd_session.h"

#include <cstdio>
#include <thread>

#include "camera/host/usb_context.h"

using namespace gomob::eys3d::android;
using gomob::camera::CameraCapabilities;
using gomob::camera::CameraFrame;
using gomob::camera::SessionCallbacks;
using gomob::camera::SessionConfig;
using gomob::camera::SessionState;
using gomob::camera::UsbContext;

namespace {
int g_fail = 0;
void Check(const char* tag, bool ok) {
  std::printf("  %-50s -> %s\n", tag, ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}
}  // namespace

int main() {
  Eys3dFdDriver drv;

  CameraCapabilities caps = drv.capabilities();
  Check("capabilities eYs3D/RS-D550", caps.vendor == "eYs3D" && caps.model == "RS-D550");
  Check("USB2 depth 640x128 metric_onchip",
        !caps.depth_profiles.empty() && caps.depth_profiles[0].width == 640 &&
            caps.depth_is_metric_onchip);
  auto ids = drv.match_usb_ids();
  Check("match 0x3438:0x0206", ids.size() == 1 && ids[0].vid == 0x3438 && ids[0].pid == 0x0206);

  // 非法 fds → nullptr。
  Check("open_fd({}) → nullptr", drv.open_fd({}, SessionConfig{}) == nullptr);
  Check("open_fd({-1}) → nullptr", drv.open_fd({-1}, SessionConfig{}) == nullptr);

  // Android 驱动无 host 路径。
  {
    UsbContext ctx;
    Check("open_host(Android driver) → nullptr", drv.open_host(ctx, SessionConfig{}) == nullptr);
  }

  // 坏 fd(非 usbfs)→ wrap 失败 → 会话进 kError,poll 返回 <=0,不崩溃。
  {
    auto sess = drv.open_fd({3}, SessionConfig{});
    Check("open_fd({3}) 出会话", sess != nullptr);
    if (sess) {
      sess->start(SessionCallbacks{});
      CameraFrame f;
      int last = 0;
      for (int i = 0; i < 50; ++i) {
        last = sess->poll(&f, 20);
        if (sess->state() == SessionState::kError) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
      }
      Check("坏 fd → 会话 kError", sess->state() == SessionState::kError);
      Check("坏 fd → poll<=0", last <= 0);
      sess->stop();
      sess->join();
      Check("stop/join 不崩溃", true);
    }
  }

  std::printf("eys3d_fd_session_smoke: %s (fails=%d)\n", g_fail == 0 ? "PASS" : "FAIL", g_fail);
  return g_fail == 0 ? 0 : 1;
}
