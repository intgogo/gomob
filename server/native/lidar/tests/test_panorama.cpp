// STEP 6b offline test: cylindrical panorama projection (azimuth->col, z->row), color, occlusion.
#include <cstdio>
#include "texture/panorama.h"

using namespace lidar;
static int g_fail = 0;
#define CHECK(cond, msg)                                            \
  do {                                                              \
    if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }    \
    else         { std::printf("  ok  : %s\n", msg); }              \
  } while (0)

static PointXYZRGB pt(float x, float y, float z, int r, int g, int b) {
  PointXYZRGB p; p.x = x; p.y = y; p.z = z; p.r = r; p.g = g; p.b = b; return p;
}

int main() {
  PanoramaOptions opt; opt.width = 360; opt.height = 100;

  std::printf("[1] dimensions + empty\n");
  {
    CloudXYZRGB empty;
    CHECK(renderCylindricalPanorama(empty, opt).empty(), "empty cloud -> empty image");
  }

  std::printf("[2] azimuth->column, z->row, color\n");
  {
    CloudXYZRGB c;
    c.points.push_back(pt(5, 0, 5, 255, 0, 0));   // az=0 -> mid col; z=5 mid row; red
    c.points.push_back(pt(-5, 0, 0, 0, 255, 0));  // az=pi -> last col; z=0 -> bottom row(top? zmax=5 here)
    c.width = c.points.size(); c.height = 1;
    cv::Mat img = renderCylindricalPanorama(c, opt);
    CHECK(img.rows == 100 && img.cols == 360, "image is 360x100");
    // az=0 -> col round(0.5*359)=180; z=5=zmax -> row 0. Scan a small window for the red pixel.
    bool red = false; int rcol = -1;
    for (int x = 175; x <= 185; ++x) { auto p = img.at<cv::Vec3b>(0, x); if (p[2] == 255 && p[1] == 0 && p[0] == 0) { red = true; rcol = x; } }
    CHECK(red, "az=0,z=max point is red near mid-column of row 0");
    std::printf("  info: red pixel at row0 col=%d\n", rcol);
  }

  std::printf("[3] nearest-by-range occlusion\n");
  {
    CloudXYZRGB c;
    c.points.push_back(pt(5, 0, 2, 0, 0, 255));   // far (range 5), blue
    c.points.push_back(pt(1, 0, 2, 255, 0, 0));   // near (range 1), red -- same az & z -> same pixel
    c.width = 2; c.height = 1;
    cv::Mat img = renderCylindricalPanorama(c, opt);
    // both map to same col (az=0) and same row (single z -> row 0); nearer (red) must win
    bool red = false, anyblue = false;
    for (int x = 175; x <= 185; ++x) { auto p = img.at<cv::Vec3b>(0, x); if (p[2] == 255 && p[0] == 0) red = true; if (p[0] == 255) anyblue = true; }
    CHECK(red && !anyblue, "nearer point (red) wins over farther (blue)");
  }

  std::printf("\n%s (%d failure%s)\n", g_fail ? "FAILED" : "PASSED", g_fail, g_fail == 1 ? "" : "s");
  return g_fail ? 1 : 0;
}
