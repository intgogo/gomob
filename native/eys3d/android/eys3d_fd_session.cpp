#include "eys3d/android/eys3d_fd_session.h"

#include <libusb-1.0/libusb.h>

#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "eys3d/host/eys3d_usb_api.h"
#include "eys3d/android/eys3d_libuvc.h"
#include "eys3d/android/eys3d_vendor_cpp_session.h"

namespace gomob::eys3d::android {

using gomob::camera::CameraFrame;
using gomob::camera::DepthControls;
using gomob::camera::SessionCallbacks;
using gomob::camera::SessionConfig;

namespace {
// ★ eYs3D 取流后端走 saki4510t fork libusb100（VINCreator/eSPDI 同款，能连续出流），而非现代 libusb wrap_sys_device
//   （后者真机只吐 ~2 帧即停）。dlopen(RTLD_LOCAL) 隔离，dlsym 填 Eys3dUsb() 函数表；Berxel 仍用现代 libusb-1.0 不动。
int64_t NowNs2() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

// libuvc color 回调(测 MJPEG 能否经 libuvc 连续出流)。MJPEG/YUYV 字节透传给 core,Kotlin 端解码。
struct LibuvcCtx {
  gomob::eys3d::Eys3dSessionCore* core = nullptr;
  std::atomic<int64_t> frames{0};
  std::atomic<int64_t> bytes{0};
};
void LIBUVC_ColorCb(struct uvc_frame* frame, void* user) {
  auto* c = static_cast<LibuvcCtx*>(user);
  if (!frame || !frame->data || frame->data_bytes == 0) return;
  c->core->OnColorFrame(static_cast<const uint8_t*>(frame->data), frame->data_bytes, NowNs2());
  const int64_t n = ++c->frames;
  c->bytes += static_cast<int64_t>(frame->data_bytes);
  if (n <= 3 || n % 30 == 0)
    __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "libuvc color #%lld %zuB", (long long)n,
                        frame->data_bytes);
}


// depth 原始帧诊断:14bit 模式下深度疑似"列恒定垃圾"。抽 column=640 跨 480 行,统计相异值占比 +
// 全帧 min/max,据此在 logcat 判定 depth 内容【真 metric vs 垃圾】(决定是否需切 videoMode 取真深度)。
void DiagRawDepth(const uint8_t* raw, size_t bytes, int64_t n) {
  if (n > 3) return;
  const size_t count = bytes / 2;
  const auto* u16 = reinterpret_cast<const uint16_t*>(raw);
  uint16_t mn = 0xFFFF, mx = 0;
  for (size_t i = 0; i < count; ++i) {
    const uint16_t v = u16[i];
    if (v < mn) mn = v;
    if (v > mx) mx = v;
  }
  // 尺寸无关的"内容变化度"检测:统计整帧不同 u16 值个数(真深度→大量不同值;垃圾/恒定→极少)。
  std::vector<uint16_t> sample;
  const size_t step = count > 4096 ? count / 4096 : 1;
  for (size_t i = 0; i < count; i += step) sample.push_back(u16[i]);
  std::sort(sample.begin(), sample.end());
  int distinct = sample.empty() ? 0 : 1;
  for (size_t i = 1; i < sample.size(); ++i) if (sample[i] != sample[i - 1]) ++distinct;
  __android_log_print(ANDROID_LOG_INFO, "eys3d_stream",
                      "depth raw#%lld %zuB(count=%zu) u16min=%u max=%u distinct=%d/%zu %s",
                      (long long)n, bytes, count, mn, mx, distinct, sample.size(),
                      distinct < 16 ? "[内容恒定?垃圾]" : "[内容丰富?真深度]");
}

void* g_libusb100 = nullptr;
[[maybe_unused]] bool LoadLibusb100() {
  if (g_libusb100) return true;
  g_libusb100 = dlopen("libusb100.so", RTLD_NOW | RTLD_LOCAL);
  if (!g_libusb100) {
    __android_log_print(ANDROID_LOG_ERROR, "eys3d_stream", "dlopen libusb100.so failed: %s", dlerror());
    return false;
  }
  auto& u = gomob::eys3d::Eys3dUsb();
#define EYS_SYM(field, name) u.field = reinterpret_cast<decltype(u.field)>(dlsym(g_libusb100, name))
  EYS_SYM(init, "libusb_init");
  EYS_SYM(init2, "libusb_init2");
  EYS_SYM(exit, "libusb_exit");
  EYS_SYM(get_device_with_fd, "libusb_get_device_with_fd");
  EYS_SYM(open, "libusb_open");
  EYS_SYM(close, "libusb_close");
  EYS_SYM(claim_interface, "libusb_claim_interface");
  EYS_SYM(release_interface, "libusb_release_interface");
  EYS_SYM(control_transfer, "libusb_control_transfer");
  EYS_SYM(alloc_transfer, "libusb_alloc_transfer");
  EYS_SYM(submit_transfer, "libusb_submit_transfer");
  EYS_SYM(cancel_transfer, "libusb_cancel_transfer");
  EYS_SYM(free_transfer, "libusb_free_transfer");
  EYS_SYM(handle_events_timeout, "libusb_handle_events_timeout");
  EYS_SYM(handle_events, "libusb_handle_events");                      // ★ 阻塞收割(libuvc 同款)=破手动 bulk 停
  EYS_SYM(handle_events_completed, "libusb_handle_events_completed");
  EYS_SYM(error_name, "libusb_error_name");
  EYS_SYM(clear_halt, "libusb_clear_halt");
  EYS_SYM(reset_device, "libusb_reset_device");
  EYS_SYM(set_auto_detach_kernel_driver, "libusb_set_auto_detach_kernel_driver");
  EYS_SYM(kernel_driver_active, "libusb_kernel_driver_active");
  EYS_SYM(detach_kernel_driver, "libusb_detach_kernel_driver");
  EYS_SYM(bulk_transfer, "libusb_bulk_transfer");
#undef EYS_SYM
  const bool ok = u.init && u.exit && u.get_device_with_fd && u.open && u.close &&
                  u.control_transfer && u.alloc_transfer && u.submit_transfer &&
                  u.cancel_transfer && u.free_transfer && u.handle_events_timeout;
  __android_log_print(ANDROID_LOG_INFO, "eys3d_stream",
                      "libusb100 loaded, table ok=%d (get_device_with_fd=%p)", ok,
                      (void*)u.get_device_with_fd);
  return ok;
}

// 从 usbfs fd 反查 busnum/devaddr（get_device_with_fd 需要）：/proc/self/fd/<fd> → /dev/bus/usb/BBB/DDD。
void ResolveBusDev(int fd, int* busnum, int* devaddr) {
  char path[64];
  char link[256];
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

// ★ 通用手动异步 bulk 泵(color MJPEG ep0x81 + depth ep0x82)。libuvc 解析不了本机 MJPEG、也不注册 IF2，
//   故两路都自己在端点上跑【异步 bulk URB】：回调剥 12B UVC 头、按 EOF/FID 组帧 → 喂 core；回调内自重投，
//   由 libuvc 事件线程(uvc_start_handler_thread)驱动。URB 尺寸 = 协商出的 dwMaxPayloadTransferSize(每 URB 一个 payload)。
constexpr int kInflight = 100;  // ★ 对齐 libuvc LIBUVC_NUM_TRANSFER_BUFS=100:慢板子调度延迟下少漏传输,
                                //   是"手动排空不停=保活"的疑似关键(32 太少→FIFO 溢出→2 帧停,见 finding 续22)。

struct BulkPump {
  gomob::eys3d::Eys3dSessionCore* core = nullptr;
  libusb_device_handle* h = nullptr;
  uint8_t ep = 0;
  bool is_color = false;       // true:MJPEG→OnColorFrame(仅按 EOF 组帧); false:depth→OnRawDepthFrame
  size_t frame_min = 0;        // depth:最小整帧字节(滤半帧); color:1(任意非空)
  size_t frame_cap = 0;
  int urb_size = 0;
  std::atomic<bool> stop{false};
  std::atomic<int> inflight{0};
  std::atomic<int64_t> frames{0};
  std::atomic<int64_t> bytes{0};
  std::vector<uint8_t> frame;  // 仅事件线程单线程访问，无需锁
  bool have_fid = false;
  bool cur_fid = false;
  libusb_transfer* xfers[kInflight] = {nullptr};
  std::vector<uint8_t> bufs[kInflight];

  void Finalize() {
    if (!frame.empty() && frame.size() >= frame_min) {
      const int64_t n = ++frames;
      bytes += static_cast<int64_t>(frame.size());
      if (is_color) {
        core->OnColorFrame(frame.data(), frame.size(), NowNs2());
      } else {
        DiagRawDepth(frame.data(), frame.size(), n);
        core->OnRawDepthFrame(frame.data(), frame.size(), NowNs2());
      }
      if (n <= 3 || n % 30 == 0)
        __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "%s bulk frame #%lld %zuB",
                            is_color ? "color" : "depth", (long long)n, frame.size());
    }
    frame.clear();
  }
};

std::atomic<int64_t> g_cb_calls{0};
void BulkXferCb(libusb_transfer* t) {
  auto* d = static_cast<BulkPump*>(t->user_data);
  auto& U = gomob::eys3d::Eys3dUsb();
  const int64_t nc = ++g_cb_calls;
  if (nc <= 6)
    __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "URB cb#%lld ep=0x%02x status=%d len=%d",
                        (long long)nc, d->ep, (int)t->status, t->actual_length);
  if (d->stop.load()) { --d->inflight; return; }  // 关流:不再重投
  if (t->status == LIBUSB_TRANSFER_COMPLETED && t->actual_length >= 2) {
    const uint8_t* b = t->buffer;
    const uint8_t hlen = b[0];
    const uint8_t info = b[1];
    if (hlen >= 2 && hlen <= t->actual_length && !(info & 0x40)) {  // 头合法、无 ERR
      const bool fid = info & 0x01;
      const bool eof = info & 0x02;
      if (d->have_fid && fid != d->cur_fid && !d->frame.empty()) d->Finalize();  // FID 翻转=隐式帧界
      d->cur_fid = fid;
      d->have_fid = true;
      const int dlen = t->actual_length - hlen;
      if (dlen > 0 && d->frame.size() + dlen <= d->frame_cap)
        d->frame.insert(d->frame.end(), b + hlen, b + t->actual_length);
      if (eof) d->Finalize();
    }
  } else if (t->status == LIBUSB_TRANSFER_STALL) {
    if (U.clear_halt) U.clear_halt(d->h, d->ep);
  } else if (t->status == LIBUSB_TRANSFER_CANCELLED) {
    --d->inflight; return;
  }
  if (U.submit_transfer(t) != 0) { --d->inflight; }
}

