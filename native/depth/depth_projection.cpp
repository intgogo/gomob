// depth/ — 深度图 → 相机坐标系点云
// 占位实现：用针孔模型把 (u,v,d) 反投影为 (X,Y,Z)；
// 后续接 Berxel SDK 时可换成厂商提供的高精度反畸变路径。

#include <cstdint>
#include <vector>

namespace gomob::depth {

std::vector<float> ProjectToPointCloud(
        const int16_t* depth, int width, int height,
        double fx, double fy, double cx, double cy) {
    std::vector<float> out;
    out.reserve(static_cast<size_t>(width) * height * 3);
    for (int v = 0; v < height; ++v) {
        for (int u = 0; u < width; ++u) {
            int16_t d = depth[v * width + u];
            if (d <= 0) {
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
