#include "cloud/registration.h"

#include <cmath>
#include <fstream>
#include <nlohmann/json.hpp>
#include <pcl/common/transforms.h>
#include <pcl/filters/voxel_grid.h>
#include <pcl/registration/icp.h>

namespace lidar {
namespace {

CloudXYZ::Ptr voxelDown(const CloudXYZ& in, double leaf) {
  auto out = std::make_shared<CloudXYZ>();
  pcl::VoxelGrid<PointXYZ> vg;
  vg.setInputCloud(in.makeShared());
  vg.setLeafSize(static_cast<float>(leaf), static_cast<float>(leaf), static_cast<float>(leaf));
  vg.filter(*out);
  return out;
}

Eigen::Vector4f centroidOf(const CloudXYZ& c) {
  Eigen::Vector4f ctr(0, 0, 0, 1);
  for (const auto& p : c.points) { ctr.x() += p.x; ctr.y() += p.y; ctr.z() += p.z; }
  if (!c.points.empty()) { ctr.x() /= c.size(); ctr.y() /= c.size(); ctr.z() /= c.size(); }
  return ctr;
}

}  // namespace

CloudXYZ::Ptr applyTransform(const CloudXYZ& cloud, const Eigen::Matrix4d& T) {
  auto out = std::make_shared<CloudXYZ>();
  pcl::transformPointCloud(cloud, *out, T.cast<float>());
  return out;
}

RegistrationResult registerTwoUnits(const CloudXYZ& source, const CloudXYZ& target,
                                    double voxel, double max_corr, int iters) {
  RegistrationResult best;
  if (source.empty() || target.empty()) return best;

  CloudXYZ::Ptr src_ds = voxelDown(source, voxel);
  CloudXYZ::Ptr tgt_ds = voxelDown(target, voxel);

  const Eigen::Vector4f cs = centroidOf(*src_ds);
  const Eigen::Vector4f ct = centroidOf(*tgt_ds);

  pcl::IterativeClosestPoint<PointXYZ, PointXYZ> icp;
  icp.setInputSource(src_ds);
  icp.setInputTarget(tgt_ds);
  icp.setMaximumIterations(iters);
  icp.setMaxCorrespondenceDistance(max_corr);
  icp.setTransformationEpsilon(1e-9);

  for (int yaw = 0; yaw < 360; yaw += 90) {
    // coarse init: rotate source by yaw about Z (about its centroid), then shift centroid->target.
    const double a = yaw * M_PI / 180.0, c = std::cos(a), s = std::sin(a);
    Eigen::Matrix4f R = Eigen::Matrix4f::Identity();
    R(0, 0) = c; R(0, 1) = -s; R(1, 0) = s; R(1, 1) = c;
    Eigen::Matrix4f Tc = Eigen::Matrix4f::Identity();  Tc.block<3, 1>(0, 3) = -cs.head<3>();
    Eigen::Matrix4f Tb = Eigen::Matrix4f::Identity();  Tb.block<3, 1>(0, 3) = ct.head<3>();
    Eigen::Matrix4f init = Tb * R * Tc;

    CloudXYZ aligned;
    icp.align(aligned, init);
    const double fit = icp.getFitnessScore();
    if (icp.hasConverged() && fit < best.fitness) {
      best.fitness = fit;
      best.transform = icp.getFinalTransformation().cast<double>();
      best.converged = true;
      best.best_yaw_deg = yaw;
    }
  }
  return best;
}

bool saveSiteExtrinsic(const std::string& path, const Eigen::Matrix4d& b_to_a) {
  nlohmann::json j;
  std::vector<double> m(16);
  for (int r = 0; r < 4; ++r)
    for (int c = 0; c < 4; ++c) m[r * 4 + c] = b_to_a(r, c);
  j["b_to_a"] = m;
  std::ofstream out(path);
  if (!out) return false;
  out << j.dump(2) << '\n';
  return static_cast<bool>(out);
}

bool loadSiteExtrinsic(const std::string& path, Eigen::Matrix4d& b_to_a) {
  std::ifstream in(path);
  if (!in) return false;
  try {
    nlohmann::json j; in >> j;
    const auto m = j.at("b_to_a").get<std::vector<double>>();
    if (m.size() != 16) return false;
    for (int r = 0; r < 4; ++r)
      for (int c = 0; c < 4; ++c) b_to_a(r, c) = m[r * 4 + c];
  } catch (const std::exception&) { return false; }
  return true;
}

}  // namespace lidar
