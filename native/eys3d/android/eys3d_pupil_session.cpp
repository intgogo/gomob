// eYs3D mode25 真深度取流 — pupil libuvc(libusb100 后端,能出流)直接流 IF2 深度。
//
// ★★ 2026-06-11 续33 铁律修复:**IF1 彩色保活 + IF2 深度 双流**(libuvc_lusb100,libusb100 后端,能解析 MJPEG)。
//   核心铁律(续16/17/21/23 反复实证):设备 stereo→depth ASIC 流水线**只在 libuvc 持续排空 IF1(彩色)时才出 IF2 深度**;
//   手动 bulk 排空 IF1 = 2 帧停,只有 libuvc 内部传输管理能保活。续17 用 libuvc-YUYV-color + 手动 depth 实跑连续(14bit)证此。
//   ⚠ 本会话此前(及该文件旧注释)"原厂只开 IF2 不流 IF1"是**错前提**:rsd550_clean_seq.txt 只垫了 libusb100,
//   而 VINCreator 的 RS-D550 IF1 彩色走 **libuvc1→libusb1001.so**(续29)、IF2 深度走 libuvc→libusb100.so → 垫片漏抓 IF1 COMMIT,
//   误判成"不开 IF1"。导致只开 IF2 → 深度流水线没启动 → 16+ 次全 status=2 零帧。
//   正解:libuvc_lusb100 流 IF1 MJPEG 1280×256(纯保活丢帧) + IF2 YUYV 640×128(u16 视差→core.OnRawDepthFrame→metric mm)。
//   前置 arming(videoMode 0x24 + sensor reg + counter 门控 flash)走 Java(armViaJava,live-counter,writes ok=1008 fail=0)。

#include "libuvc/libuvc.h"

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <vector>

#include "eys3d/portable/eys3d_session_core.h"
#include "eys3d_clean_arming_blob.h"  // 单句柄 arming blob(2049 条,VINCreator 原序)

#define PLOG(...) __android_log_print(ANDROID_LOG_INFO, "eys3d_pupil", __VA_ARGS__)

