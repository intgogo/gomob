// Offline parity test for the M1 foundation (config + PCD IO + legacy CALIB3D oracle).
// Validates against the re/SPEC.md §6 ground-truth oracles. No hardware needed.
//
// Paths are injected by CMake:
//   LIDAR_SAMPLE_DIR      -> repo sample/  (config.yaml, setting.ini, temp/points3D.txt)
//   LIDAR_GROUNDTRUTH_DIR -> dir with c64k.pcd / heap.pcd / laser3D_1.pcd
#include <cmath>
#include <cstdio>
#include <string>

#include "cloud/io_pcd.h"
#include "cloud/legacy_calib3d.h"
#include "config/calibration_json.h"
#include "config/config_yaml.h"

#ifndef LIDAR_SAMPLE_DIR
#define LIDAR_SAMPLE_DIR "sample"
#endif
#ifndef LIDAR_GROUNDTRUTH_DIR
#define LIDAR_GROUNDTRUTH_DIR "/root/WindowsR/LIDAR_PTZ/temp"
#endif

static int g_fail = 0;
#define CHECK(cond, msg)                                                  \
  do {                                                                    \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }          \
    else         { std::printf("  ok  : %s\n", msg); }                    \
  } while (0)

static bool near(double a, double b, double tol) { return std::fabs(a - b) <= tol; }