// VS PROBE/COMMIT(UVC 1.0 26B)。返回设备协商出的 dwMaxPayloadTransferSize(URB 尺寸)，<=0 失败。
int CommitStream(libusb_device_handle* h, int iface, uint8_t fmt_idx, uint8_t frame_idx,
                 uint32_t max_frame, uint32_t payload_hint, const char* tag) {
  auto& U = gomob::eys3d::Eys3dUsb();
  unsigned char p[26] = {0};
  p[0] = 0x01;                 // bmHint
  p[2] = fmt_idx; p[3] = frame_idx;
  p[4] = 0x80; p[5] = 0x84; p[6] = 0x1e;  // dwFrameInterval=2000000(5fps)
  p[18] = max_frame & 0xff; p[19] = (max_frame >> 8) & 0xff;
  p[20] = (max_frame >> 16) & 0xff; p[21] = (max_frame >> 24) & 0xff;
  p[22] = payload_hint & 0xff; p[23] = (payload_hint >> 8) & 0xff;
  p[24] = (payload_hint >> 16) & 0xff; p[25] = (payload_hint >> 24) & 0xff;
  unsigned char back[26] = {0};
  // 超时收紧到 500ms:失败时别让 set/get/commit 串起来吃光 Kotlin 5s watchdog 预算。
  int r1 = U.control_transfer(h, 0x21, 0x01, 0x0100, iface, p, 26, 500);          // SET PROBE
  int r2 = (r1 == 26) ? U.control_transfer(h, 0xA1, 0x81, 0x0100, iface, back, 26, 500) : -1;  // GET PROBE(SET 成才取)
  uint32_t neg_pay = back[22] | (back[23] << 8) | (back[24] << 16) | ((uint32_t)back[25] << 24);
  uint32_t neg_frame = back[18] | (back[19] << 8) | (back[20] << 16) | ((uint32_t)back[21] << 24);
  // COMMIT 回灌设备协商结果(GET 成功时)，否则用请求值。
  unsigned char* commit_buf = (r2 == 26) ? back : p;
  int r3 = U.control_transfer(h, 0x21, 0x01, 0x0200, iface, commit_buf, 26, 500);  // SET COMMIT
  __android_log_print(ANDROID_LOG_INFO, "eys3d_stream",
                      "%s PROBE/COMMIT set=%d get=%d commit=%d negFrame=%u negPayload=%u", tag, r1, r2,
                      r3, neg_frame, neg_pay);
  if (neg_pay == 0) neg_pay = payload_hint;
  return static_cast<int>(neg_pay);
}

