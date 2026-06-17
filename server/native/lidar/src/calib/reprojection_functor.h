// ReprojectionFunctor — camera<->axis + intrinsics + distortion calibration residual.
// AutoDiffCostFunction<…, 2, 3, 4, 4, 5>: param blocks cam_t[3], cam_q[4](wxyz),
// intrinsics[fx,fy,cx,cy], distortion[k1,k2,p1,p2,k3]. residual = (u_pred-u_obs, v_pred-v_obs).
// world->camera chain (re/spec_geometry_resolved §2/§3.1): the device-leg constants
// (Pw, q_dev, t_dev, heading, T_fix_cam) are captured; only cam_*/intr/dist are optimized.
#pragma once

#include <ceres/rotation.h>
#include <Eigen/Geometry>

namespace lidar {

class ReprojectionFunctor {
 public:
  ReprojectionFunctor(const Eigen::Vector3d& Pw, double u_obs, double v_obs, double heading,
                      const Eigen::Vector3d& t_dev, const Eigen::Quaterniond& q_dev,
                      const Eigen::Matrix4d& T_fix_cam)
      : u_obs_(u_obs), v_obs_(v_obs) {
    // Precompute the constant part: Pm = T_fix_cam · Rz(-heading) · R(q_dev)^-1 · (Pw - t_dev).
    Eigen::Vector3d d  = Pw - t_dev;
    Eigen::Vector3d Pq = q_dev.normalized().conjugate() * d;
    const double c = std::cos(-heading), s = std::sin(-heading);
    Eigen::Vector3d Pr(Pq.x() * c - Pq.y() * s, Pq.x() * s + Pq.y() * c, Pq.z());
    Pm_ = (T_fix_cam * Pr.homogeneous()).head<3>();
  }

  template <typename T>
  bool operator()(const T* cam_t, const T* cam_q, const T* intr, const T* dist, T* residual) const {
    // Pd = Pm - cam_t ;  Pc = R(cam_q)^-1 · Pd  (conjugate of the unit quaternion)
    T Pd[3] = {T(Pm_.x()) - cam_t[0], T(Pm_.y()) - cam_t[1], T(Pm_.z()) - cam_t[2]};
    T q_conj[4] = {cam_q[0], -cam_q[1], -cam_q[2], -cam_q[3]};
    T Pc[3];
    ceres::QuaternionRotatePoint(q_conj, Pd, Pc);

    const T xn = Pc[0] / Pc[2], yn = Pc[1] / Pc[2];
    const T k1 = dist[0], k2 = dist[1], p1 = dist[2], p2 = dist[3], k3 = dist[4];
    const T r2 = xn * xn + yn * yn;
    const T radial = T(1) + k1 * r2 + k2 * r2 * r2 + k3 * r2 * r2 * r2;
    const T xd = xn * radial + T(2) * p1 * xn * yn + p2 * (r2 + T(2) * xn * xn);
    const T yd = yn * radial + p1 * (r2 + T(2) * yn * yn) + T(2) * p2 * xn * yn;
    const T u = intr[0] * xd + intr[2];  // fx*xd + cx
    const T v = intr[1] * yd + intr[3];  // fy*yd + cy
    residual[0] = u - T(u_obs_);
    residual[1] = v - T(v_obs_);
    return true;
  }

 private:
  Eigen::Vector3d Pm_;
  double u_obs_, v_obs_;
};

}  // namespace lidar
