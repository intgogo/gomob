// 合成场离线测试：已知标记位姿(中心+朝向) + 已知 B→A，喂解算器核心(aggregateMarkerCorners + umeyama)，
// 验证复原。不碰真实图像/OpenCV 检测——只验证几何/帧/umeyama 逻辑（前端 solvePnP 由真机数据验证）。
//
// 核心改动验证：用每个标记的 4 角点(带 solvePnP 位姿/朝向)而非仅中心点求外参。重点 case [3]：
// 仅 2 个共面标记——旧的"仅中心点"法在这种少量/共面布置下旋转欠约束(实测真机偏 ~20°)，角点法应复原准。
#include <array>
#include <cmath>
#include <cstdio>
#include <map>
#include <random>
#include <vector>
#include <Eigen/Geometry>
#include "calib/site_marker_calib.h"

using namespace lidar;
using Eigen::Vector3d;
using Eigen::Matrix3d;
using Eigen::Matrix4d;
using Eigen::Quaterniond;

static int g_fail = 0;
#define CHECK(cond, msg)                                         \
  do {                                                           \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; } \
    else         { std::printf("  ok  : %s\n", msg); }           \
  } while (0)

static CameraModel makeCam(double fixYawDeg, const Vector3d& tcam, double corrDeg) {
  CameraModel m;
  m.t_dev = Vector3d::Zero();
  m.q_dev = Quaterniond::Identity();
  Matrix3d Rfix = Eigen::AngleAxisd(fixYawDeg * M_PI / 180.0, Vector3d::UnitY()).toRotationMatrix() *
                  Eigen::AngleAxisd(-M_PI / 2, Vector3d::UnitX()).toRotationMatrix();
  m.T_fix_cam = Matrix4d::Identity();
  m.T_fix_cam.topLeftCorner<3, 3>() = Rfix;
  m.t_cam = tcam;
  m.q_cam = Quaterniond(Eigen::AngleAxisd(corrDeg * M_PI / 180.0, Vector3d::UnitZ()));
  m.intrinsic = {2000, 2000, 1920, 1080};
  m.distortion = {0, 0, 0, 0, 0};
  return m;
}

// 一个标记：A 系中心 + 朝向(标记平面法向由 R 给)。
struct Marker { int id; Vector3d center; Matrix3d R; };

static const double kMarkerLen = 0.15;
// 标记系四角(z=0，中心原点)，序同检测/solvePnP。
static std::array<Vector3d, 4> objCorners() {
  const double h = kMarkerLen / 2.0;
  return {Vector3d(-h, h, 0), Vector3d(h, h, 0), Vector3d(h, -h, 0), Vector3d(-h, -h, 0)};
}
// 标记 4 角点在 A 系(世界)。
static std::array<Vector3d, 4> cornersWorldA(const Marker& m) {
  auto obj = objCorners();
  std::array<Vector3d, 4> w;
  for (int k = 0; k < 4; ++k) w[k] = m.center + m.R * obj[k];
  return w;
}

// 合成两单元角点观测：把 A 系角点经 AToB 转到 B 系，再 worldToCamera 投到各自相机系，多 heading 累积。
// noise_m>0 时给相机系角点加高斯噪声。
static void synth(const std::vector<Marker>& markers, const Matrix4d& AToB,
                  const CameraModel& camA, const CameraModel& camB, const std::vector<double>& headings,
                  double noise_m, std::vector<MarkerCenterObs>& obsA, std::vector<MarkerCenterObs>& obsB) {
  std::mt19937 rng(20260617);
  std::normal_distribution<double> noise(0.0, noise_m);
  auto add = [&](std::vector<MarkerCenterObs>& out, int id, double h,
                 const std::array<Vector3d, 4>& worldCorners, const CameraModel& cam) {
    MarkerCenterObs o;
    o.id = id;
    o.heading_rad = h;
    Vector3d c = Vector3d::Zero();
    for (int k = 0; k < 4; ++k) {
      Vector3d cc = worldToCamera(worldCorners[k], h, cam);
      if (noise_m > 0) cc += Vector3d(noise(rng), noise(rng), noise(rng));
      o.corners_cam[k] = cc;
      c += cc;
    }
    o.center_cam = c / 4.0;
    out.push_back(o);
  };
  for (double h : headings) {
    for (const auto& m : markers) {
      auto wA = cornersWorldA(m);
      std::array<Vector3d, 4> wB;
      for (int k = 0; k < 4; ++k) wB[k] = (AToB * wA[k].homogeneous()).head<3>();
      add(obsA, m.id, h, wA, camA);
      add(obsB, m.id, h, wB, camB);
    }
  }
}

static Matrix4d makeBToA(double yawDeg, const Vector3d& t) {
  Matrix4d T = Matrix4d::Identity();
  double a = yawDeg * M_PI / 180.0;
  Matrix3d Rz;
  Rz << std::cos(a), -std::sin(a), 0, std::sin(a), std::cos(a), 0, 0, 0, 1;
  T.topLeftCorner<3, 3>() = Rz;
  T.block<3, 1>(0, 3) = t;
  return T;
}

