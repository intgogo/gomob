// 厂商无关相机统一 JNI 入口 — 把 native/camera 抽象(ICameraDriver/ICameraSession/CameraRegistry)
// 接到 Kotlin NativeBridge。两相机(eYs3D RS-D550 / 后续 Berxel adapter)经同一 cameraXxx 入口,
// 按 vid:pid 由 CameraRegistry 分发到 driver,出同一 CameraFrame(kDepthMm) 契约。
//
// ★ 注册 Eys3dFdDriver(0x3438:0x0206) + BerxelDriver(0x0603:0x001f,M6.8b ④ 已并入)。
//   Berxel 经 BerxelDriver → berxel_open_dual 走与 host 同一 portable 序列(color+depth 真机 PASS)。
// handle = ICameraSession* 转 jlong;Kotlin 持有,cameraStop 唯一释放点。
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "camera/camera_registry.h"
#include "camera/camera_session.h"
#include "eys3d/android/eys3d_fd_session.h"
#include "eys3d/portable/eys3d_driver.h"        // kRsd550RectifiedFx / kRsd550BaselineMm
#include "hlsd8/hlsd8_uvc_session.h"           // HLSD8 RGB 相机（0x0C45:0x6366，独立第二颗相机）
#include "berxel/host/berxel_camera_adapter.h"  // M6.8b ④：MakeBerxelDriver()

#define LOG_TAG "gomob_camera_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using gomob::camera::CameraFrame;
using gomob::camera::CameraRegistry;
using gomob::camera::DepthControls;
using gomob::camera::ICameraDriver;
using gomob::camera::ICameraSession;
using gomob::camera::SessionCallbacks;
using gomob::camera::SessionConfig;
using gomob::camera::UsbId;

// 进程内全局注册表(once-init)。eYs3D(0x3438:0x0206)+ Berxel(0x0603:0x001f)双相机统一分发。
CameraRegistry& Registry() {
  static CameraRegistry* reg = [] {
    auto* r = new CameraRegistry();
    r->Register(std::make_shared<gomob::eys3d::android::Eys3dFdDriver>(
        /*usb3=*/false, gomob::eys3d::DepthPath::kHardwareAsic));
    r->Register(std::make_shared<gomob::hlsd8::Hlsd8Driver>());  // HLSD8 RGB（独立第二颗相机）
    r->Register(gomob::berxel::host::MakeBerxelDriver());  // M6.8b ④
    return r;
  }();
  return *reg;
}

}  // namespace

