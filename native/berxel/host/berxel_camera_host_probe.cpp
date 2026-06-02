// Berxel P100R3 host 统一路径验证工具（M6.8b ④ host）。
//
// 经【厂商无关】ICameraDriver(BerxelDriver)::open_host 在 Linux 开发服务器对真机取双流,
// 与 Android open_fd 同一 berxel_setup_and_launch 序列、同一 BerxelSessionAdapter snapshot 契约。
// 验证统一抽象在 host 端真机出 metric depth(raw/8 mm),dump 前 N 帧给 analyze.py 判定。
//
// 用法：berxel_camera_host_probe <secs> <out_dir> [depthFps=45] [enableColor=0]
//   asset(master XU5 / companion init JSON)固定取仓内 core:native-bridge assets,与 Android 完全一致。
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

#include "berxel/host/berxel_camera_adapter.h"
#include "camera/camera_session.h"
#include "camera/host/usb_context.h"

using gomob::camera::CameraFrame;
using gomob::camera::SessionCallbacks;
using gomob::camera::SessionConfig;
using gomob::camera::UsbContext;

namespace {

constexpr const char* kMasterXuAsset =
    "core/native-bridge/src/main/assets/berxel/iHawkP100R3_master_xu5_init.json";
constexpr const char* kCompanionInitAsset =
    "core/native-bridge/src/main/assets/berxel/iHawkP100R3_init_sequence.json";

std::vector<uint8_t> read_file(const char* path) {
  std::ifstream f(path, std::ios::binary);
  if (!f) return {};
  return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)),
                              std::istreambuf_iterator<char>());
}

// 与 Kotlin packBerxelOptions / native unpack_berxel_options 对称：
// [u32 xuLen][xu][u32 initLen][init][14×i32 cfg]（小端）。
std::string pack_options(const std::vector<uint8_t>& xu, const std::vector<uint8_t>& init,
                         const int32_t cfg[14]) {
  std::string b;
  auto put_u32 = [&](uint32_t v) { b.append(reinterpret_cast<const char*>(&v), 4); };
  put_u32(static_cast<uint32_t>(xu.size()));
  b.append(reinterpret_cast<const char*>(xu.data()), xu.size());
  put_u32(static_cast<uint32_t>(init.size()));
  b.append(reinterpret_cast<const char*>(init.data()), init.size());
  for (int i = 0; i < 14; ++i) b.append(reinterpret_cast<const char*>(&cfg[i]), 4);
  return b;
}

}  // namespace

int main(int argc, char** argv) {
  const int secs = argc > 1 ? std::atoi(argv[1]) : 8;
  const std::string out = argc > 2 ? argv[2] : ".dev/berxel_camera_host";
  const int depthFps = argc > 3 ? std::atoi(argv[3]) : 45;
  const int enableColor = argc > 4 ? std::atoi(argv[4]) : 0;
  const int saveFrames = argc > 5 ? std::atoi(argv[5]) : 5;  // parity 需多帧 distinct
  std::filesystem::create_directories(out);

  const auto master_xu = read_file(kMasterXuAsset);
  const auto comp_init = read_file(kCompanionInitAsset);
  if (master_xu.empty() || comp_init.empty()) {
    std::fprintf(stderr, "读 asset 失败(从仓根运行?): xu=%zu init=%zu\n", master_xu.size(), comp_init.size());
    return 2;
  }

  // 与 BerxelNativeStack.startDualNative 同一 14-int config。
  const int32_t depthInterval = 10'000'000 / (depthFps < 5 ? 5 : depthFps);
  const int32_t cfg[14] = {
      1280, 801, depthFps, 1, depthInterval,   // depth: w,h,fps,frameIdx,interval100ns
      640,  400, 15,       3, 666667,          // color: w,h,fps,frameIdx,interval100ns
      50,                                      // keepaliveMs
      64 * 1024,                               // readLen (DUAL_ASYNC_READ_LEN)
      enableColor,                             // enableColor
      0,                                       // depthTemporal on(>=0)
  };
  std::printf("berxel host 统一验证: open_host(BerxelDriver) depth=1280x801@%d enableColor=%d secs=%d\n",
              depthFps, enableColor, secs);

  UsbContext ctx;
  if (!ctx.valid()) { std::fprintf(stderr, "UsbContext 无效(libusb_init 失败)\n"); return 1; }

  auto drv = gomob::berxel::host::MakeBerxelDriver();
  SessionConfig scfg;
  scfg.options_json = pack_options(master_xu, comp_init, cfg);

  auto sess = drv->open_host(ctx, scfg);
  if (!sess) { std::fprintf(stderr, "open_host 失败(设备在线? 看上面 LOGE)\n"); return 3; }
  if (!sess->start(SessionCallbacks{})) std::fprintf(stderr, "start 返 false\n");

  // active depth 1280x800 → 1024000 u16；按最大分配。
  const size_t kCapPx = 1280 * 800;
  std::vector<uint16_t> depth(kCapPx);
  int saved = 0, polled = 0;
  // parity：按 ~300ms 间隔存 distinct 帧（静态场景下取稳定中位）。
  auto next_save = std::chrono::steady_clock::now();
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(secs);
  while (std::chrono::steady_clock::now() < deadline) {
    int64_t meta[4] = {0, 0, 0, 0};
    const int n = sess->snapshot_depth_mm(depth.data(), kCapPx, meta);
    if (n > 0) {
      ++polled;
      const auto now = std::chrono::steady_clock::now();
      if (saved < saveFrames && now >= next_save) {
        char name[64];
        std::snprintf(name, sizeof(name), "/depthmm_%03d.bin", saved);
        std::ofstream o(out + name, std::ios::binary);
        o.write(reinterpret_cast<const char*>(depth.data()), n);
        std::printf("  depthMm 帧 %d: %lldx%lld %dB frame=%lld\n", saved,
                    (long long)meta[0], (long long)meta[1], n, (long long)meta[2]);
        ++saved;
        next_save = now + std::chrono::milliseconds(300);
      }
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(20));
  }

  int64_t ext[16] = {0};
  const int ne = sess->extended_stats(ext, 16);
  std::printf("== 结果 == depth帧=%lld(ext) color帧=%lld depthSeq=%lld 存=%d polled=%d → %s\n",
              ne > 0 ? (long long)ext[0] : -1, ne > 4 ? (long long)ext[4] : -1,
              ne > 15 ? (long long)ext[15] : -1, saved, polled,
              polled > 0 ? "✅出 depthMm" : "❌无 depth");

  sess->stop();
  sess->join();
  return 0;
}
