// R5 offline test: ICP two-unit registration recovers a known transform on synthetic data.
#include <cmath>
#include <cstdio>
#include "cloud/registration.h"

using namespace lidar;
static int g_fail = 0;
#define CHECK(cond, msg)                                            \
  do {                                                              \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }    \
    else         { std::printf("  ok  : %s\n", msg); }              \
  } while (0)

int main() {
  // Asymmetric feature-rich surface so 180° yaw is unambiguous: z = 0.25*x^2 + 0.1*y.
  auto target = std::make_shared<CloudXYZ>();
  for (int i = 0; i <= 60; ++i)
    for (int j = 0; j <= 30; ++j) {
      double x = i * 0.1, y = j * 0.1;
      target->points.emplace_back(float(x), float(y), float(0.25 * x * x + 0.1 * y));
    }
  target->width = target->points.size(); target->height = 1;

  // Source = target rotated 180° about Z + translated (the two-unit opposite-facing case).
  Eigen::Matrix4d T = Eigen::Matrix4d::Identity();
  T(0, 0) = -1; T(1, 1) = -1;          // yaw 180
  T(0, 3) = 5.0; T(1, 3) = 3.0;        // translation
  auto source = applyTransform(*target, T);

  std::printf("[1] ICP recovers the inter-unit transform\n");
  auto r = registerTwoUnits(*source, *target, 0.06, 2.0, 80);
  std::printf("  info: converged=%d best_yaw=%d fitness=%.6f\n", r.converged, r.best_yaw_deg, r.fitness);
  CHECK(r.converged, "ICP converged");
  CHECK(r.best_yaw_deg == 180, "coarse init picked 180° yaw");
  CHECK(r.fitness < 1e-3, "fitness ~0 (clouds aligned)");

  // Applying the recovered transform to source should land it back on target.
  auto aligned = applyTransform(*source, r.transform);
  double maxd = 0;
  for (std::size_t k = 0; k < aligned->size(); k += 97) {
    const auto& a = aligned->points[k];
    const auto& t = target->points[k];
    const double dx = a.x - t.x, dy = a.y - t.y, dz = a.z - t.z;
    maxd = std::max(maxd, std::sqrt(dx * dx + dy * dy + dz * dz));
  }
  std::printf("  info: max sampled point error = %.5f\n", maxd);
  CHECK(maxd < 0.05, "registered source aligns onto target (<5cm)");

  std::printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}
