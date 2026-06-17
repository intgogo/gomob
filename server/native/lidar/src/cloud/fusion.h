// Cloud transform / fusion / downsample / crop (re/SPEC.md §4.2, spec_fusion_export).
// Fusion = transform each unit into the shared world frame, then a plain point-set
// UNION (no ICP at fusion time). Downsample = RANDOM keep-ratio (synthesis_voxel /
// template_sample_ratio), NOT a VoxelGrid leaf size. All transforms are inputs, so
// these are correct regardless of the forward-synthesis order (R1) resolution.
#pragma once

#include <vector>
#include <Eigen/Geometry>
#include "cloud/types.h"

namespace lidar {

// Rigid+scale device->world pose: P_world = scale * R(quat) * P + offset.
struct Pose {
  Eigen::Quaterniond quat{1, 0, 0, 0};   // normalized on use
  Eigen::Vector3d    offset{0, 0, 0};
  double             scale{1.0};
  Eigen::Affine3d affine() const {
    Eigen::Affine3d a = Eigen::Affine3d::Identity();
    a.linear() = scale * quat.normalized().toRotationMatrix();
    a.translation() = offset;
    return a;
  }
};

// Transform a copy of `in` by pose (P' = scale*R*P + offset).
CloudXYZ::Ptr transformCloud(const CloudXYZ& in, const Pose& pose);

// Union of clouds already in a common frame (PCL *A += *B). count(out) == sum(counts).
CloudXYZ::Ptr fuse(const std::vector<CloudXYZ::ConstPtr>& clouds);

// Random keep-ratio downsample: keep = round(N*ratio); ratio>=1 keeps all (RNG seeded
// for reproducibility). Matches debug.synthesis_voxel / debug.template_sample_ratio.
CloudXYZ::Ptr randomKeep(const CloudXYZ& in, double ratio, unsigned seed = 1);

// AABB crop (PCL CropBox), min/max in the cloud's unit. keep_inside=false removes inside.
CloudXYZ::Ptr cropBox(const CloudXYZ& in, const Eigen::Vector3d& mn, const Eigen::Vector3d& mx,
                      bool keep_inside = true);

}  // namespace lidar
