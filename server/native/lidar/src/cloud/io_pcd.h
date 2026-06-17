// PCD / ASCII point-cloud IO. Wraps PCL for binary PCD v0.7 (FIELDS x y z, etc.),
// matching the headers the original writes (re/SPEC.md §5). Plus AABB + TXT helpers.
#pragma once

#include <string>
#include "cloud/types.h"

namespace lidar {

// Load a binary/ASCII PCD into a PointXYZ cloud. Throws std::runtime_error on failure.
CloudXYZ::Ptr loadPCD(const std::string& path);

// Save as binary PCD v0.7 (pcl::io::savePCDFileBinary). Returns false on failure.
bool savePCDBinary(const std::string& path, const CloudXYZ& cloud);
bool savePCDBinary(const std::string& path, const CloudXYZI& cloud);
bool savePCDBinary(const std::string& path, const CloudXYZRGB& cloud);

// Compute the AABB of a cloud (same unit as the points).
Bbox bbox(const CloudXYZ& cloud);

// Load a whitespace-separated ASCII point list "x y z [attr]" (e.g. temp/points3D.txt).
// Returns points in the file's native unit (mm for legacy files). attr is ignored.
CloudXYZ::Ptr loadAsciiXYZ(const std::string& path);

// --- Factory vehicle-capture output writers (re/spec_fusion_export §1.2, §5.3) ---

// Integer cartesian dump matching the original `points3D.txt` / viewer-cloud writer
// (`"%d\t%d\t%d\t%d\n"` = x y z attr, MILLIMETRES). `mm_scale` multiplies the cloud's
// unit into mm (1000 for a metres cloud, 1 if already mm). attr written as 0.
bool savePoints3DTxt(const std::string& path, const CloudXYZ& cloud, double mm_scale = 1000.0);

// Append one running-log line to `pointcloud_number.txt`:
//   "<yyyy-MM-dd HH:mm:ss.zzz> - pointcloud_number:<N>\n"
// If `timestamp` is empty, the current local time is used. One line per capture (mode "a").
bool appendPointcloudNumber(const std::string& path, std::size_t n,
                            const std::string& timestamp = "");

}  // namespace lidar