// 在指定端点起异步 bulk。COMMIT 在外面做完，这里只挂 URB。
bool StartBulk(BulkPump* d) {
  auto& U = gomob::eys3d::Eys3dUsb();
  d->frame.reserve(d->frame_cap);
  // 在飞 URB 内存预算 ~8MB：payload 大时减少并发数，防 16×大 URB OOM。
  int n = kInflight;
  if (d->urb_size > 0) {
    int by_budget = (8 << 20) / d->urb_size;
    if (by_budget < n) n = by_budget;
  }
  if (n < 2) n = 2;
  if (n > kInflight) n = kInflight;
  int submitted = 0;
  for (int i = 0; i < n; ++i) {
    d->bufs[i].assign(d->urb_size, 0);
    d->xfers[i] = U.alloc_transfer(0);
    if (!d->xfers[i]) continue;
    libusb_fill_bulk_transfer(d->xfers[i], d->h, d->ep, d->bufs[i].data(), d->urb_size, BulkXferCb,
                              d, 5000 /*同 libuvc:5s 超时即重投*/);
    if (U.submit_transfer(d->xfers[i]) == 0) { ++submitted; ++d->inflight; }
  }
  __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "%s bulk 起流 ep=0x%02x urb=%d n=%d submitted=%d",
                      d->is_color ? "color" : "depth", d->ep, d->urb_size, n, submitted);
  return submitted > 0;
}

