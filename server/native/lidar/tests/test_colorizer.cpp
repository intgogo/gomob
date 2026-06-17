// STEP 6 offline test: worldToCamera<->cameraToWorld round-trip (real config params),
// pinhole projection geometry, and best-frame coloring with GRAY(128) default.
#include <cmath>
#include <cstdio>
#include <opencv2/core.hpp>

#include "config/config_yaml.h"
#include "texture/colorizer.h"

#ifndef LIDAR_SAMPLE_DIR
#define LIDAR_SAMPLE_DIR "sample"
#endif

using namespace lidar;
static int g_fail = 0;
#define CHECK(cond, msg)                                            \
  do {                                                              \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }    \
    else         { std::printf("  ok  : %s\n", msg); }              \
  } while (0)
static bool near(double a, double b, double t) { return std::fabs(a - b) <= t; }

int main() {
  const std::string sample = LIDAR_SAMPLE_DIR;

  std::printf("[1] worldToCamera <-> cameraToWorld round-trip (real config)\n");
  try {
    auto cfg = loadConfig(sample + "/config.yaml");
    auto m = CameraModel::fromConfig(cfg);
    const double headings[] = {0.0, 0.3, -1.2, 2.0};
    const Eigen::Vector3d pts[] = {{1, 2, 3}, {-2, 0.5, 4}, {0, 0, 5}, {3, -1, 2}};
    bool ok = true;
    for (double a : headings)
      for (const auto& Pw : pts) {
        Eigen::Vector3d Pc = worldToCamera(Pw, a, m);
        Eigen::Vector3d back = cameraToWorld(Pc, a, m);
        if (!near((back - Pw).norm(), 0.0, 1e-9)) ok = false;
      }
    CHECK(ok, "round-trip exact for 16 (point,heading) pairs");
  } catch (const std::exception& e) {
    std::printf("  SKIP config (%s)\n", e.what());
  }

  std::printf("[2] pinhole projection geometry (no distortion)\n");
  {
    CameraModel m;  // identity transforms
    m.intrinsic = {1000, 1000, 500, 500};
    m.distortion = {0, 0, 0, 0, 0};
    double u, v;
    CHECK(projectToPixel({0, 0, 5}, m, u, v) && near(u, 500, 1e-9) && near(v, 500, 1e-9), "optical axis -> (cx,cy)");
    // Pc=[1,2,5]: xn=0.2,yn=0.4 -> u=1000*0.2+500=700, v=1000*0.4+500=900
    CHECK(projectToPixel({1, 2, 5}, m, u, v) && near(u, 700, 1e-9) && near(v, 900, 1e-9), "off-axis pinhole");
    CHECK(!projectToPixel({1, 1, -1}, m, u, v), "behind camera rejected");
  }

  std::printf("[3] best-frame coloring + gray default\n");
  {
    CameraModel m;  // identity chain
    m.intrinsic = {1000, 1000, 500, 500};
    m.distortion = {0, 0, 0, 0, 0};
    m.image_width = 1000; m.image_height = 1000; m.safe_pixel_margin = 0; m.near_plane = 0.1;
    cv::Mat red(1000, 1000, CV_8UC3, cv::Scalar(0, 0, 255));  // BGR red
    std::vector<CameraFrame> frames{{red, 0.0}};

    CloudXYZ cloud;
    cloud.points.emplace_back(0.f, 0.f, 5.f);    // -> pixel (500,500) inside -> red
    cloud.points.emplace_back(0.f, 0.f, -5.f);   // behind -> unmapped -> gray
    cloud.width = 2; cloud.height = 1;

    std::size_t mapped = 0;
    auto col = colorize(cloud, frames, m, &mapped);
    CHECK(col->size() == 2, "2 colored points");
    CHECK(mapped == 1, "exactly 1 point mapped");
    CHECK(col->points[0].r == 255 && col->points[0].g == 0 && col->points[0].b == 0, "front point -> red");
    CHECK(col->points[1].r == 128 && col->points[1].g == 128 && col->points[1].b == 128, "behind point -> gray(128)");
  }

  std::printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}
