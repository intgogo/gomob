// vehicle_measure harness CLI：对每个 PCD 跑测量管线，输出 JSONL（一行一朵云）。
// 用法：measure_cli <a.pcd> [b.pcd ...]   单位 mm。
#include <cstdio>
#include <string>

#include "lidar/io_pcd.h"
#include "measurement/measure_vehicle.h"

using gomob::lidar::Cloud;
using namespace gomob::measure;

static const char* base(const std::string& p) {
    auto s = p.find_last_of("/\\");
    return p.c_str() + (s == std::string::npos ? 0 : s + 1);
}

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "用法: %s <pcd> [pcd...]\n", argv[0]); return 2; }
    for (int i = 1; i < argc; ++i) {
        Cloud c;
        if (!gomob::lidar::loadPcd(argv[i], c)) {
            std::fprintf(stderr, "loadPcd 失败: %s\n", argv[i]);
            return 1;
        }
        VehicleDimensions d = measureVehicle(c, MeasureParams{});
        std::printf(
            "{\"file\":\"%s\",\"raw\":%zu,\"roi\":%zu,\"body\":%zu,\"ratio\":%.3f,"
            "\"length\":%.1f,\"width\":%.1f,\"height\":%.1f,\"angle\":%.2f,\"valid\":%s}\n",
            base(argv[i]), d.raw_pts, d.roi_pts, d.body_pts, d.body_ratio,
            d.length, d.width, d.height, d.obb_angle_deg, d.valid ? "true" : "false");
    }
    return 0;
}
