// STEP 4 — laser-line + axis-angle -> world point cloud (Stage-4 `synthesize`).
// Forward chain byte-verified from FUN_140049ad0 (re/spec_geometry_resolved.md §1):
//   P_world = R(q_b2w)·{ Rz(+h)·( T_fix_lidar·( R(q_lidar_rot)·P_line + lidar_corr_offset ) ) } + b2w_offset
//   P_line  = [cos(v)*r, sin(v)*r, 0],  r = dist*range_scale,  h = h_angle_deg*pi/180
// Quaternions Hamilton [w,x,y,z], normalized on use (re/SPEC.md §3.2). Metres.
#pragma once

#include <vector>
#include <Eigen/Geometry>
#include "cloud/types.h"
#include "config/config_yaml.h"
#include "config/calibration_json.h"
#include "device/scan_stream.h"

namespace lidar {

struct SynthesisParams {
  Eigen::Quaterniond q_lidar_rot{1, 0, 0, 0};                    // lidar -> axis (optimized; init from config)
  Eigen::Vector3d    lidar_corr_offset{0, 0, 0};                 // translation only (NO corr-quat in synth)
  Eigen::Matrix4d    T_fix_lidar{Eigen::Matrix4d::Identity()};  // axis->device, read from config YAML
  Eigen::Quaterniond q_b2w{1, 0, 0, 0};                          // device -> world
  Eigen::Vector3d    b2w_offset{0, 0, 0};                        // device -> world translation
  // U1 (HIGH, re/spec_geometry_resolved §1.5): dist comes from the parser already in metres
  // (raw_mm*0.001), so range_scale defaults to 1.0. The original applies struct[+0x250]; its value
  // is UNCERTAIN and finally pinned by replaying a raw LDR vs synthesized_final.pcd. Tunable here.
  double range_scale{1.0};

  // Pre-calibration init from config.yaml (uses init_quaternion / device_pose / fixed_transform).
  static SynthesisParams fromConfig(const Config& c);
  // Override with optimized calibration results (lidar_rot_quat, corr_offset, b2w_*).
  void applyCalibration(const CalibParams& cp);
};

// Single point: laser optical (v_angle, dist) at axis heading h_angle_deg -> world xyz.
Eigen::Vector3d lineToWorld(double v_angle_rad, double dist, double h_angle_deg, const SynthesisParams& p);

// Build a cloud from device-native LDR frames (polar). Each LdrPoint carries v_angle_deg + dist_m;
// each LdrFrame carries h_angle_deg (the axis sweep angle for that line).
CloudXYZ::Ptr  buildFromLDR(const std::vector<device::LdrFrame>& frames, const SynthesisParams& p);
CloudXYZI::Ptr buildFromLDRI(const std::vector<device::LdrFrame>& frames, const SynthesisParams& p);

}  // namespace lidar
