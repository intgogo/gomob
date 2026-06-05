// 车辆外廓测量层 — 公共类型（Eigen-only，无 PCL）。
// 逆向自原厂 JCHY（docs/architecture/16）：消费融合/单元彩色点云 → 外廓尺寸数字。单位全程 mm。
#pragma once

#include <cstddef>
#include "lidar/lidar_types.h"

namespace gomob::measure {

using gomob::lidar::Bbox;
using gomob::lidar::Cloud;

// 俯视（XY 平面）最小面积外接矩形：长边=车长，短边=车宽，angle=长轴相对 X 轴夹角(度)。
struct ObbXY {
    float length{0.f};      // 长边（mm）
    float width{0.f};       // 短边（mm）
    float angle_deg{0.f};   // 取最小面积时的旋转角
};

// 单台车（或单镜头点云）的外廓测量结果。罐体/栏板/护栏/轴距属后续里程碑，先留位。
struct VehicleDimensions {
    float length{0.f};      // 车长 = OBB 长边
    float width{0.f};       // 车宽 = OBB 短边
    float height{0.f};      // 车高 = 主体 Z 跨度
    float obb_angle_deg{0.f};

    std::size_t raw_pts{0};     // 输入点数
    std::size_t roi_pts{0};     // ROI 裁剪后
    std::size_t body_pts{0};    // 主簇 + 离群剔除后（参与测量）
    float body_ratio{0.f};      // body_pts / roi_pts，主体占比（噪声诊断）
    Bbox body_aabb;             // 主体轴对齐包围盒（mm）
    bool valid{false};          // 点云非空且管线成功
};

// 测量管线参数。默认值取自原厂 setting.ini [Param] + 离线验证（docs/16 §6.3、M9.1/9.2）。
struct MeasureParams {
    // ROI 体裁剪（mm，setting.ini [Param]）。
    Eigen::Vector3f roi_min{270.f, 0.f, 10.f};
    Eigen::Vector3f roi_max{1000.f, 2200.f, 800.f};
    bool use_roi{true};

    float cluster_leaf{10.f};   // 主簇体素边长（mm）；体素 26-连通 BFS 取最大连通体
    float ror_radius{15.f};     // 半径离群剔除半径（mm）
    int   ror_min_neighbors{12};// 半径内最少邻居数，低于则视为离群点
    bool  use_ror{true};

    float obb_step_deg{0.25f};  // OBB 角度扫描步长（度）
};

}  // namespace gomob::measure
