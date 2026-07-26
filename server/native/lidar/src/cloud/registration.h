// Two-unit registration (R5): align unit-B's cloud into unit-A's frame via PCL ICP.
// Both devices report b2w=identity, so their clouds live in separate device frames; this
// recovers the inter-unit transform by registration. The two units scan opposite sides, so a
// multi-yaw coarse init (0/90/180/270° about Z + centroid match) precedes ICP for robustness.
#pragma once

#include <Eigen/Geometry>
#include "cloud/types.h"

namespace lidar {

struct RegistrationResult {
  Eigen::Matrix4d transform{Eigen::Matrix4d::Identity()};  // source -> target frame
  double fitness{1e9};      // ICP mean-squared correspondence distance (lower = better)
  bool   converged{false};
  int    best_yaw_deg{0};   // which coarse yaw init won
};

// Register `source` onto `target`. Downsamples to `voxel` (m) for the search, tries 4 yaw inits,
// returns the best transform (applies to the FULL source, not the downsampled one).
RegistrationResult registerTwoUnits(const CloudXYZ& source, const CloudXYZ& target,
                                    double voxel = 0.08, double max_corr = 1.0, int iters = 60);

// Apply a 4x4 transform to a copy of the cloud.
CloudXYZ::Ptr applyTransform(const CloudXYZ& cloud, const Eigen::Matrix4d& T);

// --- Stored site extrinsic (the production alternative to per-scan ICP) ---
// Persist/restore a frozen inter-unit transform (unit-B device frame -> unit-A device frame) as
// JSON {"b_to_a":[16 row-major floats]}. Computed ONCE via registerTwoUnits on a calibration scan,
// then reused so fusion stays a plain union with no ICP in the per-scan hot path (matches the
// factory's "no ICP at fusion" once a site calibration exists). Return false on IO/parse failure.
bool saveSiteExtrinsic(const std::string& path, const Eigen::Matrix4d& b_to_a);
bool loadSiteExtrinsic(const std::string& path, Eigen::Matrix4d& b_to_a);

}  // namespace lidar
