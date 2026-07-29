// eYs3D / Etron RS-D550（ROSIE4，0x3438:0x0206）mode25 真深度取流 —— ★ 全自研独立路径，零原厂库 ★。
//
// 后端 = libuvc_gomob.so（pupil libuvc 源 + eYs3D 定制 arming/bulk 恢复，链【stock libusb-1.0】v1.0.27，
//   构建脚本 third_party/libuvc-android/build_gomob_stock.sh）。fd 传递走 libusb_wrap_sys_device（同 Berxel 现代栈），
//   彻底替代原厂 libUVCCamera.so / libusb100.so（saki4510t fork）。
//
// 配方（finding 续47 + 2026-06-15 零原厂攻关）：把控制与取流彻底分离 ——
//   ① 控制全走【裸 USBDEVFS_CONTROL ioctl】，按 VINCreator 抓包【原序】回放整条 blob：
//      arm(flash/counter/sensor) + setVideoMode(36) + VS PROBE/COMMIT + a0 stream-activation + IR(e0/e2)。
//      关键：a0 激活在抓包里位于 VS COMMIT【之后】；旧路径"跳 VS 交 libuvc commit"会把激活错排到 commit 前 → bulk 0。
//   ② libuvc 只做 bulk 收割（flags=0x06 = external-commit + skip-set-interface），不再 PROBE/COMMIT/SET_INTERFACE。
//   铁律仍在：IF1 彩色须先持续排空（喂活 stereo→depth ASIC），暖机数秒后再收割 IF2 深度（u16 视差→metric mm）。
//
// 替代 eys3d_pupil_session.cpp（原厂 libusb100 后端 + Java arming）。控制字节见 eys3d_protocol.h（反汇编锁定）+
//   .dev/vinshim/rsd550_clean_seq.txt（干净抓包，blob 由 gen_clean_arming 生成）。

#include "eys3d/android/eys3d_libuvc.h"  // uvc_frame / uvc_stream_ctrl / uvc_frame_format / uvc_frame_callback_t 类型复用

#include <libusb-1.0/libusb.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>

#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <cstring>

#include "eys3d/portable/eys3d_session_core.h"
#include "eys3d_clean_arming_blob.h"  // EYS3D_CLEAN_ARMING_BLOB：VINCreator 全 arming 抓包（2049 op，含 counter/flash）

