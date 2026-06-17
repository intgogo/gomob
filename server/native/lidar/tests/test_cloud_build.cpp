// STEP 4 offline test: lineToWorld() exact-algebra checks against hand-computed
// cases for each factor of the verified synthesis chain (re/spec_geometry_resolved §1),
// plus a buildFromLDR structural check. End-to-end bbox parity vs temp/c64k.pcd needs
// the raw LDR capture that produced it (live hardware) — not asserted here.
#include <cmath>
#include <cstdio>
#include <vector>

#include "cloud/cloud_build.h"
#include "config/config_yaml.h"
#include "device/scan_stream.h"

#ifndef LIDAR_SAMPLE_DIR
#define LIDAR_SAMPLE_DIR "sample"
#endif

using namespace lidar;
static int g_fail = 0;
#define CHECK(cond, msg)                                            \
  do {                                                              \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }    \
    else         { std::printf("  ok  : %s\n", msg); }              \
  } while (0)
static bool v3(const Eigen::Vector3d& a, double x, double y, double z, double t = 1e-9) {
  return std::fabs(a.x() - x) <= t && std::fabs(a.y() - y) <= t && std::fabs(a.z() - z) <= t;
}

int main(int, char**) {
  const std::string sample = LIDAR_SAMPLE_DIR;

  std::printf("[1] lineToWorld factor-by-factor\n");
  {
    SynthesisParams p;  // all identity, range_scale=1
    // A: v=0, dist=2, h=0 -> P_line=[2,0,0], all identity -> [2,0,0]
    CHECK(v3(lineToWorld(0.0, 2.0, 0.0, p), 2, 0, 0), "identity: [2,0,0]");
    // B: v=pi/2 -> P_line=[0,2,0]
    CHECK(v3(lineToWorld(M_PI / 2, 2.0, 0.0, p), 0, 2, 0), "v=90deg -> [0,2,0]");
    // C: Rz(+90) about Z on [2,0,0] -> [0,2,0]
    CHECK(v3(lineToWorld(0.0, 2.0, 90.0, p), 0, 2, 0), "h=90 -> Rz(+90)[2,0,0]=[0,2,0]");
    // D: range_scale 0.5 -> r=1
    p.range_scale = 0.5;
    CHECK(v3(lineToWorld(0.0, 2.0, 0.0, p), 1, 0, 0), "range_scale 0.5 -> [1,0,0]");
    p.range_scale = 1.0;
    // E: b2w_offset added last
    p.b2w_offset = {10, 20, 30};
    CHECK(v3(lineToWorld(0.0, 2.0, 0.0, p), 12, 20, 30), "b2w_offset added: [12,20,30]");
    p.b2w_offset = {0, 0, 0};
    // F: lidar_corr_offset added before T_fix (identity T_fix -> same as adding directly)
    p.lidar_corr_offset = {0, 0, 5};
    CHECK(v3(lineToWorld(0.0, 2.0, 0.0, p), 2, 0, 5), "lidar_corr_offset: [2,0,5]");
  }

  std::printf("[2] T_fix_lidar = Ry(+90) from config\n");
  try {
    auto cfg = loadConfig(sample + "/config.yaml");
    SynthesisParams p = SynthesisParams::fromConfig(cfg);
    // q_lidar_rot=I, corr_off=0, q_b2w from device_pose (non-trivial). Test T_fix alone:
    SynthesisParams t;  // identity except T_fix
    t.T_fix_lidar = cfg.lidar.fixed_transform;  // Ry+90 = [[0,0,1],[0,1,0],[-1,0,0]]
    // Ry(+90)*[2,0,0] = [0,0,-2]
    CHECK(v3(lineToWorld(0.0, 2.0, 0.0, t), 0, 0, -2), "Ry(+90)[2,0,0] = [0,0,-2]");
    // fromConfig: q_b2w normalized (device_pose.q non-unit)
    CHECK(std::fabs(p.q_b2w.norm() - 1.0) < 1e-12, "fromConfig q_b2w normalized");
  } catch (const std::exception& e) {
    std::printf("  SKIP config (%s)\n", e.what());
  }

  std::printf("[3] buildFromLDR structural\n");
  {
    SynthesisParams p;  // identity
    device::LdrFrame fr;
    fr.h_angle_deg = 0.0;
    fr.points.push_back({2.0, 7, 0.0, 0});   // dist 2, attr 7, v_angle 0 -> [2,0,0]
    fr.points.push_back({3.0, 8, 90.0, 1});  // dist 3, v_angle 90deg -> [0,3,0]
    auto cloud = buildFromLDR({fr}, p);
    CHECK(cloud->size() == 2, "2 points built");
    CHECK(v3(Eigen::Vector3d(cloud->points[0].x, cloud->points[0].y, cloud->points[0].z), 2, 0, 0), "pt0 = [2,0,0]");
    CHECK(v3(Eigen::Vector3d(cloud->points[1].x, cloud->points[1].y, cloud->points[1].z), 0, 3, 0), "pt1 = [0,3,0]");
    auto ci = buildFromLDRI({fr}, p);
    CHECK(ci->size() == 2 && std::fabs(ci->points[0].intensity - 7.0) < 1e-6, "intensity = attr");
  }

  std::printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}
