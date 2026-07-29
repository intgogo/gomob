// ortho_rectify_test — 合成 RGBD 验证双相机正射图几何（host-only，纯计算）。
//
// 思路：造一个略微倾斜的平面，depth 相机与 RGB 相机有已知外参（基线 t）。RGB 纹理把【平面点的
// depth 系世界坐标 X,Y】线性编码进 R/G 通道。跑 OrthoRectify 后，每个正射像素采到的颜色应解回
// 它对应平面点的 (X,Y)。据此判定：平面拟合是否准、覆盖率、外参投影是否正确（中心像素解回质心）、
// 度量尺度是否正确（相邻正射像素解回的世界位移 ≈ pixel_size）。能捕获 scale 错、投影错、外参 t 漏用。
#include "vin/ortho_rectify.h"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

namespace {

constexpr int kDW = 240, kDH = 240;     // depth 分辨率
constexpr int kRW = 240, kRH = 240;     // RGB 分辨率
constexpr double kFx = 500, kFy = 500;  // 两相机同内参（简化）
constexpr double kCx = 120, kCy = 120;
constexpr double kZ0 = 600.0;           // 平面参考深度 mm
constexpr double kBaseline = 40.0;      // RGB 相对 depth 的基线 mm（+X）
constexpr double kEncRange = 160.0;     // 世界 X/Y 编码量程 mm（[-R,R]→[1,255]）

// 平面（depth 系）：n0·P = c0，过 (0,0,Z0)，略倾斜。
const double kN0[3] = {0.1, 0.05, -1.0};  // 未归一化，朝相机（z<0）
double g_n0n[3], g_c0;

void NormalizePlane() {
  const double m = std::sqrt(kN0[0] * kN0[0] + kN0[1] * kN0[1] + kN0[2] * kN0[2]);
  for (int i = 0; i < 3; ++i) g_n0n[i] = kN0[i] / m;
  // 过 (0,0,Z0)：c0 = n·P0
  g_c0 = g_n0n[2] * kZ0;
}

inline double Dot(const double a[3], double x, double y, double z) {
  return a[0] * x + a[1] * y + a[2] * z;
}

void EncodeXY(double x, double y, uint8_t out[3]) {
  auto enc = [](double v) {
    double t = 128.0 + v / kEncRange * 127.0;
    if (t < 0) t = 0;
    if (t > 255) t = 255;
    return static_cast<uint8_t>(std::lround(t));
  };
  out[0] = enc(x);
  out[1] = enc(y);
  out[2] = 100;  // 常量标记通道
}

void DecodeXY(const uint8_t* c, double& x, double& y) {
  x = (c[0] - 128.0) / 127.0 * kEncRange;
  y = (c[1] - 128.0) / 127.0 * kEncRange;
}

// 渲染合成 depth：每个 depth 像素射线交平面 → Z=mm。
std::vector<uint16_t> RenderDepth() {
  std::vector<uint16_t> d(kDW * kDH, 0);
  for (int v = 0; v < kDH; ++v) {
    for (int u = 0; u < kDW; ++u) {
      const double dx = (u - kCx) / kFx, dy = (v - kCy) / kFy, dz = 1.0;
      const double denom = Dot(g_n0n, dx, dy, dz);
      if (std::abs(denom) < 1e-9) continue;
      const double s = g_c0 / denom;  // n·(s*dir)=c0
      if (s <= 0) continue;
      const double Z = s * dz;
      d[v * kDW + u] = static_cast<uint16_t>(std::lround(Z));
    }
  }
  return d;
}

// 渲染合成 RGB：RGB 相机射线（depth 系原点 = -t，方向 = dir）交平面 → 取该平面点的世界 X,Y 编码。
std::vector<uint8_t> RenderRgb() {
  std::vector<uint8_t> img(kRW * kRH * 3, 0);
  const double ox = -kBaseline, oy = 0, oz = 0;  // RGB 相机中心在 depth 系（R=I）
  for (int v = 0; v < kRH; ++v) {
    for (int u = 0; u < kRW; ++u) {
      const double dx = (u - kCx) / kFx, dy = (v - kCy) / kFy, dz = 1.0;
      // 交平面：n·(O + s*dir) = c0
      const double nd = Dot(g_n0n, dx, dy, dz);
      const double no = Dot(g_n0n, ox, oy, oz);
      if (std::abs(nd) < 1e-9) continue;
      const double s = (g_c0 - no) / nd;
      if (s <= 0) continue;
      const double Px = ox + s * dx, Py = oy + s * dy;
      uint8_t col[3];
      EncodeXY(Px, Py, col);
      img[(v * kRW + u) * 3 + 0] = col[0];
      img[(v * kRW + u) * 3 + 1] = col[1];
      img[(v * kRW + u) * 3 + 2] = col[2];
    }
  }
  return img;
}

int g_failures = 0;
void Check(bool cond, const char* msg, double val) {
  std::printf("  [%s] %s = %.4f\n", cond ? "OK" : "FAIL", msg, val);
  if (!cond) ++g_failures;
}

}  // namespace

