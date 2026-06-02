// eYs3D mode25 真机流验证工具(host,零厂商 SDK)。用【自研】Eys3dHostSession + Mode25Usb2Plan
// 跑 mode25(videoMode=36 + 1280×256 MJPEG + 640×128 depth),把 metric depthMm 帧 dump 出去给
// analyze.py 判定(u16 是否回 0-2047、列内 std 是否不再恒定)。device-gated:需真机 + 带电 hub。
//
// 用法:eys3d_mode25_stream <secs> <out_dir> [color_frame_idx] [depth_frame_idx] [depth_status_rows]
//   帧索引默认 2/4(scripts/eys3d-parse-descriptor.py 从真机 lsusb -v 解析后填)。
#include <libusb-1.0/libusb.h>

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <thread>
#include <vector>

#include "camera/camera_session.h"
#include "eys3d/host/eys3d_host_session.h"
#include "eys3d/portable/eys3d_driver.h"

using namespace gomob::eys3d;
using namespace gomob::eys3d::host;
using gomob::camera::CameraFrame;
using gomob::camera::CameraStreamKind;
using gomob::camera::SessionCallbacks;
using gomob::camera::SessionConfig;
using gomob::camera::StreamProfile;

int main(int argc, char** argv) {
  const int secs = argc > 1 ? atoi(argv[1]) : 8;
  const std::string out = argc > 2 ? argv[2] : ".dev/eys3d_mode25";
  const uint8_t color_idx = static_cast<uint8_t>(argc > 3 ? atoi(argv[3]) : 2);
  const uint8_t depth_idx = static_cast<uint8_t>(argc > 4 ? atoi(argv[4]) : 4);
  const uint16_t status_rows = static_cast<uint16_t>(argc > 5 ? atoi(argv[5]) : 0);
  std::filesystem::create_directories(out);

  std::printf("eYs3D mode25 流验证:videoMode=36 color=1280x256MJPG(frame=%u) depth=640x128(frame=%u,status=%u) secs=%d\n",
              color_idx, depth_idx, status_rows, secs);

  if (libusb_init(nullptr) != 0) { std::fprintf(stderr, "libusb_init 失败\n"); return 1; }
  libusb_device_handle* h =
      libusb_open_device_with_vid_pid(nullptr, kRsd550UsbId.vid, kRsd550UsbId.pid);
  if (!h) {
    std::fprintf(stderr, "打开 %04x:%04x 失败(设备在线?接带电 hub?)\n", kRsd550UsbId.vid, kRsd550UsbId.pid);
    libusb_exit(nullptr);
    return 2;
  }

  // mode25 会话配置:depth 640×128(+状态行)。
  SessionConfig cfg;
  cfg.color = StreamProfile{1280, 256, 5, StreamProfile::Format::kMjpeg, "1280x256MJPG@5"};
  cfg.depth = StreamProfile{640, static_cast<uint16_t>(128 + status_rows), 5,
                            StreamProfile::Format::kDepthU16, "640x128@5"};
  cfg.want_color = true;
  cfg.want_depth = true;

  {
    Eys3dHostSession session(h, Mode25Usb2Plan(color_idx, depth_idx, status_rows), cfg);
    session.SetGeometric(kRsd550RectifiedFx, kRsd550BaselineMm);  // 几何兜底度量
    if (!session.start(SessionCallbacks{})) { std::fprintf(stderr, "start 失败\n"); }

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(secs);
    int saved = 0, polled = 0;
    while (std::chrono::steady_clock::now() < deadline) {
      CameraFrame f;
      const int n = session.poll(&f, 200);
      if (n > 0 && f.kind == CameraStreamKind::kDepthMm) {
        ++polled;
        if (saved < 5) {  // 存前 5 帧 depthMm(u16 mm)给 analyze.py
          std::ofstream o(out + "/depthmm_" + std::to_string(saved) + ".bin", std::ios::binary);
          o.write(reinterpret_cast<const char*>(f.data), static_cast<std::streamsize>(f.size));
          std::printf("  depthMm 帧 %d: %ux%u %zuB serial=%d\n", saved, f.width, f.height, f.size, f.serial);
          ++saved;
        }
      }
    }
    const auto st = session.stats();
    std::printf("== 结果 ==  depth帧=%lld color帧=%lld dropped=%lld errors=%lld 存=%d → %s\n",
                (long long)st.depth_frames, (long long)st.color_frames, (long long)st.dropped,
                (long long)st.errors, saved, polled > 0 ? "✅出 depthMm" : "❌无 depth");
    session.stop();
    session.join();
  }

  libusb_close(h);
  libusb_exit(nullptr);
  return 0;
}
