#include "config/config_yaml.h"

#include <stdexcept>
#include <yaml-cpp/yaml.h>

namespace lidar {
namespace {

Eigen::Matrix4d readMatrix4(const YAML::Node& n, const std::string& where) {
  if (!n || !n.IsSequence() || n.size() != 4)
    throw std::runtime_error("config: " + where + " must be a 4x4 sequence");
  Eigen::Matrix4d m;
  for (int r = 0; r < 4; ++r) {
    const YAML::Node row = n[r];
    if (!row.IsSequence() || row.size() != 4)
      throw std::runtime_error("config: " + where + " row " + std::to_string(r) + " must have 4 cols");
    for (int c = 0; c < 4; ++c) m(r, c) = row[c].as<double>();
  }
  return m;
}

Eigen::Vector3d readVec3(const YAML::Node& n, const Eigen::Vector3d& def) {
  if (!n || !n.IsSequence() || n.size() != 3) return def;
  return {n[0].as<double>(), n[1].as<double>(), n[2].as<double>()};
}

// YAML quaternion is [w,x,y,z]; returns normalized (re/SPEC.md §3.2).
Eigen::Quaterniond readQuatWXYZ(const YAML::Node& n, const Eigen::Quaterniond& def) {
  if (!n || !n.IsSequence() || n.size() != 4) return def;
  Eigen::Quaterniond q(n[0].as<double>(), n[1].as<double>(), n[2].as<double>(), n[3].as<double>());
  q.normalize();
  return q;
}

template <std::size_t N>
std::array<double, N> readArrayN(const YAML::Node& n, const std::array<double, N>& def) {
  if (!n || !n.IsSequence() || n.size() != N) return def;
  std::array<double, N> a;
  for (std::size_t i = 0; i < N; ++i) a[i] = n[i].as<double>();
  return a;
}

}  // namespace

Config loadConfig(const std::string& path) {
  YAML::Node root = YAML::LoadFile(path);  // throws if unreadable
  Config c;

  if (const YAML::Node lc = root["lidar_calibration"]) {
    c.lidar.fixed_transform   = readMatrix4(lc["fixed_transform"], "lidar_calibration.fixed_transform");
    c.lidar.prior_translation = readVec3(lc["prior_translation"], c.lidar.prior_translation);
    c.lidar.init_translation  = readVec3(lc["init_translation"],  c.lidar.init_translation);
    c.lidar.init_quaternion   = readQuatWXYZ(lc["init_quaternion"], c.lidar.init_quaternion);
    if (lc["icp_max_iterations"]) c.lidar.icp_max_iterations = lc["icp_max_iterations"].as<int>();
    if (lc["plane_max_distance"]) c.lidar.plane_max_distance = lc["plane_max_distance"].as<double>();
    if (const YAML::Node ps = lc["planes"]; ps && ps.IsSequence()) {
      for (const auto& pn : ps) {
        if (!pn.IsSequence() || pn.size() < 6) continue;
        PlaneConstraint pc;
        pc.normal = {pn[0].as<double>(), pn[1].as<double>(), pn[2].as<double>()};
        pc.point  = {pn[3].as<double>(), pn[4].as<double>(), pn[5].as<double>()};
        for (std::size_t i = 6, k = 0; i < pn.size() && k < 3; ++i, ++k) pc.extra[k] = pn[i].as<double>();
        c.lidar.planes.push_back(pc);
      }
    }
  }

  if (const YAML::Node dp = root["device_pose"]) {
    c.device_pose.translation = readVec3(dp["translation"], c.device_pose.translation);
    c.device_pose.quaternion  = readQuatWXYZ(dp["quaternion"], c.device_pose.quaternion);
  }

  if (const YAML::Node cc = root["camera_calibration"]) {
    c.camera.fixed_transform = readMatrix4(cc["fixed_transform"], "camera_calibration.fixed_transform");
    c.camera.init_translation = readVec3(cc["init_translation"], c.camera.init_translation);
    c.camera.init_quaternion  = readQuatWXYZ(cc["init_quaternion"], c.camera.init_quaternion);
    c.camera.init_intrinsics  = readArrayN<4>(cc["init_intrinsics"], c.camera.init_intrinsics);
    c.camera.init_distortion  = readArrayN<5>(cc["init_distortion"], c.camera.init_distortion);
    if (cc["max_iterations"])    c.camera.max_iterations    = cc["max_iterations"].as<int>();
    if (cc["num_threads"])       c.camera.num_threads       = cc["num_threads"].as<int>();
    if (cc["function_tolerance"])c.camera.function_tolerance= cc["function_tolerance"].as<double>();
  }

  if (const YAML::Node tm = root["texture_mapping"]) {
    if (tm["image_width"])          c.texture.image_width          = tm["image_width"].as<int>();
    if (tm["image_height"])         c.texture.image_height         = tm["image_height"].as<int>();
    if (tm["fov_margin_ratio"])     c.texture.fov_margin_ratio     = tm["fov_margin_ratio"].as<double>();
    if (tm["angle_threshold_extra"])c.texture.angle_threshold_extra= tm["angle_threshold_extra"].as<double>();
    if (tm["safe_pixel_margin"])    c.texture.safe_pixel_margin    = tm["safe_pixel_margin"].as<int>();
  }

  if (const YAML::Node db = root["debug"]) {
    if (db["save_intermediate_results"]) c.debug.save_intermediate_results = db["save_intermediate_results"].as<bool>();
    if (db["save_transformed_cloud"])    c.debug.save_transformed_cloud    = db["save_transformed_cloud"].as<bool>();
    if (db["template_sample_ratio"])     c.debug.template_sample_ratio     = db["template_sample_ratio"].as<double>();
    if (db["enable_synthesis"])          c.debug.enable_synthesis          = db["enable_synthesis"].as<bool>();
    if (db["enable_texture_mapping"])    c.debug.enable_texture_mapping    = db["enable_texture_mapping"].as<bool>();
    if (db["synthesis_voxel"])           c.debug.synthesis_voxel           = db["synthesis_voxel"].as<double>();
    if (db["output_dir"])                c.debug.output_dir                = db["output_dir"].as<std::string>();
  }

  return c;
}

}  // namespace lidar
