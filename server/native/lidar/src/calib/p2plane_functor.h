// P2PlaneFunctor — lidar<->axis calibration residual (point-to-plane + soft tethers).
// AutoDiffCostFunction<…, 2, 3, 4, 3, 4, 1>: param blocks dev_t[3], dev_q[4](wxyz),
// lid_t[3], lid_q[4](wxyz), h_offset[1]. residual DIM 2 (re/spec_geometry_resolved §3.2):
//   P_world = R(dev_q)·{ Rz(heading·h_offset) · ( M_fix · ( R(lid_q)·p_lidar + lid_t ) ) } + dev_t
//   residual[0] = a*X + b*Y + c*Z + d                          (signed point-to-plane, metres)
//   residual[1] = 200·‖P_world − center‖² + 100·(h_offset − 1)²  (patch-pull + h_offset prior)
#pragma once

#include <ceres/rotation.h>
#include <Eigen/Geometry>

namespace lidar {

class P2PlaneFunctor {
 public:
  P2PlaneFunctor(const Eigen::Vector3d& p_lidar, double heading, const Eigen::Matrix4d& M_fix,
                 const Eigen::Vector4d& plane /*a,b,c,d*/, const Eigen::Vector3d& center)
      : p_lidar_(p_lidar), heading_(heading), M_fix_(M_fix), plane_(plane), center_(center) {}

  template <typename T>
  bool operator()(const T* dev_t, const T* dev_q, const T* lid_t, const T* lid_q, const T* h_offset,
                  T* residual) const {
    // P0 = R(lid_q) · p_lidar + lid_t
    T pl[3] = {T(p_lidar_.x()), T(p_lidar_.y()), T(p_lidar_.z())};
    T P0[3];
    ceres::QuaternionRotatePoint(lid_q, pl, P0);
    P0[0] += lid_t[0]; P0[1] += lid_t[1]; P0[2] += lid_t[2];

    // Pf = M_fix · [P0 ; 1]  (full 4x4)
    T Pf[3];
    for (int i = 0; i < 3; ++i)
      Pf[i] = T(M_fix_(i, 0)) * P0[0] + T(M_fix_(i, 1)) * P0[1] + T(M_fix_(i, 2)) * P0[2] + T(M_fix_(i, 3));

    // Rz(heading · h_offset) on X,Y.  Unqualified cos/sin -> ADL picks ceres::Jet overloads.
    using std::cos;
    using std::sin;
    const T ang = T(heading_) * h_offset[0];
    const T c = cos(ang), s = sin(ang);
    T Ph[3] = {Pf[0] * c - Pf[1] * s, Pf[0] * s + Pf[1] * c, Pf[2]};

    // P_world = R(dev_q) · Ph + dev_t
    T Pw[3];
    ceres::QuaternionRotatePoint(dev_q, Ph, Pw);
    Pw[0] += dev_t[0]; Pw[1] += dev_t[1]; Pw[2] += dev_t[2];

    // residual[0] = signed point-to-plane distance (n unit)
    residual[0] = T(plane_[0]) * Pw[0] + T(plane_[1]) * Pw[1] + T(plane_[2]) * Pw[2] + T(plane_[3]);
    // residual[1] = 200·‖Pw − center‖² + 100·(h_offset − 1)²
    const T dx = Pw[0] - T(center_.x()), dy = Pw[1] - T(center_.y()), dz = Pw[2] - T(center_.z());
    const T dh = h_offset[0] - T(1.0);
    residual[1] = T(200.0) * (dx * dx + dy * dy + dz * dz) + T(100.0) * dh * dh;
    return true;
  }

 private:
  Eigen::Vector3d p_lidar_;
  double heading_;
  Eigen::Matrix4d M_fix_;
  Eigen::Vector4d plane_;
  Eigen::Vector3d center_;
};

}  // namespace lidar
