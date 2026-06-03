#include "lidar/io_pcd.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <fstream>

namespace gomob::lidar {

bool savePcdBinary(const std::string& path, const Cloud& cloud) {
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    const std::size_t n = cloud.size();
    out << "# .PCD v0.7 - Point Cloud Data file format\n"
        << "VERSION 0.7\nFIELDS x y z\nSIZE 4 4 4\nTYPE F F F\nCOUNT 1 1 1\n"
        << "WIDTH " << n << "\nHEIGHT 1\nVIEWPOINT 0 0 0 1 0 0 0\n"
        << "POINTS " << n << "\nDATA binary\n";
    for (const auto& p : cloud) {
        const float xyz[3] = {p.x(), p.y(), p.z()};
        out.write(reinterpret_cast<const char*>(xyz), sizeof(xyz));
    }
    return static_cast<bool>(out);
}

bool savePoints3DTxt(const std::string& path, const Cloud& cloud) {
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    for (const auto& p : cloud) {
        out << static_cast<long>(std::lround(p.x())) << '\t'
            << static_cast<long>(std::lround(p.y())) << '\t'
            << static_cast<long>(std::lround(p.z())) << "\t0\n";
    }
    return static_cast<bool>(out);
}

}  // namespace gomob::lidar
