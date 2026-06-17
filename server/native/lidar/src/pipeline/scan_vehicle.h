// STEP 10 — vehicle reconstruction orchestrator (the factory GUI capture pipeline, pipeline B).
// Reverse-engineered production flow (re/spec_fusion_export + the vehicle-recon RE pass):
//   per-unit world cloud  ->  align unit-B into unit-A's frame  ->  point-set UNION (no ICP at
//   fusion) ->  random keep-ratio downsample (synthesis_voxel)  ->  optional AABB crop to the
//   lane/vehicle volume (legacy mm [CALIB3D]+[PARAM]; zMin slices the floor — NO RANSAC)  ->
//   save .pcd + integer points3D.txt + append pointcloud_number.txt in the Data/<SN>/<date>/
//   clouds_<ts>/ session layout.
// The "reconstructed vehicle" deliverable is a POINT CLOUD only (no mesh / dimensions / contour —
// confirmed: 0 such writers in the binary).
//
// Live reality: both units report b2w=identity, so per-unit b2w does NOT register them. Inter-unit
// alignment therefore comes from a frozen site extrinsic (preferred) or ICP fallback (R5 stopgap).
// The USER triggers the scan; this code only consumes already-built per-unit clouds (capture is
// passive, in scan_stream/stream_capture).
#pragma once

#include <cstddef>
#include <string>
#include <Eigen/Geometry>
#include "cloud/types.h"

namespace lidar {

struct ScanVehicleParams {
  // --- inter-unit alignment B -> A (priority: site_extrinsic > use_icp > none) ---
  std::string site_extrinsic;          // load frozen 4x4 (registration::loadSiteExtrinsic) if set
  bool        use_icp{false};          // fallback: registerTwoUnits(B, A)
  double      icp_voxel{0.08}, icp_maxcorr{1.0};

  // --- downsample (factory debug.synthesis_voxel; random keep-ratio, NOT a leaf size) ---
  double keep_ratio{1.0};              // 0.5 in factory config.yaml; 1.0 = keep all

  // --- ROI crop (optional). Two modes: ---
  bool        crop{false};
  std::string setting_ini;             // legacy [CALIB3D]+[PARAM]: input treated as MM, affine+AABB
  Eigen::Vector3d crop_min{0, 0, 0};   // else (if crop && setting_ini empty) AABB in the cloud unit
  Eigen::Vector3d crop_max{0, 0, 0};

  // --- output ---
  std::string out_dir{"out_live/vehicle"};   // session root
  std::string sn{"UNKNOWN"};                  // for Data/<SN>/<date>/clouds_<ts>/
  bool        write_factory_layout{true};     // build the session dir tree + pointcloud_number.txt
  double      mm_scale{1000.0};               // metres -> mm for points3D.txt (1.0 if already mm)
};

struct ScanVehicleResult {
  std::size_t pts_a{0}, pts_b{0}, fused{0}, after_downsample{0}, after_crop{0};
  std::string vehicle_pcd, points3d_txt, session_dir, pointcloud_number_txt;
  Eigen::Matrix4d b_to_a{Eigen::Matrix4d::Identity()};
  std::string align_method{"none"};           // "site" | "icp" | "none"
  Bbox vehicle_bbox;                           // bbox of the final cloud (cloud unit)
};

// Reconstruct one vehicle capture from the two per-unit world clouds (each in its own device frame,
// metres unless a legacy mm setting.ini is used). Writes the deliverables and returns counts/paths.
ScanVehicleResult reconstructVehicle(const CloudXYZ& unitA, const CloudXYZ& unitB,
                                     const ScanVehicleParams& p);

}  // namespace lidar
