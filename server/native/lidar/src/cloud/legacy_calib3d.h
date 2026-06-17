// Legacy QtTrainScan geometry oracle (VALIDATED, re/SPEC.md §3.7).
// setting.ini [CALIB3D] = 3x4 affine  P_world = R·P_device + t  (t in mm),
// then [PARAM] AABB crop. Reproduces temp/laser3D_1.pcd from temp/points3D.txt.
// Used as the M1b cross-check that the affine direction is forward (not inverse).
// NOTE: this path is MILLIMETERS (legacy). The new pipeline works in meters.
#pragma once

#include <string>
#include <Eigen/Geometry>
#include "cloud/types.h"

namespace lidar {

struct LegacyCalib3D {
  Eigen::Matrix3d R{Eigen::Matrix3d::Identity()};  // device -> world rotation
  Eigen::Vector3d t{0, 0, 0};                       // device -> world translation (mm)
};

struct LegacyCropBox {
  // [PARAM] AABB in world/laser-3D frame (mm).
  double xMin{0}, xMax{7000}, yMin{250}, yMax{20000}, zMin{500}, zMax{4000};
  bool contains(const Eigen::Vector3d& p) const {
    return p.x() >= xMin && p.x() <= xMax && p.y() >= yMin && p.y() <= yMax &&
           p.z() >= zMin && p.z() <= zMax;
  }
};

// Parse setting.ini for [CALIB3D] (laser3D_r00..r23) and [PARAM] (xMin..zMax).
struct LegacySettings {
  LegacyCalib3D calib;
  LegacyCropBox crop;
};
LegacySettings loadLegacySettings(const std::string& settingIniPath);

// Apply P_world = R·p + t to every point, then crop by box. Input/output in mm.
CloudXYZ::Ptr transformAndCrop(const CloudXYZ& deviceFrame,
                               const LegacyCalib3D& calib,
                               const LegacyCropBox& crop);

}  // namespace lidar
