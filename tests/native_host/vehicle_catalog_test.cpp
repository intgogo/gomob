// 车型目录 + carType 编解码 host 单测。验证：XOR 自逆往返、明文解析、真机 carType.ini 解密、26 车型目录。
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "measurement/vehicle_catalog.h"

using namespace gomob::measure;

static int g_fail = 0;
#define CHECK(cond, msg)                                             \
    do {                                                             \
        if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++g_fail; } \
        else std::printf("  ok  : %s\n", msg);                       \
    } while (0)

static bool off_eq(const CarTypeOffset& o, float x, float y, float z) {
    return std::fabs(o.x - x) < 1e-3 && std::fabs(o.y - y) < 1e-3 && std::fabs(o.z - z) < 1e-3;
}

int main(int argc, char** argv) {
    // 1) XOR 自逆往返：明文 → 密文 → 明文。
    std::printf("== applyCarTypeXor 自逆往返 ==\n");
    const std::string plain0 = "Type0_x=-20,Type0_y=-35,Type0_z=0\r\nType2_x=-20,Type2_y=-35,Type2_z=-10\r\n";
    std::vector<std::uint8_t> buf(plain0.begin(), plain0.end());
    applyCarTypeXor(buf);                                  // 加密
    CHECK(std::string(buf.begin(), buf.end()) != plain0, "XOR 后与明文不同(确已混淆)");
    applyCarTypeXor(buf);                                  // 解密
    CHECK(std::string(buf.begin(), buf.end()) == plain0, "二次 XOR 复原明文");

    // 2) 解析明文 → 偏移表。
    std::printf("== parseCarTypeOffsets ==\n");
    std::unordered_map<std::string, CarTypeOffset> t;
    CHECK(parseCarTypeOffsets(plain0, t), "解析非空");
    CHECK(t.count("Type0") && off_eq(t["Type0"], -20, -35, 0), "Type0=(-20,-35,0)");
    CHECK(t.count("Type2") && off_eq(t["Type2"], -20, -35, -10), "Type2=(-20,-35,-10)");

    // 3) 真机 carType.ini 解密（存在才测；默认原厂路径，JCHY_CARTYPE 可覆盖）。
    std::printf("== loadCarTypeTable 真机 carType.ini ==\n");
    std::string path = (argc > 1) ? argv[1] : "/root/WindowsR/JCHY_OFFLINE/carType.ini";
    std::unordered_map<std::string, CarTypeOffset> real;
    if (loadCarTypeTable(path, real)) {
        CHECK(real.size() == 31, "解出 31 条目");
        CHECK(off_eq(real["Type0"], -20, -35, 0), "Type0=(-20,-35,0)");
        CHECK(off_eq(real["Type2"], -20, -35, -10), "Type2=(-20,-35,-10)");
        CHECK(off_eq(real["Type51"], 90, -100, 10), "Type51=(90,-100,10)");
        CHECK(off_eq(real["Tank5"], -40, -60, 0), "Tank5=(-40,-60,0)");
        CHECK(off_eq(real["Type59"], 50, -10, 0), "Type59=(50,-10,0)");
        CHECK(real.count("board") && off_eq(real["board"], 0, 0, 0), "board=(0,0,0)");
        CHECK(real.count("Type2_s"), "含 Type2_s 子工况");
    } else {
        std::printf("  skip: 无 %s（设 JCHY_CARTYPE 指向原厂 carType.ini）\n", path.c_str());
    }

    // 4) 26 车型目录。
    std::printf("== vehicleCatalog 26 车型 ==\n");
    CHECK(vehicleCatalog().size() == 26, "共 26 车型");
    CHECK(findVehicleType(0) && std::string(findVehicleType(0)->name) == "牵引头", "0=牵引头");
    CHECK(findVehicleType(2) && std::string(findVehicleType(2)->name) == "常规", "2=常规");
    CHECK(findVehicleType(58) && std::string(findVehicleType(58)->name) == "下灰式罐体挂车", "58=下灰式罐体挂车");
    CHECK(findVehicleType(16) == nullptr && findVehicleType(60) == nullptr, "缺号 16/60 不存在");
    CHECK(findVehicleType(50)->group == VehicleGroup::Trailer, "50 是挂车组");
    CHECK(findVehicleType(2)->group == VehicleGroup::Truck, "2 是货车组");
    CHECK(findVehicleType(1)->crane && findVehicleType(9)->crane && findVehicleType(10)->crane, "1/9/10 吊车型");
    CHECK(findVehicleType(5)->tank && findVehicleType(53)->tank && findVehicleType(59)->tank, "5/53/59 罐体型");

    std::printf(g_fail ? "\nVEHICLE CATALOG TEST: %d FAIL\n" : "\nVEHICLE CATALOG TEST: ALL PASS\n", g_fail);
    return g_fail ? 1 : 0;
}
