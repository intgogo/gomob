// host_capture — 在 Linux host 用厂商 SDK 顺序抓 depth + light-IR 两批,
// 作为 Android 合一交织流在 host 的等价物,供 IR 置信链路在服务器上复跑(无需手机)。
//
// 为什么顺序而非并发:Linux SDK 只支持 color+depth 的 MIX,不支持 depth+light-IR 并发
//   (light-IR 是结构光原始 IR 图,depth 由它在器件内算出,MIX 双开实测零帧)。
//   但 light-IR 与 depth 是同一个 640x400 传感器,天然逐像素对齐;静态近物场景下顺序抓即可,
//   confidence_probe 要的是「散斑对比度 vs 深度时域 MAD」的空间相关,顺序静态采集完全成立。
//
// 流程(单设备会话):SINGULAR 模式 →(density-first 控制)→ depth 流抓 N 帧 → 停 →
//   light-IR 流抓 N 帧 → 停。depth pix=2(raw/8=mm),light-IR pix=3(纯 16bit IR 灰度,
//   比交织流 high-byte 更干净)。
//
// density-first(--dense 默认开):temporalDenoise=0 + spatialDenoise=0 + confidence(3),
//   逼出稠密带噪深度,才有弱像素供置信掩码验证(对应 finding M1.6.17 density-first regime)。
//
// 编译见 run.sh(链接 .dev 解出的 BerxelSDK-Linux);产物写 OUTPUT_DIR。
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
#include <string>
#include <vector>

