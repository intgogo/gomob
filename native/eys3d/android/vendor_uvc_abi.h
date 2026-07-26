// eYs3D 厂商 libUVCCamera/libESPDI/libuvc 的 native 直驱 ABI 契约（零 Java 路径）。
//
// 第一性：mode25 真深度由设备 ASIC 片上立体匹配产出；厂商 libUVCCamera 内部 = saki4510t UVC 栈
//   (UVCCamera/UVCPreview/FrameGrabber C++ 类) + libuvc(纯 C 起流) + libESPDI(CVideoDevice 控制/ZD)。
//   Java 只是厂商把这套 C++ 引擎包成 Android API 的外壳，非技术必需。本头让 gomob 在 native 直调这套
//   C++ 引擎：保留厂商已验证的起流链（规避自研 -EPROTO 硬墙），仅留 Java UsbManager 拿 fd。
//
// 帧出线路（反汇编实证，见 finding_eys3d_zero_vendor_independence 续2）：
//   FrameGrabber 回调 = 纯 C 函数指针 void(*)(vec<uchar>& depth,int dW,int dH, vec<uchar>& color,int cW,int cH, int serial, void* ctx)。
//   UVCCamera::connect 内部建 1 个 FrameGrabber + 2 个 UVCPreview(color mode0 / depth mode1)共享之；
//   FrameGrabber 默认回调指向 UVCCamera::livePlyCallback(无 livePly jobject 时短路丢帧、零 JNI)。
//   我们把该 FrameGrabber 的 [+8]=回调 / [+0x10]=ctx 改指向自己的 trampoline → 帧全 native 进 gomob，绝不碰 _jobject。
//
// ABI 纪律（abi 侦察实证）：厂商 = NDK r12 std::__ndk1 静态 libc++；gomob = NDK27 std::__1。
//   - 不在 gomob 侧构造/析构任何 __ndk1 C++ 对象(shared_ptr/vector)。所有 C++ 对象造/计数/销毁全留厂商 .so 内。
//   - 回调里厂商 vector<uchar>& 当 3 指针 POD【只读】(begin/end/cap)，绝不调方法、绝不持指针越栈。
//   - dlopen(RTLD_LOCAL) 隔离厂商自带 libusb100(saki fork)，不遮蔽 gomob libusb-1.0(续35 教训)。
#pragma once

#include <cstddef>
#include <cstdint>

namespace gomob::eys3d::android {

// FrameGrabber 回调签名（vec<uchar>& 以指针形式到达，按 RawVec 只读）。
using VendorFrameCb = void (*)(void* depth_vec, int depth_w, int depth_h, void* color_vec,
                               int color_w, int color_h, int serial, void* ctx);

// std::__ndk1::vector<uchar> 的二进制布局（与 __1 一致）：3 个连续指针。只读视图，绝不写/调方法。
struct RawVec {
  const uint8_t* begin;
  const uint8_t* end;
  const uint8_t* cap;
};
inline const uint8_t* VecData(const void* v) {
  return v ? reinterpret_cast<const RawVec*>(v)->begin : nullptr;
}
inline size_t VecSize(const void* v) {
  if (!v) return 0;
  const auto* p = reinterpret_cast<const RawVec*>(v);
  return p->end >= p->begin ? static_cast<size_t>(p->end - p->begin) : 0;
}

// 厂商 C++ 方法的 dlsym 函数指针表（this 显式作首参——非虚成员可当自由函数调）。
// 全部经 dlopen(RTLD_LOCAL)+dlsym mangled 取；不 link、不 System.loadLibrary。
struct VendorUvcAbi {
  void* lib = nullptr;  // libUVCCamera.so 句柄（RTLD_LOCAL）

