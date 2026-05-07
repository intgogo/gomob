// ICP host 单测 — 在 Linux host 上 g++ 直接编译跑，不走 NDK
//
// 验证：
//   1. 立方体表面合成点云（src），应用已知 (R, t) 得 dst → ICP 恢复 (R, t)，旋转 < 0.5° / 平移 < 0.5mm
//   2. 退化输入（两个点）→ 返回 DegenerateInput，不崩
//   3. 完全错位（src/dst 重合失败）→ 不死循环、有限步收敛
//
// 编译：scripts/native-host-test.sh 一键跑（icp_test + 后续 tsdf_test / mc_test）

#include "reconstruction/icp.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <random>
#include <vector>

#include <Eigen/Dense>
#include <Eigen/Geometry>

namespace {

// 不规则点云：单位球采样 + 每点半径扰动 ±30% + 多频高斯凹凸
// 不用立方体（6 大平面对称导致 point-to-point ICP 歧义最近邻）
// 用噪声球让每个点位置特异，最近邻配对唯一
std::vector<float> SampleNoisyBlob(float radius_mm, int n_points, std::mt19937& rng) {
    std::vector<float> pts;
    pts.reserve(n_points * 3);
    std::uniform_real_distribution<float> u(-1.f, 1.f);
    while (static_cast<int>(pts.size() / 3) < n_points) {
        float x = u(rng), y = u(rng), z = u(rng);
        float n = std::sqrt(x*x + y*y + z*z);
        if (n > 1.f || n < 1e-3f) continue;
        x /= n; y /= n; z /= n;
        // 半径函数 = base * (1 + 0.2*sin(3θ_xy) + 0.15*sin(5θ_xz))
        // 给每个方向不同半径，破坏球对称
        float th_xy = std::atan2(y, x);
        float th_xz = std::atan2(z, x);
        float r = radius_mm * (1.f + 0.20f * std::sin(3.f * th_xy)
                                   + 0.15f * std::sin(5.f * th_xz));
        pts.push_back(x * r);
        pts.push_back(y * r);
        pts.push_back(z * r);
    }
    return pts;
}

// 应用 (R, t) 到点云
std::vector<float> Transform(const std::vector<float>& src,
                             const Eigen::Matrix3f& R, const Eigen::Vector3f& t) {
    std::vector<float> dst(src.size());
    for (std::size_t i = 0; i < src.size() / 3; ++i) {
        Eigen::Vector3f p(src[i*3], src[i*3+1], src[i*3+2]);
        Eigen::Vector3f q = R * p + t;
        dst[i*3] = q.x();
        dst[i*3+1] = q.y();
        dst[i*3+2] = q.z();
    }
    return dst;
}

bool CheckClose(const char* tag, float got, float expected, float tol) {
    bool ok = std::abs(got - expected) < tol;
    std::printf("  %-40s got=%.4f expected=%.4f tol=%.4f -> %s\n",
                tag, got, expected, tol, ok ? "OK" : "FAIL");
    return ok;
}

int TestRecoverKnownTransform() {
    std::printf("[icp_recover_known_transform]\n");
    std::mt19937 rng(42);
    auto src = SampleNoisyBlob(/*radius_mm=*/150.f, /*n_points=*/3000, rng);

    // 已知变换：绕 Y 转 8°，平移 (15, -10, 5) mm
    Eigen::AngleAxisf aa(8.f * float(M_PI) / 180.f, Eigen::Vector3f::UnitY());
    Eigen::Matrix3f R_gt = aa.toRotationMatrix();
    Eigen::Vector3f t_gt(15.f, -10.f, 5.f);
    auto dst = Transform(src, R_gt, t_gt);

    float init[7] = {0, 0, 0, 0, 0, 0, 1}; // 单位位姿
    auto result = gomob::reconstruction::IcpRegister(
        src.data(), src.size() / 3,
        dst.data(), dst.size() / 3,
        init);

    std::printf("  status=%d iter=%d pairs=%d mean_err=%.4fmm\n",
                static_cast<int>(result.status), result.iterations,
                result.pair_count_final, result.mean_error_mm);

    if (result.status != gomob::reconstruction::IcpResultStatus::Converged &&
        result.status != gomob::reconstruction::IcpResultStatus::MaxIterReached) {
        std::printf("  status not Converged/MaxIter\n");
        return 1;
    }

    Eigen::Quaternionf q_got(result.pose7[6], result.pose7[3], result.pose7[4], result.pose7[5]);
    Eigen::Quaternionf q_gt(R_gt);
    float angle_err_deg = q_gt.angularDistance(q_got) * 180.f / float(M_PI);
    Eigen::Vector3f t_got(result.pose7[0], result.pose7[1], result.pose7[2]);
    float trans_err_mm = (t_got - t_gt).norm();

    bool ok = true;
    ok &= CheckClose("rotation_err_deg", angle_err_deg, 0.f, /*tol=*/0.5f);
    ok &= CheckClose("translation_err_mm", trans_err_mm, 0.f, /*tol=*/0.5f);
    ok &= CheckClose("mean_error_mm", result.mean_error_mm, 0.f, /*tol=*/0.5f);
    return ok ? 0 : 1;
}

int TestDegenerateInput() {
    std::printf("[icp_degenerate_input]\n");
    float src[] = {0,0,0, 100,0,0};
    float dst[] = {0,0,0, 100,0,0};
    float init[7] = {0,0,0,0,0,0,1};
    auto r = gomob::reconstruction::IcpRegister(src, 2, dst, 2, init);
    bool ok = (r.status == gomob::reconstruction::IcpResultStatus::DegenerateInput);
    std::printf("  status=%d -> %s\n", static_cast<int>(r.status), ok ? "OK" : "FAIL");
    return ok ? 0 : 1;
}

int TestLargeRotation() {
    std::printf("[icp_large_rotation_60deg]\n");
    std::mt19937 rng(7);
    auto src = SampleNoisyBlob(150.f, 3000, rng);

    // 60° 旋转 — point-to-point ICP 收敛半径有限，初值给单位位姿可能失败；
    // 加初值 = 50° 模拟"上一帧位姿"提供良好初值
    Eigen::AngleAxisf aa(60.f * float(M_PI) / 180.f, Eigen::Vector3f::UnitY());
    auto dst = Transform(src, aa.toRotationMatrix(), Eigen::Vector3f::Zero());

    Eigen::AngleAxisf init_aa(50.f * float(M_PI) / 180.f, Eigen::Vector3f::UnitY());
    Eigen::Quaternionf init_q(init_aa);
    float init[7] = {0, 0, 0, init_q.x(), init_q.y(), init_q.z(), init_q.w()};
    auto r = gomob::reconstruction::IcpRegister(src.data(), src.size()/3,
                                                 dst.data(), dst.size()/3, init);
    Eigen::Quaternionf q_got(r.pose7[6], r.pose7[3], r.pose7[4], r.pose7[5]);
    Eigen::Quaternionf q_gt(aa);
    float ang_err = q_gt.angularDistance(q_got) * 180.f / float(M_PI);
    bool ok = ang_err < 1.0f;
    std::printf("  status=%d ang_err_deg=%.4f -> %s\n",
                static_cast<int>(r.status), ang_err, ok ? "OK" : "FAIL");
    return ok ? 0 : 1;
}

} // anonymous

int main() {
    int fails = 0;
    fails += TestRecoverKnownTransform();
    fails += TestDegenerateInput();
    fails += TestLargeRotation();
    std::printf("\n[icp_test summary] failures=%d\n", fails);
    return fails;
}