void StopBulk(BulkPump* d, libusb_context* ctx) {
  auto& U = gomob::eys3d::Eys3dUsb();
  d->stop.store(true);
  for (auto* t : d->xfers)
    if (t && U.cancel_transfer) U.cancel_transfer(t);
  // ctx!=null:自己 pump 事件收敛取消(单线程模型)。ctx==null:外部(libuvc)事件线程在跑,这里只等。
  for (int i = 0; i < 80 && d->inflight.load() > 0; ++i) {
    if (ctx) { timeval tv{0, 20000}; U.handle_events_timeout(ctx, &tv); }
    else usleep(20000);
  }
  for (auto*& t : d->xfers)
    if (t && U.free_transfer) { U.free_transfer(t); t = nullptr; }
}

// 中断 EP 0x83 心跳：SDK 全程 ~3ms 轮询 0x83 维持流，缺它设备约 1s 即断流。数据丢弃，只为周期 IN。
struct IntrPump {
  std::atomic<bool> stop{false};
  std::atomic<int> inflight{0};
  std::atomic<int64_t> done{0};
  libusb_transfer* xfers[2] = {nullptr};
  std::vector<uint8_t> bufs[2];
};
void IntrCb(libusb_transfer* t) {
  auto* d = static_cast<IntrPump*>(t->user_data);
  ++d->done;
  if (d->stop.load()) { --d->inflight; return; }
  if (gomob::eys3d::Eys3dUsb().submit_transfer(t) != 0) --d->inflight;  // NAK 超时也重投=心跳
}
}  // namespace

