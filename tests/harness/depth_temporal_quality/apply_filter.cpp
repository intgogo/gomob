// 把 P100R3TemporalFilter 套到录制的 depth raw16 序列上，产出融合帧供 analyze.py 判定。
// 只链 portable.cpp（无 libusb）：读 .raw 文件 → 逐帧 push → 写 fused-NNN.raw + stats.csv。
// 用法：apply_filter <in_dir> <glob_suffix> <width> <height> <out_dir>
//        [--window N] [--motion-mm F] [--motion-percent F] [--min-full N]
#include "gomob_berxel_portable.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <string>
#include <sys/stat.h>
#include <vector>

using gomob::berxel::host::P100R3TemporalFilter;
using gomob::berxel::host::P100R3TemporalFilterConfig;
using gomob::berxel::host::P100R3TemporalFilterStats;

namespace {

std::vector<std::string> list_sorted(const std::string& dir, const std::string& suffix) {
    std::vector<std::string> out;
    DIR* d = opendir(dir.c_str());
    if (!d) return out;
    for (dirent* e = readdir(d); e; e = readdir(d)) {
        std::string name = e->d_name;
        if (name.size() >= suffix.size() &&
            name.compare(name.size() - suffix.size(), suffix.size(), suffix) == 0) {
            out.push_back(dir + "/" + name);
        }
    }
    closedir(d);
    std::sort(out.begin(), out.end());
    return out;
}

bool read_raw16(const std::string& path, size_t pixels, std::vector<uint16_t>* out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    out->assign(pixels, 0);
    f.read(reinterpret_cast<char*>(out->data()), static_cast<std::streamsize>(pixels * 2));
    return static_cast<size_t>(f.gcount()) == pixels * 2;
}

void write_raw16(const std::string& path, const std::vector<uint16_t>& data) {
    std::ofstream f(path, std::ios::binary);
    f.write(reinterpret_cast<const char*>(data.data()),
            static_cast<std::streamsize>(data.size() * 2));
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 6) {
        std::fprintf(stderr,
                     "用法: apply_filter <in_dir> <glob_suffix> <width> <height> <out_dir> "
                     "[--window N] [--motion-mm F] [--motion-percent F] [--min-full N]\n");
        return 2;
    }
    const std::string in_dir = argv[1];
    const std::string suffix = argv[2];
    const int width = std::atoi(argv[3]);
    const int height = std::atoi(argv[4]);
    const std::string out_dir = argv[5];

    P100R3TemporalFilterConfig cfg;
    for (int i = 6; i + 1 < argc; i += 2) {
        std::string k = argv[i];
        std::string v = argv[i + 1];
        if (k == "--window") cfg.window = std::atoi(v.c_str());
        else if (k == "--motion-mm") cfg.motion_reset_mm = std::atof(v.c_str());
        else if (k == "--noise-k") cfg.motion_reset_noise_k = std::atof(v.c_str());
        else if (k == "--motion-percent") cfg.motion_reset_percent = std::atof(v.c_str());
        else if (k == "--min-full") cfg.min_samples_full_conf = std::atoi(v.c_str());
    }

    auto files = list_sorted(in_dir, suffix);
    if (files.empty()) {
        std::fprintf(stderr, "无匹配帧: %s/*%s\n", in_dir.c_str(), suffix.c_str());
        return 1;
    }
    mkdir(out_dir.c_str(), 0755);
    const size_t pixels = static_cast<size_t>(width) * static_cast<size_t>(height);

    P100R3TemporalFilter filter(cfg);
    std::vector<uint16_t> frame, fused;
    std::vector<uint8_t> conf;
    P100R3TemporalFilterStats stats;

    std::ofstream csv(out_dir + "/stats.csv");
    csv << "idx,fused_pixels,motion_resets,single_sample_pixels,mean_window_fill,src\n";

    int idx = 0;
    for (const auto& path : files) {
        if (!read_raw16(path, pixels, &frame)) {
            std::fprintf(stderr, "跳过尺寸不符: %s\n", path.c_str());
            continue;
        }
        if (!filter.push(frame, static_cast<uint16_t>(width), static_cast<uint16_t>(height),
                         &fused, &conf, &stats)) {
            std::fprintf(stderr, "push 失败: %s\n", path.c_str());
            return 1;
        }
        char name[64];
        std::snprintf(name, sizeof(name), "/fused-%03d.raw", idx);
        write_raw16(out_dir + name, fused);
        char cname[64];
        std::snprintf(cname, sizeof(cname), "/conf-%03d.raw", idx);
        write_raw16(out_dir + cname, std::vector<uint16_t>(conf.begin(), conf.end()));
        const std::string base = path.substr(path.find_last_of('/') + 1);
        csv << idx << ',' << stats.fused_pixels << ',' << stats.motion_resets << ','
            << stats.single_sample_pixels << ',' << stats.mean_window_fill << ',' << base << '\n';
        idx++;
    }
    std::printf("applied filter: frames=%d window=%d motion_mm=%.1f percent=%.3f out=%s\n",
                idx, std::max(1, std::min(255, cfg.window)), cfg.motion_reset_mm,
                cfg.motion_reset_percent, out_dir.c_str());
    return 0;
}
