// Offline test for cloud transform / fusion / downsample / crop (no hardware).
#include <cmath>
#include <cstdio>
#include "cloud/fusion.h"

using namespace lidar;

static int g_fail = 0;
#define CHECK(cond, msg)                                            \
  do {                                                              \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }    \
    else         { std::printf("  ok  : %s\n", msg); }              \
  } while (0)
static bool near(double a, double b, double t) { return std::fabs(a - b) <= t; }

static CloudXYZ::Ptr grid(int n) {  // n×n×1 unit grid at z=0
  auto c = std::make_shared<CloudXYZ>();
  for (int i = 0; i < n; ++i)
    for (int j = 0; j < n; ++j) c->points.emplace_back(float(i), float(j), 0.0f);
  c->width = c->points.size(); c->height = 1;
  return c;
}

int main() {
  std::printf("[1] transformCloud (translation + scale)\n");
  {
    auto c = grid(4);                         // 16 pts
    Pose p; p.offset = {10, 20, 30}; p.scale = 2.0;  // identity rot
    auto t = transformCloud(*c, p);
    CHECK(t->size() == 16, "count preserved");
    // point (1,1,0) -> 2*(1,1,0)+(10,20,30) = (12,22,30)
    bool found = false;
    for (auto& q : t->points) if (near(q.x, 12, 1e-4) && near(q.y, 22, 1e-4) && near(q.z, 30, 1e-4)) found = true;
    CHECK(found, "(1,1,0) -> (12,22,30)");
  }

  std::printf("[2] fuse (union count == sum)\n");
  {
    auto a = grid(4);   // 16
    auto b = grid(3);   // 9
    auto f = fuse({a, b});
    CHECK(f->size() == 25, "16 + 9 == 25");
  }

  std::printf("[3] randomKeep (ratio semantics)\n");
  {
    auto c = grid(10);  // 100 pts
    CHECK(randomKeep(*c, 0.5)->size() == 50, "ratio 0.5 -> 50");
    CHECK(randomKeep(*c, 1.0)->size() == 100, "ratio 1.0 -> keep all");
    CHECK(randomKeep(*c, 2.0)->size() == 100, "ratio >=1 -> keep all");
    CHECK(randomKeep(*c, 0.0)->size() == 0, "ratio 0 -> empty");
  }

  std::printf("[4] cropBox (AABB subset)\n");
  {
    auto c = grid(10);  // x,y in 0..9
    auto in = cropBox(*c, {2, 2, -1}, {5, 5, 1}, true);   // x,y in [2,5] -> 4x4 = 16
    CHECK(in->size() == 16, "inside [2,5]^2 -> 16");
    auto out = cropBox(*c, {2, 2, -1}, {5, 5, 1}, false); // complement -> 100-16 = 84
    CHECK(out->size() == 84, "outside -> 84");
  }

  std::printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}
