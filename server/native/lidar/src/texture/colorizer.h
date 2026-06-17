// STEP 6 — PointCloudColorizer: per-point best-frame projection coloring.
// world->camera chain byte-verified == reproj 9-step (re/spec_geometry_resolved §2):
//   Pc = R(q_cam)^-1 · ( T_fix_cam · [ Rz(-a) · R(q_dev)^-1·(Pw - t_dev) ; 1 ] - t_cam )
// then Brown-Conrady [k1,k2,p1,p2,k3] + pinhole. Quats Hamilton [w,x,y,z], normalized.
#pragma once

#include <array>
#include <cstddef>
#include <vector>
#include <Eigen/Geometry>
#include <opencv2/core.hpp>
#include "cloud/types.h"
#include "config/calibration_json.h"
#include "config/config_yaml.h"

namespace lidar {

struct CameraModel {
  Eigen::Vector3d    t_dev{0, 0, 0};  Eigen::Quaterniond q_dev{1, 0, 0, 0};  // device->world (b2w)
  Eigen::Matrix4d    T_fix_cam{Eigen::Matrix4d::Identity()};                  // axis->camera mount (YAML)
  Eigen::Vector3d    t_cam{0, 0, 0};  Eigen::Quaterniond q_cam{1, 0, 0, 0};  // camera fine correction
  std::array<double, 4> intrinsic{0, 0, 0, 0};     // fx,fy,cx,cy
  std::array<double, 5> distortion{0, 0, 0, 0, 0}; // k1,k2,p1,p2,k3 (OpenCV order)
  int    image_width{3840}, image_height{2160};
  int    safe_pixel_margin{50};
  double near_plane{0.1};

  static CameraModel fromConfig(const Config& c);
  void applyCalibration(const CalibParams& cp);  // q_cam=camera_corr_quat；camera_rot_quat 已在 fixed_transform 中体现
};

// world -> camera-optical point (heading a in radians).
Eigen::Vector3d worldToCamera(const Eigen::Vector3d& Pw, double heading_rad, const CameraModel& m);
// exact inverse (for tests/debug).
Eigen::Vector3d cameraToWorld(const Eigen::Vector3d& Pc, double heading_rad, const CameraModel& m);

// camera-optical point -> distorted pixel (u,v). Returns false if at/behind the focal plane.
bool projectToPixel(const Eigen::Vector3d& Pc, const CameraModel& m, double& u, double& v);

struct CameraFrame { cv::Mat image_bgr; double heading_rad{0}; };

// Colorize each point by the lowest-score visible frame; unmapped -> GRAY(128,128,128).
// score = Pc.z/10 + hypot(u-cx, v-cy)/hypot(cx, cy). Sets *mapped to the colored-point count.
CloudXYZRGB::Ptr colorize(const CloudXYZ& cloud, const std::vector<CameraFrame>& frames,
                          const CameraModel& m, std::size_t* mapped = nullptr);

}  // namespace lidar
