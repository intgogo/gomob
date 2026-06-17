// Calibration result IO (calibration_results.json / calibration.json).
// Models the two coexisting schemas in the original (re/SPEC.md §4.5, §7.2):
//   (A) "parameters{lidar,camera,body2world}" form — read by PointCloudColorizer
//       (loadJsonParams) and pushed to the device (update_calib_parameters).
//   (B) "report" form (saveCalibrationResultsToJson) — lidar_to_axis /
//       device_to_world / camera_to_axis nodes with quaternion_wxyz + translation.
// Quaternions are [w,x,y,z]; offsets assumed [x,y,z] (R15/U5 UNCERTAIN until a
// real JSON is dumped). All angles in the new-pipeline meters convention.
#pragma once

#include <array>
#include <string>
#include <Eigen/Geometry>

namespace lidar {

struct CalibParams {
  // lidar -> axis correction (P2Plane output)
  Eigen::Quaterniond lidar_rot_quat{1, 0, 0, 0};
  Eigen::Quaterniond lidar_corr_quat{1, 0, 0, 0};
  Eigen::Vector3d    lidar_corr_offset{0, 0, 0};
  // camera -> axis + intrinsics + distortion (Reprojection output)
  Eigen::Quaterniond camera_rot_quat{1, 0, 0, 0};
  Eigen::Quaterniond camera_corr_quat{1, 0, 0, 0};
  Eigen::Vector3d    camera_corr_offset{0, 0, 0};
  std::array<double, 4> camera_intrinsic{0, 0, 0, 0};    // fx,fy,cx,cy
  std::array<double, 5> camera_distortion{0, 0, 0, 0, 0};// k1,k2,p1,p2,k3
  // device -> world (body2world)
  Eigen::Quaterniond b2w_quat{1, 0, 0, 0};
  Eigen::Vector3d    b2w_offset{0, 0, 0};
  double             b2w_scale{1.0};
};

// Read form (A). Throws std::runtime_error if the file is unreadable or the
// "parameters" root node is missing/invalid. Enforces intrinsic==4, distortion==5.
CalibParams loadCalibrationJson(const std::string& path);

// Write form (A): {"parameters":{"lidar":{...},"camera":{...},"body2world":{...}}}.
bool saveCalibrationParamsJson(const std::string& path, const CalibParams& p);

// Write form (B): report layout with *_to_axis / device_to_world nodes.
bool saveCalibrationReportJson(const std::string& path, const CalibParams& p);

}  // namespace lidar
