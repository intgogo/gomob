// 车型目录 + carType 参数表（逆向自 JCHY，docs/architecture/16 §4）。
// 两件事：①26 车型枚举（编号→名称→分组）②carType.ini 解密(周期16回文 XOR)+解析每车型三轴偏移(mm)。
// 让 gomob 直接消费原厂 carType.ini（真实数据源，可按部署调），不硬编码副本。Eigen-only，无 PCL。
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

namespace gomob::measure {

// 车型分组：货车(0-15) / 挂车(50-59)。
enum class VehicleGroup { Truck, Trailer };

struct VehicleTypeInfo {
    int id;
    const char* name;   // 原厂中文名（UTF-8）
    VehicleGroup group;
    bool tank;          // 罐体型（洒水/搅拌/罐挂）
    bool crane;         // 吊车型
};

// 全 26 车型目录（编号即语义分组，docs/16 §4.1）。
const std::vector<VehicleTypeInfo>& vehicleCatalog();
// 按编号查；未知返 nullptr。
const VehicleTypeInfo* findVehicleType(int id);

// carType 每车型三轴偏移（mm，docs/16 §4.3）。键如 "Type2"/"Tank5"/"Type2_s"/"board"。
struct CarTypeOffset { float x{0.f}, y{0.f}, z{0.f}; };

// carType.ini ↔ 明文的混淆：周期-16 回文 key 的逐字节 XOR（自逆，加解密同一函数）。
void applyCarTypeXor(std::vector<std::uint8_t>& buf);

// 解析明文（"Type0_x=-20,Type0_y=-35,Type0_z=0\r\n..."）→ 键(去 _x/_y/_z)→偏移。
bool parseCarTypeOffsets(const std::string& plain,
                         std::unordered_map<std::string, CarTypeOffset>& out);

// 读 carType.ini（密文）→ 解密 → 解析。失败返 false。
bool loadCarTypeTable(const std::string& path,
                      std::unordered_map<std::string, CarTypeOffset>& out);

}  // namespace gomob::measure
