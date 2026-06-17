// STEP 10 test: the vehicle-reconstruction orchestrator (align -> union -> downsample -> crop ->
// save) on synthetic two-unit data with a known inter-unit transform.
#include <cmath>
#include <cstdio>
#include <filesystem>
#include "cloud/registration.h"
#include "pipeline/scan_vehicle.h"

namespace fs = std::filesystem;
using namespace lidar;
static int g_fail = 0;
#define CHECK(cond, msg)                                          \
  do {                                                            \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }  \
    else         { std::printf("  ok  : %s\n", msg); }            \
  } while (0)

int main() {
  // Unit A: asymmetric feature-rich surface (z = 0.25 x^2 + 0.1 y) so 180° yaw is unambiguous.
  auto A = std::make_shared<CloudXYZ>();
  for (int i = 0; i <= 60; ++i)
    for (int j = 0; j <= 30; ++j) {
      double x = i * 0.1, y = j * 0.1;
      A->points.emplace_back(float(x), float(y), float(0.25 * x * x + 0.1 * y));
    }
  A->width = A->points.size(); A->height = 1;

  // Unit B = A rotated 180° about Z + translated (the opposite-facing two-unit case).
  Eigen::Matrix4d T = Eigen::Matrix4d::Identity();
  T(0, 0) = -1; T(1, 1) = -1; T(0, 3) = 5.0; T(1, 3) = 3.0;
  auto B = applyTransform(*A, T);  // B is in its own frame; B->A transform is T^{-1}

  const std::string out = "out_test_scanveh";
  fs::remove_all(out);

  std::printf("[1] reconstructVehicle with ICP align\n");
  ScanVehicleParams p;
  p.use_icp = true;
  p.write_factory_layout = false;
  p.out_dir = out;
  auto r = reconstructVehicle(*A, *B, p);
  std::printf("  info: A=%zu B=%zu fused=%zu align=%s\n", r.pts_a, r.pts_b, r.fused, r.align_method.c_str());
  CHECK(r.pts_a == A->size() && r.pts_b == B->size(), "per-unit counts preserved");
  CHECK(r.fused == A->size() + B->size(), "fusion is a union (count == A+B)");
  CHECK(r.align_method == "icp", "ICP alignment path taken");
  CHECK(fs::exists(r.vehicle_pcd), "clouds.pcd written");
  CHECK(fs::exists(r.points3d_txt), "points3D.txt written");
  CHECK(fs::exists(r.pointcloud_number_txt), "pointcloud_number.txt written");

  std::printf("[2] frozen site extrinsic is applied verbatim\n");
  Eigen::Matrix4d known = Eigen::Matrix4d::Identity();
  known(0, 3) = 1.5; known(2, 3) = -0.25;
  const std::string sj = out + "/site.json";
  CHECK(saveSiteExtrinsic(sj, known), "saveSiteExtrinsic ok");
  ScanVehicleParams p2;
  p2.site_extrinsic = sj;
  p2.write_factory_layout = false;
  p2.out_dir = out + "/site";
  auto r2 = reconstructVehicle(*A, *B, p2);
  CHECK(r2.align_method == "site", "site-extrinsic path taken");
  CHECK((r2.b_to_a - known).cwiseAbs().maxCoeff() < 1e-9, "site extrinsic round-trips exactly");
  CHECK(r2.fused == A->size() + B->size(), "fusion still a union under site align");

  std::printf("[3] AABB crop reduces the cloud\n");
  ScanVehicleParams p3;
  p3.use_icp = false;                 // identity align: A in [0,6]x[0,3], B around (5,3)+
  p3.write_factory_layout = false;
  p3.out_dir = out + "/crop";
  p3.crop = true;
  p3.crop_min = Eigen::Vector3d(0, 0, -1);
  p3.crop_max = Eigen::Vector3d(3, 3, 100);   // keep only the low-x slab
  auto r3 = reconstructVehicle(*A, *B, p3);
  std::printf("  info: fused=%zu after_crop=%zu\n", r3.fused, r3.after_crop);
  CHECK(r3.after_crop > 0 && r3.after_crop < r3.fused, "crop keeps a strict subset");

  std::printf("[4] keep-ratio downsample\n");
  ScanVehicleParams p4;
  p4.write_factory_layout = false;
  p4.out_dir = out + "/ds";
  p4.keep_ratio = 0.5;
  auto r4 = reconstructVehicle(*A, *B, p4);
  const std::size_t expect = std::lround((A->size() + B->size()) * 0.5);
  std::printf("  info: fused=%zu kept=%zu (expect ~%zu)\n", r4.fused, r4.after_downsample, expect);
  CHECK(r4.after_downsample == expect, "random keep-ratio keeps round(N*ratio)");

  fs::remove_all(out);
  std::printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}
