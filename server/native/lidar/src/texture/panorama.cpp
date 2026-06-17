#include "texture/panorama.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <vector>
#include <opencv2/imgcodecs.hpp>

namespace lidar {

cv::Mat renderCylindricalPanorama(const CloudXYZRGB& cloud, const PanoramaOptions& opt) {
  if (cloud.points.empty() || opt.width <= 0) return {};

  // Z-range and a representative radius (median of hypot(x,y)).
  double zmin = std::numeric_limits<double>::max(), zmax = -zmin;
  std::vector<double> radii;
  radii.reserve(cloud.points.size());
  for (const auto& p : cloud.points) {
    zmin = std::min(zmin, double(p.z));
    zmax = std::max(zmax, double(p.z));
    radii.push_back(std::hypot(double(p.x), double(p.y)));
  }
  const double zspan = std::max(zmax - zmin, 1e-9);

  int height = opt.height;
  if (height <= 0) {
    auto mid = radii.begin() + radii.size() / 2;
    std::nth_element(radii.begin(), mid, radii.end());
    const double R = std::max(*mid, 1e-6);
    height = static_cast<int>(std::lround(opt.width * zspan / (2.0 * M_PI * R)));
    height = std::clamp(height, 8, 8192);
  }

  const std::uint8_t bg = opt.fill_background_gray ? 128 : 0;
  cv::Mat img(height, opt.width, CV_8UC3, cv::Scalar(bg, bg, bg));
  std::vector<float> zbuf(static_cast<std::size_t>(height) * opt.width, std::numeric_limits<float>::max());

  for (const auto& p : cloud.points) {
    const double az = std::atan2(double(p.y), double(p.x));           // [-pi, pi]
    int col = static_cast<int>(std::lround((az + M_PI) / (2.0 * M_PI) * (opt.width - 1)));
    int row = static_cast<int>(std::lround((zmax - double(p.z)) / zspan * (height - 1)));  // top = high z
    if (col < 0) col = 0; else if (col >= opt.width) col = opt.width - 1;
    if (row < 0) row = 0; else if (row >= height) row = height - 1;
    const float range = static_cast<float>(std::hypot(double(p.x), double(p.y)));
    const std::size_t idx = static_cast<std::size_t>(row) * opt.width + col;
    if (range < zbuf[idx]) {  // nearest point wins
      zbuf[idx] = range;
      img.at<cv::Vec3b>(row, col) = cv::Vec3b(p.b, p.g, p.r);  // BGR
    }
  }
  return img;
}

bool saveCylindricalPanorama(const std::string& path, const CloudXYZRGB& cloud, const PanoramaOptions& opt) {
  cv::Mat img = renderCylindricalPanorama(cloud, opt);
  if (img.empty()) return false;
  return cv::imwrite(path, img);
}

}  // namespace lidar
