#include <BerxelHawkContext.h>
#include <BerxelHawkDefines.h>
#include <BerxelHawkDevice.h>
#include <BerxelHawkFrame.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

namespace {

struct Args {
    std::string out_dir = ".dev/berxel-host-sdk/vendor-depth";
    std::string prefix = "vendor-depth";
    int frames = 20;
    int skip = 10;
    int timeout_ms = 100;
    int attempts = 400;
    int width = 640;
    int height = 400;
    int fps = 0;
    bool list_modes = false;
    bool controls_demo = false;
    int depth_ae = -1;
    int depth_confidence = -1;
    int temporal = -1;
    int spatial = -1;
    int fill_hole = -1;
    int denoise = -1;
    int edge = -1;
    int max_depth_mm = 0;
};

void usage(const char* argv0) {
    std::cout
        << "usage: " << argv0 << " [--out-dir DIR] [--prefix NAME]\n"
        << "       [--frames N] [--skip N] [--timeout-ms N] [--attempts N]\n"
        << "       [--width N] [--height N] [--fps N]\n"
        << "       [--list-modes] [--controls-demo]\n"
        << "       [--depth-ae 0|1] [--depth-confidence N]\n"
        << "       [--temporal 0|1] [--spatial 0|1]\n"
        << "       [--fill-hole 0|1] [--denoise 0|1] [--edge 0|1] [--max-depth-mm N]\n\n"
        << "默认保存 640x400 原厂 SDK depth raw16 多帧到 out-dir。\n";
}

Args parse_args(int argc, char** argv) {
    Args args;
    for (int i = 1; i < argc; ++i) {
        const std::string key = argv[i];
        auto next = [&]() -> std::string {
            if (++i >= argc) return {};
            return argv[i];
        };
        if (key == "--out-dir") {
            args.out_dir = next();
        } else if (key == "--prefix") {
            args.prefix = next();
        } else if (key == "--frames") {
            args.frames = std::atoi(next().c_str());
        } else if (key == "--skip") {
            args.skip = std::atoi(next().c_str());
        } else if (key == "--timeout-ms") {
            args.timeout_ms = std::atoi(next().c_str());
        } else if (key == "--attempts") {
            args.attempts = std::atoi(next().c_str());
        } else if (key == "--width") {
            args.width = std::atoi(next().c_str());
        } else if (key == "--height") {
            args.height = std::atoi(next().c_str());
        } else if (key == "--fps") {
            args.fps = std::atoi(next().c_str());
        } else if (key == "--list-modes") {
            args.list_modes = true;
        } else if (key == "--controls-demo") {
            args.controls_demo = true;
        } else if (key == "--depth-ae") {
            args.depth_ae = std::atoi(next().c_str()) != 0 ? 1 : 0;
        } else if (key == "--depth-confidence") {
            args.depth_confidence = std::atoi(next().c_str());
        } else if (key == "--temporal") {
            args.temporal = std::atoi(next().c_str()) != 0 ? 1 : 0;
        } else if (key == "--spatial") {
            args.spatial = std::atoi(next().c_str()) != 0 ? 1 : 0;
        } else if (key == "--fill-hole") {
            args.fill_hole = std::atoi(next().c_str()) != 0 ? 1 : 0;
        } else if (key == "--denoise") {
            args.denoise = std::atoi(next().c_str()) != 0 ? 1 : 0;
        } else if (key == "--edge") {
            args.edge = std::atoi(next().c_str()) != 0 ? 1 : 0;
        } else if (key == "--max-depth-mm") {
            args.max_depth_mm = std::atoi(next().c_str());
        } else if (key == "-h" || key == "--help") {
            usage(argv[0]);
            std::exit(0);
        } else {
            std::cerr << "unknown arg: " << key << "\n";
            usage(argv[0]);
            std::exit(2);
        }
    }
    args.frames = std::max(0, args.frames);
    args.skip = std::max(0, args.skip);
    args.timeout_ms = std::max(1, args.timeout_ms);
    args.attempts = std::max(args.attempts, args.frames + args.skip);
    return args;
}

std::string index_leaf(const std::string& prefix, int index, const std::string& suffix) {
    std::ostringstream ss;
    ss << prefix << '-' << std::setw(3) << std::setfill('0') << index << suffix;
    return ss.str();
}

bool write_binary(const std::filesystem::path& path, const void* data, size_t size) {
    if (!data || size == 0) return false;
    std::filesystem::create_directories(path.parent_path());
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    out.write(static_cast<const char*>(data), static_cast<std::streamsize>(size));
    return static_cast<bool>(out);
}

int fraction_bits(berxel::BerxelHawkPixelType pixel_type) {
    switch (pixel_type) {
        case berxel::BERXEL_HAWK_PIXEL_TYPE_DEP_16BIT_13I_3D:
            return 3;
        case berxel::BERXEL_HAWK_PIXEL_TYPE_DEP_16BIT_14I_2D:
            return 2;
        case berxel::BERXEL_HAWK_PIXEL_TYPE_DEP_16BIT_12I_4D:
        default:
            return 4;
    }
}

uint16_t read_u16_le(const uint8_t* data, size_t index) {
    const size_t byte_index = index * 2;
    return static_cast<uint16_t>(data[byte_index]) |
           static_cast<uint16_t>(static_cast<uint16_t>(data[byte_index + 1]) << 8);
}

struct Stats {
    uint32_t pixels = 0;
    uint32_t nonzero = 0;
    uint16_t raw_min = 0;
    uint16_t raw_p01 = 0;
    uint16_t raw_p50 = 0;
    uint16_t raw_p95 = 0;
    uint16_t raw_p99 = 0;
    uint16_t raw_max = 0;
    double mean_raw = 0.0;
    double mean_mm = 0.0;
};

Stats compute_stats(const uint8_t* data,
                    uint32_t size,
                    uint32_t width,
                    uint32_t height,
                    berxel::BerxelHawkPixelType pixel_type) {
    Stats stats;
    if (!data || size < 2) return stats;
    const size_t pixel_count = std::min<size_t>(size / 2, static_cast<size_t>(width) * height);
    stats.pixels = static_cast<uint32_t>(std::min<size_t>(pixel_count, UINT32_MAX));

    std::vector<uint16_t> nonzero;
    nonzero.reserve(pixel_count);
    uint64_t sum = 0;
    for (size_t i = 0; i < pixel_count; ++i) {
        const uint16_t v = read_u16_le(data, i);
        if (v == 0) continue;
        nonzero.push_back(v);
        sum += v;
    }
    stats.nonzero = static_cast<uint32_t>(std::min<size_t>(nonzero.size(), UINT32_MAX));
    if (nonzero.empty()) return stats;

    std::sort(nonzero.begin(), nonzero.end());
    auto quantile = [&](double q) -> uint16_t {
        const size_t index = std::min(nonzero.size() - 1,
                                      static_cast<size_t>(q * (nonzero.size() - 1)));
        return nonzero[index];
    };
    stats.raw_min = nonzero.front();
    stats.raw_p01 = quantile(0.01);
    stats.raw_p50 = quantile(0.50);
    stats.raw_p95 = quantile(0.95);
    stats.raw_p99 = quantile(0.99);
    stats.raw_max = nonzero.back();
    stats.mean_raw = static_cast<double>(sum) / static_cast<double>(nonzero.size());
    stats.mean_mm = stats.mean_raw / static_cast<double>(1 << fraction_bits(pixel_type));
    return stats;
}

bool write_pgm(const std::filesystem::path& path,
               const uint8_t* data,
               uint32_t size,
               uint32_t width,
               uint32_t height) {
    if (!data || width == 0 || height == 0 || size < width * height * 2u) return false;
    const Stats stats = compute_stats(
        data,
        size,
        width,
        height,
        berxel::BERXEL_HAWK_PIXEL_TYPE_DEP_16BIT_12I_4D);
    if (stats.nonzero == 0) return false;
    uint16_t low = stats.raw_p01;
    uint16_t high = stats.raw_p99;
    if (high <= low) high = static_cast<uint16_t>(low + 1);

    std::filesystem::create_directories(path.parent_path());
    std::ofstream out(path, std::ios::binary);
    if (!out) return false;
    out << "P5\n" << width << " " << height << "\n255\n";
    const size_t pixels = static_cast<size_t>(width) * height;
    for (size_t i = 0; i < pixels; ++i) {
        const uint16_t v = read_u16_le(data, i);
        int gray = 0;
        if (v > low) {
            gray = static_cast<int>(
                (static_cast<uint32_t>(std::min<uint16_t>(v, high) - low) * 255u) /
                static_cast<uint32_t>(high - low));
        }
        const uint8_t byte = static_cast<uint8_t>(std::clamp(gray, 0, 255));
        out.write(reinterpret_cast<const char*>(&byte), 1);
    }
    return static_cast<bool>(out);
}

void list_depth_modes(berxel::BerxelHawkDevice* device) {
    const berxel::BerxelHawkStreamFrameMode* modes = nullptr;
    uint32_t count = 0;
    const int rc = device->getSupportFrameModes(berxel::BERXEL_HAWK_DEPTH_STREAM, &modes, &count);
    std::cout << "getSupportFrameModes depth rc=" << rc << " count=" << count << "\n";
    if (rc != 0 || !modes) return;
    for (uint32_t i = 0; i < count; ++i) {
        std::cout << "  [" << i << "] " << modes[i].resolutionX << "x"
                  << modes[i].resolutionY << "@" << static_cast<int>(modes[i].framerate)
                  << " pix=" << static_cast<int>(modes[i].pixelType) << "\n";
    }
}

void apply_demo_controls(berxel::BerxelHawkDevice* device) {
    std::cout << "setDepthAEStatus(true) rc=" << device->setDepthAEStatus(true) << "\n";
    std::cout << "setDepthConfidence(3) rc=" << device->setDepthConfidence(3) << "\n";
    std::cout << "setTemporalDenoiseStatus(false) rc="
              << device->setTemporalDenoiseStatus(false) << "\n";
    std::cout << "setSpatialDenoiseStatus(false) rc="
              << device->setSpatialDenoiseStatus(false) << "\n";
}

void apply_optional_controls(berxel::BerxelHawkDevice* device, const Args& args) {
    if (args.depth_ae >= 0) {
        std::cout << "setDepthAEStatus(" << args.depth_ae
                  << ") rc=" << device->setDepthAEStatus(args.depth_ae != 0) << "\n";
    }
    if (args.depth_confidence >= 0) {
        std::cout << "setDepthConfidence(" << args.depth_confidence
                  << ") rc=" << device->setDepthConfidence(args.depth_confidence) << "\n";
    }
    if (args.temporal >= 0) {
        std::cout << "setTemporalDenoiseStatus(" << args.temporal
                  << ") rc=" << device->setTemporalDenoiseStatus(args.temporal != 0) << "\n";
    }
    if (args.spatial >= 0) {
        std::cout << "setSpatialDenoiseStatus(" << args.spatial
                  << ") rc=" << device->setSpatialDenoiseStatus(args.spatial != 0) << "\n";
    }
}

}  // namespace

