#include "eys3d_driver.h"

namespace gomob::eys3d {

using gomob::camera::CameraCapabilities;
using gomob::camera::StreamProfile;

CameraCapabilities BuildRsd550Capabilities(bool usb3, DepthPath path) {
  CameraCapabilities c;
  c.vendor = "eYs3D";
  c.model = "RS-D550";
  c.has_color = true;
  c.has_depth = true;
  c.has_confidence = false;  // RS-D550 IF 无独立 confidence 流
  c.has_ir = true;           // IR 投射器(钢板等无纹理目标的纹理源)
  // 硬件 ASIC 路径:ASIC 直出视差 + ZD 表查 mm = 端侧 metric;软件 stereo:host 重建,非 on-chip。
  c.depth_is_metric_onchip = (path == DepthPath::kHardwareAsic);

  if (usb3) {
    // videoMode 4(RECTIFY_11_BITS):color/depth 640x480@10。
    c.color_profiles.push_back(StreamProfile{640, 480, 10, StreamProfile::Format::kYuyv, "640x480@10"});
    c.depth_profiles.push_back(StreamProfile{640, 480, 10, StreamProfile::Format::kDepthU16, "640x480@10 disp11"});
  } else {
    // mode25(SCALE_DOWN_11_BITS=36):color 1280x256 MJPG@5 + depth 640x128@5。color 必须配对同开。
    c.color_profiles.push_back(StreamProfile{1280, 256, 5, StreamProfile::Format::kMjpeg, "1280x256MJPG@5"});
    c.depth_profiles.push_back(StreamProfile{640, 128, 5, StreamProfile::Format::kDepthU16, "640x128@5 disp11"});
  }
  return c;
}

bool DepthFinalizer::Finalize(const uint16_t* disp, uint16_t width, uint16_t height,
                              std::vector<uint16_t>& out_mm) const {
  if (disp == nullptr || width == 0 || height == 0 || !ready()) return false;
  const size_t count = static_cast<size_t>(width) * height;
  out_mm.resize(count);
  if (zd_.valid()) {
    zd_.DisparityToDepthMm(disp, count, out_mm.data(), comp_scale_, comp_bias_);
  } else {
    GeometricDisparityToDepthMm(disp, count, out_mm.data(), fx_rect_, baseline_mm_, subpixel_);
  }
  return true;
}

}  // namespace gomob::eys3d
