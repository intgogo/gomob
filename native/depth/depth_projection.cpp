// depth/ — 深度图 → 相机坐标系点云
// 占位实现：用针孔模型把 (u,v,d) 反投影为 (X,Y,Z)；
// 后续接 Berxel SDK 时可换成厂商提供的高精度反畸变路径。

#include <cstddef>
#include <cstdint>
#include <vector>

namespace gomob::depth {

// Berxel iHawkP100R3.0 工作距离窗口（来自 docs/iHawkP100R3.0_V1.2.pdf 表1）：
//   - 深度工作范围: 0.2 - 8m
//   - 理想工作范围: 0.25 - 2m, 精度 ≤1% @ 1-2m（远端 5-8m 精度退化到 cm 级，但仍是有效观测）
//   - 最近工作距离: 0.2m @ 所有分辨率
// 因此过滤窗口取 [200mm, 8000mm]：
//   - 下限 200mm：spec 给的硬下限，低于该值 SDK 仍会吐 raw 深度但属于无效像素（镜头本体反射 / 结构光基线失效）
//   - 上限 8000mm：spec 给的硬上限，超过该值 SDK 不再保证有意义的视差，应丢弃
// 这两个常量同时被 ProjectToPointCloud（点云用于 ICP）和 tsdf::Integrate（积分到 voxel grid）
// 一致使用 — 任何一边漏过滤，TSDF 与 ICP reference 数据源就会错位 → 渐进性漂移。
//
// 注意：[200, 250] 与 [2000, 8000] 是"可用但非理想"区间；上层应基于扫描场景（桌面物 vs 大件 vs
// 大场景）选不同的 grid_extent / grid_center_z 把目标主体落在 [250, 2000] 理想带内做积分，
// 而不是把这两个常量再收紧 — 否则用户在中远距扫大件时全帧被滤光（曾经因 250/2500 误杀真机数据）。
static constexpr int16_t kMinValidDepthMm = 200;
static constexpr int16_t kMaxValidDepthMm = 8000;

bool IsDepthValid(int16_t d) {
    return d >= kMinValidDepthMm && d <= kMaxValidDepthMm;
}

std::vector<float> ProjectToPointCloud(
        const int16_t* depth, int width, int height,
        double fx, double fy, double cx, double cy) {
    std::vector<float> out;
    out.reserve(static_cast<size_t>(width) * height * 3);
    for (int v = 0; v < height; ++v) {
        for (int u = 0; u < width; ++u) {
            int16_t d = depth[v * width + u];
            // 工作距离外的像素 = 噪声，按"无效深度"占位 (0,0,0)，下游 ICP / Transform 会跳过
            if (!IsDepthValid(d)) {
                out.push_back(0.f); out.push_back(0.f); out.push_back(0.f);
                continue;
            }
            double Z = static_cast<double>(d);                // 单位毫米
            double X = (u - cx) * Z / fx;
            double Y = (v - cy) * Z / fy;
            out.push_back(static_cast<float>(X));
            out.push_back(static_cast<float>(Y));
            out.push_back(static_cast<float>(Z));
        }
    }
    return out;
}

} // namespace gomob::depth
