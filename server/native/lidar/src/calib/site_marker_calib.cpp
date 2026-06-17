#include "calib/site_marker_calib.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <filesystem>

#include <opencv2/aruco.hpp>
#include <opencv2/calib3d.hpp>
#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>

#include "config/calibration_json.h"
#include "config/config_yaml.h"

namespace lidar {
namespace {
constexpr double kDegToRad = 3.14159265358979323846 / 180.0;

// 从设备 config(YAML) + calib(JSON) 组相机模型，约定同 colorizer。
CameraModel buildCameraModel(const std::string& config_path, const std::string& calib_path) {
  CameraModel cam = CameraModel::fromConfig(loadConfig(config_path));
  cam.applyCalibration(loadCalibrationJson(calib_path));
  return cam;
}
}  // namespace

// ---- CORE ----

MarkerCornersWorld aggregateMarkerCorners(const std::vector<MarkerCenterObs>& obs,
                                          const CameraModel& cam) {
  std::map<int, std::array<Eigen::Vector3d, 4>> sum;
  std::map<int, int>                            cnt;
  for (const auto& o : obs) {
    auto it = sum.find(o.id);
    if (it == sum.end()) {
      std::array<Eigen::Vector3d, 4> zero{Eigen::Vector3d::Zero(), Eigen::Vector3d::Zero(),
                                          Eigen::Vector3d::Zero(), Eigen::Vector3d::Zero()};
      it = sum.emplace(o.id, zero).first;
      cnt[o.id] = 0;
    }
    // 4 角点：相机系 → 该单元自身系（cameraToWorld 内含 heading + 相机↔轴 + device→world）。
    for (int k = 0; k < 4; ++k) it->second[k] += cameraToWorld(o.corners_cam[k], o.heading_rad, cam);
    cnt[o.id] += 1;
  }
  MarkerCornersWorld mean;
  for (auto& kv : sum) {
    const double n = static_cast<double>(cnt[kv.first]);
    for (int k = 0; k < 4; ++k) mean[kv.first][k] = kv.second[k] / n;
  }
  return mean;
}

SiteMarkerResult solveSiteExtrinsic(const MarkerCornersWorld& cornersA,
                                    const MarkerCornersWorld& cornersB,
                                    const SiteMarkerConfig& cfg) {
  SiteMarkerResult r;
  // 公共标记 id（两单元都重建到的）。
  std::vector<int> common;
  for (const auto& kv : cornersA)
    if (cornersB.count(kv.first)) common.push_back(kv.first);
  r.n_common = static_cast<int>(common.size());
  if (r.n_common < cfg.min_common || r.n_common < 1) {
    r.ok = false;
    r.msg = "公共标记不足: " + std::to_string(r.n_common) + " < " + std::to_string(cfg.min_common);
    return r;
  }

  // 构 3×N 点集（每标记 4 角点）：src=B、dst=A；umeyama 求 T 使 A ≈ T·B（即 B→A）。
  // 角点带 solvePnP 位姿，单标记 4 角点即非共线、约束完整旋转——少量/共面标记也解得准。
  const int N = r.n_common * 4;
  Eigen::Matrix3Xd src(3, N), dst(3, N);
  int col = 0;
  for (int id : common) {
    for (int k = 0; k < 4; ++k, ++col) {
      src.col(col) = cornersB.at(id)[k];
      dst.col(col) = cornersA.at(id)[k];
    }
  }
  r.b_to_a = Eigen::umeyama(src, dst, /*with_scaling=*/false);

  // 对齐残差 RMS（全角点）。
  double se = 0;
  for (int i = 0; i < N; ++i) {
    Eigen::Vector3d p = (r.b_to_a * src.col(i).homogeneous()).head<3>();
    se += (p - dst.col(i)).squaredNorm();
  }
  r.rms_m = std::sqrt(se / N);
  r.ok = r.rms_m <= cfg.max_rms_m;
  r.msg = r.ok ? "ok" : ("RMS 偏大: " + std::to_string(r.rms_m) + "m > " + std::to_string(cfg.max_rms_m) + "m");
  return r;
}

// ---- FRONT-END (OpenCV) ----

namespace {
// 从文件名 ..._h<角度>.<ext> 解析航向（度）。失败返回 false。
bool parseHeadingDeg(const std::string& fname, double& deg) {
  auto p = fname.rfind("_h");
  if (p == std::string::npos) return false;
  std::string s = fname.substr(p + 2);
  auto dot = s.rfind('.');
  if (dot != std::string::npos) s = s.substr(0, dot);  // 去扩展名
  try {
    deg = std::stod(s);
  } catch (...) {
    return false;
  }
  return true;
}
}  // namespace

std::vector<MarkerCenterObs> detectUnitCenters(const std::string& image_dir, const CameraModel& cam,
                                               const SiteMarkerConfig& cfg) {
  std::vector<MarkerCenterObs> out;
  namespace fs = std::filesystem;
  if (!fs::is_directory(image_dir)) {
    std::fprintf(stderr, "[site-marker] 非目录: %s\n", image_dir.c_str());
    return out;
  }

  cv::Ptr<cv::aruco::Dictionary> dict = cv::aruco::getPredefinedDictionary(cv::aruco::DICT_APRILTAG_36h11);
  const double fx = cam.intrinsic[0], fy = cam.intrinsic[1], cx = cam.intrinsic[2], cy = cam.intrinsic[3];
  cv::Mat K = (cv::Mat_<double>(3, 3) << fx, 0, cx, 0, fy, cy, 0, 0, 1);
  cv::Mat D = (cv::Mat_<double>(1, 5) << cam.distortion[0], cam.distortion[1], cam.distortion[2],
               cam.distortion[3], cam.distortion[4]);
  const float h = static_cast<float>(cfg.marker_len_m / 2.0);
  // 标记物点（标记系，z=0，中心原点）：左上、右上、右下、左下（ArUco 角点序）。
  const std::vector<cv::Point3f> objPts = {{-h, h, 0}, {h, h, 0}, {h, -h, 0}, {-h, -h, 0}};

  std::vector<std::string> files;
  for (const auto& e : fs::directory_iterator(image_dir)) {
    if (!e.is_regular_file()) continue;
    std::string ext = e.path().extension().string();
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    if (ext == ".jpg" || ext == ".jpeg" || ext == ".png") files.push_back(e.path().string());
  }
  std::sort(files.begin(), files.end());

  int n_imgs = 0, n_det = 0;
  for (const auto& f : files) {
    double deg = 0;
    if (!parseHeadingDeg(fs::path(f).filename().string(), deg)) {
      std::fprintf(stderr, "[site-marker] 跳过(文件名无 _h<角度>): %s\n", f.c_str());
      continue;
    }
    cv::Mat img = cv::imread(f, cv::IMREAD_COLOR);
    if (img.empty()) continue;
    ++n_imgs;
    std::vector<int>                       ids;
    std::vector<std::vector<cv::Point2f>>  corners;
    cv::aruco::detectMarkers(img, dict, corners, ids);
    for (size_t i = 0; i < ids.size(); ++i) {
      cv::Vec3d rvec, tvec;
      if (!cv::solvePnP(objPts, corners[i], K, D, rvec, tvec, false, cv::SOLVEPNP_IPPE_SQUARE)) continue;
      MarkerCenterObs o;
      o.id = ids[i];
      o.center_cam = Eigen::Vector3d(tvec[0], tvec[1], tvec[2]);  // 标记中心，相机光心系（米）
      // 4 角点（相机系）= solvePnP 位姿 × 标记物点，带标记朝向，供完整 6DoF 解算。
      cv::Mat R;
      cv::Rodrigues(rvec, R);
      for (int k = 0; k < 4; ++k) {
        cv::Mat pm = (cv::Mat_<double>(3, 1) << objPts[k].x, objPts[k].y, objPts[k].z);
        cv::Mat pc = R * pm + cv::Mat(tvec);
        o.corners_cam[k] = Eigen::Vector3d(pc.at<double>(0), pc.at<double>(1), pc.at<double>(2));
      }
      o.heading_rad = deg * kDegToRad;
      out.push_back(o);
      ++n_det;
    }
  }
  std::fprintf(stderr, "[site-marker] %s: 图 %d 张, 检出标记 %d 个\n", image_dir.c_str(), n_imgs, n_det);
  return out;
}

SiteMarkerResult calibrateSiteMarkers(const std::string& image_dir_a, const std::string& config_a,
                                      const std::string& calib_a, const std::string& image_dir_b,
                                      const std::string& config_b, const std::string& calib_b,
                                      const SiteMarkerConfig& cfg) {
  SiteMarkerResult r;
  try {
    CameraModel camA = buildCameraModel(config_a, calib_a);
    CameraModel camB = buildCameraModel(config_b, calib_b);
    auto obsA = detectUnitCenters(image_dir_a, camA, cfg);
    auto obsB = detectUnitCenters(image_dir_b, camB, cfg);
    auto mwA = aggregateMarkerCorners(obsA, camA);
    auto mwB = aggregateMarkerCorners(obsB, camB);
    std::fprintf(stderr, "[site-marker] 单元A 重建标记 %zu, 单元B 重建标记 %zu\n", mwA.size(), mwB.size());
    r = solveSiteExtrinsic(mwA, mwB, cfg);
  } catch (const std::exception& e) {
    r.ok = false;
    r.msg = std::string("异常: ") + e.what();
  }
  return r;
}

}  // namespace lidar
