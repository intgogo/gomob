#include "measurement/vehicle_catalog.h"

#include <cstdlib>
#include <fstream>
#include <sstream>

namespace gomob::measure {
namespace {

using G = VehicleGroup;
// 26 车型（docs/16 §4.1，编号→中文名→分组→罐体/吊车标志）。
const std::vector<VehicleTypeInfo> kCatalog = {
    {0,  "牵引头",         G::Truck,   false, false},
    {1,  "吊车",           G::Truck,   false, true },
    {2,  "常规",           G::Truck,   false, false},
    {3,  "路边清障车",     G::Truck,   false, false},
    {4,  "垃圾清理车",     G::Truck,   false, false},
    {5,  "洒水罐车",       G::Truck,   true,  false},
    {6,  "小型平板货车",   G::Truck,   false, false},
    {7,  "水泥搅拌车",     G::Truck,   true,  false},
    {8,  "大型平板货车",   G::Truck,   false, false},
    {9,  "特殊吊车",       G::Truck,   false, true },
    {10, "特殊栏板吊车",   G::Truck,   false, true },
    {11, "专项特殊车",     G::Truck,   false, false},
    {12, "箱式尾板车",     G::Truck,   false, false},
    {13, "自卸式货车",     G::Truck,   false, false},
    {14, "仓栅式货车",     G::Truck,   false, false},
    {15, "箱式货车",       G::Truck,   false, false},
    {50, "常规挂车",       G::Trailer, false, false},
    {51, "光板挂车",       G::Trailer, false, false},
    {52, "光板挂车(带杆)", G::Trailer, false, false},
    {53, "常规罐体挂车",   G::Trailer, true,  false},
    {54, "低平板挂车",     G::Trailer, false, false},
    {55, "异型挂车",       G::Trailer, false, false},
    {56, "箱式挂车",       G::Trailer, false, false},
    {57, "仓栅式挂车",     G::Trailer, false, false},
    {58, "下灰式罐体挂车", G::Trailer, true,  false},
    {59, "水泥罐体挂车",   G::Trailer, true,  false},
};

// 周期-16 回文 key（docs/16 §4.2，python 实算复现）。
const std::uint8_t kKey[16] = {0x00, 0x20, 0x00, 0x00, 0x07, 0x00, 0x30, 0x10,
                               0x10, 0x30, 0x00, 0x07, 0x00, 0x00, 0x20, 0x00};

}  // namespace

const std::vector<VehicleTypeInfo>& vehicleCatalog() { return kCatalog; }

const VehicleTypeInfo* findVehicleType(int id) {
    for (const auto& v : kCatalog)
        if (v.id == id) return &v;
    return nullptr;
}

void applyCarTypeXor(std::vector<std::uint8_t>& buf) {
    for (std::size_t i = 0; i < buf.size(); ++i) buf[i] ^= kKey[i % 16];
}

bool parseCarTypeOffsets(const std::string& plain,
                         std::unordered_map<std::string, CarTypeOffset>& out) {
    out.clear();
    // 明文形如 "Type0_x=-20,Type0_y=-35,Type0_z=0"，逗号/换行分隔的 name=value。
    std::string tok;
    auto flush = [&](std::string t) {
        // 去首尾空白
        auto a = t.find_first_not_of(" \t\r\n");
        if (a == std::string::npos) return;
        auto b = t.find_last_not_of(" \t\r\n");
        t = t.substr(a, b - a + 1);
        if (t.empty() || t[0] == '#') return;
        auto eq = t.find('=');
        if (eq == std::string::npos) return;
        std::string name = t.substr(0, eq);
        float val = static_cast<float>(std::atof(t.c_str() + eq + 1));
        if (name.size() < 3 || name[name.size() - 2] != '_') return;  // 末尾须 _x/_y/_z
        char axis = name.back();
        std::string base = name.substr(0, name.size() - 2);
        CarTypeOffset& o = out[base];
        if (axis == 'x') o.x = val;
        else if (axis == 'y') o.y = val;
        else if (axis == 'z') o.z = val;
    };
    for (char c : plain) {
        if (c == ',' || c == '\n') { flush(tok); tok.clear(); }
        else tok.push_back(c);
    }
    flush(tok);
    return !out.empty();
}

bool loadCarTypeTable(const std::string& path,
                      std::unordered_map<std::string, CarTypeOffset>& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;
    std::vector<std::uint8_t> buf((std::istreambuf_iterator<char>(in)),
                                  std::istreambuf_iterator<char>());
    if (buf.empty()) return false;
    applyCarTypeXor(buf);  // 解密
    return parseCarTypeOffsets(std::string(buf.begin(), buf.end()), out);
}

}  // namespace gomob::measure
