// eYs3D host driver/会话 smoke(链 libusb)— 验证编译自洽 + 工厂可离线工作 + 无设备优雅降级。
// 流取帧本身需真机(设备门控),不在此验;此处只证 driver/session 结构端到端可构造、可委托。
#include "eys3d/host/eys3d_host_session.h"

#include <cstdio>

#include "camera/host/usb_context.h"

using namespace gomob::eys3d::host;
using gomob::camera::CameraCapabilities;
using gomob::camera::SessionConfig;
using gomob::camera::StreamProfile;
using gomob::camera::UsbContext;

namespace {
int g_fail = 0;
void Check(const char* tag, bool ok) {
  std::printf("  %-50s -> %s\n", tag, ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}
}  // namespace

int main() {
  Eys3dDriver drv;  // 默认 USB2 / 硬件 ASIC 路径

  // 工厂离线面:capabilities / match。
  CameraCapabilities caps = drv.capabilities();
  Check("capabilities vendor=eYs3D model=RS-D550", caps.vendor == "eYs3D" && caps.model == "RS-D550");
  Check("USB2 depth=640x128 + metric_onchip",
        !caps.depth_profiles.empty() && caps.depth_profiles[0].width == 640 &&
            caps.depth_profiles[0].height == 128 && caps.depth_is_metric_onchip);
  auto ids = drv.match_usb_ids();
  Check("match_usb_ids = 0x3438:0x0206",
        ids.size() == 1 && ids[0].vid == 0x3438 && ids[0].pid == 0x0206);

  // proven 开流计划:双流端点/帧大小/PROBE 负载。
  Eys3dOpenPlan plan = ProvenWrongModePlan();
  Check("plan color ep=0x81 frame=1228800", plan.color.endpoint == 0x81 && plan.color.frame_bytes == 1228800);
  Check("plan depth ep=0x82 urb=3072", plan.depth.endpoint == 0x82 && plan.depth.urb_size == 3072);
  Check("plan color probe 26B", plan.color.probe_zero.size() == 26 && plan.color.probe_neg.size() == 26);
  // PROBE neg 的 maxFrame 落 offset 18(小端 0x12c000)。
  const auto& pn = plan.color.probe_neg;
  Check("color probe_neg maxFrame=0x12c000",
        pn[18] == 0x00 && pn[19] == 0xc0 && pn[20] == 0x12 && pn[21] == 0x00);
  Check("proven plan videomode_reg=0x02(14bit 错配置)", plan.arm.videomode_reg == 0x02);

  // ★ mode25 正确计划:videoMode=36 + color 1280×256 MJPEG(变长 frame_bytes=0)+ depth 640×128。
  Eys3dOpenPlan m25 = Mode25Usb2Plan();
  Check("mode25 videomode_reg=36(0x24)", m25.arm.videomode_reg == 36);
  Check("mode25 color MJPEG 变长(frame_bytes=0)", m25.color.frame_bytes == 0);
  Check("mode25 depth 640x128=163840B", m25.depth.frame_bytes == 640 * 128 * 2);
  Check("mode25 color fmt=2(MJPEG)", m25.color.probe_neg[2] == 2);

  // open_host 无设备时优雅返回 nullptr(不崩溃)。
  {
    UsbContext ctx;
    Check("UsbContext 初始化", ctx.valid());
    auto sess = drv.open_host(ctx, SessionConfig{});
    // 测试环境通常无 RS-D550 → nullptr;若恰好接了真机则非空也可接受。
    std::printf("  [info] open_host(无/有设备) → %s\n", sess ? "session(检测到设备)" : "nullptr(无设备)");
    Check("open_host 不崩溃", true);
  }

  // open_fd(Android 路径) host build 返回 nullptr。
  Check("open_fd host build → nullptr", drv.open_fd({3}, SessionConfig{}) == nullptr);

  std::printf("eys3d_host_session_smoke: %s (fails=%d)\n", g_fail == 0 ? "PASS" : "FAIL", g_fail);
  return g_fail == 0 ? 0 : 1;
}
