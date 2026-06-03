// 激光几何内核 host 单测（Eigen-only，无 PCL）：union/randomKeep/cropBox/transform 计数精确，
// ICP 4-yaw 复原已知 180° 机间变换，reconstructVehicle 端到端。单位 mm。
#include <cmath>
#include <cstdio>
#include "lidar/cloud_build.h"
#include "lidar/fusion.h"
#include "lidar/registration.h"
#include "lidar/scan_vehicle.h"

using namespace gomob::lidar;
static int g_fail = 0;
#define CHECK(cond, msg)                                          \
    do {                                                          \
        if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; } \
        else         { std::printf("  ok  : %s\n", msg); }        \
    } while (0)

static Cloud featureSurface() {
    // 非对称特征面（mm）：z = 2e-4·x² + 0.1·y，让 180° yaw 无歧义。
    Cloud c;
    for (int i = 0; i <= 60; ++i)
        for (int j = 0; j <= 30; ++j) {
            const float x = i * 50.0f, y = j * 50.0f;
            c.emplace_back(x, y, 2e-4f * x * x + 0.1f * y);
        }
    return c;
}

int main() {
    Cloud A = featureSurface();

    // B = A 绕 Z 转 180° + 平移（两台对扫的相反朝向，mm）
    Eigen::Matrix4f T = Eigen::Matrix4f::Identity();
    T(0, 0) = -1; T(1, 1) = -1; T(0, 3) = 5000.0f; T(1, 3) = 3000.0f;
    Cloud B = transformCloud(A, T);

    std::printf("[1] union / randomKeep / cropBox 计数\n");
    Cloud u = fuseUnion({&A, &B});
    CHECK(u.size() == A.size() + B.size(), "union count == A+B");
    Cloud k = randomKeep(u, 0.5f);
    CHECK(k.size() == static_cast<std::size_t>(u.size() * 0.5f + 0.5f), "randomKeep == round(N*0.5)");
    CHECK(randomKeep(u, 1.0f).size() == u.size(), "ratio>=1 全保留");
    Bbox b = boundingBox(A);
    Cloud cr = cropBox(A, b.min, Eigen::Vector3f(b.min.x() + (b.size().x() * 0.5f), b.max.y(), b.max.z()));
    CHECK(cr.size() > 0 && cr.size() < A.size(), "cropBox 保留严格子集");

    std::printf("[2] registerTwoUnits 复原 180° 机间变换\n");
    auto reg = registerTwoUnits(B, A, 2000.0f);  // 把 B 配准到 A
    std::printf("  info: converged=%d yaw=%d fitness=%.3fmm\n", reg.converged, reg.best_yaw_deg, reg.fitness);
    CHECK(reg.best_yaw_deg == 180, "粗初值选中 180° yaw");
    CHECK(reg.fitness < 5.0f, "ICP 平均误差 < 5mm");
    Cloud bAligned = transformCloud(B, reg.transform);
    float maxd = 0;
    for (std::size_t i = 0; i < A.size(); i += 37)
        maxd = std::max(maxd, (bAligned[i] - A[i]).norm());
    std::printf("  info: 抽样最大点距 = %.3fmm\n", maxd);
    CHECK(maxd < 10.0f, "配准后 B 落回 A（<10mm）");

    std::printf("[3] reconstructVehicle — ICP 对齐 + union\n");
    ScanVehicleParams p;
    p.align = AlignMethod::Icp;
    p.icp_reject_mm = 2000.0f;
    auto r = reconstructVehicle(A, B, p);
    std::printf("  info: A=%zu B=%zu fused=%zu align=%d\n", r.pts_a, r.pts_b, r.fused, (int)r.align_used);
    CHECK(r.fused == A.size() + B.size(), "融合 = union (count==A+B)");
    CHECK(r.align_used == AlignMethod::Icp, "走 ICP 对齐");

    std::printf("[4] reconstructVehicle — site-extrinsic 原样施加 + 降采样 + 裁剪\n");
    ScanVehicleParams p2;
    p2.align = AlignMethod::Site;
    p2.site_extrinsic = Eigen::Matrix4f::Identity();
    p2.site_extrinsic(0, 3) = 1500.0f;
    p2.keep_ratio = 0.5f;
    p2.crop = true;
    p2.crop_min = Eigen::Vector3f(-1e6f, -1e6f, -1e6f);
    p2.crop_max = Eigen::Vector3f(1e6f, 1e6f, 1e6f);
    auto r2 = reconstructVehicle(A, B, p2);
    CHECK(r2.align_used == AlignMethod::Site, "走 site 对齐");
    CHECK((r2.b_to_a - p2.site_extrinsic).cwiseAbs().maxCoeff() < 1e-6f, "site 外参原样");
    const std::size_t expect = static_cast<std::size_t>((A.size() + B.size()) * 0.5f + 0.5f);
    CHECK(r2.after_downsample == expect, "随机降采样 round(N*0.5)");
    CHECK(r2.after_crop == r2.after_downsample, "全包围裁剪不丢点");

    std::printf("[5] lineToWorld — 单位/前向链 sanity\n");
    SynthesisParams sp;  // identity 链：v=0, dist=1000mm, h=0 → (1000,0,0)
    Eigen::Vector3f w = lineToWorld(0.0f, 1000.0f, 0.0f, sp);
    CHECK((w - Eigen::Vector3f(1000, 0, 0)).norm() < 1e-3f, "identity 链 lineToWorld 正确");

    std::printf("\n%s (%d 失败)\n", g_fail ? "FAILED" : "PASSED", g_fail);
    return g_fail ? 1 : 0;
}
