#include "eys3d/android/eys3d_vendor_cpp_session.h"

#include <android/log.h>
#include <android/native_window.h>
#include <dlfcn.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#define VLOG(...) __android_log_print(ANDROID_LOG_INFO, "eys3d_vcpp", __VA_ARGS__)
#define VLOGE(...) __android_log_print(ANDROID_LOG_ERROR, "eys3d_vcpp", __VA_ARGS__)

namespace gomob::eys3d::android {
namespace {

int64_t NowNs() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

// /proc/self/fd/<fd> → /dev/bus/usb/BBB/DDD：解析 busnum/devaddr。
// ★ usbfs 参数 = 根目录 "/dev/bus/usb"（对齐 UVCCamera.getUSBFSName：砍掉末两段 BBB/DDD 取根；
//   传完整设备节点路径会让 uvc_init2 的 libusb usbfs 根错→设备查找失败→connect -4）。设备由 bus/dev 选。
constexpr const char* kUsbfsRoot = "/dev/bus/usb";
void ResolveBusDev(int fd, int* bus, int* dev) {
  *bus = 0;
  *dev = 0;
  char proc[64];
  char link[256];
  std::snprintf(proc, sizeof(proc), "/proc/self/fd/%d", fd);
  ssize_t n = readlink(proc, link, sizeof(link) - 1);
  if (n <= 0) return;
  link[n] = '\0';
  const char* base = "/dev/bus/usb/";
  const char* p = std::strstr(link, base);
  if (!p) return;
  p += std::strlen(base);
  *bus = std::atoi(p);
  const char* slash = std::strchr(p, '/');
  if (slash) *dev = std::atoi(slash + 1);
}

// 离屏 ANativeWindow（AImageReader，纯 native）：仅满足 startPreview 非空门控 + prepare_preview 设几何；
// FrameGrabber isStarted 后 do_preview 旁路 draw，不真渲染（无 dequeue/queue，故 reader 不会因 maxImages 阻塞）。
ANativeWindow* MakeOffscreenWindow(AImageReader** out_reader, int w, int h) {
  AImageReader* reader = nullptr;
  media_status_t s = AImageReader_new(w, h, AIMAGE_FORMAT_RGBA_8888, 2, &reader);
  if (s != AMEDIA_OK || !reader) {
    VLOGE("AImageReader_new(%dx%d) 失败 s=%d", w, h, (int)s);
    return nullptr;
  }
  ANativeWindow* win = nullptr;
  s = AImageReader_getWindow(reader, &win);
  if (s != AMEDIA_OK || !win) {
    VLOGE("AImageReader_getWindow 失败 s=%d", (int)s);
    AImageReader_delete(reader);
    return nullptr;
  }
  *out_reader = reader;
  return win;
}

// FrameGrabber worker 线程 → gomob：ctx = Eys3dVendorCppSession*。零 JNI（非 attach 线程，仅读 vendor 内存 + 喂 core）。
void FrameTrampoline(void* depth_vec, int dW, int dH, void* color_vec, int cW, int cH, int serial,
                     void* ctx) {
  if (!ctx) return;
  reinterpret_cast<Eys3dVendorCppSession*>(ctx)->OnVendorFrame(depth_vec, dW, dH, color_vec, cW, cH,
                                                               serial);
}

}  // namespace

bool LoadVendorUvcAbi(VendorUvcAbi* abi) {
  if (abi->lib) return abi->Loaded();
  abi->lib = dlopen("libUVCCamera.so", RTLD_NOW | RTLD_LOCAL);
  if (!abi->lib) {
    VLOGE("dlopen libUVCCamera.so 失败: %s", dlerror());
    return false;
  }
  void* L = abi->lib;
#define SYM(field, name) abi->field = reinterpret_cast<decltype(abi->field)>(dlsym(L, name))
  SYM(cam_ctor, "_ZN9UVCCameraC1Ev");
  SYM(cam_dtor, "_ZN9UVCCameraD1Ev");
  SYM(cam_connect, "_ZN9UVCCamera7connectEiiiiiPKc");
  SYM(cam_set_video_mode, "_ZN9UVCCamera12setVideoModeEt");
  SYM(cam_set_exposure_mode, "_ZN9UVCCamera15setExposureModeEi");
  SYM(cam_set_fw_register, "_ZN9UVCCamera13setFWRegisterEtti");
  SYM(cam_set_preview_size, "_ZN9UVCCamera14setPreviewSizeEiiiiifi");
  SYM(cam_set_preview_display, "_ZN9UVCCamera17setPreviewDisplayEP13ANativeWindowi");
  SYM(cam_start_preview, "_ZN9UVCCamera12startPreviewEi");
  SYM(cam_stop_preview, "_ZN9UVCCamera11stopPreviewEi");
  SYM(cam_get_zd_table, "_ZN9UVCCamera10GetZDTableEPhiPii");
  SYM(cam_get_current_file_index, "_ZN9UVCCamera19getCurrentFileIndexEv");
  SYM(cam_set_interleave_mode, "_ZN9UVCCamera17setInterleaveModeEb");
  SYM(cam_set_external_storage, "_ZN9UVCCamera33setExternalStoragePublicDirectoryEPKc");
  SYM(fg_set_frame_format, "_ZN12FrameGrabber14SetFrameFormatEiiii");
  SYM(fg_open, "_ZN12FrameGrabber4OpenEv");
  SYM(fg_close, "_ZN12FrameGrabber5CloseEv");
  SYM(fg_is_started, "_ZN12FrameGrabber9isStartedEv");
  abi->live_ply_callback =
      dlsym(L, "_ZN9UVCCamera15livePlyCallbackERNSt6__ndk16vectorIhNS0_9allocatorIhEEEEiiS5_iiiPv");
#undef SYM
  const bool ok = abi->Loaded();
  VLOG("LoadVendorUvcAbi ok=%d (ctor=%p connect=%p startPreview=%p getZD=%p fgOpen=%p livePly=%p)", ok,
       (void*)abi->cam_ctor, (void*)abi->cam_connect, (void*)abi->cam_start_preview,
       (void*)abi->cam_get_zd_table, (void*)abi->fg_open, abi->live_ply_callback);
  return ok;
}

Eys3dVendorCppSession::Eys3dVendorCppSession(int fd, const gomob::camera::SessionConfig& cfg)
    : fd_(fd), cfg_(cfg) {
  SessionCoreConfig cc;
  cc.color = cfg.color;
  cc.depth = cfg.depth;
  cc.depth_router = DepthRouterConfig{cfg.depth.width, cfg.depth.height, 0, false};
  cc.want_color = cfg.want_color;
  cc.want_depth = cfg.want_depth;
  core_.Configure(cc);
}

Eys3dVendorCppSession::~Eys3dVendorCppSession() {
  stop();
  join();
}

bool Eys3dVendorCppSession::start(const gomob::camera::SessionCallbacks& cb) {
  if (running_.exchange(true)) return false;
  if (cb.on_frame) core_.SetOnFrame(cb.on_frame);
  thread_ = std::thread(&Eys3dVendorCppSession::Run, this);
  return true;
}

void Eys3dVendorCppSession::OnVendorFrame(void* depth_vec, int dW, int dH, void* color_vec, int cW,
                                          int cH, int serial) {
  const int64_t n = ++cb_frames_;
  const int64_t host_ns = NowNs();

  // ---- color：字节透传（MJPEG 压缩帧，size 即压缩字节数，不能按 w*h*bpp 算）----
  //   core_.OnColorFrame 内部按 cfg_.color.width/height 给帧打标签;故须用回调真实 cW/cH 做一致性门控,
  //   回调几何与配置不符时丢弃并告警(否则会把异常分辨率帧错标成配置分辨率喂下游)。
  const uint8_t* color = VecData(color_vec);
  const size_t color_sz = VecSize(color_vec);
  if (color && color_sz > 0) {
    if (cW == cfg_.color.width && cH == cfg_.color.height) {
      core_.OnColorFrame(color, color_sz, host_ns);
    } else if (n <= 5 || n % 30 == 0) {
      VLOGE("ourCb #%lld color 几何不符 回调 %dx%d 期望 %dx%d sz=%zu → 丢弃", (long long)n, cW, cH,
            cfg_.color.width, cfg_.color.height, color_sz);
    }
  }

  // ---- depth：FrameGrabber 路径已是 metric mm（vendor do_preview 内 DepthFilter + 自载 ZD 表转好），直接喂，勿再套 LUT ----
  const uint8_t* draw = VecData(depth_vec);
  const size_t dsz = VecSize(depth_vec);
  const size_t px = static_cast<size_t>(dW) * static_cast<size_t>(dH);
  if (draw && dsz >= px * 2 && px > 0) {
    const auto* mm16 = reinterpret_cast<const uint16_t*>(draw);
    core_.OnDepthMmFrame(mm16, static_cast<uint16_t>(dW), static_cast<uint16_t>(dH), host_ns);
    if (n <= 5 || n % 30 == 0) {
      size_t nz = 0;
      uint16_t mx = 0;
      uint64_t sum = 0;
      for (size_t i = 0; i < px; ++i) {
        const uint16_t v = mm16[i];
        if (v) { ++nz; sum += v; if (v > mx) mx = v; }
      }
      const size_t c = (static_cast<size_t>(dH) / 2) * dW + dW / 2;
      VLOG("ourCb #%lld depth %dx%d color %dx%d serial=%d centerMm=%u valid=%.1f%% max=%u meanNZ=%llu",
           (long long)n, dW, dH, cW, cH, serial, mm16[c], 100.0 * nz / px, mx,
           nz ? (unsigned long long)(sum / nz) : 0ull);
    }
  } else if (n <= 5) {
    VLOG("ourCb #%lld depth 尺寸异常 dW=%d dH=%d dsz=%zu (期望>=%zu)", (long long)n, dW, dH, dsz,
         px * 2);
  }
}

void Eys3dVendorCppSession::Run() {
  if (fd_ < 0) { core_.MarkError("invalid fd"); return; }
  if (!LoadVendorUvcAbi(&abi_)) { core_.MarkError("load vendor uvc abi failed"); return; }

  int bus = 0, dev = 0;
  ResolveBusDev(fd_, &bus, &dev);
  VLOG("fd=%d bus=%d dev=%d usbfs=%s", fd_, bus, dev, kUsbfsRoot);

  // 1) new UVCCamera（over-alloc 裸内存，厂商 ctor 写 vtable+建内部 FrameGrabber）
  cam_ = std::malloc(kUvcCameraAllocBytes);
  if (!cam_) { core_.MarkError("alloc UVCCamera failed"); return; }
  std::memset(cam_, 0, kUvcCameraAllocBytes);
  abi_.cam_ctor(cam_);

  // 2) 定位并校验内部 FrameGrabber（[cam+0x2430]=fg；fg 的默认回调必须 == livePlyCallback 才认对）
  fg_ = *reinterpret_cast<void**>(reinterpret_cast<char*>(cam_) + kUvcCameraFgObjOffset);
  if (!fg_) { core_.MarkError("FrameGrabber 未找到(offset?)"); Teardown(); return; }
  void* cur_cb = *reinterpret_cast<void**>(reinterpret_cast<char*>(fg_) + kFrameGrabberCbOffset);
  Dl_info di{};
  dladdr(cur_cb, &di);
  if (cur_cb != abi_.live_ply_callback) {
    VLOGE("FrameGrabber 校验失败:fg=%p cb=%p(livePly=%p, dli=%s) 不 repoint,中止", fg_, cur_cb,
          abi_.live_ply_callback, di.dli_sname ? di.dli_sname : "?");
    core_.MarkError("FrameGrabber 校验失败(ABI offset 不符)");
    Teardown();
    return;
  }
  VLOG("FrameGrabber 校验通过 fg=%p cb==livePlyCallback", fg_);

  // 2.5) 必设 external storage 目录(connect 会 strdup 传给 UVCPreview;NULL 必崩)。优先用 Kotlin 经 options_json
  //   下发的 app 专属外部目录(getExternalFilesDir,无需权限可写);为空时兜底。仅 vendor debug 存图用,本路径不触发写。
  const char* storage = cfg_.options_json.empty() ? "/sdcard/Android/data/io.gomob.scan/files/eys3d"
                                                  : cfg_.options_json.c_str();
  if (abi_.cam_set_external_storage) abi_.cam_set_external_storage(cam_, storage);
  VLOG("external storage = %s", storage);

  // 3) connect(fd)：厂商内部 uvc_get_device_with_fd + uvc_open + 建 2 个 UVCPreview(共享 fg)
  int rc = abi_.cam_connect(cam_, 0x3438, 0x0206, fd_, bus, dev, kUsbfsRoot);  // (vid,pid,fd,bus,dev,usbfs)
  VLOG("UVCCamera::connect rc=%d", rc);

  // 4) arming：mode25 + 关交织（IR/AE 待彩色起流后设，对齐 Java）
  if (abi_.cam_set_video_mode) VLOG("setVideoMode(36) rc=%d", abi_.cam_set_video_mode(cam_, kVideoModeMode25));
  if (abi_.cam_set_interleave_mode) abi_.cam_set_interleave_mode(cam_, false);
  // ZD LUT 已删:FrameGrabber 路径深度 do_preview 内自载 ZD 表转好 metric mm(见 OnVendorFrame),
  //   我方不再消费视差→mm LUT;原 BuildZdLut 仅做无人读的 GetZDTable USB 往返,纯浪费控制传输,移除。

  // 5) repoint FrameGrabber 回调 → 我们的 trampoline（在 Open 前，worker 未起，安全）
  old_fg_cb_ = cur_cb;
  old_fg_ctx_ = *reinterpret_cast<void**>(reinterpret_cast<char*>(fg_) + kFrameGrabberCtxOffset);
  *reinterpret_cast<void**>(reinterpret_cast<char*>(fg_) + kFrameGrabberCbOffset) =
      reinterpret_cast<void*>(&FrameTrampoline);
  *reinterpret_cast<void**>(reinterpret_cast<char*>(fg_) + kFrameGrabberCtxOffset) = this;
  if (abi_.fg_set_frame_format) {
    abi_.fg_set_frame_format(fg_, kCameraColor, 2, kColorW, kColorH);
    abi_.fg_set_frame_format(fg_, kCameraDepth, 2, kDepthW, kDepthH);
  }
  if (abi_.fg_open) abi_.fg_open(fg_);  // 置 isStarted → do_preview 才喂 FrameGrabber

  // 6) 彩色流（先建离屏窗口满足 startPreview 门控，MJPEG passthrough）
  color_win_ = MakeOffscreenWindow(reinterpret_cast<AImageReader**>(&color_reader_), kColorW, kColorH);
  abi_.cam_set_preview_size(cam_, kColorW, kColorH, 1, kFps, kColorPreviewMode, 1.0f, kCameraColor);
  if (abi_.cam_set_preview_display)
    abi_.cam_set_preview_display(cam_, color_win_, kCameraColor);
  VLOG("color startPreview rc=%d (win=%p)", abi_.cam_start_preview(cam_, kCameraColor), color_win_);

  // 7) IR 补光 + AE（彩色起流后，序列见 finding_eys3d_zero_vendor_independence「mode25 Java 起流序列」）
  if (abi_.cam_set_exposure_mode) abi_.cam_set_exposure_mode(cam_, kAeModeAuto);
  if (abi_.cam_set_fw_register) {
    abi_.cam_set_fw_register(cam_, kIrMaxReg, kIrMaxVal, kFwFlag1Byte);
    abi_.cam_set_fw_register(cam_, kIrCurReg, kIrCurVal, kFwFlag1Byte);
  }

  // 8) 暖机 ~3s（彩色 AE/IR 稳定后再开深度，对齐 Java warmup）
  for (int i = 0; i < 30 && !core_.stop_requested(); ++i) usleep(100000);

  // 9) 深度流（mode25 11bit 视差 → ASIC 直出）
  if (!core_.stop_requested()) {
    depth_win_ = MakeOffscreenWindow(reinterpret_cast<AImageReader**>(&depth_reader_), kDepthW, kDepthH);
    abi_.cam_set_preview_size(cam_, kDepthW, kDepthH, 1, kFps, kDepthPreviewMode, 1.0f, kCameraDepth);
    if (abi_.cam_set_preview_display)
      abi_.cam_set_preview_display(cam_, depth_win_, kCameraDepth);
    VLOG("depth startPreview rc=%d (win=%p)", abi_.cam_start_preview(cam_, kCameraDepth), depth_win_);
  }

  core_.MarkStreaming();

  // 10) 保活：厂商预览线程 + FrameGrabber worker 在跑，帧经 trampoline 进 core。
  int tick = 0;
  while (!core_.stop_requested()) {
    usleep(200000);
    if (++tick % 10 == 0)
      VLOG("tick cbFrames=%lld state=%d", (long long)cb_frames_.load(), (int)core_.state());
  }
  Teardown();
}

void Eys3dVendorCppSession::Teardown() {
  if (cam_) {
    if (abi_.cam_stop_preview) {
      abi_.cam_stop_preview(cam_, kCameraDepth);
      abi_.cam_stop_preview(cam_, kCameraColor);
    }
    if (fg_ && abi_.fg_close) abi_.fg_close(fg_);
    // 还原 FrameGrabber 回调，避免厂商析构期间误入我方 trampoline。
    if (fg_ && old_fg_cb_) {
      *reinterpret_cast<void**>(reinterpret_cast<char*>(fg_) + kFrameGrabberCbOffset) = old_fg_cb_;
      *reinterpret_cast<void**>(reinterpret_cast<char*>(fg_) + kFrameGrabberCtxOffset) = old_fg_ctx_;
    }
    if (abi_.cam_dtor) abi_.cam_dtor(cam_);  // D1：析构成员（含 shared_ptr→厂商销毁 fg），不释放 this
    std::free(cam_);
    cam_ = nullptr;
    fg_ = nullptr;
  }
  if (color_reader_) {
    AImageReader_delete(reinterpret_cast<AImageReader*>(color_reader_));
    color_reader_ = nullptr;
    color_win_ = nullptr;
  }
  if (depth_reader_) {
    AImageReader_delete(reinterpret_cast<AImageReader*>(depth_reader_));
    depth_reader_ = nullptr;
    depth_win_ = nullptr;
  }
  VLOG("Teardown 完成 cbFrames=%lld", (long long)cb_frames_.load());
}

int Eys3dVendorCppSession::poll(gomob::camera::CameraFrame* out, uint32_t timeout_ms) {
  return core_.Poll(out, timeout_ms);
}

bool Eys3dVendorCppSession::set_controls(const gomob::camera::DepthControls& c) {
  (void)c;
  return false;  // TODO(M6.5)：IR/AE/denoise 语义控制 → UVCCamera::setFWRegister/setExposureMode
}

void Eys3dVendorCppSession::stop() {
  if (running_.load()) core_.RequestStop();
}

void Eys3dVendorCppSession::join() {
  if (thread_.joinable()) thread_.join();
  running_.store(false);
}

}  // namespace gomob::eys3d::android
