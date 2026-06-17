#include "cloud/registration.h"

#include <cmath>
#include <fstream>
#include <nlohmann/json.hpp>
#include <pcl/common/transforms.h>
#include <pcl/filters/voxel_grid.h>
#include <pcl/kdtree/kdtree_flann.h>
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

// 把 src 经 T 变换后，对 tree(target) 求每点最近邻平方距离（>max_corr 的丢弃，不被非重叠区拖偏），
// 返回有效对应的均方距离。用同一度量在 init 与 refined 处对比，给 ICP 精修一个单调采纳判据。
double meanNearestSq(const CloudXYZ& src, const pcl::KdTreeFLANN<PointXYZ>& tree,
                     const Eigen::Matrix4d& T, double max_corr) {
  auto t = applyTransform(src, T);
  std::vector<int>   idx(1);
  std::vector<float> d2(1);
  const double cap = max_corr * max_corr;
  double      sum = 0;
  std::size_t cnt = 0;
  for (const auto& p : t->points) {
    if (tree.nearestKSearch(p, 1, idx, d2) > 0 && static_cast<double>(d2[0]) <= cap) {
      sum += d2[0];
      ++cnt;
    }
  }
  return cnt ? sum / static_cast<double>(cnt) : 1e18;
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

RegistrationResult refineRegistration(const CloudXYZ& source, const CloudXYZ& target,
                                      const Eigen::Matrix4d& init, bool& improved,
                                      double voxel, double max_corr, int iters) {
  improved = false;
  RegistrationResult r;
  r.transform = init;
  if (source.empty() || target.empty()) return r;

  CloudXYZ::Ptr src_ds = voxelDown(source, voxel);
  CloudXYZ::Ptr tgt_ds = voxelDown(target, voxel);
  if (src_ds->empty() || tgt_ds->empty()) return r;

  pcl::KdTreeFLANN<PointXYZ> tree;
  tree.setInputCloud(tgt_ds);
  const double f0 = meanNearestSq(*src_ds, tree, init, max_corr);  // 标记外参处基准残差(最终细阈下)

  pcl::IterativeClosestPoint<PointXYZ, PointXYZ> icp;
  icp.setInputSource(src_ds);
  icp.setInputTarget(tgt_ds);
  icp.setMaximumIterations(iters);
  icp.setTransformationEpsilon(1e-10);
  // 由粗到细 correspondence 距离：先用大阈值容忍标记外参的较大偏差，再逐步收紧锁定真实重叠。
  // 单一大阈值会让非重叠区的错误对应把对齐拖偏(实测真机发散)；递减阈值逐级排除外点。
  Eigen::Matrix4f T = init.cast<float>();
  bool converged = false;
  for (double mc : {4.0 * max_corr, 2.0 * max_corr, max_corr}) {
    icp.setMaxCorrespondenceDistance(mc);
    CloudXYZ aligned;
    icp.align(aligned, T);
    if (icp.hasConverged()) {
      T = icp.getFinalTransformation();
      converged = true;
    }
  }
  if (!converged) return r;

  const Eigen::Matrix4d T1 = T.cast<double>();
  const double          f1 = meanNearestSq(*src_ds, tree, T1, max_corr);
  r.fitness = f1;
  if (f1 < f0) {  // 单调采纳：只在确实降低残差时用精修结果，否则保留标记外参
    r.transform = T1;
    r.converged = true;
    improved    = true;
  }
  return r;
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