extern "C" {

// 按 vid:pid 经注册表选 driver → open_fd(fds) → start → 返 ICameraSession* 句柄(失败 0L)。
// eYs3D:fds=[单节点 usbfs fd];configJson 透传给 driver(SessionConfig.options_json)。
JNIEXPORT jlong JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraOpenByFds(
        JNIEnv* env, jobject /*thiz*/, jint vid, jint pid, jintArray fds, jbyteArray configJson) {
  ICameraDriver* drv = Registry().MatchByUsbId(UsbId{static_cast<uint16_t>(vid),
                                                     static_cast<uint16_t>(pid)});
  if (!drv) { LOGE("cameraOpenByFds: no driver for %04x:%04x", vid, pid); return 0L; }

  std::vector<int> fdv;
  if (fds) {
    const jsize n = env->GetArrayLength(fds);
    fdv.resize(static_cast<size_t>(n));
    if (n > 0) env->GetIntArrayRegion(fds, 0, n, reinterpret_cast<jint*>(fdv.data()));
  }
  SessionConfig cfg;
  if (configJson) {
    const jsize n = env->GetArrayLength(configJson);
    cfg.options_json.resize(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(configJson, 0, n,
                                       reinterpret_cast<jbyte*>(cfg.options_json.data()));
  }

  std::unique_ptr<ICameraSession> sess = drv->open_fd(fdv, cfg);
  if (!sess) { LOGE("cameraOpenByFds: open_fd failed (%04x:%04x)", vid, pid); return 0L; }
  if (!sess->start(SessionCallbacks{})) { LOGE("cameraOpenByFds: start failed"); return 0L; }
  LOGI("cameraOpenByFds ok %04x:%04x fds=%zu ptr=%p", vid, pid, fdv.size(), (void*)sess.get());
  return reinterpret_cast<jlong>(sess.release());  // 所有权交 Kotlin,cameraStop 释放
}

// ★★★ Java ApcCamera 路径绑定(2026-06-15 改主路):dlopen libUVCCamera.so(RTLD_LOCAL 隔离其自带
//   libusb100,不被 gomob libusb-1.0 全局符号遮蔽) + 手动调其 JNI_OnLoad(传 vm)。
//   JNI_OnLoad 内部 FindClass(com/esp/android/usb/camera/core/{UVCCamera,ApcCamera}) → RegisterNatives
//   绑定 gomob 复制进来的 esp Java 类的全部 native 方法 + setVM(回调线程 AttachCurrentThread 用)。
//   【不】System.loadLibrary("UVCCamera")(否则走 app 默认 RTLD_GLOBAL,libusb100 符号遮蔽 gomob libusb-1.0,续35)。
//   调用时机:Kotlin 在 `new ApcCamera()` 前调一次(nativeCreate 须先被 RegisterNatives 绑定)。
//   幂等:句柄静态缓存,JNI_OnLoad 只跑一次。返 JNI 版本号(>0 成功)/ 0 失败。
JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_bindEys3dVendorJni(JNIEnv* env, jobject /*thiz*/) {
  static void* handle = nullptr;
  static jint version = 0;
  if (handle) return version;  // 已绑定,幂等返回
  handle = dlopen("libUVCCamera.so", RTLD_NOW | RTLD_LOCAL);
  if (!handle) { LOGE("bindEys3dVendorJni: dlopen libUVCCamera.so 失败: %s", dlerror()); return 0; }
  using FnOnLoad = jint (*)(JavaVM*, void*);
  auto on_load = reinterpret_cast<FnOnLoad>(dlsym(handle, "JNI_OnLoad"));
  if (!on_load) { LOGE("bindEys3dVendorJni: dlsym JNI_OnLoad 失败: %s", dlerror()); dlclose(handle); handle = nullptr; return 0; }
  JavaVM* vm = nullptr;
  if (env->GetJavaVM(&vm) != JNI_OK || !vm) { LOGE("bindEys3dVendorJni: GetJavaVM 失败"); return 0; }
  version = on_load(vm, nullptr);  // RegisterNatives(gomob 的 esp 类)+ vendor setVM
  if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
  LOGI("bindEys3dVendorJni: libUVCCamera JNI_OnLoad → 0x%x", version);
  return version;
}

// 对 Java 传来的 usbfs fd 做一次 USB 端口 reset，清设备流引擎残留状态（eYs3D host proven 5 大硬约束之首）。
// ★ reset 会触发重枚举 → 本 fd 随即失效，调用方必须 close 旧 connection 并重新 openDevice 取新 fd 再开流。
// 成功（reset OK 或设备已 NOT_FOUND=已在重枚举）返 true。
#include <libusb-1.0/libusb.h>
JNIEXPORT jboolean JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraResetByFd(JNIEnv* /*env*/, jobject /*thiz*/, jint fd) {
  if (fd < 0) return JNI_FALSE;
  libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
  libusb_context* ctx = nullptr;
  if (libusb_init(&ctx) != 0 || ctx == nullptr) return JNI_FALSE;
  libusb_device_handle* h = nullptr;
  if (libusb_wrap_sys_device(ctx, static_cast<intptr_t>(fd), &h) != 0 || h == nullptr) {
    libusb_exit(ctx);
    return JNI_FALSE;
  }
  const int rc = libusb_reset_device(h);
  libusb_close(h);
  libusb_exit(ctx);
  LOGI("cameraResetByFd fd=%d rc=%d (%s)", fd, rc,
       (rc == 0 || rc == LIBUSB_ERROR_NOT_FOUND) ? "ok" : "fail");
  return (rc == 0 || rc == LIBUSB_ERROR_NOT_FOUND) ? JNI_TRUE : JNI_FALSE;
}

// 停止 + 释放(唯一释放点)。
JNIEXPORT void JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraStop(JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
  auto* s = reinterpret_cast<ICameraSession*>(handle);
  if (!s) return;
  s->stop();
  s->join();
  delete s;
}

// 取最新 depthMm 帧写进 directBuffer(容量需 >= w*h*2)。返回字节数;无新帧 0;buffer 不足 -1。
// outInfo(>=4)=[width,height,serial,host_ns]。两相机同契约。
JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraPollDepthMm(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobject directBuffer, jlongArray outInfo) {
  auto* s = reinterpret_cast<ICameraSession*>(handle);
  if (!s || !directBuffer) return 0;
  auto* dst = reinterpret_cast<uint16_t*>(env->GetDirectBufferAddress(directBuffer));
  const jlong cap = env->GetDirectBufferCapacity(directBuffer);
  if (!dst || cap <= 0) return -1;
  int64_t meta[4] = {0, 0, 0, 0};
  const int written = s->snapshot_depth_mm(dst, static_cast<size_t>(cap) / sizeof(uint16_t), meta);
  if (written > 0 && outInfo && env->GetArrayLength(outInfo) >= 4) {
    jlong m[4] = {meta[0], meta[1], meta[2], meta[3]};
    env->SetLongArrayRegion(outInfo, 0, 4, m);
  }
  return written;
}

// 取最新 color 帧字节(consume-once);无新帧返 null。
JNIEXPORT jbyteArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraPollColor(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
  auto* s = reinterpret_cast<ICameraSession*>(handle);
  if (!s) return nullptr;
  std::vector<uint8_t> color;
  int64_t meta[4] = {0, 0, 0, 0};
  if (!s->snapshot_color(&color, meta) || color.empty()) return nullptr;
  jbyteArray out = env->NewByteArray(static_cast<jsize>(color.size()));
  if (!out) return nullptr;
  env->SetByteArrayRegion(out, 0, static_cast<jsize>(color.size()),
                          reinterpret_cast<const jbyte*>(color.data()));
  return out;
}

// 取最新逐像素 confidence(uint8,W*H) 写 directBuffer。返回字节数 / 0 无 / -1 cap 不足。
// outInfo(>=4)=[w,h,serial,host_ns]。无 conf 的相机(eYs3D)默认返 0。
JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraPollDepthConf(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobject directBuffer, jlongArray outInfo) {
  auto* s = reinterpret_cast<ICameraSession*>(handle);
  if (!s || !directBuffer) return 0;
  auto* dst = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(directBuffer));
  const jlong cap = env->GetDirectBufferCapacity(directBuffer);
  if (!dst || cap <= 0) return -1;
  int64_t meta[4] = {0, 0, 0, 0};
  const int written = s->snapshot_confidence(dst, static_cast<size_t>(cap), meta);
  if (written > 0 && outInfo && env->GetArrayLength(outInfo) >= 4) {
    jlong m[4] = {meta[0], meta[1], meta[2], meta[3]};
    env->SetLongArrayRegion(outInfo, 0, 4, m);
  }
  return written;
}

// 取最新 IR/phase 灰度(uint8,W*H) 写 directBuffer。返回字节数 / 0 无 / -1 cap 不足。
JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraPollIrGrey(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jobject directBuffer, jlongArray outInfo) {
  auto* s = reinterpret_cast<ICameraSession*>(handle);
  if (!s || !directBuffer) return 0;
  auto* dst = reinterpret_cast<uint8_t*>(env->GetDirectBufferAddress(directBuffer));
  const jlong cap = env->GetDirectBufferCapacity(directBuffer);
  if (!dst || cap <= 0) return -1;
  int64_t meta[4] = {0, 0, 0, 0};
  const int written = s->snapshot_ir(dst, static_cast<size_t>(cap), meta);
  if (written > 0 && outInfo && env->GetArrayLength(outInfo) >= 4) {
    jlong m[4] = {meta[0], meta[1], meta[2], meta[3]};
    env->SetLongArrayRegion(outInfo, 0, 4, m);
  }
  return written;
}

// 厂商扩展诊断统计(driver 自定义 int64 序列;Berxel=16 项,eYs3D=空)。无扩展返长度 0 数组。
JNIEXPORT jlongArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraExtendedStats(JNIEnv* env, jobject /*thiz*/, jlong handle) {
  auto* s = reinterpret_cast<ICameraSession*>(handle);
  int64_t buf[16] = {0};
  int n = 0;
  if (s) n = s->extended_stats(buf, 16);
  if (n < 0) n = 0;
  jlongArray out = env->NewLongArray(n);
  if (out && n > 0) {
    jlong m[16];
    for (int i = 0; i < n; ++i) m[i] = buf[i];
    env->SetLongArrayRegion(out, 0, n, m);
  }
  return out;
}

// 调试:dump 最新 depth transport 原始字节到 path。返回写入字节数。
JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraDumpRawDepth(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring path) {
  auto* s = reinterpret_cast<ICameraSession*>(handle);
  if (!s || !path) return 0;
  const char* p = env->GetStringUTFChars(path, nullptr);
  const int written = s->dump_raw_depth(p);
  env->ReleaseStringUTFChars(path, p);
  return written;
}

// 调试:dump 最新 color 原始预览帧到 path。Berxel 为 MJPEG。返回写入字节数。
JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraDumpRawColor(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring path) {
  auto* s = reinterpret_cast<ICameraSession*>(handle);
  if (!s || !path) return 0;
  const char* p = env->GetStringUTFChars(path, nullptr);
  const int written = s->dump_raw_color(p);
  env->ReleaseStringUTFChars(path, p);
  return written;
}

// [colorFrames, depthFrames, dropped, errors, state]。
JNIEXPORT jlongArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraStats(JNIEnv* env, jobject /*thiz*/, jlong handle) {
  auto* s = reinterpret_cast<ICameraSession*>(handle);
  jlong v[5] = {0, 0, 0, 0, 0};
  if (s) {
    const auto st = s->stats();
    v[0] = st.color_frames; v[1] = st.depth_frames; v[2] = st.dropped; v[3] = st.errors;
    v[4] = static_cast<jlong>(s->state());
  }
  jlongArray out = env->NewLongArray(5);
  if (out) env->SetLongArrayRegion(out, 0, 5, v);
  return out;
}

// 语义深度控制(负值=不改)→ session->set_controls(各 driver 内部翻 XU)。
JNIEXPORT jboolean JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraSetControls(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jfloat confThr, jint temporal, jint spatial,
        jint ae, jint gain, jint irCurrent) {
  auto* s = reinterpret_cast<ICameraSession*>(handle);
  if (!s) return JNI_FALSE;
  DepthControls c;
  c.confidence_threshold = confThr;
  c.temporal_denoise = temporal;
  c.spatial_denoise = spatial;
  c.auto_exposure = ae;
  c.gain = gain;
  c.ir_current = irCurrent;
  return s->set_controls(c) ? JNI_TRUE : JNI_FALSE;
}

// 不开流即取设备能力 JSON(vendor/model/has_*/metric/profiles),供 UI 显型号/档位。
JNIEXPORT jstring JNICALL
Java_io_gomob_nativebridge_NativeBridge_cameraCapabilitiesJson(
        JNIEnv* env, jobject /*thiz*/, jint vid, jint pid) {
  ICameraDriver* drv = Registry().MatchByUsbId(UsbId{static_cast<uint16_t>(vid),
                                                     static_cast<uint16_t>(pid)});
  if (!drv) return env->NewStringUTF("{}");
  const auto c = drv->capabilities();
  std::string j = "{";
  j += "\"vendor\":\"" + c.vendor + "\",";
  j += "\"model\":\"" + c.model + "\",";
  j += "\"has_color\":" + std::string(c.has_color ? "true" : "false") + ",";
  j += "\"has_depth\":" + std::string(c.has_depth ? "true" : "false") + ",";
  j += "\"has_confidence\":" + std::string(c.has_confidence ? "true" : "false") + ",";
  j += "\"has_ir\":" + std::string(c.has_ir ? "true" : "false") + ",";
  j += "\"depth_is_metric_onchip\":" + std::string(c.depth_is_metric_onchip ? "true" : "false");
  if (!c.depth_profiles.empty()) {
    const auto& p = c.depth_profiles[0];
    j += ",\"depth_w\":" + std::to_string(p.width) + ",\"depth_h\":" + std::to_string(p.height) +
         ",\"depth_fps\":" + std::to_string(p.fps);
  }
  j += "}";
  return env->NewStringUTF(j.c_str());
}

}  // extern "C"