namespace {

struct Args {
    std::string out_dir = ".dev/depth_ir_guided/host_capture";
    int frames = 18;     // 与 confidence_probe 时域窗口一致
    int fps = 30;
    int width = 640;     // depth 与 light-IR 都取 640x400 对齐
    int height = 400;
    int timeout_ms = 200;
    int attempts = 0;    // 0 -> 自动 = frames*8
    bool dense = true;   // density-first 控制位
};

Args parse_args(int argc, char** argv) {
    Args a;
    for (int i = 1; i < argc; ++i) {
        std::string k = argv[i];
        auto next = [&]() -> std::string { return (++i < argc) ? argv[i] : std::string(); };
        if (k == "--out-dir") a.out_dir = next();
        else if (k == "--frames") a.frames = std::max(1, std::atoi(next().c_str()));
        else if (k == "--fps") a.fps = std::max(1, std::atoi(next().c_str()));
        else if (k == "--width") a.width = std::atoi(next().c_str());
        else if (k == "--height") a.height = std::atoi(next().c_str());
        else if (k == "--timeout-ms") a.timeout_ms = std::max(1, std::atoi(next().c_str()));
        else if (k == "--attempts") a.attempts = std::atoi(next().c_str());
        else if (k == "--no-dense") a.dense = false;
        else if (k == "--dense") a.dense = true;
        else {
            std::fprintf(stderr,
                "usage: %s [--out-dir DIR] [--frames N] [--fps N] [--width W] [--height H]\n"
                "          [--timeout-ms N] [--attempts N] [--dense|--no-dense]\n", argv[0]);
            std::exit(2);
        }
    }
    if (a.attempts <= 0) a.attempts = a.frames * 8;
    return a;
}

bool write_raw(const std::filesystem::path& p, const void* data, size_t size) {
    if (!data || size == 0) return false;
    std::filesystem::create_directories(p.parent_path());
    std::ofstream out(p, std::ios::binary);
    if (!out) return false;
    out.write(static_cast<const char*>(data), static_cast<std::streamsize>(size));
    return static_cast<bool>(out);
}

std::string leaf(const std::string& prefix, int idx) {
    char buf[64];
    std::snprintf(buf, sizeof(buf), "%s_%02d.raw", prefix.c_str(), idx);
    return buf;
}

// 选最接近请求 w/h/fps 的支持模式;失败回退当前模式。
bool pick_mode(berxel::BerxelHawkDevice* dev, berxel::BerxelHawkStreamType stream,
               const Args& a, berxel::BerxelHawkStreamFrameMode& out) {
    const berxel::BerxelHawkStreamFrameMode* modes = nullptr;
    uint32_t n = 0;
    if (dev->getCurrentFrameMode(stream, &out) != 0) return false;
    if (dev->getSupportFrameModes(stream, &modes, &n) == 0 && modes && n > 0) {
        int best = -1; long bestcost = 1L << 60;
        for (uint32_t i = 0; i < n; ++i) {
            long dw = (long)modes[i].resolutionX - a.width;
            long dh = (long)modes[i].resolutionY - a.height;
            long df = (long)modes[i].framerate - a.fps;
            long cost = dw * dw + dh * dh + df * df;
            if (cost < bestcost) { bestcost = cost; best = (int)i; }
        }
        if (best >= 0) out = modes[best];
    }
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    using namespace berxel;
    const Args a = parse_args(argc, argv);
    std::filesystem::create_directories(a.out_dir);

    BerxelHawkContext* ctx = BerxelHawkContext::getBerxelContext();
    if (!ctx) { std::fprintf(stderr, "getBerxelContext failed\n"); return 1; }

    BerxelHawkDeviceInfo* devices = nullptr;
    uint32_t count = 0;
    int rc = ctx->getDeviceList(&devices, &count);
    std::printf("getDeviceList rc=%d count=%u\n", rc, count);
    if (rc != 0 || count == 0 || !devices) { BerxelHawkContext::destroyBerxelContext(ctx); return 2; }

    BerxelHawkDevice* dev = ctx->openDevice(devices[0]);
    if (!dev) { std::fprintf(stderr, "openDevice failed\n"); BerxelHawkContext::destroyBerxelContext(ctx); return 3; }

    dev->setSystemClock();
    rc = dev->setStreamFlagMode(BERXEL_HAWK_SINGULAR_STREAM_FLAG_MODE);
    std::printf("setStreamFlagMode(SINGULAR) rc=%d\n", rc);

    // density-first 控制位(只影响 depth)
    if (a.dense) {
        std::printf("setDepthConfidence(3) rc=%d\n", dev->setDepthConfidence(3));
        std::printf("setTemporalDenoiseStatus(false) rc=%d\n", dev->setTemporalDenoiseStatus(false));
        std::printf("setSpatialDenoiseStatus(false) rc=%d\n", dev->setSpatialDenoiseStatus(false));
    }

    std::ofstream csv(std::filesystem::path(a.out_dir) / "pairs.csv");
    csv << "kind,idx,ts,frame_idx,w,h,pix,nonzero_or_max\n";

    // 单流顺序抓:返回实际存帧数
    auto capture = [&](BerxelHawkStreamType stream, const char* kind, bool is_depth) -> int {
        BerxelHawkStreamFrameMode mode{};
        if (pick_mode(dev, stream, a, mode)) {
            int r = dev->setFrameMode(stream, &mode);
            std::printf("setFrameMode(%s) rc=%d %dx%d@%d pix=%d\n", kind, r,
                        mode.resolutionX, mode.resolutionY, mode.framerate, mode.pixelType);
        }
        int r = dev->startStreams(stream);
        std::printf("startStreams(%s) rc=%d\n", kind, r);
        if (r != 0) return 0;
        int saved = 0;
        for (int i = 0; i < a.attempts && saved < a.frames; ++i) {
            BerxelHawkFrame* f = nullptr;
            r = is_depth ? dev->readDepthFrame(f, a.timeout_ms)
                         : dev->readLightIrFrame(f, a.timeout_ms);
            if (r != 0 || !f) continue;
            const uint8_t* d = static_cast<const uint8_t*>(f->getData());
            const uint32_t sz = f->getDataSize();
            uint32_t nz = 0; uint16_t mx = 0;
            for (uint32_t k = 0; k + 1 < sz; k += 2) {
                uint16_t v = (uint16_t)d[k] | ((uint16_t)d[k + 1] << 8);
                if (v) nz++;
                if (v > mx) mx = v;
            }
            write_raw(std::filesystem::path(a.out_dir) / leaf(kind, saved), d, sz);
            const uint32_t w = f->getWidth(), h = f->getHeight();
            csv << kind << ',' << saved << ',' << f->getTimeStamp() << ',' << f->getFrameIndex() << ','
                << w << ',' << h << ',' << (int)f->getPixelType() << ',' << (is_depth ? nz : mx) << '\n';
            std::printf("%s[%d] ts=%llu %ux%u pix=%d %s=%u\n", kind, saved,
                        (unsigned long long)f->getTimeStamp(), w, h, (int)f->getPixelType(),
                        is_depth ? "nonzero" : "max", is_depth ? nz : mx);
            dev->releaseFrame(f);
            saved++;
        }
        dev->stopStreams(stream);
        std::printf("=== %s saved=%d ===\n", kind, saved);
        return saved;
    };

    const int nd = capture(BERXEL_HAWK_DEPTH_STREAM, "depth", true);
    const int ni = capture(BERXEL_HAWK_LIGHT_IR_STREAM, "lightir", false);

    std::printf("\n=== TOTAL depth=%d light-ir=%d (target %d each) ===\n", nd, ni, a.frames);
    ctx->closeDevice(dev);
    BerxelHawkContext::destroyBerxelContext(ctx);
    return (nd >= 1 && ni >= 1) ? 0 : 6;
}
