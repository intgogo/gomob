#include "calib/calibration_pipeline.h"

#include <ceres/ceres.h>

#include "calib/p2plane_functor.h"
#include "calib/reprojection_functor.h"

namespace lidar {
namespace {
// Eigen quaternion <-> ceres scalar-first array [w,x,y,z].
void toArr(const Eigen::Quaterniond& q, double a[4]) {
  Eigen::Quaterniond n = q.normalized();
  a[0] = n.w(); a[1] = n.x(); a[2] = n.y(); a[3] = n.z();
}
Eigen::Quaterniond fromArr(const double a[4]) { return Eigen::Quaterniond(a[0], a[1], a[2], a[3]).normalized(); }

// Ceres 2.x: QuaternionParameterization is deprecated for QuaternionManifold but both exist.
ceres::LocalParameterization* quatParam() { return new ceres::QuaternionParameterization(); }
}  // namespace

CameraCalibResult solveCameraCalibration(const std::vector<ReprojObservation>& obs, const CameraCalibInput& in) {
  double cam_t[3] = {in.cam_t.x(), in.cam_t.y(), in.cam_t.z()};
  double cam_q[4]; toArr(in.cam_q, cam_q);
  double intr[4] = {in.intrinsics[0], in.intrinsics[1], in.intrinsics[2], in.intrinsics[3]};
  double dist[5] = {in.distortion[0], in.distortion[1], in.distortion[2], in.distortion[3], in.distortion[4]};

  ceres::Problem problem;
  for (const auto& o : obs) {
    auto* cost = new ceres::AutoDiffCostFunction<ReprojectionFunctor, 2, 3, 4, 4, 5>(
        new ReprojectionFunctor(o.Pw, o.u, o.v, o.heading, in.t_dev, in.q_dev, in.T_fix_cam));
    problem.AddResidualBlock(cost, nullptr, cam_t, cam_q, intr, dist);
  }
  if (!obs.empty()) {
    problem.SetParameterization(cam_q, quatParam());
    if (in.fix_intrinsics) problem.SetParameterBlockConstant(intr);
    if (in.fix_distortion) problem.SetParameterBlockConstant(dist);
  }

  ceres::Solver::Options opt;
  opt.max_num_iterations = in.max_iterations;
  opt.num_threads = in.num_threads;
  opt.function_tolerance = in.function_tolerance;
  opt.linear_solver_type = ceres::DENSE_QR;
  opt.minimizer_progress_to_stdout = false;
  ceres::Solver::Summary summary;
  ceres::Solve(opt, &problem, &summary);

  CameraCalibResult r;
  r.cam_t = {cam_t[0], cam_t[1], cam_t[2]};
  r.cam_q = fromArr(cam_q);
  r.intrinsics = {intr[0], intr[1], intr[2], intr[3]};
  r.distortion = {dist[0], dist[1], dist[2], dist[3], dist[4]};
  r.final_cost = summary.final_cost;
  r.iterations = static_cast<int>(summary.iterations.size());
  r.converged = (summary.termination_type == ceres::CONVERGENCE);
  return r;
}

LidarCalibResult solveLidarCalibration(const std::vector<PlaneObservation>& obs, const LidarCalibInput& in) {
  double dev_t[3] = {in.dev_t.x(), in.dev_t.y(), in.dev_t.z()};
  double dev_q[4]; toArr(in.dev_q, dev_q);
  double lid_t[3] = {in.lid_t.x(), in.lid_t.y(), in.lid_t.z()};
  double lid_q[4]; toArr(in.lid_q, lid_q);
  double h_offset[1] = {in.h_offset};

  ceres::Problem problem;
  for (const auto& o : obs) {
    if (o.plane_id < 0 || o.plane_id >= static_cast<int>(in.planes.size())) continue;
    const auto& pl = in.planes[o.plane_id];
    auto* cost = new ceres::AutoDiffCostFunction<P2PlaneFunctor, 2, 3, 4, 3, 4, 1>(
        new P2PlaneFunctor(o.p_lidar, o.heading, in.M_fix, pl.plane, pl.center));
    problem.AddResidualBlock(cost, nullptr, dev_t, dev_q, lid_t, lid_q, h_offset);
  }
  if (problem.NumResidualBlocks() > 0) {
    problem.SetParameterization(dev_q, quatParam());
    problem.SetParameterization(lid_q, quatParam());
    if (in.fix_device) { problem.SetParameterBlockConstant(dev_t); problem.SetParameterBlockConstant(dev_q); }
  }

  ceres::Solver::Options opt;
  opt.max_num_iterations = in.max_iterations;
  opt.num_threads = in.num_threads;
  opt.function_tolerance = in.function_tolerance;
  opt.linear_solver_type = ceres::DENSE_QR;
  ceres::Solver::Summary summary;
  ceres::Solve(opt, &problem, &summary);

  LidarCalibResult r;
  r.dev_t = {dev_t[0], dev_t[1], dev_t[2]};
  r.dev_q = fromArr(dev_q);
  r.lid_t = {lid_t[0], lid_t[1], lid_t[2]};
  r.lid_q = fromArr(lid_q);
  r.h_offset = h_offset[0];
  r.final_cost = summary.final_cost;
  r.iterations = static_cast<int>(summary.iterations.size());
  r.converged = (summary.termination_type == ceres::CONVERGENCE);
  return r;
}

}  // namespace lidar