Eys3dFdSession::Eys3dFdSession(int fd, const host::Eys3dOpenPlan& plan, const SessionConfig& cfg)
    : fd_(fd), plan_(plan), cfg_(cfg) {
  SessionCoreConfig cc;
  cc.color = cfg.color;
  cc.depth = cfg.depth;
  cc.depth_router = DepthRouterConfig{cfg.depth.width, cfg.depth.height, 0, false};
  cc.want_color = cfg.want_color;
  cc.want_depth = cfg.want_depth;
  core_.Configure(cc);
}

Eys3dFdSession::~Eys3dFdSession() {
  stop();
  join();
}

bool Eys3dFdSession::start(const SessionCallbacks& cb) {
  if (running_.exchange(true)) return false;
  if (cb.on_frame) core_.SetOnFrame(cb.on_frame);
  thread_ = std::thread(&Eys3dFdSession::Run, this);
  return true;
}

// pupil-labs libuvc(解析 MJPEG) + 自有 ctx(阻塞 handler 线程)路径,实现于 eys3d_pupil_session.cpp。
void RunEys3dPupilMjpegSession(int fd, gomob::eys3d::Eys3dSessionCore& core);
// ★ P1b 自研独立路径:libuvc_gomob.so(链 stock libusb-1.0,wrap_sys_device 接管 fd) + mode25 配方,零原厂。
//   实现于 eys3d_mode25_libuvc_session.cpp。
void RunEys3dMode25LibuvcSession(int fd, gomob::eys3d::Eys3dSessionCore& core);

