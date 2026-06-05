// 测量层尺寸提取（Eigen-only，无 PCL）。对应原厂 ⑥ 尺寸提取（docs/16 §3⑥、§5）。
// 车长/车宽 = 俯视(XY)最小面积外接矩形（对应原厂 bound_box + cv::RotatedRect）；车高 = Z 跨度。单位 mm。
#pragma once

#include "measurement/measure_types.h"

namespace gomob::measure {

// 俯视最小面积外接矩形：角度扫描 [0,90) 步长 step_deg，取面积最小的旋转角下的 XY 跨度。
// 长边=length，短边=width。空云返回全 0。
ObbXY minAreaRectXY(const Cloud& cloud, float step_deg = 0.25f);

}  // namespace gomob::measure
