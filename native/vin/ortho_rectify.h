// 双相机正射图（orthophoto）几何 — depth(RS-D550) × RGB(HLSD8) → 平面正射纹理。
//
// ★ 这是 VINCreator「用深度把 RGB 几何校正成正射图」的核心：扫描面（车牌/钢架/平面）由深度反投影
//   拟合出主平面，再把【独立第二颗 RGB 相机】HLSD8 的真彩按平面几何重采样成 1:1 正射图。
//   与旧 vin_rectify.cpp（单相机 RGBD 占位桩）的关键差异：RGB 与 depth 是**两颗物理独立相机**，
//   需 RGB↔depth 外参（R|t）把平面点从 depth 系变换到 RGB 系再投影采样。R|t 来自双相机标定
//   （device-gated，见 docs/architecture/05；标定缺失时退化为 R=I,t=0 = 同相机假设，仅供调试）。
//
// 纯几何、纯逻辑（Eigen header-only，无 libusb/Android）：随主 .so 编，也随 host harness 编
// （tests/native_host/ortho_rectify_test.cpp 合成 RGBD 验证 scale/投影/外参处理）。
#pragma once

#include <cstdint>
#include <vector>

namespace gomob::vin {

// 正射相机配置。
struct OrthoConfig {
  float pixel_size_mm = 0.2f;   // 每正射像素的物理尺寸（mm/px）
  int out_w = 1024;             // 正射图宽
  int out_h = 512;              // 正射图高
  float plane_dist_thresh_mm = 3.0f;  // RANSAC 平面内点阈下限（实际取 max(此, 0.8%×中位深度)）
  int ransac_iter = 200;        // RANSAC 迭代
  float min_inlier_ratio = 0.5f;  // 低于此判平面拟合失败
  // 平面拟合 ROI（深度帧比例，中心区域）：只用图像中间部位深度拟合平面 + 定输出中心，
  // 避开背景/地面污染主平面，并让正射图对准中央目标（VIN 钢牌）。整幅传 1.0。
  float roi_cx = 0.5f;          // ROI 中心 x（占宽比例）
  float roi_cy = 0.5f;          // ROI 中心 y（占高比例）
  float roi_w = 0.6f;           // ROI 宽（占宽比例）
  float roi_h = 0.6f;           // ROI 高（占高比例）
};

// 主平面拟合结果（depth 相机系）：n·P + d = 0，n 朝向相机（单位向量）。
struct PlaneModel {
  float n[3] = {0, 0, 0};
  float d = 0;
  float rms_mm = 0;
  float inlier_ratio = 0;
  float centroid[3] = {0, 0, 0};  // 内点质心（正射网格中心锚点）
};

// 正射重采样结果。
struct OrthoResult {
  std::vector<uint8_t> rgb;   // out_w*out_h*3，未采样处为 0
  std::vector<uint8_t> mask;  // out_w*out_h，255=已采样 / 0=平面外或投影越界
  PlaneModel plane;
  int covered = 0;            // mask==255 的像素数
  int error_code = 0;         // 0=成功；1=平面拟合失败；2=无效输入
};

// 把 depth 反投影拟合主平面，再把 RGB 按平面几何重采样为正射图。
//   depth_mm[dw*dh] : depth 相机系 metric mm，0=无效。
//   k_depth[4]      : [fx,fy,cx,cy]（depth 内参，dw×dh 下）。
//   rgb[rw*rh*3]    : RGB888 源（HLSD8 真彩）。
//   k_rgb[4]        : [fx,fy,cx,cy]（RGB 内参，rw×rh 下）。
//   rt_rgb_from_depth[12]: P_rgb = R*P_depth + t，R 行优先 9 项 + t 3 项(mm)。
//                          单相机/未标定时传 R=I,t=0。
OrthoResult OrthoRectify(const uint16_t* depth_mm, int dw, int dh, const double k_depth[4],
                         const uint8_t* rgb, int rw, int rh, const double k_rgb[4],
                         const float rt_rgb_from_depth[12], const OrthoConfig& cfg);

}  // namespace gomob::vin