int main() {
  std::printf("=== ortho_rectify_test (合成 RGBD 双相机正射) ===\n");
  NormalizePlane();
  const auto depth = RenderDepth();
  const auto rgb = RenderRgb();

  const double k_depth[4] = {kFx, kFy, kCx, kCy};
  const double k_rgb[4] = {kFx, kFy, kCx, kCy};
  // R=I, t=(baseline,0,0)：P_rgb = P_depth + t（RGB 中心在 depth 系 -t 处）。
  const float rt[12] = {1, 0, 0, 0, 1, 0, 0, 0, 1, static_cast<float>(kBaseline), 0, 0};

  gomob::vin::OrthoConfig cfg;
  cfg.pixel_size_mm = 1.0f;
  cfg.out_w = 256;
  cfg.out_h = 256;
  cfg.plane_dist_thresh_mm = 2.0f;
  cfg.ransac_iter = 200;

  const auto res = gomob::vin::OrthoRectify(depth.data(), kDW, kDH, k_depth, rgb.data(), kRW, kRH,
                                            k_rgb, rt, cfg);

  Check(res.error_code == 0, "error_code(0=ok)", res.error_code);
  if (res.error_code != 0) { std::printf("OrthoRectify 失败，提前退出\n"); return 1; }

  // ① 平面拟合：法向接近 n0、内点率高、残差小。
  const double ndot = std::abs(res.plane.n[0] * g_n0n[0] + res.plane.n[1] * g_n0n[1] +
                               res.plane.n[2] * g_n0n[2]);
  Check(ndot > 0.999, "|n·n0|(法向一致)", ndot);
  Check(res.plane.inlier_ratio > 0.95, "inlier_ratio", res.plane.inlier_ratio);
  Check(res.plane.rms_mm < 2.0, "rms_mm", res.plane.rms_mm);

  // ② 覆盖率。
  const double coverage = static_cast<double>(res.covered) / (cfg.out_w * cfg.out_h);
  Check(coverage > 0.80, "coverage", coverage);

  // ③ 中心像素解回质心世界 (X,Y)（验证外参投影 + 采样正确；t 漏用会偏 ~baseline 视差）。
  const int ci = cfg.out_w / 2, cj = cfg.out_h / 2;
  const size_t co = (static_cast<size_t>(cj) * cfg.out_w + ci);
  assert(res.mask[co] == 255);
  double cx_dec, cy_dec;
  DecodeXY(&res.rgb[co * 3], cx_dec, cy_dec);
  const double cerr = std::hypot(cx_dec - res.plane.centroid[0], cy_dec - res.plane.centroid[1]);
  Check(cerr < 6.0, "center 解码-质心误差 mm", cerr);

  // ④ 度量尺度：中心 → 右移 DI 像素，解回世界位移 ≈ DI*pixel_size。
  const int DI = 40;
  const size_t ro = (static_cast<size_t>(cj) * cfg.out_w + (ci + DI));
  const size_t uo = (static_cast<size_t>(cj - DI) * cfg.out_w + ci);
  assert(res.mask[ro] == 255 && res.mask[uo] == 255);
  double rx, ry, ux, uy;
  DecodeXY(&res.rgb[ro * 3], rx, ry);
  DecodeXY(&res.rgb[uo * 3], ux, uy);
  const double dist_right = std::hypot(rx - cx_dec, ry - cy_dec);
  const double dist_up = std::hypot(ux - cx_dec, uy - cy_dec);
  const double expect = DI * cfg.pixel_size_mm;  // 40mm
  Check(std::abs(dist_right - expect) / expect < 0.20, "右向尺度比(应~1)", dist_right / expect);
  Check(std::abs(dist_up - expect) / expect < 0.20, "上向尺度比(应~1)", dist_up / expect);

  std::printf("平面 n=(%.3f,%.3f,%.3f) centroid=(%.1f,%.1f,%.1f) covered=%d/%d\n", res.plane.n[0],
              res.plane.n[1], res.plane.n[2], res.plane.centroid[0], res.plane.centroid[1],
              res.plane.centroid[2], res.covered, cfg.out_w * cfg.out_h);
  std::printf(g_failures == 0 ? "=== PASS ===\n" : "=== FAIL (%d) ===\n", g_failures);
  return g_failures == 0 ? 0 : 1;
}
