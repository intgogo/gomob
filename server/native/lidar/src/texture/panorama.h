// STEP 6b — cylindrical panorama export from a colored cloud (cosmetic; original at
// FUN_14004e730 writes panorama_cylindrical.jpg). Functional-equivalent cylindrical
// projection: column = azimuth about the vertical (Z) axis, row = height (Z); nearest
// point by radial distance wins (simple z-buffer). Output 8-bit BGR (OpenCV imwrite).
#pragma once

#include <string>
#include <opencv2/core.hpp>
#include "cloud/types.h"

namespace lidar {

struct PanoramaOptions {
  int  width{1920};        // azimuth resolution (full 360°)
  int  height{0};          // 0 => derived from Z-range and median radius to keep ~square pixels
  bool fill_background_gray{false};  // false=black, true=gray(128)
};

// Render the panorama; empty Mat if the cloud is empty.
cv::Mat renderCylindricalPanorama(const CloudXYZRGB& cloud, const PanoramaOptions& opt = {});

// Render + write (OpenCV imwrite by extension, e.g. .jpg/.png). Returns false on failure.
bool saveCylindricalPanorama(const std::string& path, const CloudXYZRGB& cloud,
                             const PanoramaOptions& opt = {});

}  // namespace lidar
