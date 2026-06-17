#include "cloud/legacy_calib3d.h"

#include <fstream>
#include <stdexcept>
#include <string>
#include <unordered_map>

namespace lidar {
namespace {

// Minimal INI reader: returns key -> value over all sections (keys are unique here).
std::unordered_map<std::string, std::string> parseIni(const std::string& path) {
  std::ifstream in(path);
  if (!in) throw std::runtime_error("loadLegacySettings: cannot open " + path);
  std::unordered_map<std::string, std::string> kv;
  std::string line;
  while (std::getline(in, line)) {
    auto h = line.find('#'); if (h != std::string::npos) line.erase(h);
    auto eq = line.find('=');
    if (eq == std::string::npos) continue;
    std::string key = line.substr(0, eq);
    std::string val = line.substr(eq + 1);
    auto trim = [](std::string& s) {
      const char* ws = " \t\r\n";
      auto b = s.find_first_not_of(ws); auto e = s.find_last_not_of(ws);
      s = (b == std::string::npos) ? "" : s.substr(b, e - b + 1);
    };
    trim(key); trim(val);
    if (!key.empty()) kv[key] = val;
  }
  return kv;
}

double getd(const std::unordered_map<std::string, std::string>& kv, const std::string& k, double def) {
  auto it = kv.find(k);
  return it == kv.end() ? def : std::stod(it->second);
}

}  // namespace

LegacySettings loadLegacySettings(const std::string& settingIniPath) {
  const auto kv = parseIni(settingIniPath);
  LegacySettings s;
  auto& R = s.calib.R; auto& t = s.calib.t;
  R(0,0)=getd(kv,"laser3D_r00",R(0,0)); R(0,1)=getd(kv,"laser3D_r01",R(0,1)); R(0,2)=getd(kv,"laser3D_r02",R(0,2));
  R(1,0)=getd(kv,"laser3D_r10",R(1,0)); R(1,1)=getd(kv,"laser3D_r11",R(1,1)); R(1,2)=getd(kv,"laser3D_r12",R(1,2));
  R(2,0)=getd(kv,"laser3D_r20",R(2,0)); R(2,1)=getd(kv,"laser3D_r21",R(2,1)); R(2,2)=getd(kv,"laser3D_r22",R(2,2));
  t.x()=getd(kv,"laser3D_r03",0); t.y()=getd(kv,"laser3D_r13",0); t.z()=getd(kv,"laser3D_r23",0);
  s.crop.xMin=getd(kv,"xMin",s.crop.xMin); s.crop.xMax=getd(kv,"xMax",s.crop.xMax);
  s.crop.yMin=getd(kv,"yMin",s.crop.yMin); s.crop.yMax=getd(kv,"yMax",s.crop.yMax);
  s.crop.zMin=getd(kv,"zMin",s.crop.zMin); s.crop.zMax=getd(kv,"zMax",s.crop.zMax);
  return s;
}

CloudXYZ::Ptr transformAndCrop(const CloudXYZ& deviceFrame,
                               const LegacyCalib3D& calib,
                               const LegacyCropBox& crop) {
  auto out = std::make_shared<CloudXYZ>();
  out->points.reserve(deviceFrame.points.size());
  for (const auto& p : deviceFrame.points) {
    Eigen::Vector3d w = calib.R * Eigen::Vector3d(p.x, p.y, p.z) + calib.t;
    if (crop.contains(w))
      out->points.emplace_back(static_cast<float>(w.x()), static_cast<float>(w.y()), static_cast<float>(w.z()));
  }
  out->width  = static_cast<std::uint32_t>(out->points.size());
  out->height = 1;
  out->is_dense = false;
  return out;
}

}  // namespace lidar