namespace gomob::eys3d::android {

namespace {
#define MLOG(...) __android_log_print(ANDROID_LOG_INFO, "eys3d_mode25", __VA_ARGS__)

// libuvc_gomob.so 函数表（stock libusb-1.0 后端；uvc_init(NULL) 内部 NO_DEVICE_DISCOVERY + libusb_init，
//   uvc_get_device_with_fd 内部 libusb_wrap_sys_device，见 third_party/libuvc-android/src 改动）。
struct UvcApi {
  int (*init)(uvc_context**, libusb_context*) = nullptr;
  int (*get_device_with_fd)(uvc_context*, uvc_device_handle**, int, int, const char*, int, int, int) = nullptr;
  libusb_device_handle* (*get_libusb_handle)(uvc_device_handle*) = nullptr;
  int (*get_stream_ctrl_format_size)(uvc_device_handle*, uvc_stream_ctrl*, enum uvc_frame_format, int, int, int) = nullptr;
  int (*start_streaming)(uvc_device_handle*, uvc_stream_ctrl*, uvc_frame_callback_t*, void*, uint8_t) = nullptr;
  void (*stop_streaming)(uvc_device_handle*) = nullptr;
  void (*close)(uvc_device_handle*) = nullptr;
  void (*exit)(uvc_context*) = nullptr;
  void* lib = nullptr;
};

bool LoadUvc(UvcApi* a) {
  // RTLD_LOCAL：libuvc_gomob 的 NEEDED libusb-1.0.so 与 gomob_native 同一份（SONAME 一致，进程内唯一实例），
  //   不存在原厂 libusb100 时代的"被全局 libusb-1.0 遮蔽"问题，故无需隔离技巧。
  a->lib = dlopen("libuvc_gomob.so", RTLD_NOW | RTLD_LOCAL);
  if (!a->lib) { MLOG("dlopen libuvc_gomob.so 失败: %s", dlerror()); return false; }
#define USYM(f, n) a->f = reinterpret_cast<decltype(a->f)>(dlsym(a->lib, n))
  USYM(init, "uvc_init");
  USYM(get_device_with_fd, "uvc_get_device_with_fd");
  USYM(get_libusb_handle, "uvc_get_libusb_handle");
  USYM(get_stream_ctrl_format_size, "uvc_get_stream_ctrl_format_size");
  USYM(start_streaming, "uvc_start_streaming");
  USYM(stop_streaming, "uvc_stop_streaming");
  USYM(close, "uvc_close");
  USYM(exit, "uvc_exit");
#undef USYM
  const bool ok = a->init && a->get_device_with_fd && a->get_libusb_handle &&
                  a->get_stream_ctrl_format_size && a->start_streaming && a->stop_streaming &&
                  a->close && a->exit;
  MLOG("libuvc_gomob dlopen ok=%d", ok);
  return ok;
}

int64_t NowNs() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

// ★ 全量握手回放：按 VINCreator 抓包【原序】逐条 ioctl 回放整条 entity-4 + VS 控制 blob ——
//   arm(flash/counter/sensor) + setVideoMode(36) + VS PROBE/COMMIT + a0 stream-activation + IR(e0/e2)。
//   走【裸 USBDEVFS_CONTROL ioctl】(= 旧 armViaJava 机制)：single-handle libusb arming 会毒化 IF2 bulk(恒 status=2
//   零字节，见 eys3d_pupil_session.cpp)；同步 ioctl 与 libusb 异步 URB 分离，不毒化。IF0 已由 libuvc open 时 claim，
//   ioctl 对 ep0 的 class 控制传输可正常走。
// ★不跳 VS★：a0 激活在抓包里位于 VS COMMIT【之后】；旧路径"跳 VS 交 libuvc commit"把激活错排到 commit 前 → bulk 0
//   (2026-06-15 根因)。selector 0x0a/0x0b 写首字节用设备 live-counter(GET 0x0a00 读出)覆盖，抓包值是旧 session 会被拒。
int ReplayFullSequence(int fd) {
  const unsigned char* b = EYS3D_CLEAN_ARMING_BLOB;
  const int total = static_cast<int>(sizeof(EYS3D_CLEAN_ARMING_BLOB));
  int p = 0, ok = 0, fail = 0, vs = 0, ctrOverride = 0;
  unsigned char inbuf[512];
  unsigned char outbuf[512];
  uint8_t liveCtr = 0;        // 设备当前计数器（GET 0x0a00 读出）
  bool haveCtr = false;
  while (p + 9 <= total) {
    uint8_t bmReq = b[p], bReq = b[p + 1];
    uint16_t wVal = static_cast<uint16_t>(b[p + 2] | (b[p + 3] << 8));
    uint16_t wIdx = static_cast<uint16_t>(b[p + 4] | (b[p + 5] << 8));
    uint16_t wLen = static_cast<uint16_t>(b[p + 6] | (b[p + 7] << 8));
    uint8_t payLen = b[p + 8];
    const unsigned char* pay = b + p + 9;
    p += 9 + payLen;
    if (wLen > sizeof(inbuf)) { fail++; continue; }
    const bool isIn = (bmReq & 0x80) != 0;
    if (wIdx == 0x0001 || wIdx == 0x0002) vs++;  // VS（统计；不再跳过）
    usbdevfs_ctrltransfer ct{};
    ct.bRequestType = bmReq; ct.bRequest = bReq; ct.wValue = wVal; ct.wIndex = wIdx;
    ct.wLength = wLen; ct.timeout = 1000;
    if (isIn) {
      ct.data = inbuf;
    } else {
      // ★ live-counter：0x0a/0x0b 写的首字节必须是设备当前计数器（抓包值是旧 session 的，会被拒）。
      //   其余 payload（含 flash 标定数据、VS PROBE/COMMIT 26B）逐设备确定，原样回放。
      if ((wVal == 0x0a00 || wVal == 0x0b00) && payLen > 0 && haveCtr) {
        std::memcpy(outbuf, pay, payLen);
        outbuf[0] = liveCtr;
        ct.data = outbuf;
        ctrOverride++;
      } else {
        ct.data = const_cast<unsigned char*>(pay);
      }
    }
    int rc = ioctl(fd, USBDEVFS_CONTROL, &ct);
    if (rc < 0) { fail++; continue; }
    ok++;
    // GET 0x0a00 读到设备当前计数器 → 更新 liveCtr。
    if (isIn && wVal == 0x0a00 && rc >= 1) { liveCtr = inbuf[0]; haveCtr = true; }
  }
  MLOG("ReplayFullSequence: ok=%d fail=%d vs=%d ctrOverride=%d (共 %d op)", ok, fail, vs,
       ctrOverride, EYS3D_CLEAN_ARMING_COUNT);
  return ok;
}

struct ColorCtx {
  std::atomic<int64_t> frames{0};
  std::atomic<int64_t> bytes{0};
};
void ColorKeepaliveCb(uvc_frame* f, void* user) {
  auto* c = static_cast<ColorCtx*>(user);
  if (!f || !f->data_bytes) return;  // 丢帧纯保活
  const int64_t n = ++c->frames;
  c->bytes += static_cast<int64_t>(f->data_bytes);
  if (n <= 3 || n % 30 == 0) MLOG("color(IF1 保活) #%lld %zuB", (long long)n, f->data_bytes);
}

struct DepthCtx {
  Eys3dSessionCore* core = nullptr;
  std::atomic<int64_t> frames{0};
  std::atomic<int64_t> bytes{0};
};
void DepthCb(uvc_frame* f, void* user) {
  auto* d = static_cast<DepthCtx*>(user);
  if (!f || !f->data || !f->data_bytes) return;
  const int64_t n = ++d->frames;
  d->bytes += static_cast<int64_t>(f->data_bytes);
  if (d->core) d->core->OnRawDepthFrame(static_cast<const uint8_t*>(f->data), f->data_bytes, NowNs());
  if (n <= 3 || n % 30 == 0) MLOG("depth(IF2) #%lld %zuB", (long long)n, f->data_bytes);
}

// ★ 起流 flags（libuvc stream.c：0x02=external-commit跳COMMIT，0x04=skip-set-interface跳SET_INTERFACE）：
//   - 彩色 kColorFlags=0x00：libuvc COMMIT + 【SET_INTERFACE(1,0)】+ submit。SET_INTERFACE 是 mode25 组合流的触发器
//     （usbmon 实证 `01 0b 0000 0001`），缺它 → 深度 ep82 恒 -EPROTO(status=2,0 字节，2026-06-16 实测)。
//   - 深度 kDepthFlags=0x04：libuvc COMMIT + submit，不发 SET_INTERFACE（触发由彩色那路统一做，组合流已起）。
//   两路都让 libuvc COMMIT 紧贴 submit（ms 级）：纯 ioctl COMMIT 距 submit 数秒 → 设备看门狗反激活端点 → URB 永挂。
constexpr uint8_t kColorFlags = 0x00;
constexpr uint8_t kDepthFlags = 0x04;

// 手填 uvc_stream_ctrl（抓包 PROBE 协商值）。fmt/frm 索引须存在于设备描述符(libuvc open 时已解析)；
//   stream_start 仅读 bInterfaceNumber(定位 VS 接口) / bFormatIndex+bFrameIndex(定位帧描述符→端点+GUID) /
//   dwMaxVideoFrameSize(组帧 buf) / dwMaxPayloadTransferSize。
void FillCtrl(uvc_stream_ctrl* c, uint8_t ifnum, uint8_t fmt, uint8_t frm, uint32_t maxVfs,
              uint32_t maxPay) {
  std::memset(c, 0, sizeof(*c));
  c->bmHint = 0x0001;
  c->bFormatIndex = fmt;
  c->bFrameIndex = frm;
  c->bInterfaceNumber = ifnum;
  c->dwMaxVideoFrameSize = maxVfs;
  c->dwMaxPayloadTransferSize = maxPay;
}

// ───────── host-proven 起流尾段（eys3d_stream_loop.cpp:298-317，RHEL9 60s/1.39GB 实证）─────────
// 两路 bulk submit 后必须补这段，否则设备从未"扣扳机"→双端点恒 -EPROTO(status=2 0 字节)；缺 0x83 心跳则
//   只吐 ~1s 缓冲即断流。全走【裸 ioctl】(同 fd，不毒化 bulk)；0x83 心跳挂 libuvc 同 context 的 libusb handle。
[[maybe_unused]] int IoctlCtrl(int fd, uint8_t bmReq, uint8_t bReq, uint16_t wVal, uint16_t wIdx,
              unsigned char* data, uint16_t wLen) {
  usbdevfs_ctrltransfer ct{};
  ct.bRequestType = bmReq; ct.bRequest = bReq; ct.wValue = wVal; ct.wIndex = wIdx;
  ct.wLength = wLen; ct.timeout = 2000; ct.data = data;
  return ioctl(fd, USBDEVFS_CONTROL, &ct);
}

// 读设备当前 live-counter（GET 0a00）。tail 的 0a00 写须用当前值（抓包/host 硬编码值是别的 session 的）。
[[maybe_unused]] uint8_t ReadLiveCtr(int fd) {
  unsigned char c = 0;
  IoctlCtrl(fd, 0xa1, 0x81, 0x0a00, 0x0400, &c, 1);
  return c;
}

// 0x83 中断 EP 心跳：~3ms 主动周期 IN，维持流（数据全丢，只为让 HC 周期轮询 0x83）。
struct IntrHeartbeat {
  libusb_transfer* urbs[2] = {nullptr, nullptr};
  unsigned char bufs[2][1024];
  std::atomic<bool> stop{false};
  std::atomic<int64_t> ticks{0};
};
void LIBUSB_CALL HeartbeatCb(libusb_transfer* t) {
  auto* hb = static_cast<IntrHeartbeat*>(t->user_data);
  hb->ticks++;                                   // 完成/超时都计一拍并重提（复刻 SDK ~3ms 节拍）
  if (!hb->stop.load()) libusb_submit_transfer(t);
}
void StartHeartbeat(libusb_device_handle* h, IntrHeartbeat* hb) {
  hb->stop = false; hb->ticks = 0;
  for (int i = 0; i < 2; ++i) {
    hb->urbs[i] = libusb_alloc_transfer(0);
    libusb_fill_interrupt_transfer(hb->urbs[i], h, 0x83, hb->bufs[i], 1024, HeartbeatCb, hb, 3);
    int rc = libusb_submit_transfer(hb->urbs[i]);
    MLOG("0x83 心跳 URB#%d submit rc=%d", i, rc);
  }
}

// ★ mode25 起流尾段 = 仅 0x83 中断心跳。订正(2026-06-16 usbmon sdk_stream_trace_mode25.txt 实证)：
//   mode25 触发器 = a0 激活(a0 00/01/03，已在 ReplayFullSequence 内) + SET_INTERFACE(1,0)(彩色 flags=0x00 发)，
//   【不是 F5 trigger】(F5 + counter bump + 启动 clear_halt 是 14bit host loop 的机制，对 mode25 是噪声/有害，已去)。
//   0x83 心跳维持流(缺它 ~1s 断)。心跳 URB 走 gomob_native 直链 libusb，挂 libuvc 同 handle，由其 handler 线程泵。
void IssueStreamTail(int fd, libusb_device_handle* h, IntrHeartbeat* hb) {
  (void)fd;
  if (h) StartHeartbeat(h, hb);
  else MLOG("⚠ 无 libusb handle，0x83 心跳未起（流会 ~1s 后断）");
}

}  // namespace

// 入口（由 Eys3dFdSession::Run 调）。fd = Java UsbDeviceConnection.getFileDescriptor()，本会话不持其所有权。
void RunEys3dMode25LibuvcSession(int fd, Eys3dSessionCore& core) {
  static UvcApi uvc;
  if (!uvc.lib && !LoadUvc(&uvc)) { core.MarkError("load libuvc_gomob failed"); return; }

  uvc_context* uctx = nullptr;
  if (uvc.init(&uctx, nullptr) != 0 || !uctx) { core.MarkError("uvc_init failed"); return; }

  uvc_device_handle* devh = nullptr;
  int er = uvc.get_device_with_fd(uctx, &devh, 0x3438, 0x0206, nullptr, fd, 0, 0);
  MLOG("uvc_get_device_with_fd(fd=%d) rc=%d", fd, er);
  if (er != 0 || !devh) { core.MarkError("uvc_get_device_with_fd failed"); uvc.exit(uctx); return; }

  // 1) 全量握手：按抓包【原序】ioctl 回放整条 blob —— arm + setVideoMode(36) + VS PROBE/COMMIT + a0 激活 + IR(e0/e2)。
  //    完成后设备已进 mode25 双端点激活态（深度 ASIC 上电），libuvc 只负责 bulk 收割。
  ReplayFullSequence(fd);

  // 2) IF1 彩色 bulk 收割（纯保活，排空 IF1 喂活 stereo→depth ASIC）。手填 ctrl(IF1 fmt2/frm2 MJPEG) + flags=0x06。
  static ColorCtx cctx;
  cctx.frames = 0; cctx.bytes = 0;
  uvc_stream_ctrl cc;
  FillCtrl(&cc, /*if*/ 1, /*fmt*/ 2, /*frm*/ 2, /*maxVfs*/ 655360, /*maxPay*/ 512);
  int csr = uvc.start_streaming(devh, &cc, ColorKeepaliveCb, &cctx, kColorFlags);
  MLOG("IF1 彩色 bulk 收割 rc=%d (flags=0x%02x)", csr, kColorFlags);

  // 3) 短延（host 无暖机；仅留 ~200ms 让彩色 URB 进 in-flight，再 commit 深度）。深度 ASIC 暖机在 F5 触发后由设备完成。
  usleep(200 * 1000);

  // 4) IF2 深度 bulk 收割（IF2 fmt1/frm4，640×128 u16 视差）→ core 视差(低 11 位)→metric mm。
  static DepthCtx dctx;
  dctx.core = &core; dctx.frames = 0; dctx.bytes = 0;
  uvc_stream_ctrl dc;
  FillCtrl(&dc, /*if*/ 2, /*fmt*/ 1, /*frm*/ 4, /*maxVfs*/ 163840, /*maxPay*/ 1024);
  int dsr = uvc.start_streaming(devh, &dc, DepthCb, &dctx, kDepthFlags);
  MLOG("IF2 深度 bulk 收割 rc=%d (flags=0x%02x)", dsr, kDepthFlags);
  if (dsr != 0) {
    core.MarkError("IF2 深度 bulk 收割失败");
    uvc.stop_streaming(devh); uvc.close(devh); uvc.exit(uctx);
    return;
  }

  // 5) ★ host-proven 起流尾段：F5 trigger（双端点真正"扣扳机"）+ counter/clear_halt + 0x83 心跳（维持流）。
  //    两路 bulk submit 之后发，缺它则恒 -EPROTO/或只吐 ~1s 即断（eys3d_stream_loop.cpp 实证）。
  static IntrHeartbeat hb;
  libusb_device_handle* h = uvc.get_libusb_handle ? uvc.get_libusb_handle(devh) : nullptr;
  IssueStreamTail(fd, h, &hb);

  core.MarkStreaming();
  MLOG("mode25 自研独立起流完成（libuvc_gomob + stock libusb-1.0，零原厂；ioctl 握手+尾段 + libuvc bulk 收割 + 0x83 心跳）");

  int tick = 0;
  while (!core.stop_requested()) {
    usleep(200000);
    if (++tick % 5 == 0)
      MLOG("tick color=%lld/%lldB depth=%lld/%lldB intr=%lld", (long long)cctx.frames.load(),
           (long long)cctx.bytes.load(), (long long)dctx.frames.load(), (long long)dctx.bytes.load(),
           (long long)hb.ticks.load());
  }

  hb.stop = true;  // 停心跳（URB 由 libuvc context 收尾时取消）
  uvc.stop_streaming(devh);
  uvc.close(devh);
  uvc.exit(uctx);
}

}  // namespace gomob::eys3d::android