// TODO(R3, deferred-structural): 本 Run() 下属三条取流路径(proven stream-loop / 独立 mode25 libuvc /
//   pupil MJPEG + vendor libuvc YUYV 基线)均为「自研零厂商」在研实验路径,生产不可达 —— 唯一构造点
//   Eys3dFdDriver::open_fd 由 kUseVendorCpp=true 永远走 Eys3dVendorCppSession 分支,本类不会被实例化。
//   实验路径仍带真实 USB 副作用(claim_interface/control_transfer/bulk submit)+依赖 libuvc_lusb100 资产,
//   不应被默认链路触发。终态:用独立 build flag / 独立编译目标把整条实验路径物理隔离出生产二进制
//   (见 docs/architecture/13-eys3d-driver.md「自研独立路径」与 finding_eys3d_zero_vendor_independence)。
//   本轮不做结构隔离,仅在入口标注;实际不可达由 open_fd 的 kUseVendorCpp 门控保证。
void Eys3dFdSession::Run() {
  if (fd_ < 0) { core_.MarkError("invalid fd"); return; }

  // ★★★ P1b 自研独立(stock libusb-1.0,零原厂):走 host-proven RunEys3dStreamLoop(内置 F5 trigger + 0x83 心跳 +
  //   color-submit-before-COMMIT-IF2 正确时序,RHEL9 60s/1.39GB 实证)。gomob_native 直链 stock libusb(Berxel 同款),
  //   Eys3dUsbSetHostApi 填表即 stock 后端,wrap_sys_device 接管 Java fd。先用 ProvenWrongModePlan(kProvenArming,14bit)
  //   验证『stock libusb 在 Android 能否连续 bulk』(隔离 backend 变量); 通了再切 mode25 arming。
  // ⚠ 2026-06-16 实验否定:gomob_native 直链 libusb 被进程内 libusb100real 符号互位(VINSHIM 截 SUBMIT_BULK,
  //   async URB 全不回收 intr[done=0])→ 此路在 Android 不可用,只 libuvc_gomob(dlopen RTLD_LOCAL)绑定干净。置 false。
  constexpr bool kUseProvenStreamLoop = false;
  if (kUseProvenStreamLoop) {
    gomob::eys3d::Eys3dUsbSetHostApi();  // 函数表 = gomob_native 直链 stock libusb-1.0
    auto& U = gomob::eys3d::Eys3dUsb();
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);  // Android fd 路径:禁自动枚举
    libusb_context* ctx = nullptr;
    if (U.init(&ctx) != 0 || !ctx) { core_.MarkError("libusb_init failed"); return; }
    libusb_device_handle* h = nullptr;
    int wr = U.wrap_sys_device(ctx, static_cast<intptr_t>(fd_), &h);
    __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "stock libusb wrap_sys_device(fd=%d) rc=%d h=%p",
                        fd_, wr, (void*)h);
    if (wr != 0 || !h) { core_.MarkError("wrap_sys_device failed"); U.exit(ctx); return; }
    host::Eys3dOpenPlan plan = plan_;
    plan.external_arming = false;  // 实验:在 loop 内跑 proven kProvenArming(stock libusb control + PIPE 重试)
    RunEys3dStreamLoop(ctx, h, plan, cfg_, core_);
    U.close(h);
    U.exit(ctx);
    return;
  }

  // (备用) P1b libuvc_gomob 路径:libuvc + mainline libusb-1.0。已证 bulk 恒 -EPROTO(stock libusb 后端问题)，置 false。
  constexpr bool kUseIndependentMode25 = false;
  if (kUseIndependentMode25) { RunEys3dMode25LibuvcSession(fd_, core_); return; }

  // ★★★ 2026-06-10 根因调查终态:设备取流引擎只在 libuvc uvc_start_streaming + 自有阻塞 handler 线程时才开;
  //   全手动(timeout 或阻塞 handle_events)都不触发(实测 14bit/mode25 全手动 0~2 帧)。mode25 真深度需 IF1=MJPEG
  //   被排空,vendor libuvc 解析不了 MJPEG。→ 走 pupil libuvc(解析 MJPEG)+ uvc_init(NULL) 自有 ctx(阻塞 handler)
  //   流 mode25 MJPEG 彩色 + 手动 depth bulk 搭车。前一轮 pupil 失败是因传外部 ctx(无阻塞 handler 线程)=不公平。
  // ★ 当前默认 = 工作视频基线(vendor libuvc YUYV 排空 IF1 保活 + 手动 depth bulk 搭其阻塞 handler 线程 → 连续出流)。
  //   深度为 14bit 退化(非真 metric)。mode25 真深度路线 = eys3d_pupil_session.cpp(libuvc_lusb100:解析 MJPEG +
  //   libusb100 后端,已建好+dlopen 隔离 ok);当前 descriptor 解析崩溃(uvc_scan_control,疑 libusb100 描述符 struct
  //   ABI 不匹配,见 finding 续25),修好后把 Run 切回 RunEys3dPupilMjpegSession 即得真深度。
  if (!LoadLibusb100()) { core_.MarkError("load libusb100 failed"); return; }

  // ★ mode25 真深度主路:libuvc_lusb100(pupil 解析 MJPEG + libusb100 后端)流 MJPEG 排空 IF1 + 手动 depth bulk。
  //   置 false 回退 vendor YUYV 视频基线(下方,深度为 14bit 退化,排障备用)。
  constexpr bool kUsePupilMode25 = true;
  if (kUsePupilMode25) { RunEys3dPupilMjpegSession(fd_, core_); return; }

  LibuvcApi uvc;
  if (!LoadLibuvc(&uvc)) { core_.MarkError("load libuvc failed"); return; }
  auto& U = gomob::eys3d::Eys3dUsb();
  uvc_context* uctx = nullptr;
  if (uvc.init2(&uctx, nullptr, "/dev/bus/usb") != 0 || !uctx) { core_.MarkError("uvc_init2 failed"); return; }
  int busnum = 0, devaddr = 0;
  ResolveBusDev(fd_, &busnum, &devaddr);
  uvc_device* udev = nullptr;
  if (uvc.get_device_with_fd(uctx, &udev, 0x3438, 0x0206, nullptr, fd_, busnum, devaddr) != 0 || !udev) {
    core_.MarkError("uvc get_device_with_fd failed"); uvc.exit(uctx); return;
  }
  uvc_device_handle* udevh = nullptr;
  if (uvc.open(udev, &udevh) != 0 || !udevh) { core_.MarkError("uvc_open failed"); uvc.exit(uctx); return; }
  libusb_device_handle* h = uvc.get_libusb_handle ? uvc.get_libusb_handle(udevh) : nullptr;
  if (!h) { core_.MarkError("get_libusb_handle failed"); uvc.close(udevh); uvc.exit(uctx); return; }
  if (U.reset_device) {
    int rr = U.reset_device(h);
    __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "USB reset_device rc=%d", rr);
    usleep(200000);
  }

  // COLOR = vendor libuvc 流 YUYV(连续排空 IF1=保活)。
  struct Cand { enum uvc_frame_format fmt; int w, h, fps; const char* name; };
  static const Cand cands[] = {
      {UVC_FRAME_FORMAT_YUYV, 2560, 960, 5, "YUYV2560x960@5"},
      {UVC_FRAME_FORMAT_YUYV, 1280, 480, 5, "YUYV1280x480@5"},
      {UVC_FRAME_FORMAT_YUYV, 1280, 480, 3, "YUYV1280x480@3"}};
  uvc_stream_ctrl ctrl{};
  bool color_ok = false;
  for (const auto& c : cands) {
    uvc_stream_ctrl t{};
    int rc = uvc.get_stream_ctrl_format_size(udevh, &t, c.fmt, c.w, c.h, c.fps);
    __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "color try %s rc=%d frame=%u maxFrame=%u", c.name, rc,
                        t.bFrameIndex, t.dwMaxVideoFrameSize);
    if (rc == 0) { ctrl = t; color_ok = true; break; }
  }
  static LibuvcCtx lc;
  lc.core = &core_; lc.frames = 0; lc.bytes = 0;
  if (color_ok) {
    int rc = uvc.start_streaming(udevh, &ctrl, LIBUVC_ColorCb, &lc, 0);
    __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "libuvc color start_streaming rc=%d", rc);
    color_ok = (rc == 0);
  }

  // DEPTH = 手动 bulk(IF2 ep0x82)搭 vendor libuvc 阻塞 handler 线程(同 testC,连续)。14bit:1280×480×2。
  static BulkPump db;
  bool depth_ok = false;
  if (h && U.claim_interface) {
    U.claim_interface(h, 2);
    int depth_pay = CommitStream(h, 2, /*fmt*/ 1, /*frame*/ 1, /*maxFrame*/ 0x258000, /*hint*/ 3072, "depth");
    db.core = &core_; db.h = h; db.ep = 0x82; db.is_color = false; db.frame_min = 1228800u;
    db.frame_cap = 2u * 1228800u; db.urb_size = depth_pay > 0 ? depth_pay : 3072;
    db.stop = false; db.inflight = 0; db.frames = 0; db.bytes = 0; db.have_fid = false; db.frame.clear();
    depth_ok = StartBulk(&db);
  }

  __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "baseline(vendor YUYV) streaming color_ok=%d depth_ok=%d",
                      (int)color_ok, (int)depth_ok);
  if (color_ok || depth_ok) core_.MarkStreaming();
  else core_.MarkError("color/depth 均未起流");

  int tick = 0;
  while (!core_.stop_requested()) {
    usleep(200000);
    if (++tick % 5 == 0)
      __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "tick color=%lld/%lldB depth=%lld/%lldB",
                          (long long)lc.frames.load(), (long long)lc.bytes.load(), (long long)db.frames.load(),
                          (long long)db.bytes.load());
  }
  StopBulk(&db, nullptr);  // vendor libuvc 事件线程在跑,这里只等收敛
  uvc.stop_streaming(udevh);
  uvc.close(udevh);
  uvc.exit(uctx);
}

