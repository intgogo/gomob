// STEP 5 offline test: Ceres calibration functors + solves via synthetic-data recovery.
// Camera: generate reprojections from KNOWN params (using the colorizer's verified
// world->camera, cross-validating calib<->texture), perturb init, solve -> recover.
// Lidar: hand-check P2Plane residuals; confirm GT is a cost~0 fixed point; perturbed solve converges.
#include <cmath>
#include <cstdio>
#include <random>
#include <vector>

#include "calib/calibration_pipeline.h"
#include "calib/p2plane_functor.h"
#include "calib/reprojection_functor.h"
#include "texture/colorizer.h"

using namespace lidar;
static int g_fail = 0;
#define CHECK(cond, msg)                                            \
  do {                                                              \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }    \
    else         { std::printf("  ok  : %s\n", msg); }              \
  } while (0)
static bool near(double a, double b, double t) { return std::fabs(a - b) <= t; }

int main() {
  // ---- ground-truth camera model ----
  CameraModel gt;
  gt.t_dev = {0.1, -0.2, 0.05};
  gt.q_dev = Eigen::Quaterniond(0.5, 0, 0, 0.8).normalized();
  gt.T_fix_cam = Eigen::Matrix4d::Identity();
  gt.t_cam = {0.06, 0.037, 0.038};
  gt.q_cam = Eigen::Quaterniond(0.999916, 0.00884, -0.00205, 0.00929).normalized();
  gt.intrinsic = {1873.0, 1870.0, 1926.0, 1134.0};
  gt.distortion = {-0.0188, 0.0239, -0.0006, -0.0007, -0.0208};

  std::printf("[1] ReprojectionFunctor residual ~0 at GT params\n");
  {
    Eigen::Vector3d Pw(1.5, 0.4, 3.0);
    double a = 0.2, u, v;
    Eigen::Vector3d Pc = worldToCamera(Pw, a, gt);
    bool vis = projectToPixel(Pc, gt, u, v);
    ReprojectionFunctor f(Pw, u, v, a, gt.t_dev, gt.q_dev, gt.T_fix_cam);
    double cam_t[3] = {gt.t_cam.x(), gt.t_cam.y(), gt.t_cam.z()};
    double cam_q[4] = {gt.q_cam.w(), gt.q_cam.x(), gt.q_cam.y(), gt.q_cam.z()};
    double intr[4] = {gt.intrinsic[0], gt.intrinsic[1], gt.intrinsic[2], gt.intrinsic[3]};
    double dist[5] = {gt.distortion[0], gt.distortion[1], gt.distortion[2], gt.distortion[3], gt.distortion[4]};
    double res[2];
    f(cam_t, cam_q, intr, dist, res);
    CHECK(vis && near(res[0], 0, 1e-7) && near(res[1], 0, 1e-7), "residual == 0 at GT (matches colorizer projection)");
  }

  std::printf("[2] Camera solve recovers GT from perturbed init\n");
  {
    std::mt19937 rng(7);
    std::uniform_real_distribution<double> U(-1.5, 1.5), D(2.0, 5.0), H(-0.5, 0.5);
    std::vector<ReprojObservation> obs;
    for (int i = 0; i < 60; ++i) {
      Eigen::Vector3d Pw(U(rng), U(rng), D(rng));
      double a = H(rng), u, v;
      if (projectToPixel(worldToCamera(Pw, a, gt), gt, u, v)) obs.push_back({Pw, u, v, a});
    }
    CameraCalibInput in;
    in.t_dev = gt.t_dev; in.q_dev = gt.q_dev; in.T_fix_cam = gt.T_fix_cam;
    in.cam_t = gt.t_cam + Eigen::Vector3d(0.02, -0.02, 0.01);     // perturbed init
    in.cam_q = (gt.q_cam * Eigen::Quaterniond(Eigen::AngleAxisd(0.03, Eigen::Vector3d::UnitZ()))).normalized();
    in.intrinsics = {1850, 1850, 1900, 1100};
    in.distortion = gt.distortion;  // start distortion at GT (weakly observable)
    in.max_iterations = 200;
    auto r = solveCameraCalibration(obs, in);
    std::printf("  info: obs=%zu iters=%d final_cost=%.3e\n", obs.size(), r.iterations, r.final_cost);
    CHECK(r.final_cost < 1e-6, "reprojection cost driven to ~0");
    CHECK(near(r.cam_t.x(), gt.t_cam.x(), 2e-3) && near(r.cam_t.y(), gt.t_cam.y(), 2e-3), "cam_t recovered");
    CHECK(near(r.intrinsics[0], gt.intrinsic[0], 1.0) && near(r.intrinsics[2], gt.intrinsic[2], 1.0), "intrinsics recovered");
  }

  std::printf("[3] P2PlaneFunctor residual hand-check\n");
  {
    // identity dev/lid, M_fix=I, h_offset=1, heading=0 -> P_world = p_lidar.
    Eigen::Vector3d p(1, 2, 3);
    Eigen::Vector4d plane(0, 0, 1, -3);     // z=3 plane: 0x+0y+1z-3
    Eigen::Vector3d center(1, 2, 3);        // patch center == p -> patch term 0
    P2PlaneFunctor f(p, 0.0, Eigen::Matrix4d::Identity(), plane, center);
    double dev_t[3] = {0, 0, 0}, lid_t[3] = {0, 0, 0}, h[1] = {1.0};
    double dev_q[4] = {1, 0, 0, 0}, lid_q[4] = {1, 0, 0, 0};
    double res[2];
    f(dev_t, dev_q, lid_t, lid_q, h, res);
    CHECK(near(res[0], 0, 1e-12), "residual[0] = z-3 = 0 (on plane)");
    CHECK(near(res[1], 0, 1e-12), "residual[1] = 0 (at center, h_offset=1)");
    // shift center -> patch term 200*||p-center||^2
    P2PlaneFunctor f2(p, 0.0, Eigen::Matrix4d::Identity(), plane, Eigen::Vector3d(1, 2, 2));
    f2(dev_t, dev_q, lid_t, lid_q, h, res);
    CHECK(near(res[1], 200.0 * 1.0, 1e-9), "residual[1] = 200*1^2 when center off by 1 in z");
    double h2[1] = {1.5};
    f2(dev_t, dev_q, lid_t, lid_q, h2, res);
    CHECK(near(res[1], 200.0 + 100.0 * 0.25, 1e-9), "residual[1] adds 100*(h-1)^2");
  }

  std::printf("[4] Lidar solve: GT is cost~0 fixed point, perturbed init converges\n");
  {
    // GT lidar params
    Eigen::Quaterniond dev_q = Eigen::Quaterniond(0.5, 0, 0, 0.8).normalized();
    Eigen::Vector3d dev_t(-3.5, -2.2, 0.0);
    Eigen::Quaterniond lid_q(1, 0, 0, 0);
    Eigen::Vector3d lid_t(0.02, 0.0, 0.02);
    Eigen::Matrix4d M = Eigen::Matrix4d::Identity();
    double h_off = 1.0;
    // forward to get P_world from p_lidar (heading 0 for simplicity)
    auto fwd = [&](const Eigen::Vector3d& pl) {
      Eigen::Vector3d P0 = lid_q * pl + lid_t;
      Eigen::Vector3d Pf = (M * P0.homogeneous()).head<3>();
      return Eigen::Vector3d(dev_q * Pf + dev_t);  // Rz(0)=I
    };
    // one plane through the points; place each p_lidar so its P_world == its plane center (cost 0)
    std::vector<PlaneObservation> obs;
    LidarCalibInput in;
    in.M_fix = M; in.dev_t = dev_t; in.dev_q = dev_q; in.lid_q = lid_q; in.h_offset = h_off;
    std::mt19937 rng(3); std::uniform_real_distribution<double> U(-0.3, 0.3);
    for (int k = 0; k < 6; ++k) {
      Eigen::Vector3d pl(U(rng), U(rng), U(rng));
      Eigen::Vector3d Pw = fwd(pl);
      Eigen::Vector3d n = Eigen::Vector3d(0.1 * k, 1.0, 0.2).normalized();  // distinct plane normals
      PlaneDef pd; pd.plane << n, -n.dot(Pw); pd.center = Pw;               // plane through Pw, center=Pw
      in.planes.push_back(pd);
      obs.push_back({pl, 0.0, k});
    }
    // GT init -> cost ~0
    in.lid_t = lid_t;
    auto r0 = solveLidarCalibration(obs, in);
    CHECK(r0.final_cost < 1e-12, "cost ~0 at GT lid_t");
    // perturbed lid_t, fix device -> converges and reduces cost
    in.lid_t = lid_t + Eigen::Vector3d(0.05, -0.03, 0.04);
    in.fix_device = true; in.max_iterations = 200;
    auto r1 = solveLidarCalibration(obs, in);
    std::printf("  info: converged=%d iters=%d final_cost=%.3e\n", r1.converged, r1.iterations, r1.final_cost);
    CHECK(r1.final_cost < 1e-9, "perturbed solve drives cost to ~0");
    CHECK(near(r1.lid_t.x(), lid_t.x(), 1e-3) && near(r1.lid_t.z(), lid_t.z(), 1e-3), "lid_t recovered");
  }

  std::printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}
