// 两单元全自动全局配准 harness（M9.11）：clean(SOR) → FPFH+SAC-IA 粗配(无初值,治翻转) → ICP 精配
// → 报融合一致性。用 job19 真机 unit_a/unit_b（mm PCD，内部转米）验证：现有 registerTwoUnits 只做
// yaw-about-Z 粗init，遇两单元 ~180° 翻转必发散；全局配准能否恢复正确 B→A、让两云重叠。
#include <cstdio>
#include <pcl/features/fpfh.h>
#include <pcl/features/normal_3d.h>
#include <pcl/filters/statistical_outlier_removal.h>
#include <pcl/filters/voxel_grid.h>
#include <pcl/registration/ia_ransac.h>
#include <pcl/registration/icp.h>
#include <pcl/search/kdtree.h>
#include "cloud/io_pcd.h"
#include "cloud/registration.h"
#include "cloud/types.h"

using namespace lidar;
using NormalCloud = pcl::PointCloud<pcl::Normal>;
using FPFHCloud = pcl::PointCloud<pcl::FPFHSignature33>;

static CloudXYZ::Ptr toMeters(const CloudXYZ& in) {
  auto o = std::make_shared<CloudXYZ>();
  o->points.reserve(in.size());
  for (const auto& p : in.points) o->points.emplace_back(p.x * 1e-3f, p.y * 1e-3f, p.z * 1e-3f);
  o->width = o->points.size(); o->height = 1;
  return o;
}
static void rep(const char* tag, const CloudXYZ& c) {
  auto s = bbox(c).size();
  std::printf("  %-10s n=%-7zu size m=(%.2f, %.2f, %.2f)\n", tag, c.size(), s.x(), s.y(), s.z());
}
static CloudXYZ::Ptr sor(CloudXYZ::Ptr in, int k = 20, double sd = 2.0) {
  auto o = std::make_shared<CloudXYZ>();
  pcl::StatisticalOutlierRemoval<PointXYZ> f;
  f.setInputCloud(in); f.setMeanK(k); f.setStddevMulThresh(sd); f.filter(*o);
  return o;
}
static CloudXYZ::Ptr down(CloudXYZ::Ptr in, float leaf) {
  auto o = std::make_shared<CloudXYZ>();
  pcl::VoxelGrid<PointXYZ> v; v.setInputCloud(in); v.setLeafSize(leaf, leaf, leaf); v.filter(*o);
  return o;
}
static NormalCloud::Ptr normals(CloudXYZ::Ptr c, double r) {
  auto n = std::make_shared<NormalCloud>();
  pcl::NormalEstimation<PointXYZ, pcl::Normal> ne;
  ne.setInputCloud(c); ne.setSearchMethod(std::make_shared<pcl::search::KdTree<PointXYZ>>());
  ne.setRadiusSearch(r); ne.compute(*n);
  return n;
}
static FPFHCloud::Ptr fpfh(CloudXYZ::Ptr c, NormalCloud::Ptr n, double r) {
  auto f = std::make_shared<FPFHCloud>();
  pcl::FPFHEstimation<PointXYZ, pcl::Normal, pcl::FPFHSignature33> fe;
  fe.setInputCloud(c); fe.setInputNormals(n);
  fe.setSearchMethod(std::make_shared<pcl::search::KdTree<PointXYZ>>());
  fe.setRadiusSearch(r); fe.compute(*f);
  return f;
}

int main() {
  auto A = toMeters(*loadPCD("/root/lilw/lidar/.dev/testdata/unit_a.pcd"));
  auto B = toMeters(*loadPCD("/root/lilw/lidar/.dev/testdata/unit_b.pcd"));
  std::printf("[load 原始]\n"); rep("A", *A); rep("B", *B);

  auto Ac = sor(A), Bc = sor(B);
  std::printf("[clean SOR]\n"); rep("A", *Ac); rep("B", *Bc);

  // 基线：现有 registerTwoUnits（仅 yaw-about-Z 粗init）。预期：遇翻转发散/高 fitness。
  auto base = registerTwoUnits(*Bc, *Ac, 0.08, 1.0, 60);
  std::printf("[基线 yaw-ICP] converged=%d fitness=%.4f yaw=%d\n", base.converged, base.fitness, base.best_yaw_deg);

  // 全局配准：降采样→法向→FPFH→SAC-IA 粗配→ICP 精配。
  const float leaf = 0.06f;
  auto Ad = down(Ac, leaf), Bd = down(Bc, leaf);
  std::printf("[降采样 leaf=%.2fm] A n=%zu B n=%zu\n", leaf, Ad->size(), Bd->size());
  auto An = normals(Ad, 0.15), Bn = normals(Bd, 0.15);
  auto Af = fpfh(Ad, An, 0.25), Bf = fpfh(Bd, Bn, 0.25);

  pcl::SampleConsensusInitialAlignment<PointXYZ, PointXYZ, pcl::FPFHSignature33> sac;
  sac.setInputSource(Bd); sac.setSourceFeatures(Bf);
  sac.setInputTarget(Ad); sac.setTargetFeatures(Af);
  sac.setMinSampleDistance(0.10f); sac.setMaxCorrespondenceDistance(0.50f); sac.setMaximumIterations(2000);
  CloudXYZ sac_out; sac.align(sac_out);
  Eigen::Matrix4f coarse = sac.getFinalTransformation();
  std::printf("[SAC-IA 粗配] converged=%d fitness=%.4f\n", sac.hasConverged(), sac.getFitnessScore());

  pcl::IterativeClosestPoint<PointXYZ, PointXYZ> icp;
  icp.setInputSource(Bd); icp.setInputTarget(Ad);
  icp.setMaxCorrespondenceDistance(0.30); icp.setMaximumIterations(80); icp.setTransformationEpsilon(1e-9);
  CloudXYZ icp_out; icp.align(icp_out, coarse);
  Eigen::Matrix4f T = icp.getFinalTransformation();
  std::printf("[ICP 精配] converged=%d fitness=%.4f\n", icp.hasConverged(), icp.getFitnessScore());

  // 融合一致性：把 B 变换进 A 系，看 bbox 是否收敛成一个房间（而非两个错位房间）。
  auto Bt = applyTransform(*Bc, T.cast<double>());
  CloudXYZ fused = *Ac; fused += *Bt;
  std::printf("[融合后]\n"); rep("A", *Ac); rep("B→A", *Bt); rep("fused", fused);
  std::printf("判读：fused size 若 ≈ 单间房(~A 尺度) 且 ICP fitness 小 → 配准成功；若 fused 仍很大 → 失败。\n");
  return 0;
}
