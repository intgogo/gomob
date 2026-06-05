// 车辆外廓测量编排（Eigen-only，无 PCL）。对应原厂 ② 预处理 → ⑥ 尺寸提取（docs/16 §3）。
// 输入：单镜头/融合彩色点云剥色后的 xyz（mm，设备/世界系，Z=上）。输出：车长/宽/高 + 诊断。
// 车型分类/轴距/罐体/栏板/护栏属后续里程碑（M9.3-M9.5），本编排只做 LWH（M9.1/9.2）。
#pragma once

#include "measurement/measure_types.h"

namespace gomob::measure {

// 管线：ROI 裁剪 → 最大欧氏簇 → 半径离群剔除 → 俯视 OBB(车长/宽) + Z 跨度(车高)。
VehicleDimensions measureVehicle(const Cloud& raw, const MeasureParams& params = {});

}  // namespace gomob::measure