int main() {
  const std::vector<double> headings = {-0.3, -0.1, 0.1, 0.3};
  const Matrix4d BToA = makeBToA(25.0, Vector3d(0.5, -0.3, 0.1));
  const Matrix4d AToB = BToA.inverse();
  CameraModel camA = makeCam(0, Vector3d(0.06, 0.04, 0.05), 2.0);
  CameraModel camB = makeCam(15, Vector3d(-0.05, 0.03, 0.06), -1.5);

  // 标记带朝向 R（不同倾斜），中心铺开、非共面。
  auto rotXYZ = [](double xd, double yd, double zd) {
    return (Eigen::AngleAxisd(zd * M_PI / 180, Vector3d::UnitZ()) *
            Eigen::AngleAxisd(yd * M_PI / 180, Vector3d::UnitY()) *
            Eigen::AngleAxisd(xd * M_PI / 180, Vector3d::UnitX())).toRotationMatrix();
  };
  std::vector<Marker> markers = {
      {0, {1.0, 2.0, 0.5}, rotXYZ(10, -20, 5)},  {1, {-1.5, 1.0, 0.3}, rotXYZ(-15, 10, 0)},
      {2, {0.5, -2.0, -0.4}, rotXYZ(5, 30, -10)}, {3, {2.0, 0.0, 0.8}, rotXYZ(0, 0, 25)},
      {4, {-0.5, -1.0, 0.2}, rotXYZ(20, 5, 15)},  {5, {1.2, 1.8, -0.3}, rotXYZ(-8, -12, 8)}};

  std::printf("[1] aggregateMarkerCorners 复原标记角点世界坐标 (cameraToWorld 逆 worldToCamera)\n");
  std::vector<MarkerCenterObs> obsA, obsB;
  synth(markers, AToB, camA, camB, headings, 0.0, obsA, obsB);
  auto mwA = aggregateMarkerCorners(obsA, camA);
  auto mwB = aggregateMarkerCorners(obsB, camB);
  CHECK(mwA.size() == 6 && mwB.size() == 6, "两单元各重建 6 标记");
  double maxC = 0;
  for (const auto& m : markers) {
    auto wA = cornersWorldA(m);
    for (int k = 0; k < 4; ++k) maxC = std::max(maxC, (mwA[m.id][k] - wA[k]).norm());
  }
  CHECK(maxC < 1e-9, "单元A 角点复原 A 系坐标");

  std::printf("[2] solveSiteExtrinsic (角点 umeyama) 复原 B->A\n");
  SiteMarkerConfig cfg;
  cfg.min_common = 2;
  cfg.max_rms_m = 1e-6;
  auto r = solveSiteExtrinsic(mwA, mwB, cfg);
  CHECK(r.ok, "解算 ok");
  CHECK(r.n_common == 6, "6 公共标记");
  double errT = (r.b_to_a - BToA).cwiseAbs().maxCoeff();
  std::printf("      max|B->A_est - B->A_true| = %.3e, rms = %.3em\n", errT, r.rms_m);
  CHECK(errT < 1e-6, "复原 B->A == 已知");

  std::printf("[3] 关键：仅 2 个共面标记(法向 +z, 全在 z=0 平面) —— 旧中心法失败的布置，角点法应复原\n");
  {
    std::vector<Marker> coplanar = {
        {10, {0.8, 0.5, 0.0}, Matrix3d::Identity()},   // 平贴地面(法向 +z)
        {11, {-0.7, -0.6, 0.0}, Matrix3d::Identity()}};
    std::vector<MarkerCenterObs> cA, cB;
    synth(coplanar, AToB, camA, camB, headings, 0.0, cA, cB);
    auto r3 = solveSiteExtrinsic(aggregateMarkerCorners(cA, camA), aggregateMarkerCorners(cB, camB), cfg);
    double e3 = (r3.b_to_a - BToA).cwiseAbs().maxCoeff();
    std::printf("      2 共面标记: n_common=%d, max|ΔB->A|=%.3e, rms=%.3em\n", r3.n_common, e3, r3.rms_m);
    CHECK(r3.ok && r3.n_common == 2, "2 共面标记可解(min_common=2)");
    CHECK(e3 < 1e-6, "2 共面标记复原 B->A 准(角点带朝向，旋转有约束)");
  }

  std::printf("[4] 公共标记不足 → 判不可信\n");
  MarkerCornersWorld one = {{0, mwB[0]}};
  auto r2 = solveSiteExtrinsic(mwA, one, cfg);
  CHECK(!r2.ok, "公共标记 1<2 被拒");

  std::printf("[5] 噪声鲁棒性：相机系角点加 σ=2mm 高斯噪声，多帧多角点应被均值/umeyama 平滑\n");
  {
    std::vector<MarkerCenterObs> nA, nB;
    synth(markers, AToB, camA, camB, headings, 0.002, nA, nB);
    SiteMarkerConfig ncfg;
    ncfg.min_common = 2;
    ncfg.max_rms_m = 0.02;
    auto rn = solveSiteExtrinsic(aggregateMarkerCorners(nA, camA), aggregateMarkerCorners(nB, camB), ncfg);
    double te = (rn.b_to_a - BToA).cwiseAbs().maxCoeff();
    std::printf("      噪声下 max|ΔB->A| = %.3em, rms = %.3em\n", te, rn.rms_m);
    CHECK(rn.ok, "噪声下仍达标(rms<2cm)");
    CHECK(te < 0.01, "噪声下 B->A 误差 <1cm");
  }

  std::printf(g_fail ? "FAILED %d\n" : "ALL PASS\n", g_fail);
  return g_fail ? 1 : 0;
}
