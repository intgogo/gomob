// fusion/ — 主摄 RGB 与深度点云的"主从绑定"投影回填
// 关系: P_rgb = R · P_depth + t；外参由 calibration 阶段产出。

#include <cstdint>
#include <cstring>
#include <vector>

namespace gomob::fusion {

std::vector<uint8_t> Colorize(
        const float* points, size_t pointCount,
        const uint8_t* rgb, int rgbWidth, int rgbHeight,
        double fx, double fy, double cx, double cy,
        const double* R, const double* t) {
    std::vector<uint8_t> out(pointCount * 3, 0);
    for (size_t i = 0; i < pointCount; ++i) {
        double x = points[i * 3 + 0];
        double y = points[i * 3 + 1];
        double z = points[i * 3 + 2];
        if (z <= 0) continue;

        double Xr = R[0] * x + R[1] * y + R[2] * z + t[0];
        double Yr = R[3] * x + R[4] * y + R[5] * z + t[1];
        double Zr = R[6] * x + R[7] * y + R[8] * z + t[2];
        if (Zr <= 0) continue;

        int u = static_cast<int>(fx * Xr / Zr + cx + 0.5);
        int v = static_cast<int>(fy * Yr / Zr + cy + 0.5);
        if (u < 0 || u >= rgbWidth || v < 0 || v >= rgbHeight) continue;

        const uint8_t* p = &rgb[(v * rgbWidth + u) * 3];
        out[i * 3 + 0] = p[0];
        out[i * 3 + 1] = p[1];
        out[i * 3 + 2] = p[2];
    }
    return out;
}

} // namespace gomob::fusion
