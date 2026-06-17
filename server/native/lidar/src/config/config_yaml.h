// config.yaml / config150.yaml loader (yaml-cpp). Mirrors re/SPEC.md §7.1 glossary.
// Units: meters / radians (the new-pipeline convention). fixed_transform is read
// from YAML verbatim — never hard-coded, never trusting the YAML comment.
#pragma once

#include <array>
#include <string>
#include <vector>
#include <Eigen/Geometry>

namespace lidar {

struct PlaneConstraint {
  // config.yaml: planes[] = [a,b,c, cx,cy,cz, e0,e1,e2]  (9 floats; last 3 UNCERTAIN box l,w,h)
  Eigen::Vector3d normal{0, 0, 1};       // [a,b,c]
  Eigen::Vector3d point{0, 0, 0};        // on-plane point [cx,cy,cz]
  std::array<double, 3> extra{0, 0, 0};  // [e0,e1,e2] — meaning UNCERTAIN (R13)
  double d() const { return -normal.dot(point); }  // plane: n·X + d = 0
};

struct LidarCalibration {
  Eigen::Matrix4d fixed_transform{Eigen::Matrix4d::Identity()};  // axis -> device (read from YAML)
  Eigen::Vector3d prior_translation{0.02, 0.0, 0.02};
  Eigen::Vector3d init_translation{0, 0, 0};
  Eigen::Quaterniond init_quaternion{1, 0, 0, 0};  // normalized on load
  int    icp_max_iterations{5};
  double plane_max_distance{0.2};                  // meters
  std::vector<PlaneConstraint> planes;
};

struct DevicePose {
  Eigen::Vector3d translation{-3.5, -2.2, 0.0};       // device -> world (meters)
  Eigen::Quaterniond quaternion{0.5, 0.0, 0.0, 0.8};  // NOT unit in YAML -> normalized on load
};

struct CameraCalibration {
  Eigen::Matrix4d fixed_transform{Eigen::Matrix4d::Identity()};  // axis -> camera mount
  Eigen::Vector3d init_translation{0, 0, 0};
  Eigen::Quaterniond init_quaternion{1, 0, 0, 0};
  std::array<double, 4> init_intrinsics{0, 0, 0, 0};  // fx,fy,cx,cy
  std::array<double, 5> init_distortion{0, 0, 0, 0, 0};// k1,k2,p1,p2,k3 (OpenCV order)
  int    max_iterations{100};
  int    num_threads{18};
  double function_tolerance{1e-10};
};

struct TextureMapping {
  int    image_width{3840};
  int    image_height{2160};
  double fov_margin_ratio{0.7};       // NOTE: not applied by textureMap (R-glossary)
  double angle_threshold_extra{0.1};  // radians
  int    safe_pixel_margin{50};
};

struct DebugOptions {
  bool   save_intermediate_results{true};
  bool   save_transformed_cloud{true};
  double template_sample_ratio{1.0};  // random keep-ratio
  bool   enable_synthesis{true};
  bool   enable_texture_mapping{true};
  double synthesis_voxel{0.5};        // random keep-ratio (NOT a voxel leaf size)
  std::string output_dir{"./debug_output/"};
};

struct Config {
  LidarCalibration  lidar;
  DevicePose        device_pose;
  CameraCalibration camera;
  TextureMapping    texture;
  DebugOptions      debug;
};

// Load config.yaml / config150.yaml. Throws std::runtime_error on parse failure.
// Quaternions are normalized on load (re/SPEC.md §3.2). fixed_transform read verbatim.
Config loadConfig(const std::string& path);

}  // namespace lidar
