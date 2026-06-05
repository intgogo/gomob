// 车辆外廓测量内核 host 单测（Eigen-only，无 PCL）。
// 验证：minAreaRectXY 旋转不变取长宽、预处理剥离脱离噪声团+稀疏离群、measureVehicle 合成盒 LWH 准、loadPcd 往返。
#include <cmath>
#include <cstdio>
#include <string>

#include "lidar/io_pcd.h"
#include "measurement/dimensions.h"
#include "measurement/measure_vehicle.h"
#include "measurement/preprocess.h"

using namespace gomob::measure;
using gomob::lidar::Cloud;

static int g_fail = 0;
#define CHECK(cond, msg)                                            \
    do {                                                            \
        if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; }\
        else std::printf("  ok  : %s\n", msg);                      \
    } while (0)

// 确定性 LCG，[0,1)。
struct Rng {
    std::uint64_t s{0x9e3779b97f4a7c15ULL};
    float next() { s = s * 6364136223846793005ULL + 1442695040888963407ULL; return (s >> 40) / 16777216.0f; }
};

// 在 [0,L]×[0,W]×[0,H] 盒表面密采点，按 yaw(度)绕 Z 旋转并平移到 center。step≈采样间距(mm)。
static Cloud makeBox(float L, float W, float H, float yaw_deg, Eigen::Vector3f center, float step) {
    Cloud c;
    const float r = yaw_deg * 3.14159265f / 180.f, cs = std::cos(r), sn = std::sin(r);
    auto add = [&](float x, float y, float z) {
        c.emplace_back(x * cs - y * sn + center.x(), x * sn + y * cs + center.y(), z + center.z());
    };
    for (float x = 0; x <= L; x += step)
        for (float y = 0; y <= W; y += step) { add(x, y, 0); add(x, y, H); }       // 顶/底
    for (float x = 0; x <= L; x += step)
        for (float z = 0; z <= H; z += step) { add(x, 0, z); add(x, W, z); }       // 两长侧
    for (float y = 0; y <= W; y += step)
        for (float z = 0; z <= H; z += step) { add(0, y, z); add(L, y, z); }       // 两端
    return c;
}

int main() {
    // 1) minAreaRectXY 旋转不变：长 1800 宽 530 的矩形边框，任意 yaw 都应解出长/宽。
    std::printf("== minAreaRectXY 旋转不变 ==\n");
    for (float yaw : {0.f, 17.f, 43.f, 78.f}) {
        Cloud rect = makeBox(1800, 530, 1, yaw, {500, 700, 100}, 5);
        ObbXY o = minAreaRectXY(rect, 0.25f);
        char m[96];
        std::snprintf(m, sizeof(m), "yaw=%.0f -> L=%.1f(期望1800) W=%.1f(期望530)", yaw, o.length, o.width);
        CHECK(std::fabs(o.length - 1800) < 8 && std::fabs(o.width - 530) < 8, m);
    }

    // 2) largestEuclideanCluster 剥离脱离的噪声团。
    // 采样间距 5mm 贴近真实云密度（1.pcd ~1mm，10mm 过疏会被下游 ROR 误删，见 docs/16 §6.3 离线验证）。
    std::printf("== largestEuclideanCluster 去脱离噪声 ==\n");
    Cloud body = makeBox(1800, 530, 760, 20, {400, 600, 50}, 5);
    const std::size_t body_n = body.size();
    Cloud blob = makeBox(120, 120, 120, 0, {400, 3000, 50}, 10);  // 远在 +Y、与主体不连通
    Cloud mixed = body;
    for (auto& p : blob) mixed.push_back(p);
    Cloud kept = largestEuclideanCluster(mixed, 10.f);
    CHECK(kept.size() == body_n, "主簇恰为车体点数(脱离 blob 全剔除)");
    {
        // 20° 旋转后车体自身 Y 上界 ≈1713；blob 在 Y∈[3000,3120]。阈值 2200 干净区分。
        auto bb = gomob::lidar::boundingBox(kept);
        CHECK(bb.max.y() < 2200, "主簇 Y 上界未被 3000mm 处 blob 拉伸");
    }

    // 3) radiusOutlierRemoval 去稀疏离群。
    std::printf("== radiusOutlierRemoval 去稀疏离群 ==\n");
    Cloud sparse = body;
    Rng rng;
    for (int i = 0; i < 80; ++i)  // 远离主体、彼此孤立的飞点
        sparse.emplace_back(2500 + rng.next() * 500, 2500 + rng.next() * 500, 1500 + rng.next() * 300);
    Cloud clean = radiusOutlierRemoval(sparse, 15.f, 12);
    CHECK(clean.size() >= body_n * 0.95 && clean.size() <= body_n, "稀疏飞点被剔除、稠密主体保留");

    // 4) measureVehicle 端到端（关 ROI，隔离 cluster+ror+obb）：合成盒 LWH。
    std::printf("== measureVehicle 合成盒 LWH ==\n");
    MeasureParams pp;
    pp.use_roi = false;  // 合成坐标非真机 ROI
    Cloud scene = mixed;                                   // 车体 + 脱离 blob
    for (int i = 0; i < 80; ++i) scene.emplace_back(2500 + rng.next() * 500, 2500 + rng.next() * 500, 1500 + rng.next() * 300);
    VehicleDimensions d = measureVehicle(scene, pp);
    char m[160];
    std::snprintf(m, sizeof(m), "valid=%d L=%.1f(1800) W=%.1f(530) H=%.1f(760) body=%zu/%zu ratio=%.2f",
                  d.valid, d.length, d.width, d.height, d.body_pts, d.raw_pts, d.body_ratio);
    std::printf("  %s\n", m);
    CHECK(d.valid, "measureVehicle valid");
    CHECK(std::fabs(d.length - 1800) < 15, "车长≈1800");
    CHECK(std::fabs(d.width - 530) < 15, "车宽≈530");
    CHECK(std::fabs(d.height - 760) < 15, "车高≈760");

    // 5) loadPcd 往返。
    std::printf("== loadPcd 往返 ==\n");
    Cloud small;
    small.emplace_back(1.5f, -2.5f, 3.5f);
    small.emplace_back(100.f, 200.f, 300.f);
    const std::string path = ".dev/native-host/_measure_roundtrip.pcd";
    CHECK(gomob::lidar::savePcdBinary(path, small), "savePcdBinary");
    Cloud rd;
    CHECK(gomob::lidar::loadPcd(path, rd), "loadPcd");
    CHECK(rd.size() == 2 && (rd[0] - small[0]).norm() < 1e-3 && (rd[1] - small[1]).norm() < 1e-3,
          "往返坐标一致");

    std::printf(g_fail ? "\nMEASUREMENT TEST: %d FAIL\n" : "\nMEASUREMENT TEST: ALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
