// 双相机正射图几何实现 — 见 ortho_rectify.h 头注。
#include "vin/ortho_rectify.h"

#include <Eigen/Dense>

#include <cmath>
#include <random>

namespace gomob::vin {

namespace {

using Eigen::Vector3d;

// depth 像素 (u,v,Z) → depth 相机系 3D 点（mm）。
inline Vector3d Backproject(int u, int v, double z, const double k[4]) {
  return Vector3d((u - k[2]) / k[0] * z, (v - k[3]) / k[1] * z, z);
}

// 双线性采样 RGB888（越界返回 false）。
inline bool SampleBilinear(const uint8_t* rgb, int rw, int rh, double x, double y, uint8_t out[3]) {
  if (x < 0 || y < 0 || x > rw - 1 || y > rh - 1) return false;
  const int x0 = static_cast<int>(std::floor(x));
  const int y0 = static_cast<int>(std::floor(y));
  const int x1 = std::min(x0 + 1, rw - 1);
  const int y1 = std::min(y0 + 1, rh - 1);
  const double fx = x - x0, fy = y - y0;
  for (int c = 0; c < 3; ++c) {
    const double v00 = rgb[(y0 * rw + x0) * 3 + c];
    const double v10 = rgb[(y0 * rw + x1) * 3 + c];
    const double v01 = rgb[(y1 * rw + x0) * 3 + c];
    const double v11 = rgb[(y1 * rw + x1) * 3 + c];
    const double v = v00 * (1 - fx) * (1 - fy) + v10 * fx * (1 - fy) +
                     v01 * (1 - fx) * fy + v11 * fx * fy;
    out[c] = static_cast<uint8_t>(std::lround(v));
  }
  return true;
}

// 一组点最小二乘拟合平面（SVD）：返回单位法向 + d，使 n·P+d=0。
void FitPlaneLS(const std::vector<Vector3d>& pts, Vector3d& n, double& d, Vector3d& centroid) {
  centroid.setZero();
  for (const auto& p : pts) centroid += p;
  centroid /= static_cast<double>(pts.size());
  Eigen::Matrix3d cov = Eigen::Matrix3d::Zero();
  for (const auto& p : pts) {
    const Vector3d q = p - centroid;
    cov += q * q.transpose();
  }
  Eigen::SelfAdjointEigenSolver<Eigen::Matrix3d> es(cov);
  n = es.eigenvectors().col(0);  // 最小特征值对应方向 = 法向
  n.normalize();
  d = -n.dot(centroid);
}

}  // namespace

OrthoResult OrthoRectify(const uint16_t* depth_mm, int dw, int dh, const double k_depth[4],
                         const uint8_t* rgb, int rw, int rh, const double k_rgb[4],
                         const float rt_rgb_from_depth[12], const OrthoConfig& cfg) {
  OrthoResult res;
  if (!depth_mm || !rgb || dw <= 0 || dh <= 0 || rw <= 0 || rh <= 0 || cfg.out_w <= 0 ||
      cfg.out_h <= 0) {
    res.error_code = 2;
    return res;
  }

  // ① 反投影所有有效深度像素到 depth 系 3D 点。
  std::vector<Vector3d> pts;
  pts.reserve(static_cast<size_t>(dw) * dh / 2);
  for (int v = 0; v < dh; ++v) {
    for (int u = 0; u < dw; ++u) {
      const uint16_t z = depth_mm[v * dw + u];
      if (z == 0) continue;
      pts.push_back(Backproject(u, v, z, k_depth));
    }
  }
  if (pts.size() < 16) { res.error_code = 1; return res; }

  // ② RANSAC 主平面（固定种子，host 可复现）。
  std::mt19937 rng(12345u);
  std::uniform_int_distribution<size_t> pick(0, pts.size() - 1);
  const double thr = cfg.plane_dist_thresh_mm;
  Vector3d best_n(0, 0, 1);
  double best_d = 0;
  size_t best_inliers = 0;
  for (int it = 0; it < cfg.ransac_iter; ++it) {
    const Vector3d& a = pts[pick(rng)];
    const Vector3d& b = pts[pick(rng)];
    const Vector3d& c = pts[pick(rng)];
    Vector3d n = (b - a).cross(c - a);
    if (n.norm() < 1e-6) continue;
    n.normalize();
    const double d = -n.dot(a);
    size_t inliers = 0;
    for (const auto& p : pts)
      if (std::abs(n.dot(p) + d) <= thr) ++inliers;
    if (inliers > best_inliers) {
      best_inliers = inliers;
      best_n = n;
      best_d = d;
    }
  }
  const double inlier_ratio = static_cast<double>(best_inliers) / pts.size();
  if (inlier_ratio < cfg.min_inlier_ratio) { res.error_code = 1; return res; }

  // ③ 用内点最小二乘精修平面。
  std::vector<Vector3d> inliers;
  inliers.reserve(best_inliers);
  for (const auto& p : pts)
    if (std::abs(best_n.dot(p) + best_d) <= thr) inliers.push_back(p);
  Vector3d n, centroid;
  double d;
  FitPlaneLS(inliers, n, d, centroid);

  // 法向朝相机（相机在原点）：n·centroid>0 说明 n 背离相机，翻转。
  if (n.dot(centroid) > 0) { n = -n; d = -d; }

  double sum_sq = 0;
  for (const auto& p : inliers) {
    const double r = n.dot(p) + d;
    sum_sq += r * r;
  }
  const double rms = std::sqrt(sum_sq / inliers.size());

  // ④ 构造平面内正交基（right/up）。up = 相机 Y 在平面内的投影；right = up×n。
  Vector3d cam_up(0, 1, 0);
  Vector3d up = cam_up - cam_up.dot(n) * n;
  if (up.norm() < 1e-4) {  // 退化（平面法向 ∥ 相机 Y）：改用相机 X
    Vector3d cam_x(1, 0, 0);
    up = cam_x - cam_x.dot(n) * n;
  }
  up.normalize();
  Vector3d right = up.cross(n);
  right.normalize();

  // ⑤ RGB↔depth 外参。
  Eigen::Matrix3d R;
  R << rt_rgb_from_depth[0], rt_rgb_from_depth[1], rt_rgb_from_depth[2],
       rt_rgb_from_depth[3], rt_rgb_from_depth[4], rt_rgb_from_depth[5],
       rt_rgb_from_depth[6], rt_rgb_from_depth[7], rt_rgb_from_depth[8];
  Vector3d t(rt_rgb_from_depth[9], rt_rgb_from_depth[10], rt_rgb_from_depth[11]);

  // ⑥ 逐正射像素：网格点 Q（平面上）→ 变到 RGB 系 → 投影采样。
  res.rgb.assign(static_cast<size_t>(cfg.out_w) * cfg.out_h * 3, 0);
  res.mask.assign(static_cast<size_t>(cfg.out_w) * cfg.out_h, 0);
  const double px = cfg.pixel_size_mm;
  const double half_w = cfg.out_w * 0.5;
  const double half_h = cfg.out_h * 0.5;
  int covered = 0;
  for (int j = 0; j < cfg.out_h; ++j) {
    const double dy = (half_h - 0.5 - j) * px;  // 行 0 = 顶部（+up）
    for (int i = 0; i < cfg.out_w; ++i) {
      const double dx = (i + 0.5 - half_w) * px;  // 列 0 = 左（-right）
      const Vector3d Q = centroid + dx * right + dy * up;  // depth 系平面点
      const Vector3d Qr = R * Q + t;                       // RGB 系
      if (Qr.z() <= 1e-3) continue;
      const double su = k_rgb[0] * Qr.x() / Qr.z() + k_rgb[2];
      const double sv = k_rgb[1] * Qr.y() / Qr.z() + k_rgb[3];
      uint8_t col[3];
      if (!SampleBilinear(rgb, rw, rh, su, sv, col)) continue;
      const size_t o = (static_cast<size_t>(j) * cfg.out_w + i);
      res.rgb[o * 3 + 0] = col[0];
      res.rgb[o * 3 + 1] = col[1];
      res.rgb[o * 3 + 2] = col[2];
      res.mask[o] = 255;
      ++covered;
    }
  }

  res.plane.n[0] = static_cast<float>(n.x());
  res.plane.n[1] = static_cast<float>(n.y());
  res.plane.n[2] = static_cast<float>(n.z());
  res.plane.d = static_cast<float>(d);
  res.plane.rms_mm = static_cast<float>(rms);
  res.plane.inlier_ratio = static_cast<float>(inlier_ratio);
  res.plane.centroid[0] = static_cast<float>(centroid.x());
  res.plane.centroid[1] = static_cast<float>(centroid.y());
  res.plane.centroid[2] = static_cast<float>(centroid.z());
  res.covered = covered;
  res.error_code = 0;
  return res;
}

}  // namespace gomob::vin
