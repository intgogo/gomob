// 最小化原厂 MIX color+depth reader —— 专用于 usbmon 抓【MIX 模式开流的 USB 命令序列】。
// 序列对齐 HawkMixColorDepth 样例:openDevice → setSystemClock → setStreamFlagMode(MIX) →
//   setFrameMode(COLOR) → setFrameMode(DEPTH) → startStreams(COLOR|DEPTH) → 读几帧 → 退。
// 不做帧分析,目的是让 usbmon 录到原厂 MIX 的 master BX(XU5)+ companion 命令序列,供 diff 我们的 setup_dual。
#include <BerxelHawkContext.h>
#include <BerxelHawkDefines.h>
#include <BerxelHawkDevice.h>
#include <BerxelHawkFrame.h>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <chrono>

using namespace berxel;

int main(int argc, char** argv) {
    int wantW = argc > 1 ? std::atoi(argv[1]) : 640;
    int wantH = argc > 2 ? std::atoi(argv[2]) : 400;
    int wantFps = argc > 3 ? std::atoi(argv[3]) : 30;
    int readN = argc > 4 ? std::atoi(argv[4]) : 30;

    BerxelHawkContext* ctx = BerxelHawkContext::getBerxelContext();
    if (!ctx) { std::fprintf(stderr, "no context\n"); return 1; }
    BerxelHawkDeviceInfo* devs = nullptr; uint32_t n = 0;
    if (ctx->getDeviceList(&devs, &n) != 0 || n == 0) { std::fprintf(stderr, "no device\n"); return 2; }

    std::printf("=== MARK openDevice ===\n"); std::fflush(stdout);
    BerxelHawkDevice* dev = ctx->openDevice(devs[0]);
    if (!dev) { std::fprintf(stderr, "openDevice failed\n"); return 3; }

    dev->setSystemClock();
    std::printf("=== MARK setStreamFlagMode(MIX=0x02) ===\n"); std::fflush(stdout);
    int rc = dev->setStreamFlagMode(BERXEL_HAWK_MIX_STREAM_FLAG_MODE);
    std::printf("setStreamFlagMode(MIX) rc=%d\n", rc); std::fflush(stdout);

    // COLOR frame mode
    BerxelHawkStreamFrameMode cm{};
    dev->getCurrentFrameMode(BERXEL_HAWK_COLOR_STREAM, &cm);
    cm.resolutionX = static_cast<int16_t>(wantW);
    cm.resolutionY = static_cast<int16_t>(wantH);
    cm.framerate = static_cast<int8_t>(wantFps);
    std::printf("=== MARK setFrameMode(COLOR %dx%d@%d) ===\n", wantW, wantH, wantFps); std::fflush(stdout);
    std::printf("setFrameMode(COLOR) rc=%d\n", dev->setFrameMode(BERXEL_HAWK_COLOR_STREAM, &cm)); std::fflush(stdout);

    // DEPTH frame mode
    BerxelHawkStreamFrameMode dm{};
    dev->getCurrentFrameMode(BERXEL_HAWK_DEPTH_STREAM, &dm);
    dm.resolutionX = static_cast<int16_t>(wantW);
    dm.resolutionY = static_cast<int16_t>(wantH);
    dm.framerate = static_cast<int8_t>(wantFps);
    std::printf("=== MARK setFrameMode(DEPTH %dx%d@%d) ===\n", wantW, wantH, wantFps); std::fflush(stdout);
    std::printf("setFrameMode(DEPTH) rc=%d\n", dev->setFrameMode(BERXEL_HAWK_DEPTH_STREAM, &dm)); std::fflush(stdout);

    std::printf("=== MARK startStreams(COLOR|DEPTH=0x03) ===\n"); std::fflush(stdout);
    rc = dev->startStreams(BERXEL_HAWK_COLOR_STREAM | BERXEL_HAWK_DEPTH_STREAM);
    std::printf("startStreams(COLOR|DEPTH) rc=%d\n", rc); std::fflush(stdout);
    if (rc != 0) { ctx->closeDevice(dev); return 4; }

    std::printf("=== MARK streaming ===\n"); std::fflush(stdout);
    int gotColor = 0, gotDepth = 0;
    for (int i = 0; i < readN; ++i) {
        BerxelHawkFrame* df = nullptr; BerxelHawkFrame* cf = nullptr;
        dev->readDepthFrame(df, 100);
        if (df) { gotDepth++; dev->releaseFrame(df); }
        dev->readColorFrame(cf, 100);
        if (cf) { gotColor++; dev->releaseFrame(cf); }
    }
    std::printf("=== RESULT gotColor=%d gotDepth=%d ===\n", gotColor, gotDepth); std::fflush(stdout);

    dev->stopStreams(BERXEL_HAWK_COLOR_STREAM | BERXEL_HAWK_DEPTH_STREAM);
    ctx->closeDevice(dev);
    BerxelHawkContext::destroyBerxelContext(ctx);
    return (gotColor > 0 && gotDepth > 0) ? 0 : 5;
}