namespace gomob::eys3d::android {

namespace {

// libuvc_lusb100.so 用 dlopen(RTLD_LOCAL):其 libusb_* 走自己 NEEDED 的 libusb100.so,不被全局 libusb-1.0.so 遮蔽。
struct LusbUvcApi {
  uvc_error_t (*init)(uvc_context_t**, struct libusb_context*) = nullptr;
  uvc_error_t (*get_device_with_fd)(uvc_context_t*, uvc_device_handle_t**, int, int, const char*, int, int, int) = nullptr;
  uvc_error_t (*get_stream_ctrl_format_size)(uvc_device_handle_t*, uvc_stream_ctrl_t*, enum uvc_frame_format, int, int, int) = nullptr;
  int (*eys3d_arm)(uvc_device_handle_t*, const unsigned char*, int, int, int) = nullptr;  // 单句柄 arming(skip_vs,max_ops)
  uvc_error_t (*start_streaming)(uvc_device_handle_t*, uvc_stream_ctrl_t*, uvc_frame_callback_t*, void*, uint8_t) = nullptr;
  void (*stop_streaming)(uvc_device_handle_t*) = nullptr;
  void (*close)(uvc_device_handle_t*) = nullptr;
  void (*exit)(uvc_context_t*) = nullptr;
  void* lib = nullptr;
};

bool LoadLusbUvc(LusbUvcApi* a) {
  a->lib = dlopen("libuvc_lusb100.so", RTLD_NOW | RTLD_LOCAL);
  if (!a->lib) { PLOG("dlopen libuvc_lusb100.so failed: %s", dlerror()); return false; }
#define USYM(f, n) a->f = reinterpret_cast<decltype(a->f)>(dlsym(a->lib, n))
  USYM(init, "uvc_init");
  USYM(get_device_with_fd, "uvc_get_device_with_fd");
  USYM(get_stream_ctrl_format_size, "uvc_get_stream_ctrl_format_size");
  USYM(eys3d_arm, "uvc_eys3d_arm");
  USYM(start_streaming, "uvc_start_streaming");
  USYM(stop_streaming, "uvc_stop_streaming");
  USYM(close, "uvc_close");
  USYM(exit, "uvc_exit");
#undef USYM
  const bool ok = a->init && a->get_device_with_fd && a->get_stream_ctrl_format_size &&
                  a->eys3d_arm && a->start_streaming && a->stop_streaming && a->close && a->exit;
  PLOG("libuvc_lusb100 dlopen ok=%d eys3d_arm=%p", ok, (void*)a->eys3d_arm);
  return ok;
}

int64_t NowNs() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

// ★ native ioctl arming（skip-VS + live-counter）：取代已删的 Java armViaJava。逐条 USBDEVFS_CONTROL 回放抓包 blob 里
//   非 VS 的控制（videoMode 0x24 + IR + a0 激活 + sensor reg + counter 门控 flash），跳过 VS PROBE/COMMIT（wIdx1/2，
//   交 libuvc 标准协商）。0a/0b 写首字节用设备 live-counter 覆盖。走裸 ioctl 不毒化 bulk（同步控制与 libusb 异步 URB 分离）。
int ArmSkipVs(int fd) {
  const unsigned char* b = EYS3D_CLEAN_ARMING_BLOB;
  const int total = static_cast<int>(sizeof(EYS3D_CLEAN_ARMING_BLOB));
  int p = 0, ok = 0, fail = 0, skipVs = 0, ctrOverride = 0;
  unsigned char inbuf[512], outbuf[512];
  uint8_t liveCtr = 0; bool haveCtr = false;
  while (p + 9 <= total) {
    uint8_t bmReq = b[p], bReq = b[p + 1];
    uint16_t wVal = static_cast<uint16_t>(b[p + 2] | (b[p + 3] << 8));
    uint16_t wIdx = static_cast<uint16_t>(b[p + 4] | (b[p + 5] << 8));
    uint16_t wLen = static_cast<uint16_t>(b[p + 6] | (b[p + 7] << 8));
    uint8_t payLen = b[p + 8];
    const unsigned char* pay = b + p + 9;
    p += 9 + payLen;
    if (wIdx == 0x0001 || wIdx == 0x0002) { skipVs++; continue; }  // VS 交 libuvc 协商
    if (wLen > sizeof(inbuf)) { fail++; continue; }
    const bool isIn = (bmReq & 0x80) != 0;
    usbdevfs_ctrltransfer ct{};
    ct.bRequestType = bmReq; ct.bRequest = bReq; ct.wValue = wVal; ct.wIndex = wIdx;
    ct.wLength = wLen; ct.timeout = 1000;
    if (isIn) {
      ct.data = inbuf;
    } else if ((wVal == 0x0a00 || wVal == 0x0b00) && payLen > 0 && haveCtr) {
      std::memcpy(outbuf, pay, payLen); outbuf[0] = liveCtr; ct.data = outbuf; ctrOverride++;
    } else {
      ct.data = const_cast<unsigned char*>(pay);
    }
    int rc = ioctl(fd, USBDEVFS_CONTROL, &ct);
    if (rc < 0) { fail++; continue; }
    ok++;
    if (isIn && wVal == 0x0a00 && rc >= 1) { liveCtr = inbuf[0]; haveCtr = true; }
  }
  PLOG("ArmSkipVs(native ioctl): ok=%d fail=%d skipVs=%d ctrOverride=%d", ok, fail, skipVs, ctrOverride);
  return ok;
}

// usbfs fd → busnum/devaddr(libusb_get_device_with_fd 需要)。
void ResolveBusDev(int fd, int* busnum, int* devaddr) {
  char path[64], link[256];
  std::snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
  ssize_t n = readlink(path, link, sizeof(link) - 1);
  if (n <= 0) return;
  link[n] = '\0';
  const char* base = "/dev/bus/usb/";
  const char* p = std::strstr(link, base);
  if (!p) return;
  p += std::strlen(base);
  *busnum = std::atoi(p);
  const char* slash = std::strchr(p, '/');
  if (slash) *devaddr = std::atoi(slash + 1);
}

// 深度帧内容诊断:判 u16 是真视差(内容丰富/min-max 跨度大)还是恒定垃圾。
void DiagDepth(const uint8_t* p, size_t bytes, int64_t n) {
  if (n > 3 && n % 30 != 0) return;
  const size_t count = bytes / 2;
  if (!count) return;
  const uint16_t* u16 = reinterpret_cast<const uint16_t*>(p);
  uint16_t mn = 0xFFFF, mx = 0;
  std::vector<uint16_t> s;
  const size_t step = count > 4096 ? count / 4096 : 1;
  for (size_t i = 0; i < count; i += step) {
    s.push_back(u16[i]);
    if (u16[i] < mn) mn = u16[i];
    if (u16[i] > mx) mx = u16[i];
  }
  std::sort(s.begin(), s.end());
  int distinct = s.empty() ? 0 : 1;
  for (size_t i = 1; i < s.size(); ++i)
    if (s[i] != s[i - 1]) ++distinct;
  PLOG("depth#%lld %zuB u16 min=%u max=%u distinct=%d/%zu %s", (long long)n, bytes, mn, mx, distinct,
       s.size(), distinct < 16 ? "[恒定?垃圾]" : "[丰富?真深度]");
}

// 深度流回调上下文。
struct DepthCtx {
  Eys3dSessionCore* core = nullptr;
  std::atomic<int64_t> frames{0};
  std::atomic<int64_t> bytes{0};
};

// libuvc 深度回调(IF2 YUYV 640×128 = 640×128 u16 视差)→ 路由到深度通路。
void DepthUvcCb(uvc_frame_t* frame, void* user) {
  auto* d = static_cast<DepthCtx*>(user);
  if (!frame || !frame->data || !frame->data_bytes) return;
  const int64_t n = ++d->frames;
  d->bytes += static_cast<int64_t>(frame->data_bytes);
  DiagDepth(static_cast<const uint8_t*>(frame->data), frame->data_bytes, n);
  if (d->core)
    d->core->OnRawDepthFrame(static_cast<const uint8_t*>(frame->data), frame->data_bytes, NowNs());
  if (n <= 3 || n % 30 == 0) PLOG("depth(IF2 YUYV) #%lld %zuB", (long long)n, frame->data_bytes);
}

// ★ IF1 彩色保活上下文 + 回调(2026-06-11 续33):只计数丢帧,纯为持续排空 IF1 唤醒设备 stereo→depth 流水线。
struct ColorCtx {
  std::atomic<int64_t> frames{0};
  std::atomic<int64_t> bytes{0};
};
void ColorKeepaliveCb(uvc_frame_t* frame, void* user) {
  auto* c = static_cast<ColorCtx*>(user);
  if (!frame || !frame->data_bytes) return;
  const int64_t n = ++c->frames;
  c->bytes += static_cast<int64_t>(frame->data_bytes);
  if (n <= 3 || n % 30 == 0) PLOG("color(IF1 keepalive) #%lld %zuB", (long long)n, frame->data_bytes);
}

}  // namespace

// 入口:fd → libuvc_lusb100 流 IF2 深度(YUYV 640×128,VINCreator 同款),IF1 不开、不手动 bulk。
// 前置:Eys3dCameraService.armViaJava 已发 videoMode 0x24 + sensor reg(skip VS,留给 libuvc 协商 IF2)。
void RunEys3dPupilMjpegSession(int fd, Eys3dSessionCore& core) {
  static LusbUvcApi uvc;
  if (!uvc.lib && !LoadLusbUvc(&uvc)) { core.MarkError("load libuvc_lusb100 failed"); return; }

  uvc_context_t* uctx = nullptr;
  uvc_error_t er = uvc.init(&uctx, nullptr);  // libuvc_lusb100 用 libusb_init2(libusb100) 自有 ctx
  if (er != UVC_SUCCESS || !uctx) { core.MarkError("uvc_init failed"); PLOG("uvc_init rc=%d", er); return; }

  int busnum = 0, devaddr = 0;
  ResolveBusDev(fd, &busnum, &devaddr);
  uvc_device_handle_t* devh = nullptr;
  er = uvc.get_device_with_fd(uctx, &devh, 0x3438, 0x0206, nullptr, fd, busnum, devaddr);
  PLOG("uvc_get_device_with_fd(fd=%d bus=%d dev=%d) rc=%d", fd, busnum, devaddr, er);
  if (er != UVC_SUCCESS || !devh) { core.MarkError("uvc_get_device_with_fd failed"); uvc.exit(uctx); return; }

  // ★ arming：native ioctl（skip-VS + live-counter）—— 必须在 libuvc open/claim 之后（裸 ioctl 控制需接口已认领，
  //   open 前发恒 fail，实测 ok=0/fail=2016）。arm 进 mode25（videoMode 0x24 + IR + a0 激活 + flash/counter），
  //   随后 libuvc_lusb100 做标准 PROBE/COMMIT/SET_INTERFACE + saki 开源 libusb bulk 收割。
  ArmSkipVs(fd);

  // ★ dual-handle(2026-06-11):arming 已由 Java(USBDEVFS 同步 ioctl,Eys3dCameraService.armViaJava)在同 fd 上做完。
  //   不在此走 libusb arming —— single-handle libusb arming 实测致 IF2 bulk 恒 status=2 零字节(libusb 异步 URB 对
  //   counter/flash 序列与设备不合)。native 这边只负责 libuvc 标准 PROBE+COMMIT+SET_INTERFACE 激活 + bulk 收割。
  PLOG("eys3d arming 由 Java 完成(dual-handle),native 只做 commit+bulk");

  // ★★ IF1 彩色保活(2026-06-11 续33,铁律修复):设备 stereo→depth 流水线**只在 libuvc 持续排空 IF1(彩色)时才出 IF2 深度**
  //   (续16/17/21/23 反复实证)。此前"只开 IF2"是错前提——基于只垫 libusb100 的抓包,漏掉 VINCreator 走 libusb1001 的 IF1 彩色流。
  //   mode25 IF1 彩色 = MJPEG 1280×256。libuvc_lusb100 能解析 MJPEG(续26 证 rc=0)。这里先起 IF1 彩色(丢帧纯保活),再起 IF2 深度。
  static ColorCtx cctx;
  cctx.frames = 0; cctx.bytes = 0;
  uvc_stream_ctrl_t cctrl;
  std::memset(&cctrl, 0, sizeof(cctrl));
  bool color_ok = false;
  for (int fps : {5, 10, 30}) {
    uvc_stream_ctrl_t t;
    std::memset(&t, 0, sizeof(t));
    uvc_error_t rc = uvc.get_stream_ctrl_format_size(devh, &t, UVC_FRAME_FORMAT_MJPEG, 1280, 256, fps);
    PLOG("color try MJPG1280x256@%d rc=%d bFmt=%u bFrame=%u maxFrame=%u maxPay=%u", fps, rc, t.bFormatIndex,
         t.bFrameIndex, t.dwMaxVideoFrameSize, t.dwMaxPayloadTransferSize);
    if (rc == UVC_SUCCESS) { cctrl = t; color_ok = true; break; }
  }
  if (color_ok) {
    uvc_error_t rc = uvc.start_streaming(devh, &cctrl, ColorKeepaliveCb, &cctx, 0);
    PLOG("uvc_start_streaming(IF1 color MJPEG keepalive) rc=%d", rc);
    color_ok = (rc == UVC_SUCCESS);
  }
  PLOG("IF1 彩色保活 color_ok=%d (设备深度流水线依赖此持续排空)", (int)color_ok);

  // 协商 IF2 深度 = YUYV 640×128(设备里仅 IF2 有此帧,fmt1/frame4,GUID=YUY2)。fps 优先 5(mode25)。
  static DepthCtx dctx;
  dctx.core = &core; dctx.frames = 0; dctx.bytes = 0;
  uvc_stream_ctrl_t ctrl;
  std::memset(&ctrl, 0, sizeof(ctrl));
  bool depth_ok = false;
  struct Cand { int w, h, fps; const char* name; };
  static const Cand cands[] = {
      {640, 128, 5, "YUYV640x128@5"}, {640, 128, 10, "YUYV640x128@10"}, {640, 128, 30, "YUYV640x128@30"}};
  for (const auto& cd : cands) {
    uvc_stream_ctrl_t t;
    std::memset(&t, 0, sizeof(t));
    uvc_error_t rc = uvc.get_stream_ctrl_format_size(devh, &t, UVC_FRAME_FORMAT_YUYV, cd.w, cd.h, cd.fps);
    PLOG("depth try %s rc=%d bFmt=%u bFrame=%u maxFrame=%u maxPay=%u", cd.name, rc, t.bFormatIndex,
         t.bFrameIndex, t.dwMaxVideoFrameSize, t.dwMaxPayloadTransferSize);
    if (rc == UVC_SUCCESS) { ctrl = t; depth_ok = true; break; }
  }
  if (depth_ok) {
    // flags=0:Java 已 arming(videoMode/sensor),libuvc 做标准 PROBE+COMMIT+SET_INTERFACE(IF2 alt0 激活 bulk)+收割。
    //   = 老 dual-handle 出 3KB 的同款路径(待验证是否连续/残留)。
    uvc_error_t rc = uvc.start_streaming(devh, &ctrl, DepthUvcCb, &dctx, 0);
    PLOG("uvc_start_streaming(IF2 depth, commit+skip-set-interface) rc=%d", rc);
    depth_ok = (rc == UVC_SUCCESS);
  }

  PLOG("pupil(lusb100) mode25 depth streaming depth_ok=%d", (int)depth_ok);
  if (depth_ok) core.MarkStreaming();
  else { core.MarkError("IF2 深度协商/起流失败"); uvc.close(devh); uvc.exit(uctx); return; }

  // 监控循环(libuvc handler 线程驱动收割)。
  int tick = 0;
  while (!core.stop_requested()) {
    usleep(200000);
    if (++tick % 5 == 0)
      PLOG("tick color=%lld/%lldB depth=%lld/%lldB", (long long)cctx.frames.load(), (long long)cctx.bytes.load(),
           (long long)dctx.frames.load(), (long long)dctx.bytes.load());
  }

  uvc.stop_streaming(devh);
  uvc.close(devh);
  uvc.exit(uctx);
}

}  // namespace gomob::eys3d::android