  // ---- UVCCamera（this = 我方 over-alloc 裸内存，由厂商 ctor 写 vtable）----
  void (*cam_ctor)(void* self) = nullptr;                                       // UVCCamera()
  void (*cam_dtor)(void* self) = nullptr;                                       // ~UVCCamera()(D1,非释放)
  // ★ 参数顺序对齐 ApcCamera.nativeConnect：(vid, pid, fd, busNum, devNum, usbfs)，不是 (fd,vid,pid,...)！
  int (*cam_connect)(void* self, int vid, int pid, int fd, int bus, int dev,
                     const char* usbfs) = nullptr;                              // connect
  int (*cam_set_video_mode)(void* self, unsigned short mode) = nullptr;         // setVideoMode(36)
  int (*cam_set_exposure_mode)(void* self, int mode) = nullptr;                 // setExposureMode(8)
  int (*cam_set_fw_register)(void* self, unsigned short addr, unsigned short val,
                             int flag) = nullptr;                              // setFWRegister(IR)
  int (*cam_set_preview_size)(void* self, int w, int h, int min_fps, int max_fps, int mode,
                              float bw, int idx) = nullptr;                     // setPreviewSize
  int (*cam_set_preview_display)(void* self, void* anative_window, int idx) = nullptr;  // setPreviewDisplay(nullptr)
  int (*cam_start_preview)(void* self, int idx) = nullptr;                      // startPreview(idx)
  int (*cam_stop_preview)(void* self, int idx) = nullptr;                       // stopPreview(idx)
  int (*cam_get_zd_table)(void* self, unsigned char* buf, int len, int* actual,
                          int index) = nullptr;                               // GetZDTable
  int (*cam_get_current_file_index)(void* self) = nullptr;                      // getCurrentFileIndex
  int (*cam_set_interleave_mode)(void* self, bool on) = nullptr;                // setInterleaveMode
  // ★ 必设:connect 内部把此目录 strdup 传给 UVCPreview;NULL→strdup(NULL) 崩(对齐 Java nativeSetExternalStoragePublicDirectory)。
  void (*cam_set_external_storage)(void* self, const char* path) = nullptr;     // setExternalStoragePublicDirectory

  // ---- FrameGrabber（this = 从 UVCCamera 取出的厂商对象指针）----
  int (*fg_set_frame_format)(void* self, int sel, int extra, int w, int h) = nullptr;  // SetFrameFormat
  int (*fg_open)(void* self) = nullptr;                                         // Open()(置 isStarted)
  int (*fg_close)(void* self) = nullptr;                                        // Close()
  bool (*fg_is_started)(void* self) = nullptr;                                  // isStarted()

  // 校验锚点：FrameGrabber 默认回调指向它（确认我们找对了 FrameGrabber 才 repoint）。
  void* live_ply_callback = nullptr;  // UVCCamera::livePlyCallback

  bool Loaded() const {
    return lib && cam_ctor && cam_dtor && cam_connect && cam_set_video_mode && cam_set_preview_size &&
           cam_set_preview_display && cam_start_preview && cam_stop_preview && cam_get_zd_table &&
           fg_set_frame_format && fg_open && fg_close && fg_is_started && cam_set_external_storage &&
           live_ply_callback;
  }
};

// UVCCamera 对象内 FrameGrabber shared_ptr 的成员偏移（disasm 实证）：
//   [cam+0x2430]=FrameGrabber 对象指针，[cam+0x2438]=控制块指针。
constexpr size_t kUvcCameraFgObjOffset = 0x2430;
// FrameGrabber 对象内：[fg+8]=回调函数指针，[fg+0x10]=ctx（disasm 实证 ctor 写入）。
constexpr size_t kFrameGrabberCbOffset = 8;
constexpr size_t kFrameGrabberCtxOffset = 0x10;
// UVCCamera over-alloc 尺寸（成员见至 0x2440+，宽放）。
constexpr size_t kUvcCameraAllocBytes = 0x8000;

// 流索引（对齐 Java CAMERA_COLOR/CAMERA_DEPTH）。
constexpr int kCameraColor = 0;
constexpr int kCameraDepth = 1;
// mode25 档位常量（实证来源 finding_eys3d_zero_vendor_independence「mode25 Java 起流序列」）。
constexpr unsigned short kVideoModeMode25 = 36;  // SCALE_DOWN_11_BITS
constexpr int kColorW = 1280, kColorH = 256;     // MJPEG mode25 color（单 L' 左矫正）。★实测:改 1280×480/2560×960 出 R 但整流破，不可行
constexpr int kDepthW = 640, kDepthH = 128;      // mode25 depth(11bit 视差)
constexpr int kFps = 5;
constexpr int kColorPreviewMode = 1;  // MJPEG
constexpr int kDepthPreviewMode = 0;  // RAW
constexpr unsigned short kIrMaxReg = 0xe2, kIrMaxVal = 6;
constexpr unsigned short kIrCurReg = 0xe0, kIrCurVal = 3;
constexpr int kFwFlag1Byte = 0x11;     // FG_Address_1Byte|FG_Value_1Byte
constexpr int kAeModeAuto = 8;         // setExposureMode 值
constexpr unsigned short kDispMask = 0x07FF;  // 11bit 视差掩码

// dlopen(RTLD_LOCAL) libUVCCamera.so 并 dlsym 填表。失败返回 false（日志记原因）。
bool LoadVendorUvcAbi(VendorUvcAbi* abi);

}  // namespace gomob::eys3d::android
