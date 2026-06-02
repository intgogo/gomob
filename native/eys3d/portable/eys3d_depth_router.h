// eYs3D RS-D550 IF2 深度帧路由(硬件 ASIC 路径)。纯 portable,无 libusb / 无 OpenCV。
//
// 职责:把会话层(host libusb / Android fd)从 IF2(ep 0x82)组装好的【原始视差帧】转成
// metric depthMm 帧(u16, mm),供 ICameraSession::poll 出 CameraFrame(kDepthMm)。
//
// IF2 11-bit 模式每像素 = 1 个小端 u16 视差(有效 0..2047),【不是 YUY2,不要拆】
// (见 docs/agent-memory/finding_rsd550_open_sequence_decoded_2026-06-01.md §eSPDI 深度真实链路)。
// 度量交给 DepthFinalizer:优先 ZD 表查表 Z=byteswap(ZDtable[disp]),无表时几何 Z=fx'·B/disp。
//
// 字节序:实测 IF2 视差本身是【小端】(主机序直接可用),disparity_byteswap 默认 false。
// ZD 表内部(FromFlashBlob)已对大端 flash 做过 byteswap,两处不要重复 swap。
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

#include "eys3d_driver.h"  // gomob::eys3d::DepthFinalizer

namespace gomob::eys3d {

struct DepthRouterConfig {
  uint16_t width = 640;       // IF2 传输帧宽(mode25=640)
  uint16_t height = 128;      // IF2 传输帧高含状态行(mode25=128;USB3=480)
  uint16_t status_rows = 0;   // 顶部状态行行数(eYs3D 11bit 实测无;留参数防型号差异)
  bool disparity_byteswap = false;  // IF2 u16 视差是否需 byteswap 成主机序(实测小端,默认否)
};

// IF2 原始视差帧 → metric depthMm 帧。
class DepthRouter {
 public:
  DepthRouter() = default;

  void Configure(const DepthRouterConfig& cfg) { cfg_ = cfg; }
  const DepthRouterConfig& config() const { return cfg_; }

  // 终结器(设 ZD 表 / 几何参数 / 补偿)。会话开流前配置。
  DepthFinalizer& finalizer() { return fin_; }
  const DepthFinalizer& finalizer() const { return fin_; }

  // active 区(剥状态行后)。
  uint16_t active_width() const { return cfg_.width; }
  uint16_t active_height() const {
    return cfg_.height > cfg_.status_rows ? static_cast<uint16_t>(cfg_.height - cfg_.status_rows) : 0;
  }
  // 期望的 IF2 原始帧字节数(含状态行,每像素 2B)。
  size_t expected_frame_bytes() const {
    return static_cast<size_t>(cfg_.width) * cfg_.height * 2u;
  }

  // raw(IF2 原始帧 size 字节)→ out_mm(active_width*active_height 个 u16, mm)。
  // size 必须 >= expected_frame_bytes();终结器未就绪 / 尺寸不符 → false。
  bool Route(const uint8_t* raw, size_t size, std::vector<uint16_t>* out_mm) const;

 private:
  DepthRouterConfig cfg_;
  DepthFinalizer fin_;
  mutable std::vector<uint16_t> disp_scratch_;  // active 区视差(主机序),复用免重分配
};

}  // namespace gomob::eys3d
