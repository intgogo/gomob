// 把 P100R3TemporalFilter（含飞点剔除）套到合成 GT 场景帧序列，逐像素累计飞点标记次数，
// 供 analyze.py 对照 GT label 算 TP/FP/FN/recall/geom_keep。只链 portable.cpp（无 libusb）。
// 用法：apply <scene_dir> <W> <H> <out_dir>
//   [--window N --grazing F --tstd-floor F --tstd-pct F --min-support N --support-band F
//    --min-stable N --warmup N]
#include "gomob_berxel_portable.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <dirent.h>
#include <fstream>
#include <string>
#include <sys/stat.h>
#include <vector>

using namespace gomob::berxel::host;

namespace {
std::vector<std::string> frames_sorted(const std::string& dir) {
    std::vector<std::string> out;
    DIR* d = opendir(dir.c_str());
    if (!d) return out;
    for (dirent* e = readdir(d); e; e = readdir(d)) {
        std::string n = e->d_name;
        if (n.rfind("frame-", 0) == 0 && n.size() > 4 &&
            n.compare(n.size() - 4, 4, ".raw") == 0)
            out.push_back(dir + "/" + n);
    }
    closedir(d);
    std::sort(out.begin(), out.end());
    return out;
}
bool read_u16(const std::string& p, size_t px, std::vector<uint16_t>* o) {
    std::ifstream f(p, std::ios::binary);
    if (!f) return false;
    o->assign(px, 0);
    f.read(reinterpret_cast<char*>(o->data()), static_cast<std::streamsize>(px * 2));
    return static_cast<size_t>(f.gcount()) == px * 2;
}
}  // namespace

int main(int argc, char** argv) {
    if (argc < 5) {
        std::fprintf(stderr, "用法: apply <scene_dir> <W> <H> <out_dir> [params]\n");
        return 2;
    }
    const std::string scene = argv[1];
    const int W = std::atoi(argv[2]), H = std::atoi(argv[3]);
    const std::string out_dir = argv[4];
    P100R3TemporalFilterConfig cfg;
    cfg.flying_enable = true;
    cfg.spatial_denoise_enable = false;  // 合成 GT 隔离飞点检测：空间降噪会提前 median 掉合成飞点
    int warmup = 4;
    for (int i = 5; i + 1 < argc; i += 2) {
        std::string k = argv[i]; std::string v = argv[i + 1];
        if (k == "--window") cfg.window = std::atoi(v.c_str());
        else if (k == "--grazing") cfg.flying.grazing_angle_max_deg = std::atof(v.c_str());
        else if (k == "--tstd-floor") cfg.flying_tstd_floor_mm = std::atof(v.c_str());
        else if (k == "--tstd-pct") cfg.flying_tstd_percent = std::atof(v.c_str());
        else if (k == "--min-support") cfg.flying.min_coplanar_support = std::atoi(v.c_str());
        else if (k == "--support-band") cfg.flying.support_band_mm = std::atof(v.c_str());
        else if (k == "--min-stable") cfg.flying_min_stable_samples = std::atoi(v.c_str());
        else if (k == "--warmup") warmup = std::atoi(v.c_str());
    }

    auto files = frames_sorted(scene);
    if (files.empty()) { std::fprintf(stderr, "无帧: %s\n", scene.c_str()); return 1; }
    const size_t px = static_cast<size_t>(W) * static_cast<size_t>(H);
    mkdir(out_dir.c_str(), 0755);

    P100R3TemporalFilter filt(cfg);
    std::vector<uint16_t> frame, fused;
    std::vector<uint8_t> conf, fly;
    P100R3TemporalFilterStats st;
    std::vector<uint16_t> detect_count(px, 0);  // 每像素 post-warmup 被标记次数
    int post = 0;
    std::ofstream csv(out_dir + "/stats.csv");
    csv << "idx,flying_pixels,spatial_hits,temporal_gated,blocked_by_support\n";
    int idx = 0;
    for (const auto& f : files) {
        if (!read_u16(f, px, &frame)) continue;
        filt.push(frame, static_cast<uint16_t>(W), static_cast<uint16_t>(H),
                  &fused, &conf, &st, &fly);
        csv << idx << ',' << st.flying_pixels << ',' << st.flying_spatial_hits << ','
            << st.flying_temporal_gated << ',' << st.flying_blocked_by_support << '\n';
        if (idx >= warmup) {
            for (size_t i = 0; i < px; ++i)
                if (fly[i]) detect_count[i]++;
            post++;
        }
        idx++;
    }
    std::ofstream dc(out_dir + "/detect_count.raw", std::ios::binary);
    dc.write(reinterpret_cast<const char*>(detect_count.data()),
             static_cast<std::streamsize>(px * 2));
    std::ofstream meta(out_dir + "/apply_meta.json");
    meta << "{\"frames\":" << idx << ",\"warmup\":" << warmup << ",\"post_warmup\":" << post
         << ",\"window\":" << std::max(1, std::min(255, cfg.window))
         << ",\"grazing\":" << cfg.flying.grazing_angle_max_deg
         << ",\"tstd_floor_mm\":" << cfg.flying_tstd_floor_mm
         << ",\"min_support\":" << cfg.flying.min_coplanar_support
         << ",\"min_stable\":" << cfg.flying_min_stable_samples << "}\n";
    std::printf("apply %s: frames=%d post=%d → %s\n", scene.c_str(), idx, post, out_dir.c_str());
    return 0;
}
