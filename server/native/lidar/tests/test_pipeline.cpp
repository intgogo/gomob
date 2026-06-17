// STEP 9 offline test: end-to-end replay pipeline on real sample data
// (config.yaml + heap.pcd + cam.jpg). Validates orchestration + IO (not coloring
// correctness, which test_colorizer covers; here a config-init camera may map few points).
#include <cstdio>
#include <filesystem>

#include "cloud/io_pcd.h"
#include "config/calibration_json.h"
#include "pipeline/pipeline.h"

namespace fs = std::filesystem;

#ifndef LIDAR_SAMPLE_DIR
#define LIDAR_SAMPLE_DIR "sample"
#endif
#ifndef LIDAR_GROUNDTRUTH_DIR
#define LIDAR_GROUNDTRUTH_DIR "/root/WindowsR/LIDAR_PTZ/temp"
#endif
#ifndef LIDAR_CAMJPG
#define LIDAR_CAMJPG "/root/WindowsR/LIDAR_PTZ/cam.jpg"
#endif

using namespace lidar;
static int g_fail = 0;
#define CHECK(cond, msg)                                            \
  do {                                                              \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }    \
    else         { std::printf("  ok  : %s\n", msg); }              \
  } while (0)

int main() {
  std::printf("[1] offline replay pipeline on sample data\n");
  PipelineInputs in;
  in.config_path = std::string(LIDAR_SAMPLE_DIR) + "/config.yaml";
  in.cloud_pcd   = std::string(LIDAR_GROUNDTRUTH_DIR) + "/heap.pcd";
  in.images      = {{LIDAR_CAMJPG, 0.0}};
  in.output_dir  = "/tmp/lidar_pipe_test";

  try {
    auto o = runOfflinePipeline(in);
    std::printf("  info: in=%zu out=%zu mapped=%zu textured=%d\n",
                o.input_points, o.output_points, o.mapped_points, o.textured);
    CHECK(o.input_points == 169783, "loaded heap.pcd (169,783 pts)");
    // config synthesis_voxel = 0.5 -> ~half kept
    CHECK(o.output_points > 80000 && o.output_points < 90000, "synthesis_voxel 0.5 kept ~half");
    CHECK(fs::exists(o.synthesized_pcd), "synthesized_final.pcd written");
    CHECK(o.textured && fs::exists(o.textured_pcd), "textured_final.pcd written");
    CHECK(fs::exists(o.panorama_jpg) && fs::file_size(o.panorama_jpg) > 0, "panorama_cylindrical.jpg written");
    CHECK(fs::exists(o.calibration_json), "calibration_results.json written");

    // re-load outputs to confirm validity
    auto syn = loadPCD(o.synthesized_pcd);
    CHECK(syn->size() == o.output_points, "synthesized PCD re-loads with matching count");
    auto cp = loadCalibrationJson(o.calibration_json);
    CHECK(cp.camera_intrinsic[0] > 1800 && cp.camera_intrinsic[0] < 1900, "calibration JSON re-loads (fx from config)");
  } catch (const std::exception& e) {
    std::printf("  FAIL pipeline threw: %s\n", e.what());
    ++g_fail;
  }

  std::printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}
