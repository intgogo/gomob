#include "cloud/cloud_build.h"

#include <cmath>

namespace lidar {

SynthesisParams SynthesisParams::fromConfig(const Config& c) {
  SynthesisParams p;
  p.q_lidar_rot      = c.lidar.init_quaternion.normalized();   // pre-calib init
  p.lidar_corr_offset= c.lidar.init_translation;
  p.T_fix_lidar      = c.lidar.fixed_transform;
  p.q_b2w            = c.device_pose.quaternion.normalized();
  p.b2w_offset       = c.device_pose.translation;
  p.range_scale      = 1.0;
  return p;
}

void SynthesisParams::applyCalibration(const CalibParams& cp) {
  q_lidar_rot       = cp.lidar_rot_quat.normalized();
  lidar_corr_offset = cp.lidar_corr_offset;
  q_b2w             = cp.b2w_quat.normalized();
  b2w_offset        = cp.b2w_offset;
  // T_fix_lidar stays from config (axis->device mount, not optimized).
}

Eigen::Vector3d lineToWorld(double v_angle_rad, double dist, double h_angle_deg, const SynthesisParams& p) {
  const double r = dist * p.range_scale;
  Eigen::Vector3d P_line(std::cos(v_angle_rad) * r, std::sin(v_angle_rad) * r, 0.0);
  Eigen::Vector3d P = p.q_lidar_rot.normalized() * P_line;        // R(q_lidar_rot), active/forward
  P += p.lidar_corr_offset;                                       // translation only
  Eigen::Vector3d Pf = (p.T_fix_lidar * P.homogeneous()).head<3>();// full 4x4 fixed transform
  const double h = h_angle_deg * M_PI / 180.0;
  const double c = std::cos(h), s = std::sin(h);
  Eigen::Vector3d Ph(Pf.x() * c - Pf.y() * s,                     // Rz(+h), CCW about Z
                     Pf.x() * s + Pf.y() * c,
                     Pf.z());
  return p.q_b2w.normalized() * Ph + p.b2w_offset;                // R(q_b2w) + translate
}

CloudXYZ::Ptr buildFromLDR(const std::vector<device::LdrFrame>& frames, const SynthesisParams& p) {
  auto out = std::make_shared<CloudXYZ>();
  for (const auto& fr : frames) {
    for (const auto& pt : fr.points) {
      const Eigen::Vector3d w = lineToWorld(pt.v_angle_deg * M_PI / 180.0, pt.dist_m, fr.h_angle_deg, p);
      out->points.emplace_back(static_cast<float>(w.x()), static_cast<float>(w.y()), static_cast<float>(w.z()));
    }
  }
  out->width = static_cast<std::uint32_t>(out->points.size());
  out->height = 1;
  out->is_dense = false;
  return out;
}

CloudXYZI::Ptr buildFromLDRI(const std::vector<device::LdrFrame>& frames, const SynthesisParams& p) {
  auto out = std::make_shared<CloudXYZI>();
  for (const auto& fr : frames) {
    for (const auto& pt : fr.points) {
      const Eigen::Vector3d w = lineToWorld(pt.v_angle_deg * M_PI / 180.0, pt.dist_m, fr.h_angle_deg, p);
      PointXYZI q;
      q.x = static_cast<float>(w.x()); q.y = static_cast<float>(w.y()); q.z = static_cast<float>(w.z());
      q.intensity = static_cast<float>(pt.attr);
      out->points.push_back(q);
    }
  }
  out->width = static_cast<std::uint32_t>(out->points.size());
  out->height = 1;
  out->is_dense = false;
  return out;
}

}  // namespace lidar