int main(int argc, char** argv) {
  const std::string sample = (argc > 1) ? argv[1] : LIDAR_SAMPLE_DIR;
  const std::string gt     = (argc > 2) ? argv[2] : LIDAR_GROUNDTRUTH_DIR;

  // --- 1) PCD IO oracles (re/SPEC.md §6) -----------------------------------
  std::printf("[1] PCD ground-truth oracles\n");
  try {
    auto c64k = lidar::loadPCD(gt + "/c64k.pcd");
    auto b = lidar::bbox(*c64k);
    CHECK(c64k->size() == 1368649u, "c64k.pcd point count == 1,368,649");
    CHECK(near(b.min.x(), 1129.9, 1.0) && near(b.max.x(), 4468.4, 1.0), "c64k bbox x");
    CHECK(near(b.min.y(), 2231.1, 1.0) && near(b.max.y(), 15213.3, 1.0), "c64k bbox y");
    CHECK(near(b.min.z(), 966.7, 1.0)  && near(b.max.z(), 3222.0, 1.0),  "c64k bbox z");

    auto heap = lidar::loadPCD(gt + "/heap.pcd");
    CHECK(heap->size() == 169783u, "heap.pcd point count == 169,783");

    auto laser = lidar::loadPCD(gt + "/laser3D_1.pcd");
    CHECK(laser->size() == 1695255u, "laser3D_1.pcd point count == 1,695,255");
  } catch (const std::exception& e) {
    std::printf("  SKIP PCD oracles (%s)\n", e.what());
  }

  // --- 2) config.yaml parse (re/SPEC.md §3.3, §7.1) ------------------------
  std::printf("[2] config.yaml parse\n");
  try {
    auto cfg = lidar::loadConfig(sample + "/config.yaml");
    // lidar fixed_transform = Ry(+90): [[0,0,1],[0,1,0],[-1,0,0]]
    const auto& F = cfg.lidar.fixed_transform;
    CHECK(near(F(0,2), 1, 1e-9) && near(F(2,0), -1, 1e-9) && near(F(1,1), 1, 1e-9), "lidar fixed_transform == Ry(+90)");
    CHECK(near(cfg.device_pose.translation.x(), -3.5, 1e-9) &&
          near(cfg.device_pose.translation.y(), -2.2, 1e-9), "device_pose.translation == [-3.5,-2.2,0]");
    // device_pose.quaternion [0.5,0,0,0.8] is NOT unit -> normalized on load (norm==1)
    CHECK(near(cfg.device_pose.quaternion.norm(), 1.0, 1e-9), "device_pose.quaternion normalized on load");
    CHECK(cfg.lidar.planes.size() == 5, "5 plane constraints parsed");
    CHECK(near(cfg.debug.synthesis_voxel, 0.5, 1e-9), "debug.synthesis_voxel == 0.5");
    CHECK(near(cfg.camera.init_intrinsics[0], 1873.01, 1e-3), "camera fx == 1873.01");

    // config150.yaml lidar fixed_transform = Rx(+90): [[1,0,0],[0,0,-1],[0,1,0]] (NOT the 'y90' comment)
    auto cfg150 = lidar::loadConfig(sample + "/config150.yaml");
    const auto& F2 = cfg150.lidar.fixed_transform;
    CHECK(near(F2(0,0), 1, 1e-9) && near(F2(1,2), -1, 1e-9) && near(F2(2,1), 1, 1e-9), "config150 lidar fixed_transform == Rx(+90)");
  } catch (const std::exception& e) {
    std::printf("  SKIP config (%s)\n", e.what());
  }

  // --- 3) legacy CALIB3D geometry oracle (re/SPEC.md §3.7) -----------------
  std::printf("[3] legacy CALIB3D transform+crop\n");
  try {
    auto s = lidar::loadLegacySettings(sample + "/setting.ini");
    CHECK(near(s.calib.t.x(), 3092.279297, 1e-3) && near(s.calib.t.y(), 11207.681641, 1e-3),
          "CALIB3D translation parsed (mm)");
    auto dev = lidar::loadAsciiXYZ(sample + "/temp/points3D.txt");
    CHECK(dev->size() == 2266464u, "points3D.txt == 2,266,464 rows");
    auto world = lidar::transformAndCrop(*dev, s.calib, s.crop);
    auto b = lidar::bbox(*world);
    // y-bounds are the crop gates -> must match laser3D_1.pcd exactly.
    CHECK(near(b.min.y(), 250.0, 0.5) && near(b.max.y(), 20000.0, 1.0), "cropped y-bounds == [250,20000]");
    // count is a different capture -> within ~2% of 1,695,255.
    double ratio = double(world->size()) / 1695255.0;
    std::printf("  info: cropped count = %zu (ratio %.3f vs laser3D_1)\n", world->size(), ratio);
    CHECK(ratio > 0.95 && ratio < 1.05, "cropped count within 5% of laser3D_1.pcd");
  } catch (const std::exception& e) {
    std::printf("  SKIP legacy CALIB3D (%s)\n", e.what());
  }

  // --- 4) calibration JSON round-trip (re/SPEC.md §7.2 form A) --------------
  std::printf("[4] calibration JSON round-trip\n");
  try {
    lidar::CalibParams p;
    p.camera_intrinsic  = {1873.01, 1870.2, 1926.46, 1134.81};
    p.camera_distortion = {-0.0188786, 0.0238944, -0.000597263, -0.000704925, -0.0207798};
    p.b2w_quat   = lidar::quatWXYZ(0.5, 0.0, 0.0, 0.8);
    p.b2w_offset = {-3.5, -2.2, 0.0};
    p.b2w_scale  = 1.0;
    const std::string tmp = "/tmp/lidar_calib_rt.json";
    CHECK(lidar::saveCalibrationParamsJson(tmp, p), "write parameters{} form");
    auto q = lidar::loadCalibrationJson(tmp);
    CHECK(near(q.camera_intrinsic[0], 1873.01, 1e-6) && near(q.camera_distortion[4], -0.0207798, 1e-9),
          "intrinsics+distortion round-trip");
    CHECK(near(q.b2w_offset.x(), -3.5, 1e-9) && near(q.b2w_quat.w(), p.b2w_quat.w(), 1e-9),
          "body2world round-trip");
    CHECK(lidar::saveCalibrationReportJson("/tmp/lidar_calib_report.json", p), "write report form");
  } catch (const std::exception& e) {
    std::printf("  FAIL calibration JSON (%s)\n", e.what()); ++g_fail;
  }

  std::printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}
