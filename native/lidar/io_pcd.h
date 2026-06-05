// 最小 PCD / TXT 写盘（无 PCL）。落盘是可选诊断产物；端侧主路径直接出 FloatArray 喂渲染。
// PCD binary v0.7（FIELDS x y z, float32）。points3D.txt 整数 mm（原厂 viewer 云格式）。单位 mm。
#pragma once

#include <string>
#include "lidar/lidar_types.h"

namespace gomob::lidar {

bool savePcdBinary(const std::string& path, const Cloud& cloud);          // float32 xyz（mm）
bool savePoints3DTxt(const std::string& path, const Cloud& cloud);        // "%d\t%d\t%d\t0"（mm 取整）

// 读 PCD binary v0.7（DATA binary，float32 字段）。通用解析头部 FIELDS/SIZE/POINTS，
// 取 x/y/z 三列（XYZ 或 XYZRGB 均可），丢弃非有限点。仅 xyz 进 Cloud（mm）。失败返 false。
bool loadPcd(const std::string& path, Cloud& out);

}  // namespace gomob::lidar
