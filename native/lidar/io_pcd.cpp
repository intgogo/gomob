#include "lidar/io_pcd.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <vector>

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

bool loadPcd(const std::string& path, Cloud& out) {
    std::ifstream in(path, std::ios::binary);
    if (!in) return false;

    std::vector<std::string> fields;
    std::vector<int> sizes;
    std::size_t points = 0;
    bool binary = false;

    // 逐行读 ASCII 头部，直到 DATA 行；其后即二进制点体。
    std::string line;
    while (std::getline(in, line)) {
        if (!line.empty() && line.back() == '\r') line.pop_back();
        std::istringstream ls(line);
        std::string key;
        ls >> key;
        if (key == "FIELDS") {
            std::string f;
            while (ls >> f) fields.push_back(f);
        } else if (key == "SIZE") {
            int s;
            while (ls >> s) sizes.push_back(s);
        } else if (key == "POINTS") {
            ls >> points;
        } else if (key == "DATA") {
            std::string mode;
            ls >> mode;
            binary = (mode == "binary");
            break;  // 头结束，余下是数据
        }
    }
    if (!binary || fields.size() != sizes.size() || fields.empty()) return false;

    // 定位 x/y/z 字段偏移与整点步长。
    int step = 0, ox = -1, oy = -1, oz = -1;
    for (std::size_t i = 0; i < fields.size(); ++i) {
        if (fields[i] == "x") ox = step;
        else if (fields[i] == "y") oy = step;
        else if (fields[i] == "z") oz = step;
        step += sizes[i];
    }
    if (ox < 0 || oy < 0 || oz < 0 || step <= 0) return false;

    std::vector<char> buf(static_cast<std::size_t>(step) * points);
    in.read(buf.data(), static_cast<std::streamsize>(buf.size()));
    if (static_cast<std::size_t>(in.gcount()) != buf.size()) return false;

    out.clear();
    out.reserve(points);
    for (std::size_t i = 0; i < points; ++i) {
        const char* base = buf.data() + i * step;
        float x, y, z;
        std::memcpy(&x, base + ox, 4);
        std::memcpy(&y, base + oy, 4);
        std::memcpy(&z, base + oz, 4);
        if (std::isfinite(x) && std::isfinite(y) && std::isfinite(z))
            out.emplace_back(x, y, z);
    }
    return true;
}

}  // namespace gomob::lidar
