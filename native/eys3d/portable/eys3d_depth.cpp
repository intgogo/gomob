#include "eys3d_depth.h"

#include <algorithm>
#include <cmath>

namespace gomob::eys3d {

namespace {
inline uint16_t Bswap16(uint16_t v) { return static_cast<uint16_t>((v >> 8) | (v << 8)); }
}  // namespace

ZdTable ZdTable::FromFlashBlob(const uint8_t* data, size_t size) {
  if (data == nullptr || size < 2) return ZdTable();
  const size_t n = size / 2;  // u16 项数
  std::vector<uint16_t> mm(n);
  for (size_t i = 0; i < n; ++i) {
    // 厂家大端存储,读出做 byteswap(反汇编 CComputer::TableToData: rol cx,8)。
    uint16_t raw = static_cast<uint16_t>(data[2 * i] | (data[2 * i + 1] << 8));
    mm[i] = Bswap16(raw);
  }
  return ZdTable(std::move(mm));
}

ZdTable ZdTable::FromMillimeters(std::vector<uint16_t> mm) { return ZdTable(std::move(mm)); }

void ZdTable::DisparityToDepthMm(const uint16_t* disp, size_t count, uint16_t* out_mm,
                                 float comp_scale, float comp_bias) const {
  if (disp == nullptr || out_mm == nullptr) return;
  const bool compensate = (comp_scale != 1.0f) || (comp_bias != 0.0f);
  const size_t tbl = mm_.size();
  for (size_t i = 0; i < count; ++i) {
    uint32_t d = disp[i];
    if (d == 0) {  // 0 视差 = 无效/无返回
      out_mm[i] = 0;
      continue;
    }
    if (compensate) {
      float nd = static_cast<float>(d) * comp_scale + comp_bias;
      d = (nd <= 0.0f) ? 0u : static_cast<uint32_t>(nd + 0.5f);
    }
    out_mm[i] = (d < tbl) ? mm_[d] : 0;  // 越界(常见的巨值无效像素)→ 0
  }
}

uint16_t GeometricZMm(uint16_t disparity_u16, float fx_rect, float baseline_mm, float subpixel) {
  if (disparity_u16 == 0 || subpixel <= 0.0f) return 0;
  float real_disp = static_cast<float>(disparity_u16) / subpixel;
  if (real_disp <= 0.0f) return 0;
  float z = fx_rect * baseline_mm / real_disp;
  if (!(z > 0.0f) || z > 65535.0f) return 0;
  return static_cast<uint16_t>(z + 0.5f);
}

void GeometricDisparityToDepthMm(const uint16_t* disp, size_t count, uint16_t* out_mm,
                                 float fx_rect, float baseline_mm, float subpixel) {
  if (disp == nullptr || out_mm == nullptr) return;
  for (size_t i = 0; i < count; ++i) {
    out_mm[i] = GeometricZMm(disp[i], fx_rect, baseline_mm, subpixel);
  }
}

}  // namespace gomob::eys3d
