// eYs3D RS-D550 软件 stereo 深度(双路里的【软件兼】路径)。纯 portable,无 libusb / 无 OpenCV(自研)。
//
// 用途:硬件 ASIC 路径(IF2 视差)不可用 / 未配对 mode 时的 fallback。IF1 双目(1280×480 YUYV
// 并排 L/R,已近似矫正,见 finding_rsd550_open_sequence_decoded_2026-06-01)→ 自研 SAD block-matching
// → 视差(×8 亚像素)→ 复用 DepthFinalizer(ZD 表 / 几何)出 metric depthMm。
//
// 与硬件路径对称:两路最终都经 DepthFinalizer 出同一 depthMm 契约,上层无感。
// 设计取舍:
//  - SAD + 窗口:无 OpenCV 约束下最简稳。census/SGBM 后续按需(先把度量链路打通)。
//  - LR 一致性 + 唯一性比 + 亚像素抛物线:压错配/飞点,病态区直接判无效(0)给上层降噪。
//  - 输出视差单位 = 真视差 × 8(11bit 域),直接喂 ZdTable[disp] / 几何 Z=fx·B/(disp/8)。
//
// ★ 已知遗留(device-gated):右目内参/R/T 在 rectlog 被污染,真机矫正质量需明亮非周期纹理场景验
//   (TODO M6.5);此处算法在【已矫正】L/R 上正确,矫正本身留给标定层。
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

#include "eys3d_driver.h"  // gomob::eys3d::DepthFinalizer

namespace gomob::eys3d {

// 亚像素缩放:真视差 × kStereoSubpixel = 11bit 域视差(与 ZdTable 索引一致)。
constexpr int kStereoSubpixel = 8;

struct BlockMatchConfig {
  int min_disparity = 0;       // 搜索下界(真视差,像素)
  int max_disparity = 64;      // 搜索上界(不含);窗外 disp 不搜
  int block_radius = 3;        // SAD 窗口半径(窗 = (2r+1)²),典型 3 → 7×7
  // 唯一性:best 必须明显优于 second_best,否则判病态(歧义)。
  // 通过条件:best_cost * (1 + uniqueness_ratio) < second_best_cost。
  float uniqueness_ratio = 0.15f;
  int lr_consistency_max = 1;  // 左右一致性容差(像素);<0 关闭该检查
  bool subpixel = true;        // 抛物线亚像素细化
  uint8_t texture_min = 4;     // 窗口内灰度极差 < 此 → 无纹理判无效(避平坦区瞎匹配)
};

// 在【已矫正】灰度 L/R(同尺寸 width×height)上算视差。
// 输出 out_disp_x8(width×height 个 u16,= 真视差×8;无效/病态 = 0)。成功返回 true。
bool ComputeDisparitySad(const uint8_t* left, const uint8_t* right, int width, int height,
                         const BlockMatchConfig& cfg, std::vector<uint16_t>* out_disp_x8);

// 把并排 side-by-side YUYV 帧(full_width×height,左半 L 右半 R)拆成两张灰度(各 full_width/2 × height)。
// 取 YUYV 的 Y(每 2 字节一像素的第 0 字节)。full_width 必须为偶数。失败返回 false。
bool SplitSideBySideYuyvToGray(const uint8_t* yuyv, int full_width, int height,
                               std::vector<uint8_t>* left_gray, std::vector<uint8_t>* right_gray);

// 软件 stereo 深度引擎:block-matching + DepthFinalizer。与硬件路由对称的 depthMm 出口。
class StereoDepthEngine {
 public:
  StereoDepthEngine() = default;

  void SetConfig(const BlockMatchConfig& cfg) { cfg_ = cfg; }
  const BlockMatchConfig& config() const { return cfg_; }

  // 度量注入(与硬件路径共用 DepthFinalizer)。
  DepthFinalizer& finalizer() { return fin_; }
  const DepthFinalizer& finalizer() const { return fin_; }

  bool ready() const { return fin_.ready(); }

  // 已矫正灰度 L/R → metric depthMm(width×height u16, mm)。
  bool Compute(const uint8_t* left, const uint8_t* right, int width, int height,
               std::vector<uint16_t>* out_mm);

  // 便捷:从并排 YUYV 帧直接出 depthMm(内部拆 L/R 灰度)。
  bool ComputeFromSideBySideYuyv(const uint8_t* yuyv, int full_width, int height,
                                 std::vector<uint16_t>* out_mm);

 private:
  BlockMatchConfig cfg_;
  DepthFinalizer fin_;
  std::vector<uint16_t> disp_scratch_;
  std::vector<uint8_t> lg_scratch_, rg_scratch_;
};

}  // namespace gomob::eys3d
