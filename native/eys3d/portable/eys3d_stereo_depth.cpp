#include "eys3d_stereo_depth.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace gomob::eys3d {

namespace {

// 单像素 (x,y) 在给定视差 d 下的 SAD 代价(left vs right[x-d])。窗口越界返回 INT_MAX。
// 同时回传窗口灰度极差(纹理判据)。
inline int64_t SadAt(const uint8_t* left, const uint8_t* right, int width, int height, int x, int y,
                     int d, int r, int* tex_range) {
  if (x - d - r < 0) return std::numeric_limits<int64_t>::max();
  if (x + r >= width || x - r < 0 || y + r >= height || y - r < 0) {
    return std::numeric_limits<int64_t>::max();
  }
  int64_t sad = 0;
  uint8_t lo = 255, hi = 0;
  for (int dy = -r; dy <= r; ++dy) {
    const uint8_t* lrow = left + static_cast<size_t>(y + dy) * width;
    const uint8_t* rrow = right + static_cast<size_t>(y + dy) * width;
    for (int dx = -r; dx <= r; ++dx) {
      const uint8_t lv = lrow[x + dx];
      const uint8_t rv = rrow[x - d + dx];
      sad += std::abs(static_cast<int>(lv) - static_cast<int>(rv));
      if (tex_range) {
        lo = std::min(lo, lv);
        hi = std::max(hi, lv);
      }
    }
  }
  if (tex_range) *tex_range = hi - lo;
  return sad;
}

// 在 left 视角对像素 (x,y) 搜最佳真视差;回传 best/second cost 与 best d。无效 → best_d=-1。
inline int BestDisparity(const uint8_t* left, const uint8_t* right, int width, int height, int x,
                         int y, const BlockMatchConfig& cfg, int64_t* best_cost, int64_t* second_cost,
                         int* tex_range) {
  int64_t best = std::numeric_limits<int64_t>::max();
  int64_t second = std::numeric_limits<int64_t>::max();
  int best_d = -1;
  int tr_at_best = 0;
  for (int d = cfg.min_disparity; d < cfg.max_disparity; ++d) {
    int tr = 0;
    const int64_t c = SadAt(left, right, width, height, x, y, d, cfg.block_radius, &tr);
    if (c == std::numeric_limits<int64_t>::max()) continue;
    if (c < best) {
      second = best;
      best = c;
      best_d = d;
      tr_at_best = tr;
    } else if (c < second) {
      second = c;
    }
  }
  if (best_cost) *best_cost = best;
  if (second_cost) *second_cost = second;
  if (tex_range) *tex_range = tr_at_best;
  return best_d;
}

// right 视角对像素 (xr,y) 搜最佳视差(right[xr] 匹配 left[xr+d]),用于 LR 一致性。
inline int BestDisparityRight(const uint8_t* left, const uint8_t* right, int width, int height,
                              int xr, int y, const BlockMatchConfig& cfg) {
  int64_t best = std::numeric_limits<int64_t>::max();
  int best_d = -1;
  const int r = cfg.block_radius;
  if (y - r < 0 || y + r >= height || xr - r < 0 || xr + r >= width) return -1;
  for (int d = cfg.min_disparity; d < cfg.max_disparity; ++d) {
    const int xl = xr + d;
    if (xl + r >= width) break;
    int64_t sad = 0;
    for (int dy = -r; dy <= r; ++dy) {
      const uint8_t* lrow = left + static_cast<size_t>(y + dy) * width;
      const uint8_t* rrow = right + static_cast<size_t>(y + dy) * width;
      for (int dx = -r; dx <= r; ++dx) {
        sad += std::abs(static_cast<int>(lrow[xl + dx]) - static_cast<int>(rrow[xr + dx]));
      }
    }
    if (sad < best) { best = sad; best_d = d; }
  }
  return best_d;
}

}  // namespace

