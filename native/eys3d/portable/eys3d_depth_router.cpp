#include "eys3d_depth_router.h"

namespace gomob::eys3d {

namespace {
inline uint16_t Bswap16(uint16_t v) { return static_cast<uint16_t>((v >> 8) | (v << 8)); }
}  // namespace

bool DepthRouter::Route(const uint8_t* raw, size_t size, std::vector<uint16_t>* out_mm) const {
  if (raw == nullptr || out_mm == nullptr) return false;
  const uint16_t w = active_width();
  const uint16_t h = active_height();
  if (w == 0 || h == 0) return false;
  if (size < expected_frame_bytes()) return false;
  if (!fin_.ready()) return false;

  const size_t count = static_cast<size_t>(w) * h;
  // 跳过顶部状态行,定位 active 区起点。
  const uint8_t* p = raw + static_cast<size_t>(cfg_.status_rows) * cfg_.width * 2u;

  // raw 可能非 u16 对齐:统一拷进 scratch(顺带 byteswap),拿到主机序视差。
  disp_scratch_.resize(count);
  if (cfg_.disparity_byteswap) {
    for (size_t i = 0; i < count; ++i) {
      uint16_t v = static_cast<uint16_t>(p[2 * i] | (p[2 * i + 1] << 8));
      disp_scratch_[i] = Bswap16(v);
    }
  } else {
    for (size_t i = 0; i < count; ++i) {
      disp_scratch_[i] = static_cast<uint16_t>(p[2 * i] | (p[2 * i + 1] << 8));
    }
  }
  return fin_.Finalize(disp_scratch_.data(), w, h, *out_mm);
}

}  // namespace gomob::eys3d
