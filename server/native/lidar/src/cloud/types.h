// Common point-cloud / geometry typedefs for the LIDAR_PTZ Linux port.
// Convention (see re/SPEC.md §3): internal working unit = METERS; quaternions are
// Hamilton, scalar-first [w,x,y,z], unit (normalized on load), active (P' = R(q)·P).
#pragma once

#include <Eigen/Geometry>
#include <pcl/point_types.h>
#include <pcl/point_cloud.h>

namespace lidar {

using PointXYZ    = pcl::PointXYZ;
using PointXYZI   = pcl::PointXYZI;
using PointXYZRGB = pcl::PointXYZRGB;
using CloudXYZ    = pcl::PointCloud<PointXYZ>;
using CloudXYZI   = pcl::PointCloud<PointXYZI>;
using CloudXYZRGB = pcl::PointCloud<PointXYZRGB>;

// Axis-aligned bounding box helper (meters or mm — caller's unit).
struct Bbox {
  Eigen::Vector3d min{ Eigen::Vector3d::Constant( 1e300) };
  Eigen::Vector3d max{ Eigen::Vector3d::Constant(-1e300) };
  void expand(const Eigen::Vector3d& p) { min = min.cwiseMin(p); max = max.cwiseMax(p); }
  Eigen::Vector3d size()   const { return max - min; }
  Eigen::Vector3d center() const { return 0.5 * (min + max); }
  bool empty() const { return (min.array() > max.array()).any(); }
};

// Build a Hamilton, scalar-first, *normalized* quaternion from [w,x,y,z].
// (re/SPEC.md §3.2: device_pose.quaternion is NOT unit; always normalize on load.)
inline Eigen::Quaterniond quatWXYZ(double w, double x, double y, double z) {
  Eigen::Quaterniond q(w, x, y, z);
  q.normalize();
  return q;
}

}  // namespace lidar
