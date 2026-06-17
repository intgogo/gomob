#include "texture/colorizer.h"

#include <cmath>
#include <limits>

namespace lidar {
namespace {
// Rz(angle) applied to X,Y only (Z passthrough), matching the decompiled heading rotation.
Eigen::Vector3d rotZ(const Eigen::Vector3d& p, double angle) {
  const double c = std::cos(angle), s = std::sin(angle);
  return {p.x() * c - p.y() * s, p.x() * s + p.y() * c, p.z()};
}
}  // namespace

CameraModel CameraModel::fromConfig(const Config& c) {
  CameraModel m;
  m.t_dev = c.device_pose.translation;
  m.q_dev = c.device_pose.quaternion.normalized();
  m.T_fix_cam = c.camera.fixed_transform;
  m.t_cam = c.camera.init_translation;
  m.q_cam = c.camera.init_quaternion.normalized();
  m.intrinsic = c.camera.init_intrinsics;
  m.distortion = c.camera.init_distortion;
  m.image_width = c.texture.image_width;
  m.image_height = c.texture.image_height;
  m.safe_pixel_margin = c.texture.safe_pixel_margin;
  return m;
}

void CameraModel::applyCalibration(const CalibParams& cp) {
  // fixed_transform 已经承载 camera_rot_quat，q_cam 只应用真机 2° 精修量。
  q_cam  = cp.camera_corr_quat.normalized();
  t_cam  = cp.camera_corr_offset;
  q_dev  = cp.b2w_quat.normalized();
  t_dev  = cp.b2w_offset;
  intrinsic  = cp.camera_intrinsic;
  distortion = cp.camera_distortion;
}

Eigen::Vector3d worldToCamera(const Eigen::Vector3d& Pw, double a, const CameraModel& m) {
  Eigen::Vector3d d  = Pw - m.t_dev;
  Eigen::Vector3d Pq = m.q_dev.normalized().conjugate() * d;          // R(q_dev)^-1
  Eigen::Vector3d Pr = rotZ(Pq, -a);                                  // Rz(-a)
  Eigen::Vector3d Pm = (m.T_fix_cam * Pr.homogeneous()).head<3>();    // T_fix_cam (4x4)
  Eigen::Vector3d Pd = Pm - m.t_cam;
  return m.q_cam.normalized().conjugate() * Pd;                       // R(q_cam)^-1 = Pc
}

Eigen::Vector3d cameraToWorld(const Eigen::Vector3d& Pc, double a, const CameraModel& m) {
  Eigen::Vector3d Pd = m.q_cam.normalized() * Pc;                     // R(q_cam)
  Eigen::Vector3d Pm = Pd + m.t_cam;
  Eigen::Vector3d Pr = (m.T_fix_cam.inverse() * Pm.homogeneous()).head<3>();
  Eigen::Vector3d Pq = rotZ(Pr, +a);                                 // Rz(+a)
  Eigen::Vector3d d  = m.q_dev.normalized() * Pq;                    // R(q_dev)
  return d + m.t_dev;
}

bool projectToPixel(const Eigen::Vector3d& Pc, const CameraModel& m, double& u, double& v) {
  if (Pc.z() <= 0.0) return false;
  const double xn = Pc.x() / Pc.z(), yn = Pc.y() / Pc.z();
  const double k1 = m.distortion[0], k2 = m.distortion[1], p1 = m.distortion[2], p2 = m.distortion[3], k3 = m.distortion[4];
  const double r2 = xn * xn + yn * yn;
  const double radial = 1.0 + k1 * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2;
  const double xd = xn * radial + 2.0 * p1 * xn * yn + p2 * (r2 + 2.0 * xn * xn);
  const double yd = yn * radial + p1 * (r2 + 2.0 * yn * yn) + 2.0 * p2 * xn * yn;
  u = m.intrinsic[0] * xd + m.intrinsic[2];   // fx*xd + cx
  v = m.intrinsic[1] * yd + m.intrinsic[3];   // fy*yd + cy
  return true;
}

CloudXYZRGB::Ptr colorize(const CloudXYZ& cloud, const std::vector<CameraFrame>& frames,
                          const CameraModel& m, std::size_t* mapped) {
  auto out = std::make_shared<CloudXYZRGB>();
  out->points.reserve(cloud.size());
  const double fx = m.intrinsic[0], fy = m.intrinsic[1], cx = m.intrinsic[2], cy = m.intrinsic[3];
  const double halfW = m.image_width / (2.0 * fx);   // FOV cull bounds on normalized coords
  const double halfH = m.image_height / (2.0 * fy);
  const double diag = std::hypot(cx, cy);
  std::size_t nmapped = 0;

  for (const auto& sp : cloud.points) {
    PointXYZRGB q;
    q.x = sp.x; q.y = sp.y; q.z = sp.z;
    std::uint8_t r = 128, g = 128, b = 128;  // GRAY default for unmapped
    double best = std::numeric_limits<double>::max();

    for (const auto& fr : frames) {
      const Eigen::Vector3d Pc = worldToCamera(Eigen::Vector3d(sp.x, sp.y, sp.z), fr.heading_rad, m);
      if (Pc.z() <= m.near_plane) continue;
      const double xn = Pc.x() / Pc.z(), yn = Pc.y() / Pc.z();
      if (std::fabs(xn) > halfW || std::fabs(yn) > halfH) continue;   // FOV (image rectangle)
      double u, v;
      if (!projectToPixel(Pc, m, u, v)) continue;
      const int iu = static_cast<int>(std::lround(u)), iv = static_cast<int>(std::lround(v));
      if (iu < m.safe_pixel_margin || iu >= m.image_width - m.safe_pixel_margin ||
          iv < m.safe_pixel_margin || iv >= m.image_height - m.safe_pixel_margin) continue;
      if (fr.image_bgr.empty() || iv < 0 || iv >= fr.image_bgr.rows || iu < 0 || iu >= fr.image_bgr.cols) continue;
      const double score = Pc.z() / 10.0 + std::hypot(u - cx, v - cy) / diag;
      if (score < best) {
        best = score;
        const cv::Vec3b& px = fr.image_bgr.at<cv::Vec3b>(iv, iu);  // BGR
        b = px[0]; g = px[1]; r = px[2];
      }
    }
    if (best != std::numeric_limits<double>::max()) ++nmapped;
    q.r = r; q.g = g; q.b = b;
    out->points.push_back(q);
  }
  out->width = static_cast<std::uint32_t>(out->points.size());
  out->height = 1;
  out->is_dense = false;
  if (mapped) *mapped = nmapped;
  return out;
}

}  // namespace lidar