bool ComputeDisparitySad(const uint8_t* left, const uint8_t* right, int width, int height,
                         const BlockMatchConfig& cfg, std::vector<uint16_t>* out_disp_x8) {
  if (!left || !right || !out_disp_x8 || width <= 0 || height <= 0) return false;
  if (cfg.max_disparity <= cfg.min_disparity || cfg.block_radius < 0) return false;
  const size_t count = static_cast<size_t>(width) * height;
  out_disp_x8->assign(count, 0);

  for (int y = 0; y < height; ++y) {
    for (int x = 0; x < width; ++x) {
      int64_t best = 0, second = 0;
      int tex = 0;
      const int d = BestDisparity(left, right, width, height, x, y, cfg, &best, &second, &tex);
      if (d < 0) continue;
      // 纹理门:平坦区不可靠。
      if (tex < cfg.texture_min) continue;
      // 唯一性:best 不够显著优于 second → 歧义,判无效。
      if (second != std::numeric_limits<int64_t>::max() &&
          static_cast<double>(best) * (1.0 + cfg.uniqueness_ratio) >= static_cast<double>(second)) {
        continue;
      }
      // LR 一致性:right 视角反查回来的视差应与 d 接近。
      if (cfg.lr_consistency_max >= 0) {
        const int xr = x - d;
        if (xr < 0) continue;
        const int dr = BestDisparityRight(left, right, width, height, xr, y, cfg);
        if (dr < 0 || std::abs(dr - d) > cfg.lr_consistency_max) continue;
      }
      // 亚像素抛物线:用 cost(d-1),cost(d),cost(d+1) 顶点偏移。
      double sub = 0.0;
      if (cfg.subpixel && d > cfg.min_disparity && d + 1 < cfg.max_disparity) {
        const int64_t cm = SadAt(left, right, width, height, x, y, d - 1, cfg.block_radius, nullptr);
        const int64_t c0 = best;
        const int64_t cp = SadAt(left, right, width, height, x, y, d + 1, cfg.block_radius, nullptr);
        if (cm != std::numeric_limits<int64_t>::max() && cp != std::numeric_limits<int64_t>::max()) {
          const double denom = static_cast<double>(cm - 2 * c0 + cp);
          if (std::abs(denom) > 1e-6) {
            sub = 0.5 * static_cast<double>(cm - cp) / denom;
            if (sub < -1.0 || sub > 1.0) sub = 0.0;  // 异常顶点偏移弃用
          }
        }
      }
      const double real_disp = static_cast<double>(d) + sub;
      int disp_x8 = static_cast<int>(std::lround(real_disp * kStereoSubpixel));
      if (disp_x8 < 0) disp_x8 = 0;
      if (disp_x8 > 0xFFFF) disp_x8 = 0xFFFF;
      (*out_disp_x8)[static_cast<size_t>(y) * width + x] = static_cast<uint16_t>(disp_x8);
    }
  }
  return true;
}

bool SplitSideBySideYuyvToGray(const uint8_t* yuyv, int full_width, int height,
                               std::vector<uint8_t>* left_gray, std::vector<uint8_t>* right_gray) {
  if (!yuyv || !left_gray || !right_gray || full_width <= 0 || height <= 0 || (full_width & 1)) {
    return false;
  }
  const int half = full_width / 2;
  left_gray->resize(static_cast<size_t>(half) * height);
  right_gray->resize(static_cast<size_t>(half) * height);
  for (int y = 0; y < height; ++y) {
    const uint8_t* row = yuyv + static_cast<size_t>(y) * full_width * 2;  // 每像素 2 字节
    uint8_t* lg = left_gray->data() + static_cast<size_t>(y) * half;
    uint8_t* rg = right_gray->data() + static_cast<size_t>(y) * half;
    for (int x = 0; x < half; ++x) {
      lg[x] = row[x * 2];                       // 左半 Y
      rg[x] = row[(half + x) * 2];              // 右半 Y
    }
  }
  return true;
}

bool StereoDepthEngine::Compute(const uint8_t* left, const uint8_t* right, int width, int height,
                                std::vector<uint16_t>* out_mm) {
  if (!out_mm || !fin_.ready()) return false;
  if (!ComputeDisparitySad(left, right, width, height, cfg_, &disp_scratch_)) return false;
  return fin_.Finalize(disp_scratch_.data(), static_cast<uint16_t>(width),
                       static_cast<uint16_t>(height), *out_mm);
}

bool StereoDepthEngine::ComputeFromSideBySideYuyv(const uint8_t* yuyv, int full_width, int height,
                                                  std::vector<uint16_t>* out_mm) {
  if (!SplitSideBySideYuyvToGray(yuyv, full_width, height, &lg_scratch_, &rg_scratch_)) return false;
  return Compute(lg_scratch_.data(), rg_scratch_.data(), full_width / 2, height, out_mm);
}

}  // namespace gomob::eys3d
