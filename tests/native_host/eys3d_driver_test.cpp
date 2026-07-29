// eYs3D driver portable 核心单测 — 能力构造 + 深度终结器。
#include "eys3d/portable/eys3d_driver.h"

#include <cstdio>

using namespace gomob::eys3d;
using gomob::camera::StreamProfile;

namespace {
int g_fail = 0;
void Check(const char* tag, bool ok) {
  std::printf("  %-46s -> %s\n", tag, ok ? "OK" : "FAIL");
  if (!ok) ++g_fail;
}
}  // namespace

int main() {
  // USB2(mode25)硬件路径能力
  {
    auto c = BuildRsd550Capabilities(/*usb3=*/false, DepthPath::kHardwareAsic);
    Check("USB2 vendor/model", c.vendor == "eYs3D" && c.model == "RS-D550");
    Check("USB2 has_ir", c.has_ir);
    Check("USB2 硬件路径 metric_onchip", c.depth_is_metric_onchip);
    bool color_ok = c.color_profiles.size() == 1 && c.color_profiles[0].width == 1280 &&
                    c.color_profiles[0].height == 256 && c.color_profiles[0].fps == 5 &&
                    c.color_profiles[0].format == StreamProfile::Format::kMjpeg;
    Check("USB2 color=1280x256 MJPG@5", color_ok);
    bool depth_ok = c.depth_profiles.size() == 1 && c.depth_profiles[0].width == 640 &&
                    c.depth_profiles[0].height == 128 && c.depth_profiles[0].fps == 5 &&
                    c.depth_profiles[0].format == StreamProfile::Format::kDepthU16;
    Check("USB2 depth=640x128@5 disp11", depth_ok);
  }
  // USB3 能力
  {
    auto c = BuildRsd550Capabilities(/*usb3=*/true, DepthPath::kHardwareAsic);
    Check("USB3 color=640x480@10", c.color_profiles.size() == 1 && c.color_profiles[0].width == 640 &&
                                    c.color_profiles[0].fps == 10);
    Check("USB3 depth=640x480@10", c.depth_profiles.size() == 1 && c.depth_profiles[0].height == 480);
  }
  // 软件路径 → 非 on-chip metric
  {
    auto c = BuildRsd550Capabilities(false, DepthPath::kSoftwareStereo);
    Check("软件路径 metric_onchip=false", !c.depth_is_metric_onchip);
  }
  // USB ID
  Check("kRsd550UsbId=0x3438:0x0206", kRsd550UsbId.vid == 0x3438 && kRsd550UsbId.pid == 0x0206);

  // DepthFinalizer:ZD 表路径
  {
    DepthFinalizer f;
    Check("未设表/几何 → not ready", !f.ready());
    f.SetZdTable(ZdTable::FromMillimeters({0, 1000, 500, 250}));
    Check("设表后 ready + has_zd_table", f.ready() && f.has_zd_table());
    uint16_t disp[4] = {0, 1, 3, 2};  // 2x2 帧
    std::vector<uint16_t> out;
    bool ok = f.Finalize(disp, 2, 2, out);
    Check("Finalize 2x2 成功", ok && out.size() == 4);
    Check("查表 [0,1000,250,500]", out[0] == 0 && out[1] == 1000 && out[2] == 250 && out[3] == 500);
  }
  // DepthFinalizer:几何退化路径(无表)
  {
    DepthFinalizer f;
    f.SetGeometric(614.6f, 49.98f);  // fx,B
    Check("仅几何 → ready 且非 zd", f.ready() && !f.has_zd_table());
    uint16_t disp[1] = {512};  // 真视差64 → Z≈480mm
    std::vector<uint16_t> out;
    f.Finalize(disp, 1, 1, out);
    Check("几何 disp512→≈480mm", !out.empty() && out[0] >= 477 && out[0] <= 483);
  }
  // 无效输入
  {
    DepthFinalizer f;
    f.SetZdTable(ZdTable::FromMillimeters({0, 100}));
    std::vector<uint16_t> out;
    Check("nullptr 输入 → false", !f.Finalize(nullptr, 2, 2, out));
    Check("0 尺寸 → false", !f.Finalize(reinterpret_cast<const uint16_t*>(&f), 0, 0, out));
  }

  std::printf("eys3d_driver_test: %s (fails=%d)\n", g_fail == 0 ? "PASS" : "FAIL", g_fail);
  return g_fail == 0 ? 0 : 1;
}