int main(int argc, char** argv) {
    using namespace berxel;
    const Args args = parse_args(argc, argv);
    std::filesystem::create_directories(args.out_dir);

    BerxelHawkContext* context = BerxelHawkContext::getBerxelContext();
    if (!context) {
        std::fprintf(stderr, "getBerxelContext failed\n");
        return 1;
    }

    BerxelHawkDeviceInfo* devices = nullptr;
    uint32_t count = 0;
    int rc = context->getDeviceList(&devices, &count);
    std::printf("getDeviceList rc=%d count=%u\n", rc, count);
    if (rc != 0 || count == 0 || !devices) {
        BerxelHawkContext::destroyBerxelContext(context);
        return 2;
    }

    BerxelHawkDevice* device = context->openDevice(devices[0]);
    if (!device) {
        std::fprintf(stderr, "openDevice failed\n");
        BerxelHawkContext::destroyBerxelContext(context);
        return 3;
    }

    if (args.list_modes) {
        list_depth_modes(device);
    }

    device->setSystemClock();
    rc = device->setStreamFlagMode(BERXEL_HAWK_SINGULAR_STREAM_FLAG_MODE);
    std::printf("setStreamFlagMode rc=%d\n", rc);
    if (args.controls_demo) {
        apply_demo_controls(device);
    }
    apply_optional_controls(device, args);
    if (args.fill_hole >= 0) {
        std::cout << "setFillHoleStatus(" << args.fill_hole
                  << ") rc=" << device->setFillHoleStatus(args.fill_hole != 0) << "\n";
    }
    if (args.denoise >= 0) {
        std::cout << "setDenoiseStatus(" << args.denoise
                  << ") rc=" << device->setDenoiseStatus(args.denoise != 0) << "\n";
    }
    if (args.edge >= 0) {
        std::cout << "setEdgeOptimizationStatus(" << args.edge
                  << ") rc=" << device->setEdgeOptimizationStatus(args.edge != 0) << "\n";
    }
    if (args.max_depth_mm > 0) {
        std::cout << "setMaxDepthValue(" << args.max_depth_mm
                  << ") rc=" << device->setMaxDepthValue(static_cast<uint32_t>(args.max_depth_mm))
                  << "\n";
    }

    BerxelHawkStreamFrameMode mode{};
    rc = device->getCurrentFrameMode(BERXEL_HAWK_DEPTH_STREAM, &mode);
    std::printf("getCurrentFrameMode depth rc=%d %dx%d@%d pix=%d\n",
                rc,
                mode.resolutionX,
                mode.resolutionY,
                mode.framerate,
                mode.pixelType);
    if (args.width > 0) mode.resolutionX = static_cast<int16_t>(args.width);
    if (args.height > 0) mode.resolutionY = static_cast<int16_t>(args.height);
    if (args.fps > 0) mode.framerate = static_cast<int8_t>(args.fps);
    rc = device->setFrameMode(BERXEL_HAWK_DEPTH_STREAM, &mode);
    std::printf("setFrameMode depth rc=%d %dx%d@%d pix=%d\n",
                rc,
                mode.resolutionX,
                mode.resolutionY,
                mode.framerate,
                mode.pixelType);

    const std::filesystem::path out_dir(args.out_dir);
    std::ofstream csv(out_dir / "frames.csv");
    csv << "saved,frame_index,timestamp,width,height,fps,pixel_type,bytes,tx_temperature,"
        << "pixels,nonzero_u16,valid_ratio,raw_min,raw_p01,raw_p50,raw_p95,raw_p99,"
        << "raw_max,mean_raw,mean_mm,path\n";

    rc = device->startStreams(BERXEL_HAWK_DEPTH_STREAM);
    std::printf("startStreams depth rc=%d\n", rc);
    if (rc != 0) {
        context->closeDevice(device);
        BerxelHawkContext::destroyBerxelContext(context);
        return 4;
    }

    int saved = 0;
    int seen = 0;
    int result = 5;
    for (int i = 0; i < args.attempts && saved < args.frames; ++i) {
        BerxelHawkFrame* frame = nullptr;
        rc = device->readDepthFrame(frame, args.timeout_ms);
        if (!frame) continue;
        seen++;
        if (seen <= args.skip) {
            device->releaseFrame(frame);
            continue;
        }

        const uint8_t* data = static_cast<const uint8_t*>(frame->getData());
        const uint32_t size = frame->getDataSize();
        const uint32_t width = frame->getWidth();
        const uint32_t height = frame->getHeight();
        const auto pixel_type = frame->getPixelType();
        const Stats stats = compute_stats(data, size, width, height, pixel_type);
        const std::string raw_leaf = index_leaf(args.prefix, saved, ".raw");
        const std::string pgm_leaf = index_leaf(args.prefix, saved, ".pgm");
        const std::filesystem::path raw_path = out_dir / raw_leaf;
        const std::filesystem::path pgm_path = out_dir / pgm_leaf;

        const bool raw_ok = write_binary(raw_path, data, size);
        write_pgm(pgm_path, data, size, width, height);
        std::printf("saved=%d frame=%u ts=%llu %ux%u fps=%u pix=%d size=%u nonzero=%u ratio=%.4f mean_mm=%.2f path=%s\n",
                    saved,
                    frame->getFrameIndex(),
                    static_cast<unsigned long long>(frame->getTimeStamp()),
                    width,
                    height,
                    frame->getFPS(),
                    static_cast<int>(pixel_type),
                    size,
                    stats.nonzero,
                    stats.pixels == 0 ? 0.0 : static_cast<double>(stats.nonzero) / stats.pixels,
                    stats.mean_mm,
                    raw_path.string().c_str());

        csv << saved << ','
            << frame->getFrameIndex() << ','
            << frame->getTimeStamp() << ','
            << width << ','
            << height << ','
            << frame->getFPS() << ','
            << static_cast<int>(pixel_type) << ','
            << size << ','
            << frame->getTxTemperature() << ','
            << stats.pixels << ','
            << stats.nonzero << ','
            << (stats.pixels == 0 ? 0.0 : static_cast<double>(stats.nonzero) / stats.pixels) << ','
            << stats.raw_min << ','
            << stats.raw_p01 << ','
            << stats.raw_p50 << ','
            << stats.raw_p95 << ','
            << stats.raw_p99 << ','
            << stats.raw_max << ','
            << stats.mean_raw << ','
            << stats.mean_mm << ','
            << raw_leaf << '\n';

        device->releaseFrame(frame);
        if (!raw_ok) {
            result = 6;
            break;
        }
        saved++;
        result = 0;
    }

    device->stopStreams(BERXEL_HAWK_DEPTH_STREAM);
    context->closeDevice(device);
    BerxelHawkContext::destroyBerxelContext(context);

    std::printf("summary saved=%d requested=%d skipped=%d seen=%d result=%d out=%s\n",
                saved,
                args.frames,
                args.skip,
                seen,
                result,
                args.out_dir.c_str());
    return saved == args.frames ? 0 : result;
}
