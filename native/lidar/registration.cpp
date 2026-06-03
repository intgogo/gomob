#include "lidar/registration.h"

#include <array>
#include <cmath>
#include <vector>
#include "reconstruction/icp.h"

namespace gomob::lidar {
namespace {

Eigen::Vector3f centroid(const Cloud& c) {
    Eigen::Vector3f s = Eigen::Vector3f::Zero();
    for (const auto& p : c) s += p;
    return c.empty() ? s : (s / static_cast<float>(c.size()));
}

std::vector<float> flatten(const Cloud& c) {
    std::vector<float> f;
    f.reserve(c.size() * 3);
    for (const auto& p : c) { f.push_back(p.x()); f.push_back(p.y()); f.push_back(p.z()); }
    return f;
}

// pose7 [tx,ty,tz, qx,qy,qz,qw] -> 4×4
Eigen::Matrix4f poseToMat(const std::array<float, 7>& p) {
    Eigen::Quaternionf q(p[6], p[3], p[4], p[5]);
    q.normalize();
    Eigen::Matrix4f T = Eigen::Matrix4f::Identity();
    T.block<3, 3>(0, 0) = q.toRotationMatrix();
    T(0, 3) = p[0]; T(1, 3) = p[1]; T(2, 3) = p[2];
    return T;
}

std::array<float, 7> matToPose(const Eigen::Matrix4f& T) {
    Eigen::Quaternionf q(Eigen::Matrix3f(T.block<3, 3>(0, 0)));
    q.normalize();
    return {T(0, 3), T(1, 3), T(2, 3), q.x(), q.y(), q.z(), q.w()};
}

}  // namespace

RegistrationResult registerTwoUnits(const Cloud& source, const Cloud& target,
                                    float reject_pair_dist_mm, int max_iter) {
    RegistrationResult best;
    if (source.empty() || target.empty()) return best;

    const std::vector<float> src = flatten(source);
    const std::vector<float> dst = flatten(target);
    const Eigen::Vector3f cs = centroid(source);
    const Eigen::Vector3f ct = centroid(target);

    gomob::reconstruction::IcpConfig cfg;
    cfg.max_iter = max_iter;
    cfg.reject_pair_dist_mm = reject_pair_dist_mm;
    cfg.spatial_hash_cell_mm = reject_pair_dist_mm * 0.5f;
    cfg.convergence_tol_mm = 0.5f;

    for (int yaw = 0; yaw < 360; yaw += 90) {
        const float a = yaw * static_cast<float>(M_PI) / 180.0f;
        const float c = std::cos(a), s = std::sin(a);
        Eigen::Matrix3f R = Eigen::Matrix3f::Identity();
        R(0, 0) = c; R(0, 1) = -s; R(1, 0) = s; R(1, 1) = c;  // yaw about Z
        // 绕 source 质心旋转再平移到 target 质心：T = [R | ct - R*cs]
        Eigen::Matrix4f init = Eigen::Matrix4f::Identity();
        init.block<3, 3>(0, 0) = R;
        init.block<3, 1>(0, 3) = ct - R * cs;

        const std::array<float, 7> p0 = matToPose(init);
        const auto r = gomob::reconstruction::IcpRegister(
            src.data(), source.size(), dst.data(), target.size(), p0.data(), cfg);

        const bool usable = (r.status == gomob::reconstruction::IcpResultStatus::Converged ||
                             r.status == gomob::reconstruction::IcpResultStatus::MaxIterReached);
        if (usable && r.mean_error_mm < best.fitness) {
            best.fitness = r.mean_error_mm;
            best.transform = poseToMat(r.pose7);
            best.converged = (r.status == gomob::reconstruction::IcpResultStatus::Converged);
            best.best_yaw_deg = yaw;
        }
    }
    return best;
}

}  // namespace gomob::lidar
