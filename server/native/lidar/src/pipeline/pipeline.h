// STEP 9 — end-to-end pipeline orchestration (manual/offline replay path).
// Wires the verified stages: load config -> load cloud -> synthesize(downsample) ->
// texture(colorize) -> export PCD/panorama/calibration JSON, gated by config.debug flags.
// The LIVE path (device control -> scan-stream capture -> two-unit fusion) is built from the
// same modules (device/http_client, device/scan_stream, cloud/fusion) once hardware is up;
// two-unit fusion orchestration (R5) + range_scale (U1) are pinned by the live probe.
#pragma once

#include <string>
#include <utility>
#include <vector>

namespace lidar {

struct PipelineInputs {
  std::string config_path;     // config.yaml
  std::string cloud_pcd;       // input world cloud (synthesized/raw), PointXYZ
  std::string calib_json;      // optional calibration_results.json (parameters{} form); else config init
  std::vector<std::pair<std::string, double>> images;  // (image_path, heading_radians) for texture
  std::string output_dir{"./out"};
};

struct PipelineOutputs {
  std::string synthesized_pcd;   // <out>/synthesized_final.pcd  (PointXYZ, post-downsample)
  std::string textured_pcd;      // <out>/textured_final.pcd     (PointXYZRGB, if textured)
  std::string panorama_jpg;      // <out>/panorama_cylindrical.jpg (if textured)
  std::string calibration_json;  // <out>/calibration_results.json (params used)
  std::size_t input_points{0}, output_points{0}, mapped_points{0};
  bool textured{false};
};

// Run the offline replay pipeline. Throws std::runtime_error on fatal IO/parse errors.
PipelineOutputs runOfflinePipeline(const PipelineInputs& in);

}  // namespace lidar
