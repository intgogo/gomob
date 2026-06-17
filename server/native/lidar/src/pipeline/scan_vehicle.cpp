#include "pipeline/scan_vehicle.h"

#include <chrono>
#include <ctime>
#include <filesystem>
#include <vector>

#include "cloud/fusion.h"
#include "cloud/io_pcd.h"
#include "cloud/legacy_calib3d.h"
#include "cloud/registration.h"

namespace fs = std::filesystem;

namespace lidar {
namespace {

// "yyyy-MM-dd" day folder + "clouds_yyyyMMdd_HHmmss_000" capture folder (matches the factory layout).
std::pair<std::string, std::string> sessionStamps() {
  using namespace std::chrono;
  const auto now = system_clock::now();
  const auto ms  = duration_cast<milliseconds>(now.time_since_epoch()) % 1000;
  const std::time_t t = system_clock::to_time_t(now);
  std::tm tm{};
  localtime_r(&t, &tm);
  char day[16], stamp[40];
  std::strftime(day, sizeof day, "%Y-%m-%d", &tm);
  char base[24];
  std::strftime(base, sizeof base, "%Y%m%d_%H%M%S", &tm);
  std::snprintf(stamp, sizeof stamp, "clouds_%s_%03lld", base, static_cast<long long>(ms.count()));
  return {day, stamp};
}

}  // namespace

ScanVehicleResult reconstructVehicle(const CloudXYZ& unitA, const CloudXYZ& unitB,
                                     const ScanVehicleParams& p) {
  ScanVehicleResult r;
  r.pts_a = unitA.size();
  r.pts_b = unitB.size();

  // --- 1. align unit-B into unit-A's frame ---
  CloudXYZ::Ptr bAligned;
  if (!p.site_extrinsic.empty() && loadSiteExtrinsic(p.site_extrinsic, r.b_to_a)) {
    bAligned = applyTransform(unitB, r.b_to_a);
    r.align_method = "site";
  } else if (p.use_icp) {
    auto reg = registerTwoUnits(unitB, unitA, p.icp_voxel, p.icp_maxcorr);
    r.b_to_a = reg.transform;
    bAligned = reg.converged ? applyTransform(unitB, reg.transform) : unitB.makeShared();
    r.align_method = reg.converged ? "icp" : "none";
  } else {
    bAligned = unitB.makeShared();
    r.align_method = "none";
  }

  // --- 2. fusion = plain point-set union (no ICP at fusion time) ---
  CloudXYZ::Ptr fused = fuse({unitA.makeShared(), CloudXYZ::ConstPtr(bAligned)});
  r.fused = fused->size();

  // --- 3. random keep-ratio downsample (synthesis_voxel) ---
  CloudXYZ::Ptr cloud = (p.keep_ratio < 1.0) ? randomKeep(*fused, p.keep_ratio) : fused;
  r.after_downsample = cloud->size();

  // --- 4. ROI crop (optional). Legacy [CALIB3D]+[PARAM] (mm) or a plain AABB in the cloud unit ---
  if (p.crop) {
    if (!p.setting_ini.empty()) {
      const LegacySettings s = loadLegacySettings(p.setting_ini);
      cloud = transformAndCrop(*cloud, s.calib, s.crop);   // input treated as mm device frame
    } else {
      cloud = cropBox(*cloud, p.crop_min, p.crop_max);
    }
  }
  r.after_crop = cloud->size();
  r.vehicle_bbox = bbox(*cloud);

  // --- 5. save deliverables in the factory session layout ---
  std::string dir = p.out_dir;
  if (p.write_factory_layout) {
    const auto [day, stamp] = sessionStamps();
    dir = (fs::path(p.out_dir) / p.sn / day / stamp).string();
  }
  fs::create_directories(dir);

  r.vehicle_pcd  = (fs::path(dir) / "clouds.pcd").string();
  r.points3d_txt = (fs::path(dir) / "points3D.txt").string();
  savePCDBinary(r.vehicle_pcd, *cloud);
  savePoints3DTxt(r.points3d_txt, *cloud, p.mm_scale);

  r.pointcloud_number_txt = (fs::path(p.out_dir) / "pointcloud_number.txt").string();
  appendPointcloudNumber(r.pointcloud_number_txt, cloud->size());

  r.session_dir = dir;
  return r;
}

}  // namespace lidar
