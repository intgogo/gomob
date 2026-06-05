#include "lidar/cloud_build.h"

#include <cmath>

namespace gomob::lidar {

Eigen::Vector3f lineToWorld(float v_angle_rad, float dist_mm, float h_angle_deg, const SynthesisParams& p) {
    const float r = dist_mm * p.range_scale;
    Eigen::Vector3f P_line(std::cos(v_angle_rad) * r, std::sin(v_angle_rad) * r, 0.0f);
    Eigen::Vector3f P = p.q_lidar_rot.normalized() * P_line;          // R(q_lidar_rot)
    P += p.lidar_corr_offset;                                         // 平移
    Eigen::Vector3f Pf = (p.T_fix_lidar * P.homogeneous()).head<3>(); // 4×4 固定变换
    const float h = h_angle_deg * static_cast<float>(M_PI) / 180.0f;
    const float c = std::cos(h), s = std::sin(h);
    Eigen::Vector3f Ph(Pf.x() * c - Pf.y() * s,                       // Rz(+h)
                       Pf.x() * s + Pf.y() * c,
                       Pf.z());
    return p.q_b2w.normalized() * Ph + p.b2w_offset;                  // R(q_b2w) + 平移
}

Cloud buildFromLDR(const std::vector<LdrFrame>& frames, const SynthesisParams& p) {
    Cloud out;
    for (const auto& fr : frames)
        for (const auto& pt : fr.points)
            out.push_back(lineToWorld(pt.v_angle_deg * static_cast<float>(M_PI) / 180.0f,
                                      pt.dist_mm, fr.h_angle_deg, p));
    return out;
}

}  // namespace gomob::lidar
