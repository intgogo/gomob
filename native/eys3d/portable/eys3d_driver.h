// eYs3D RS-D550 driver 的 portable 核心:设备能力 + 深度终结器。
//
// 完整 EYS3DDriver:ICameraDriver / EYS3DSession:ICameraSession 在 native/eys3d/host(需 libusb),
// 复用此处的 portable 件 + eys3d_protocol(arming)+ eys3d_depth(ZD 表)+ camera/(协商/组装)。
// 深度双路(见 docs/architecture/13-eys3d-driver.md §2bis):
//   硬件 ASIC 主 — IF2 出 11bit 视差(u16)→ DepthFinalizer 经 ZD 表查 mm;depth_is_metric_onchip=true。
//   软件 stereo 兼 — IF1 双目自研重建(eys3d_stereo_depth,后续),fallback。
#pragma once
#include <cstdint>
#include <vector>

#include "camera/camera_session.h"  // gomob::camera::{CameraCapabilities,UsbId,...}
#include "eys3d_depth.h"

namespace gomob::eys3d {

// RS-D550(ROSIE4)USB ID。
inline constexpr gomob::camera::UsbId kRsd550UsbId{0x3438, 0x0206};

// RS-D550 出厂矫正标定默认(rectlog_1.bin 提取;见 finding_rsd550_open_sequence_decoded_2026-06-01)。
// 几何退化度量(Z=fx'·B/真视差)用。★终态应 adb pull 每台设备 ZD 表注入(更准,含温补/非线性),
// 见 TODO M6.5;此默认仅在无 ZD 表时兜底,不是硬编码魔法,有标定来源可追溯。
constexpr float kRsd550RectifiedFx = 1229.205f;  // 矫正焦距 px(NewCamMat1[0])
constexpr float kRsd550BaselineMm = 49.98f;      // 基线 mm(-NewCamMat[3]/fx)

// 深度通路。
enum class DepthPath { kHardwareAsic, kSoftwareStereo };

// 构造 RS-D550 能力(按 USB 速度 + 深度通路填 profiles)。
//   usb3=false(经带电 hub 跑 USB2,本设备实测)→ mode25:color 1280x256 MJPG@5 + depth 640x128@5。
//   usb3=true → color/depth 640x480@10(videoMode 4)。
//   path=kHardwareAsic → depth_is_metric_onchip=true;kSoftwareStereo → false。
gomob::camera::CameraCapabilities BuildRsd550Capabilities(bool usb3, DepthPath path);

// 深度终结器:把设备 IF2 推上来的 11bit 视差帧(u16)转成 metric depthMm 帧(u16)。
// 优先 ZD 表查表;无表时用几何 Z=fx'·B/disp 退化。供 EYS3DSession 在出 depthMm 帧前调用。
class DepthFinalizer {
 public:
  DepthFinalizer() = default;

  // 设 ZD 表(设备 flash 提取的 disparity→mm LUT)。设了即走查表路径。
  void SetZdTable(ZdTable table) { zd_ = std::move(table); }
  // 设几何退化参数(无 ZD 表时用)。
  void SetGeometric(float fx_rect, float baseline_mm, float subpixel = kDefaultSubpixel) {
    fx_rect_ = fx_rect; baseline_mm_ = baseline_mm; subpixel_ = subpixel;
  }
  // 视差线性补偿(RectLogData.depth_comp_pars),默认不补偿。
  void SetCompensation(float scale, float bias) { comp_scale_ = scale; comp_bias_ = bias; }

  bool ready() const { return zd_.valid() || (fx_rect_ > 0.0f && baseline_mm_ > 0.0f); }
  bool has_zd_table() const { return zd_.valid(); }

  // disp(width*height 个 u16 视差)→ out_mm(同尺寸 u16 metric)。成功返回 true。
  bool Finalize(const uint16_t* disp, uint16_t width, uint16_t height,
                std::vector<uint16_t>& out_mm) const;

 private:
  ZdTable zd_;
  float fx_rect_ = 0.0f;
  float baseline_mm_ = 0.0f;
  float subpixel_ = kDefaultSubpixel;
  float comp_scale_ = 1.0f;
  float comp_bias_ = 0.0f;
};

}  // namespace gomob::eys3d
