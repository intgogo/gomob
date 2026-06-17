#include "config/calibration_json.h"

#include <fstream>
#include <stdexcept>
#include <nlohmann/json.hpp>

namespace lidar {
namespace {

using json = nlohmann::json;

Eigen::Quaterniond quatFrom(const json& n, const Eigen::Quaterniond& def) {
  if (!n.is_array() || n.size() != 4) return def;
  return Eigen::Quaterniond(n[0].get<double>(), n[1].get<double>(),
                            n[2].get<double>(), n[3].get<double>());  // [w,x,y,z], not renormalized here
}
Eigen::Vector3d vec3From(const json& n, const Eigen::Vector3d& def) {
  if (!n.is_array() || n.size() != 3) return def;
  return {n[0].get<double>(), n[1].get<double>(), n[2].get<double>()};
}
json quatTo(const Eigen::Quaterniond& q) { return json::array({q.w(), q.x(), q.y(), q.z()}); }
json vec3To(const Eigen::Vector3d& v)    { return json::array({v.x(), v.y(), v.z()}); }

}  // namespace

CalibParams loadCalibrationJson(const std::string& path) {
  std::ifstream in(path);
  if (!in) throw std::runtime_error("loadCalibrationJson: cannot open " + path);
  json root;
  try { in >> root; }
  catch (const std::exception& e) { throw std::runtime_error(std::string("loadCalibrationJson: JSON parse error: ") + e.what()); }
  if (!root.is_object() || !root.contains("parameters") || !root["parameters"].is_object())
    throw std::runtime_error("loadCalibrationJson: missing or invalid 'parameters' node");
  const json& P = root["parameters"];
  CalibParams p;

  if (P.contains("lidar") && P["lidar"].is_object()) {
    const json& L = P["lidar"];
    p.lidar_rot_quat   = quatFrom(L.value("lidar_rot_quat", json()),   p.lidar_rot_quat);
    p.lidar_corr_quat  = quatFrom(L.value("lidar_corr_quat", json()),  p.lidar_corr_quat);
    p.lidar_corr_offset= vec3From(L.value("lidar_corr_offset", json()),p.lidar_corr_offset);
  }
  if (P.contains("camera") && P["camera"].is_object()) {
    const json& C = P["camera"];
    p.camera_rot_quat   = quatFrom(C.value("camera_rot_quat", json()),  p.camera_rot_quat);
    p.camera_corr_quat  = quatFrom(C.value("camera_corr_quat", json()), p.camera_corr_quat);
    p.camera_corr_offset= vec3From(C.value("camera_corr_offset", json()),p.camera_corr_offset);
    if (const json in_ = C.value("camera_intrinsic", json()); in_.is_array() && in_.size() == 4)
      for (int i = 0; i < 4; ++i) p.camera_intrinsic[i] = in_[i].get<double>();
    else throw std::runtime_error("loadCalibrationJson: camera_intrinsic must have 4 elements");
    if (const json di = C.value("camera_distortion", json()); di.is_array() && di.size() == 5)
      for (int i = 0; i < 5; ++i) p.camera_distortion[i] = di[i].get<double>();
    else throw std::runtime_error("loadCalibrationJson: camera_distortion must have 5 elements");
  }
  if (P.contains("body2world") && P["body2world"].is_object()) {
    const json& B = P["body2world"];
    p.b2w_quat   = quatFrom(B.value("b2w_quat", json()),   p.b2w_quat);
    p.b2w_offset = vec3From(B.value("b2w_offset", json()), p.b2w_offset);
    p.b2w_scale  = B.value("b2w_scale", p.b2w_scale);
  }
  return p;
}

bool saveCalibrationParamsJson(const std::string& path, const CalibParams& p) {
  json root;
  root["parameters"]["lidar"] = {
      {"lidar_rot_quat", quatTo(p.lidar_rot_quat)},
      {"lidar_corr_quat", quatTo(p.lidar_corr_quat)},
      {"lidar_corr_offset", vec3To(p.lidar_corr_offset)}};
  root["parameters"]["camera"] = {
      {"camera_rot_quat", quatTo(p.camera_rot_quat)},
      {"camera_corr_quat", quatTo(p.camera_corr_quat)},
      {"camera_corr_offset", vec3To(p.camera_corr_offset)},
      {"camera_intrinsic", json::array({p.camera_intrinsic[0], p.camera_intrinsic[1], p.camera_intrinsic[2], p.camera_intrinsic[3]})},
      {"camera_distortion", json::array({p.camera_distortion[0], p.camera_distortion[1], p.camera_distortion[2], p.camera_distortion[3], p.camera_distortion[4]})}};
  root["parameters"]["body2world"] = {
      {"b2w_quat", quatTo(p.b2w_quat)},
      {"b2w_offset", vec3To(p.b2w_offset)},
      {"b2w_scale", p.b2w_scale}};
  std::ofstream out(path);
  if (!out) return false;
  out << root.dump(2) << '\n';
  return static_cast<bool>(out);
}

bool saveCalibrationReportJson(const std::string& path, const CalibParams& p) {
  json root;
  root["lidar_to_axis"]   = {{"quaternion_wxyz", quatTo(p.lidar_rot_quat)}, {"translation", vec3To(p.lidar_corr_offset)}};
  root["device_to_world"] = {{"quaternion_wxyz", quatTo(p.b2w_quat)},       {"translation", vec3To(p.b2w_offset)}, {"scale", p.b2w_scale}};
  root["camera_to_axis"]  = {{"quaternion_wxyz", quatTo(p.camera_rot_quat)},{"translation", vec3To(p.camera_corr_offset)}};
  root["intrinsics"]              = json::array({p.camera_intrinsic[0], p.camera_intrinsic[1], p.camera_intrinsic[2], p.camera_intrinsic[3]});
  root["distortion_coefficients"] = json::array({p.camera_distortion[0], p.camera_distortion[1], p.camera_distortion[2], p.camera_distortion[3], p.camera_distortion[4]});
  std::ofstream out(path);
  if (!out) return false;
  out << root.dump(2) << '\n';
  return static_cast<bool>(out);
}

}  // namespace lidar
