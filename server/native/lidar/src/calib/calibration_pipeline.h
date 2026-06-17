// STEP 5 — CalibrationPipeline: the two Ceres solves (camera<->axis, lidar<->axis).
// Inputs are observations (2D-3D for camera, point+plane for lidar) so the solves are
// unit-testable with synthetic data (known-params recovery). See re/spec_geometry_resolved §3.
#pragma once

#include <array>
#include <vector>
#include <Eigen/Geometry>

namespace lidar {

// ---------------- Camera (ReprojectionFunctor) ----------------
struct ReprojObservation {
  Eigen::Vector3d Pw;   // tag world position
  double u{0}, v{0};    // observed pixel
  double heading{0};    // radians
};

struct CameraCalibInput {
  // fixed/captured device leg:
  Eigen::Vector3d    t_dev{0, 0, 0};
  Eigen::Quaterniond q_dev{1, 0, 0, 0};
  Eigen::Matrix4d    T_fix_cam{Eigen::Matrix4d::Identity()};
  // optimized (init values):
  Eigen::Vector3d       cam_t{0, 0, 0};
  Eigen::Quaterniond    cam_q{1, 0, 0, 0};
  std::array<double, 4> intrinsics{0, 0, 0, 0};
  std::array<double, 5> distortion{0, 0, 0, 0, 0};
  bool fix_intrinsics{false}, fix_distortion{false};
  int    max_iterations{100}, num_threads{1};
  double function_tolerance{1e-10};
};

struct CameraCalibResult {
  Eigen::Vector3d       cam_t;
  Eigen::Quaterniond    cam_q;
  std::array<double, 4> intrinsics;
  std::array<double, 5> distortion;
  double final_cost{0}; int iterations{0}; bool converged{false};
};

CameraCalibResult solveCameraCalibration(const std::vector<ReprojObservation>& obs, const CameraCalibInput& in);

// ---------------- Lidar (P2PlaneFunctor) ----------------
struct PlaneDef { Eigen::Vector4d plane{0, 0, 1, 0}; Eigen::Vector3d center{0, 0, 0}; };  // a,b,c,d + patch center
struct PlaneObservation { Eigen::Vector3d p_lidar; double heading{0}; int plane_id{0}; };

struct LidarCalibInput {
  Eigen::Matrix4d       M_fix{Eigen::Matrix4d::Identity()};
  Eigen::Vector3d       dev_t{0, 0, 0};  Eigen::Quaterniond dev_q{1, 0, 0, 0};
  Eigen::Vector3d       lid_t{0, 0, 0};  Eigen::Quaterniond lid_q{1, 0, 0, 0};
  double                h_offset{1.0};
  std::vector<PlaneDef> planes;
  bool   fix_device{false};  // hold the device->world pose constant
  int    max_iterations{50}, num_threads{1};
  double function_tolerance{1e-10};
};

struct LidarCalibResult {
  Eigen::Vector3d    dev_t;  Eigen::Quaterniond dev_q;
  Eigen::Vector3d    lid_t;  Eigen::Quaterniond lid_q;
  double             h_offset{1.0};
  double final_cost{0}; int iterations{0}; bool converged{false};
};

LidarCalibResult solveLidarCalibration(const std::vector<PlaneObservation>& obs, const LidarCalibInput& in);

}  // namespace lidar
