#include "measurement/dimensions.h"

#include <cmath>
#include <limits>

namespace gomob::measure {

ObbXY minAreaRectXY(const Cloud& cloud, float step_deg) {
    ObbXY r;
    if (cloud.empty()) return r;
    if (step_deg <= 0.f) step_deg = 0.25f;

    const float kDeg2Rad = 3.14159265358979323846f / 180.f;
    float best_area = std::numeric_limits<float>::max();

    for (float a = 0.f; a < 90.f; a += step_deg) {
        const float c = std::cos(a * kDeg2Rad), s = std::sin(a * kDeg2Rad);
        float umin = std::numeric_limits<float>::max(), umax = -umin;
        float vmin = umin, vmax = umax;
        for (const auto& p : cloud) {
            const float u = p.x() * c + p.y() * s;   // 旋转后坐标轴投影
            const float v = -p.x() * s + p.y() * c;
            if (u < umin) umin = u;
            if (u > umax) umax = u;
            if (v < vmin) vmin = v;
            if (v > vmax) vmax = v;
        }
        const float du = umax - umin, dv = vmax - vmin;
        const float area = du * dv;
        if (area < best_area) {
            best_area = area;
            r.length = std::max(du, dv);
            r.width = std::min(du, dv);
            r.angle_deg = a;
        }
    }
    return r;
}

}  // namespace gomob::measure