int Eys3dFdSession::poll(CameraFrame* out, uint32_t timeout_ms) {
  return core_.Poll(out, timeout_ms);
}

bool Eys3dFdSession::set_controls(const DepthControls& c) {
  // TODO(device-gated M6.5):语义控制 → XU 写(IR current 已有;AE/denoise 待锁定)。
  (void)c;
  return false;
}

void Eys3dFdSession::stop() {
  if (running_.load()) core_.RequestStop();
}

void Eys3dFdSession::join() {
  if (thread_.joinable()) thread_.join();
  running_.store(false);
}

std::unique_ptr<gomob::camera::ICameraSession> Eys3dFdDriver::open_fd(const std::vector<int>& fds,
                                                                     const SessionConfig& cfg) {
  if (fds.empty() || fds[0] < 0) return nullptr;
  SessionConfig c = cfg;
  auto caps = capabilities();
  if (c.color.width == 0 && !caps.color_profiles.empty()) c.color = caps.color_profiles[0];
  // ★ option C 杂交：color = libuvc YUYV(保活,容器尺寸由协商定,Kotlin 按字节判分辨率) +
  //   depth = 640×128 mode25(11bit 视差→ASIC metric mm)。depth_router 须按 640×128 校验/路由(否则拒帧);
  //   Java arming videoMode=0x24(36) 让 IF2 出真 metric 深度(见 Eys3dProvenArming.kt)。
  // ★ mode25 真深度路线(libuvc_lusb100):color = MJPG 1280×256(libuvc 排空 IF1 保活) +
  //   depth = 640×128 mode25(11bit 视差→ASIC metric mm)。depth_router 按 640×128 校验/路由。
  //   回退 vendor YUYV 基线时(kUsePupilMode25=false)改回 1280×480 14bit。
  c.color = gomob::camera::StreamProfile{1280, 256, 5, gomob::camera::StreamProfile::Format::kMjpeg, "1280x256@5 mode25 MJPG"};
  c.depth = gomob::camera::StreamProfile{640, 128, 5, gomob::camera::StreamProfile::Format::kDepthU16, "640x128@5 mode25"};

  // ★★★ eYs3D 当前生产路径(2026-06-17 收口):纯 native 直驱厂商 C++ 引擎(UVCCamera/UVCPreview/FrameGrabber)
  //   出 mode25 真深度,零 Java 编排(仅 Kotlin UsbManager 拿 fd)。复用厂商已验证起流链,规避自研 -EPROTO 硬墙。
  //   Java ApcCamera shim 已退役(见 git 历史)。下方 false 分支 = 自研独立路径(pupil libuvc + libusb100/stock),
  //   真正零厂商二进制的终态在研路线,-EPROTO 未通,保留作背景。见 finding_eys3d_zero_vendor_independence。
  constexpr bool kUseVendorCpp = true;
  if (kUseVendorCpp) {
    __android_log_print(ANDROID_LOG_INFO, "eys3d_stream", "open_fd → vendor C++ 直驱会话(零Java)");
    return std::make_unique<Eys3dVendorCppSession>(fds[0], c);
  }
  // Java arming(EYS3D_PROVEN_ARMING, videoMode=36) 做能力枚举/counter/flash/COMMIT(跳过 VS COMMIT widx1/2)，
  //   native 走 libuvc YUYV color streaming(保活) + 手动 depth bulk(IF2 mode25)搭 libuvc 事件线程(见 Run)。
  auto plan = host::ProvenWrongModePlan();
  plan.external_arming = true;  // arming 已由 Java 做（libusb 控制路径对 counter GET STALL，Java ioctl 不会）
  auto sess = std::make_unique<Eys3dFdSession>(fds[0], plan, c);
  // 注入几何度量默认(无 per-device ZD 表时兜底,出 metric mm)。终态 ZD 表见 TODO M6.5。
  sess->SetGeometric(kRsd550RectifiedFx, kRsd550BaselineMm);
  return sess;
}

}  // namespace gomob::eys3d::android
