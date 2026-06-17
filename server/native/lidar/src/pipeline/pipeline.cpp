#include "pipeline/pipeline.h"

#include <filesystem>
#include <stdexcept>
#include <opencv2/imgcodecs.hpp>

#include "cloud/fusion.h"
#include "cloud/io_pcd.h"
#include "config/calibration_json.h"
#include "config/config_yaml.h"
#include "texture/colorizer.h"
#include "texture/panorama.h"

namespace fs = std::filesystem;

namespace lidar {

PipelineOutputs runOfflinePipeline(const PipelineInputs& in) {
  PipelineOutputs out;
  const Config cfg = loadConfig(in.config_path);
  fs::create_directories(in.output_dir);

  // --- load input world cloud ---
  CloudXYZ::Ptr cloud = loadPCD(in.cloud_pcd);
  out.input_points = cloud->size();

  // --- synthesize stage: random keep-ratio downsample (synthesis_voxel) ---
  if (cfg.debug.enable_synthesis && cfg.debug.synthesis_voxel < 1.0)
    cloud = randomKeep(*cloud, cfg.debug.synthesis_voxel);
  out.output_points = cloud->size();

  out.synthesized_pcd = (fs::path(in.output_dir) / "synthesized_final.pcd").string();
  savePCDBinary(out.synthesized_pcd, *cloud);

  // --- camera model: from calibration JSON if given, else config init ---
  CameraModel cam = CameraModel::fromConfig(cfg);
  CalibParams calib;
  bool have_calib = false;
  if (!in.calib_json.empty()) {
    try { calib = loadCalibrationJson(in.calib_json); cam.applyCalibration(calib); have_calib = true; }
    catch (const std::exception&) { /* fall back to config init */ }
  }
  if (!have_calib) {
    // Populate a CalibParams from config init for the result writer.
    calib.lidar_rot_quat = cfg.lidar.init_quaternion;
    calib.lidar_corr_offset = cfg.lidar.init_translation;
    calib.camera_rot_quat = Eigen::Quaterniond(cfg.camera.fixed_transform.block<3, 3>(0, 0)).normalized();
    calib.camera_corr_quat = cfg.camera.init_quaternion;
    calib.camera_corr_offset = cfg.camera.init_translation;
    calib.camera_intrinsic = cfg.camera.init_intrinsics;
    calib.camera_distortion = cfg.camera.init_distortion;
    calib.b2w_quat = cfg.device_pose.quaternion;
    calib.b2w_offset = cfg.device_pose.translation;
    calib.b2w_scale = 1.0;
  }

  // --- texture stage: project camera frames onto the cloud ---
  if (cfg.debug.enable_texture_mapping && !in.images.empty()) {
    std::vector<CameraFrame> frames;
    for (const auto& [path, heading] : in.images) {
      cv::Mat img = cv::imread(path, cv::IMREAD_COLOR);
      if (!img.empty()) frames.push_back({img, heading});
    }
    if (!frames.empty()) {
      cam.image_width = frames.front().image_bgr.cols;
      cam.image_height = frames.front().image_bgr.rows;
      auto colored = colorize(*cloud, frames, cam, &out.mapped_points);
      out.textured = true;
      out.textured_pcd = (fs::path(in.output_dir) / "textured_final.pcd").string();
      savePCDBinary(out.textured_pcd, *colored);
      out.panorama_jpg = (fs::path(in.output_dir) / "panorama_cylindrical.jpg").string();
      saveCylindricalPanorama(out.panorama_jpg, *colored);
    }
  }

  // --- calibration result (params used) ---
  out.calibration_json = (fs::path(in.output_dir) / "calibration_results.json").string();
  saveCalibrationParamsJson(out.calibration_json, calib);

  return out;
}

}  // namespace lidar
