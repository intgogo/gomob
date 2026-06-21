// calibration/ — iHawk Color/Depth 自标定（接口已声明，实现未接入）
//
// 当前阶段：接口已声明但 **OpenCV 链接未接入**，三个入口均显式返回空向量
// 作为"未实现"失败信号（绝不返回看似合法的零内参/零外参伪装成功）。
// 详见 docs/architecture/05-calibration-pipeline.md。
// 真实实施 M2.* 阶段：cv::aruco::detectMarkers + cv::aruco::interpolateCornersCharuco +
//                    cv::calibrateCamera + cv::stereoCalibrate。
//
// OpenCV 依赖待定：
//   - 选项 A：用 SDK 自带 libopencv_java3.so（OpenCV 3.x；需要查是否含 contrib::aruco）
//   - 选项 B：把 OpenCV 4.x 编进 libgomob_native.so（增大 30-50MB，依赖明确）
// 当前先做接口 stub，OpenCV 链接留 M2.x 评估后接入。
//
// ★ 契约：调用方必须把"返回空向量"判定为标定失败，禁止把结果当合法内参使用。

#include <cstdint>
#include <vector>

namespace gomob::calibration {

std::vector<float> DetectCharuco(
        const uint8_t* /*gray*/, int /*width*/, int /*height*/,
        const int* /*board_spec*/) {
    // TODO M2.x：cv::aruco::detectMarkers
    return {};
}

std::vector<double> CalibrateCamera(
        const float* /*corners*/, const int* /*corners_per_image*/, int /*image_count*/,
        int /*width*/, int /*height*/, const int* /*board_spec*/) {
    // TODO M2.x：cv::calibrateCamera，成功时返回 [fx, fy, cx, cy, k1, k2, p1, p2, k3, rms]。
    // 未实现：返回空向量作为失败信号（绝不返回 10 个 0 伪装成"已标定但参数为 0"）。
    return {};
}

std::vector<double> StereoCalibrate(
        const float* /*color_corners*/, const float* /*depth_corners*/,
        const int* /*corners_per_image*/, int /*image_count*/,
        const double* /*color_intr*/, const double* /*depth_intr*/,
        int /*width*/, int /*height*/) {
    // TODO M2.x：cv::stereoCalibrate，成功时返回 [r00..r22, tx, ty, tz, rms]（13 个 double）。
    // 未实现：返回空向量作为失败信号（绝不返回全 0 外参——单位阵都不是，调用方禁止当合法外参用）。
    return {};
}

} // namespace gomob::calibration
