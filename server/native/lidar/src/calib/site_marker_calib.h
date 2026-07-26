// site_marker_calib — 现场共享 ArUco 标记场，自标定两单元间外参 B→A（在树融合直接吃 site_extrinsic.json）。
//
// 原理（自标定，A 系=世界）：每个单元相机内参/畸变/相机↔轴已知（设备 calib_10x.json）。
// 现场贴已知边长的 ArUco(DICT_APRILTAG_36h11)，两单元各 pan 一圈拍图：
//   1. 每张图检测标记 → solvePnP(已知边长) → 标记 4 角点在**相机系**坐标（带标记朝向，6DoF 位姿）；
//   2. cameraToWorld(角点, heading) → 角点在**该单元自身系**；多 heading 对每角点求均值 → markerCorners[id]；
//   3. 两单元公共标记的 4 角点给出 3D↔3D 对应 → Eigen::umeyama 求刚体 B→A。
// 用 4 角点（而非仅中心）是关键：单个标记的角点非共线、含朝向，足以完全约束旋转，故现场只有少量
// 甚至共面标记也能解准（仅中心点的旧法在 ≤4 个/共面标记下旋转欠约束，会偏十几到二十几度）。
// 无需测量靶坐标、无需特制板——贴就行。详见 docs/architecture/17-laser-camera-lidar-calibration.md §"多单元拼接"。
//
// 分层：CORE（纯 Eigen，可 host 测）= aggregateMarkerCorners + solveSiteExtrinsic；
//       FRONT-END（OpenCV）= detectUnitCenters（图像→标记角点相机系观测）。harness 直接喂合成观测测 CORE。
#pragma once

#include <array>
#include <map>
#include <string>
#include <vector>
#include <Eigen/Geometry>
#include "texture/colorizer.h"  // CameraModel + worldToCamera/cameraToWorld

namespace lidar {

// 一个标记的「相机系观测」：某帧（航向 heading_rad）下，标记 id 的 4 角点在相机光心系坐标。
// 角点 = solvePnP 位姿 × 标记物点（标记系四角），带标记朝向；center_cam 保留（=tvec）供预览/兼容。
struct MarkerCenterObs {
  int                            id{-1};
  Eigen::Vector3d                center_cam{0, 0, 0};  // solvePnP 的 tvec（米）
  std::array<Eigen::Vector3d, 4> corners_cam{};        // 4 角点（相机系，米），ArUco 角点序
  double                         heading_rad{0};
};

struct SiteMarkerConfig {
  double marker_len_m{0.15};  // 现场打印的 ArUco 物理边长（米）——必须与实际一致
  int    min_common{2};       // 两单元公共标记下限（角点法单标记即可解，留 2 做冗余/校验）
  double max_rms_m{0.005};    // 生产外廓 1% 预算：531mm 宽度对应约 5.3mm，RMS 须≤5mm
};

struct SiteMarkerResult {
  Eigen::Matrix4d b_to_a{Eigen::Matrix4d::Identity()};  // B 系 → A 系 刚体（米平移，与在树融合同约定）
  int             n_common{0};                           // 参与求解的公共标记数
  double          rms_m{0};                              // 对应点对齐 RMS（米）
  bool            ok{false};
  std::string     msg;
};

// ---- CORE（纯 Eigen，无 OpenCV，供 host harness）----

// 标记角点世界坐标：每个标记 4 角点在该单元自身系（米），ArUco 角点序。
using MarkerCornersWorld = std::map<int, std::array<Eigen::Vector3d, 4>>;

// 把「相机系观测」的 4 角点经 cameraToWorld 投到该单元自身系，按 id 跨帧对每个角点求均值。
MarkerCornersWorld aggregateMarkerCorners(const std::vector<MarkerCenterObs>& obs,
                                          const CameraModel& cam);

// 两单元的角点世界坐标，公共 id 的 4 角点 3D↔3D → umeyama 刚体（src=B, dst=A）→ B→A。
SiteMarkerResult solveSiteExtrinsic(const MarkerCornersWorld& cornersA,
                                    const MarkerCornersWorld& cornersB,
                                    const SiteMarkerConfig& cfg);

// ---- FRONT-END（OpenCV：检测 + solvePnP）----

// 检测一个单元的图像集（目录下 *_h<角度>.{jpg,png}，角度=度）→ 标记角点相机系观测。
// cam 提供内参/畸变（solvePnP 用）。失败/无图返回空。需 OpenCV+aruco。
std::vector<MarkerCenterObs> detectUnitCenters(const std::string& image_dir,
                                               const CameraModel& cam,
                                               const SiteMarkerConfig& cfg);

// 端到端：两单元图像目录 + 各自 config/calib → B→A。便于 CLI 调用。
SiteMarkerResult calibrateSiteMarkers(const std::string& image_dir_a, const std::string& config_a,
                                      const std::string& calib_a,
                                      const std::string& image_dir_b, const std::string& config_b,
                                      const std::string& calib_b,
                                      const SiteMarkerConfig& cfg);

}  // namespace lidar
