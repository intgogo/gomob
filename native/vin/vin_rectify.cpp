// vin/ — VIN 数码拓印（占位）
//
// 当前阶段：仅声明接口 + 桩，让 jni_bridge.cpp 能链接通过。
// 真实实施在 M4.* 阶段（详见 docs/architecture/08-vin-rectify-design.md）：
//   - ROI 内深度反投影到 3D
//   - RANSAC 平面拟合（dist_thresh=2mm, max_iter=200, min_inliers_ratio=0.95）
//   - 构造正射相机（光轴 = -n，up = world_y proj on plane，固定距离 / 像素物理尺寸）
//   - 像素重投影 + 双线性 Color 采样 + PNG 编码

#include <cstdint>
#include <cstring>
#include <vector>

namespace gomob::vin {

struct RectifyResult {
    std::vector<uint8_t> png_bytes;       // PNG-encoded ortho image
    float plane_n[3];
    float plane_d;
    float rms_residual_mm;
    float inlier_ratio;
    int output_width;
    int output_height;
    int error_code;                       // 0 = 成功；> 0 = 见 NativeError 常量
};

RectifyResult Rectify(
        const uint8_t* /*color_bgr*/, int /*color_w*/, int /*color_h*/,
        const uint16_t* /*depth_mm*/, int /*depth_w*/, int /*depth_h*/,
        const double* /*color_intr*/,
        const int* /*roi_box*/,
        const float* config /*[ortho_dist_mm, pixel_size_mm, out_w, out_h]*/) {
    RectifyResult r{};
    r.output_width = static_cast<int>(config[2]);
    r.output_height = static_cast<int>(config[3]);
    r.error_code = 1;  // NativeError.NOT_IMPLEMENTED
    // TODO M4.x: 真实拓印实现
    return r;
}

} // namespace gomob::vin
