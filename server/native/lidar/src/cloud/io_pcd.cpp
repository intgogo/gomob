#include "cloud/io_pcd.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <ctime>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <pcl/io/pcd_io.h>

namespace lidar {

CloudXYZ::Ptr loadPCD(const std::string& path) {
  auto cloud = std::make_shared<CloudXYZ>();
  if (pcl::io::loadPCDFile<PointXYZ>(path, *cloud) < 0)
    throw std::runtime_error("loadPCD: failed to read " + path);
  return cloud;
}

bool savePCDBinary(const std::string& path, const CloudXYZ& cloud) {
  return pcl::io::savePCDFileBinary(path, cloud) == 0;
}
bool savePCDBinary(const std::string& path, const CloudXYZI& cloud) {
  return pcl::io::savePCDFileBinary(path, cloud) == 0;
}
bool savePCDBinary(const std::string& path, const CloudXYZRGB& cloud) {
  return pcl::io::savePCDFileBinary(path, cloud) == 0;
}

Bbox bbox(const CloudXYZ& cloud) {
  Bbox b;
  for (const auto& p : cloud.points)
    b.expand(Eigen::Vector3d(p.x, p.y, p.z));
  return b;
}

CloudXYZ::Ptr loadAsciiXYZ(const std::string& path) {
  std::ifstream in(path);
  if (!in) throw std::runtime_error("loadAsciiXYZ: cannot open " + path);
  auto cloud = std::make_shared<CloudXYZ>();
  std::string line;
  while (std::getline(in, line)) {
    if (line.empty() || line[0] == '#') continue;
    std::istringstream ss(line);
    double x, y, z;
    if (!(ss >> x >> y >> z)) continue;  // ignore trailing attr
    cloud->points.emplace_back(static_cast<float>(x), static_cast<float>(y), static_cast<float>(z));
  }
  cloud->width  = static_cast<std::uint32_t>(cloud->points.size());
  cloud->height = 1;
  cloud->is_dense = false;
  return cloud;
}

bool savePoints3DTxt(const std::string& path, const CloudXYZ& cloud, double mm_scale) {
  std::ofstream out(path, std::ios::binary);
  if (!out) return false;
  for (const auto& p : cloud.points) {
    out << static_cast<long>(std::lround(p.x * mm_scale)) << '\t'
        << static_cast<long>(std::lround(p.y * mm_scale)) << '\t'
        << static_cast<long>(std::lround(p.z * mm_scale)) << '\t' << 0 << '\n';
  }
  return static_cast<bool>(out);
}

bool appendPointcloudNumber(const std::string& path, std::size_t n, const std::string& timestamp) {
  std::string ts = timestamp;
  if (ts.empty()) {
    using namespace std::chrono;
    const auto now = system_clock::now();
    const auto ms  = duration_cast<milliseconds>(now.time_since_epoch()) % 1000;
    const std::time_t t = system_clock::to_time_t(now);
    std::tm tm{};
    localtime_r(&t, &tm);
    char buf[32];
    std::strftime(buf, sizeof buf, "%Y-%m-%d %H:%M:%S", &tm);
    char full[48];
    std::snprintf(full, sizeof full, "%s.%03lld", buf, static_cast<long long>(ms.count()));
    ts = full;
  }
  std::ofstream out(path, std::ios::app | std::ios::binary);
  if (!out) return false;
  out << ts << " - pointcloud_number:" << n << '\n';
  return static_cast<bool>(out);
}

}  // namespace lidar
