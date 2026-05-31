// gomob native — JNI 入口集中点
//
// 设计约束：Kotlin 侧只通过 io.gomob.nativebridge.NativeBridge 调进来；
//           所有 native 模块（depth / fusion / reconstruction / vin / calibration）
//           的 JNI 导出都集中在本文件，避免符号在多个 .cpp 散落，导致链接顺序难以控制。
//
// 当前形态：
//   - depth/fusion 已实施
//   - reconstruction/vin/calibration 是占位 stub，业务实施在 M2/M3/M4.* 阶段；
//     stub 的 JNI 入口先把 NativeBridge.kt 接口铺到位，让 Kotlin 业务侧的代码能编过

#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <libusb-1.0/libusb.h>

#include "reconstruction/icp.h"
#include "berxel/include/gomob_berxel_protocol_sonix.h"

#define LOG_TAG "gomob_native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace gomob {
namespace depth {
    std::vector<float> ProjectToPointCloud(
        const int16_t* depth, int width, int height,
        double fx, double fy, double cx, double cy);
}
namespace fusion {
    std::vector<uint8_t> Colorize(
        const float* points, size_t pointCount,
        const uint8_t* rgb, int rgbWidth, int rgbHeight,
        double fx, double fy, double cx, double cy,
        const double* rotation, const double* translation);
}
namespace reconstruction {
    struct ScanSession;
    ScanSession* SessionCreate(float voxel_size_mm, float grid_extent_mm, float grid_center_z_mm);
    int SessionIngest(
        ScanSession* s,
        const uint16_t* depth_mm, int width, int height,
        double fx, double fy, double cx, double cy,
        const float* pose7, const uint8_t* conf = nullptr);
    bool SessionFinalize(ScanSession* s, const char* out_dir, int* out_stats3);
    void SessionClose(ScanSession* s);
    std::vector<float> SessionPeekVertices(ScanSession* s, int max_vertices);
    // finalize 后 UI 端拉 mesh —— 返回 const ref 避免拷贝；JNI 端再 SetXxxArrayRegion 拷一次
    const std::vector<float>& SessionMeshVertices(ScanSession* s);
    const std::vector<float>& SessionMeshNormals(ScanSession* s);
    const std::vector<uint32_t>& SessionMeshIndices(ScanSession* s);
    // IcpRegister 的真实实现在 reconstruction/icp.h，返回 IcpResult；本文件 #include 了 icp.h
}
namespace vin {
    struct RectifyResult;
    extern RectifyResult Rectify(
        const uint8_t* color_bgr, int color_w, int color_h,
        const uint16_t* depth_mm, int depth_w, int depth_h,
        const double* color_intr,
        const int* roi_box,
        const float* config);
}
namespace calibration {
    std::vector<float> DetectCharuco(
        const uint8_t* gray, int width, int height,
        const int* board_spec);
    std::vector<double> CalibrateCamera(
        const float* corners, const int* corners_per_image, int image_count,
        int width, int height, const int* board_spec);
    std::vector<double> StereoCalibrate(
        const float* color_corners, const float* depth_corners,
        const int* corners_per_image, int image_count,
        const double* color_intr, const double* depth_intr,
        int width, int height);
}
}

namespace {

void ThrowNativeException(JNIEnv* env, jint code, const char* message) {
    jclass cls = env->FindClass("io/gomob/nativebridge/NativeException");
    if (cls == nullptr) return;
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
    if (ctor == nullptr) return;
    jstring jmsg = env->NewStringUTF(message);
    auto exc = (jthrowable) env->NewObject(cls, ctor, code, jmsg);
    env->Throw(exc);
}

} // anonymous

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_gomob_nativebridge_NativeBridge_version(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("gomob_native 0.2.0");
}

// ===== depth/* =====

JNIEXPORT jfloatArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_depthToPointCloud(
        JNIEnv* env, jobject /*thiz*/,
        jshortArray depth, jint width, jint height,
        jdouble fx, jdouble fy, jdouble cx, jdouble cy) {
    jsize len = env->GetArrayLength(depth);
    if (len != width * height) {
        LOGE("depth length %d != %d*%d", len, width, height);
        ThrowNativeException(env, 2, "depth length mismatch");
        return nullptr;
    }
    jshort* depthData = env->GetShortArrayElements(depth, nullptr);
    auto cloud = gomob::depth::ProjectToPointCloud(
        reinterpret_cast<const int16_t*>(depthData), width, height, fx, fy, cx, cy);
    env->ReleaseShortArrayElements(depth, depthData, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(cloud.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(cloud.size()), cloud.data());
    return result;
}

// ===== fusion/* =====

JNIEXPORT jbyteArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_colorizePointCloud(
        JNIEnv* env, jobject /*thiz*/,
        jfloatArray points,
        jbyteArray rgb, jint rgbWidth, jint rgbHeight,
        jdouble rgbFx, jdouble rgbFy, jdouble rgbCx, jdouble rgbCy,
        jdoubleArray rotationRowMajor, jdoubleArray translation) {
    jsize pointBytes = env->GetArrayLength(points);
    jfloat* pointsData = env->GetFloatArrayElements(points, nullptr);
    jbyte* rgbData = env->GetByteArrayElements(rgb, nullptr);
    jdouble* rotData = env->GetDoubleArrayElements(rotationRowMajor, nullptr);
    jdouble* tData = env->GetDoubleArrayElements(translation, nullptr);

    auto colored = gomob::fusion::Colorize(
        pointsData, pointBytes / 3,
        reinterpret_cast<const uint8_t*>(rgbData), rgbWidth, rgbHeight,
        rgbFx, rgbFy, rgbCx, rgbCy,
        rotData, tData);

    env->ReleaseFloatArrayElements(points, pointsData, JNI_ABORT);
    env->ReleaseByteArrayElements(rgb, rgbData, JNI_ABORT);
    env->ReleaseDoubleArrayElements(rotationRowMajor, rotData, JNI_ABORT);
    env->ReleaseDoubleArrayElements(translation, tData, JNI_ABORT);

    jbyteArray result = env->NewByteArray(static_cast<jsize>(colored.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(colored.size()),
                            reinterpret_cast<const jbyte*>(colored.data()));
    return result;
}

// ===== reconstruction/* — 占位 =====

JNIEXPORT jfloatArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_icpRegister(
        JNIEnv* env, jobject /*thiz*/,
        jfloatArray src, jfloatArray dst, jfloatArray initialPose) {
    jsize srcLen = env->GetArrayLength(src);
    jsize dstLen = env->GetArrayLength(dst);
    jsize initLen = env->GetArrayLength(initialPose);
    if (initLen != 7) {
        ThrowNativeException(env, 2, "initialPose must be 7 floats");
        return nullptr;
    }
    if (srcLen % 3 != 0 || dstLen % 3 != 0) {
        ThrowNativeException(env, 2, "src/dst length must be multiple of 3");
        return nullptr;
    }
    jfloat* srcData = env->GetFloatArrayElements(src, nullptr);
    jfloat* dstData = env->GetFloatArrayElements(dst, nullptr);
    jfloat* initData = env->GetFloatArrayElements(initialPose, nullptr);

    gomob::reconstruction::IcpResult ir = gomob::reconstruction::IcpRegister(
        srcData, static_cast<size_t>(srcLen / 3),
        dstData, static_cast<size_t>(dstLen / 3),
        initData);

    env->ReleaseFloatArrayElements(src, srcData, JNI_ABORT);
    env->ReleaseFloatArrayElements(dst, dstData, JNI_ABORT);
    env->ReleaseFloatArrayElements(initialPose, initData, JNI_ABORT);

    if (ir.status == gomob::reconstruction::IcpResultStatus::DegenerateInput) {
        ThrowNativeException(env, 101, "ICP degenerate input (pairs < 6)");
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(7);
    env->SetFloatArrayRegion(result, 0, 7, ir.pose7.data());
    return result;
}

JNIEXPORT jlong JNICALL
Java_io_gomob_nativebridge_NativeBridge_scanSessionCreate(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jfloat voxelSizeMm, jfloat gridExtentMm, jfloat gridCenterZMm) {
    auto* s = gomob::reconstruction::SessionCreate(voxelSizeMm, gridExtentMm, gridCenterZMm);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_scanSessionIngest(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jobject depthBuffer, jint width, jint height,
        jdoubleArray intr, jfloatArray pose, jobject confBuffer) {
    auto* s = reinterpret_cast<gomob::reconstruction::ScanSession*>(handle);
    if (!s) {
        ThrowNativeException(env, 102, "session handle invalid");
        return -1;
    }
    auto* depthPtr = static_cast<uint16_t*>(env->GetDirectBufferAddress(depthBuffer));
    if (!depthPtr) {
        ThrowNativeException(env, 2, "depthBuffer not direct");
        return -1;
    }
    if (env->GetArrayLength(intr) != 4 || env->GetArrayLength(pose) != 7) {
        ThrowNativeException(env, 2, "intr must be 4 doubles, pose must be 7 floats");
        return -1;
    }
    // confBuffer 可选(null=均权)。零拷贝直读 DirectByteBuffer；尺寸需 >= width*height(uint8/像素),
    // 不足则忽略当作均权(宁退化勿越界)。
    const uint8_t* confPtr = nullptr;
    if (confBuffer) {
        auto* p = static_cast<uint8_t*>(env->GetDirectBufferAddress(confBuffer));
        if (p && env->GetDirectBufferCapacity(confBuffer) >=
                     static_cast<jlong>(width) * height) {
            confPtr = p;
        }
    }
    jdouble* intrData = env->GetDoubleArrayElements(intr, nullptr);
    jfloat* poseData = env->GetFloatArrayElements(pose, nullptr);
    int kfCount = gomob::reconstruction::SessionIngest(
        s, depthPtr, width, height,
        intrData[0], intrData[1], intrData[2], intrData[3],
        poseData, confPtr);
    env->ReleaseDoubleArrayElements(intr, intrData, JNI_ABORT);
    env->ReleaseFloatArrayElements(pose, poseData, JNI_ABORT);
    return kfCount;
}

JNIEXPORT jintArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_scanSessionFinalize(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring outDir) {
    auto* s = reinterpret_cast<gomob::reconstruction::ScanSession*>(handle);
    if (!s) {
        ThrowNativeException(env, 102, "session handle invalid");
        return nullptr;
    }
    const char* outDirC = env->GetStringUTFChars(outDir, nullptr);
    int stats[3] = {0, 0, 0};
    bool ok = gomob::reconstruction::SessionFinalize(s, outDirC, stats);
    env->ReleaseStringUTFChars(outDir, outDirC);
    if (!ok) {
        ThrowNativeException(env, 4, "session finalize failed");
        return nullptr;
    }
    jintArray result = env->NewIntArray(3);
    env->SetIntArrayRegion(result, 0, 3, stats);
    return result;
}

JNIEXPORT void JNICALL
Java_io_gomob_nativebridge_NativeBridge_scanSessionClose(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* s = reinterpret_cast<gomob::reconstruction::ScanSession*>(handle);
    gomob::reconstruction::SessionClose(s);
}

JNIEXPORT jfloatArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_scanSessionPeekVertices(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jint maxVertices) {
    auto* s = reinterpret_cast<gomob::reconstruction::ScanSession*>(handle);
    if (!s) {
        // handle 已 close → 返空数组（不抛异常，避免高频调用产生异常风暴）
        return env->NewFloatArray(0);
    }
    auto vs = gomob::reconstruction::SessionPeekVertices(s, maxVertices);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(vs.size()));
    if (!vs.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(vs.size()), vs.data());
    }
    return result;
}

// finalize 后 UI 端拉 mesh —— vertex / normal / index 三个独立 JNI 调用，每次返回完整数据
// 拷贝一份给 Kotlin。session 未 finalize / 已 close → 返空数组（不抛异常）。

JNIEXPORT jfloatArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_scanSessionMeshVertices(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* s = reinterpret_cast<gomob::reconstruction::ScanSession*>(handle);
    if (!s) return env->NewFloatArray(0);
    const auto& vs = gomob::reconstruction::SessionMeshVertices(s);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(vs.size()));
    if (!vs.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(vs.size()), vs.data());
    }
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_scanSessionMeshNormals(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* s = reinterpret_cast<gomob::reconstruction::ScanSession*>(handle);
    if (!s) return env->NewFloatArray(0);
    const auto& ns = gomob::reconstruction::SessionMeshNormals(s);
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(ns.size()));
    if (!ns.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(ns.size()), ns.data());
    }
    return result;
}

JNIEXPORT jintArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_scanSessionMeshIndices(
        JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* s = reinterpret_cast<gomob::reconstruction::ScanSession*>(handle);
    if (!s) return env->NewIntArray(0);
    const auto& idx = gomob::reconstruction::SessionMeshIndices(s);
    jintArray result = env->NewIntArray(static_cast<jsize>(idx.size()));
    if (!idx.empty()) {
        // uint32_t → jint 重解释 OK：mesh 顶点数 ≤ 2^31，indices 不会超 INT_MAX
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(idx.size()),
                               reinterpret_cast<const jint*>(idx.data()));
    }
    return result;
}

// ===== vin/* — 占位 =====

JNIEXPORT jobject JNICALL
Java_io_gomob_nativebridge_NativeBridge_vinRectify(
        JNIEnv* env, jobject /*thiz*/,
        jobject /*colorBgr*/, jint /*colorWidth*/, jint /*colorHeight*/,
        jobject /*depth16Mm*/, jint /*depthWidth*/, jint /*depthHeight*/,
        jdoubleArray /*colorIntr*/, jintArray /*roiBox*/, jfloatArray /*config*/) {
    // 当前 stub：直接抛 NativeException(NOT_IMPLEMENTED) — 让业务侧能感知接口未实施
    // M4.* 阶段实施真实拓印逻辑
    ThrowNativeException(env, 1, "vinRectify: not implemented yet (M4.*)");
    return nullptr;
}

// ===== berxel/* — Sonix XU 协议入口（M1.6.5 复现层 + M1.6.6 NDK port 准备） =====
//
// 入口：Kotlin 经 UsbDeviceConnection.getFileDescriptor() 把 fd 交给我们；
//       libusb_wrap_sys_device 把它包成 libusb device handle；claim_interface(0)；
//       调 BerxelProtocolSonix.asic_read(reg)；用完释放。
//
// 错误码（< 0）：
//   -1001 libusb_init / set_option 失败
//   -1002 libusb_wrap_sys_device 失败
//   -1003 libusb_claim_interface 失败
//   -1004 asic_read 返回 < 0
//   返回值 ∈ [0, 255] = 寄存器值

namespace {
// libusb_control_transfer 的指针签名跟 BerxelProtocolSonix::ControlTransferFn 几乎一致，
// 但 uint8_t* vs unsigned char* / uint32_t vs unsigned int 严格意义不是同一函数类型，
// 走一层 adapter 把签名对齐，避免依赖隐式转换。
int libusb_control_transfer_adapter(libusb_device_handle* handle,
                                     uint8_t bmRequestType, uint8_t bRequest,
                                     uint16_t wValue, uint16_t wIndex,
                                     uint8_t* data, uint16_t wLength,
                                     uint32_t timeout) {
    return libusb_control_transfer(handle, bmRequestType, bRequest, wValue, wIndex,
                                   data, wLength, static_cast<unsigned int>(timeout));
}
} // anonymous

extern "C" JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelSonixAsicRead(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jint usbFd, jint interfaceNumber, jint regAddr, jint timeoutMs) {
    LOGI("berxelSonixAsicRead fd=%d iface=%d reg=0x%04x timeout=%dms",
         usbFd, interfaceNumber, static_cast<unsigned>(regAddr), timeoutMs);

    libusb_context* ctx = nullptr;
    // Android 下 libusb 不能自己枚举，必须 NO_DEVICE_DISCOVERY；
    // 配合 libusb_wrap_sys_device 接管 Java 拿到的 usbfs fd。
    int rc = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("libusb_set_option(NO_DEVICE_DISCOVERY) rc=%d", rc);
        return -1001;
    }
    rc = libusb_init(&ctx);
    if (rc != LIBUSB_SUCCESS) {
        LOGE("libusb_init rc=%d", rc);
        return -1001;
    }

    libusb_device_handle* handle = nullptr;
    rc = libusb_wrap_sys_device(ctx, static_cast<intptr_t>(usbFd), &handle);
    if (rc != LIBUSB_SUCCESS || handle == nullptr) {
        LOGE("libusb_wrap_sys_device rc=%d", rc);
        libusb_exit(ctx);
        return -1002;
    }

    // vivo Funtouch 实测：kernel 会把 iHawk100RS USB interface 0 当作 HID 设备绑给
    // input subsystem（/dev/input/event*），导致 libusb_claim_interface(0) → BUSY。
    // 必须先 detach 才能 claim。
    int kdActive = libusb_kernel_driver_active(handle, interfaceNumber);
    LOGI("kernel_driver_active(%d)=%d", interfaceNumber, kdActive);
    if (kdActive == 1) {
        int detRc = libusb_detach_kernel_driver(handle, interfaceNumber);
        LOGI("detach_kernel_driver(%d) rc=%d", interfaceNumber, detRc);
    }

    rc = libusb_claim_interface(handle, interfaceNumber);
    if (rc == LIBUSB_ERROR_BUSY) {
        // 同进程 Berxel SDK 内部的另一份 fd 还 claim 着此 interface。
        // libusb_set_configuration(-1) 让 kernel 把所有 interface 解绑，再 set(1) 重配，
        // 等价于"软 reset" — 不影响 firmware 状态，但能强收同进程残留 claim。
        LOGE("claim_interface(%d) BUSY → 尝试 set_configuration force release", interfaceNumber);
        int cfgRc1 = libusb_set_configuration(handle, -1);
        int cfgRc2 = libusb_set_configuration(handle, 1);
        LOGI("set_configuration(-1)=%d set_configuration(1)=%d", cfgRc1, cfgRc2);
        rc = libusb_claim_interface(handle, interfaceNumber);
    }
    if (rc != LIBUSB_SUCCESS) {
        LOGE("libusb_claim_interface(%d) rc=%d (%s)",
             interfaceNumber, rc, libusb_error_name(rc));
        libusb_close(handle);
        libusb_exit(ctx);
        return -1003;
    }

    gomob::berxel::BerxelProtocolSonix proto(handle, libusb_control_transfer_adapter);
    int value = proto.asic_read(static_cast<uint16_t>(regAddr & 0xffff),
                                static_cast<uint32_t>(timeoutMs));
    LOGI("asic_read(0x%04x) -> %d", static_cast<unsigned>(regAddr & 0xffff), value);

    libusb_release_interface(handle, interfaceNumber);
    // 把 interface 还给 kernel HID driver，避免 Android input subsystem 出现 stale 状态
    if (kdActive == 1) {
        int attRc = libusb_attach_kernel_driver(handle, interfaceNumber);
        LOGI("attach_kernel_driver(%d) rc=%d", interfaceNumber, attRc);
    }
    libusb_close(handle);
    libusb_exit(ctx);

    if (value < 0) {
        // BerxelProtocolSonix 把 libusb 错误码直传出来；统一映射到 -1004 段
        return -1004;
    }
    return value;
}

// ===== berxel/* — USB descriptor dump（M1.6.6 起步） =====
//
// 通过 fd + libusb_wrap_sys_device 把 device 接管，然后用 libusb_get_active_config_descriptor
// 把 P100R3 主/companion 节点的完整 USB descriptor 序列化成可读字符串返回 Kotlin。
// 用途：M1.6.6 后续所有入口（OpenStream / SetStreamFlagMode / ReadFrame）都要拿正确的
// interface/altsetting/endpoint 号；descriptor dump 是这些数字的真理源。

namespace {

void appendf(std::string& s, const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0) s.append(buf, std::min(n, static_cast<int>(sizeof(buf) - 1)));
}

const char* xferTypeName(uint8_t bmAttributes) {
    switch (bmAttributes & 0x03) {
        case 0: return "CONTROL";
        case 1: return "ISOC";
        case 2: return "BULK";
        case 3: return "INTR";
        default: return "?";
    }
}

void dumpDeviceDescriptor(std::string& out, libusb_device* dev) {
    libusb_device_descriptor dd{};
    if (libusb_get_device_descriptor(dev, &dd) != 0) {
        out += "[device descriptor get failed]\n";
        return;
    }
    appendf(out,
        "Device: VID=0x%04x PID=0x%04x bcdUSB=0x%04x bcdDevice=0x%04x class=%d/%d/%d ep0mps=%d numConfigs=%d\n",
        dd.idVendor, dd.idProduct, dd.bcdUSB, dd.bcdDevice,
        dd.bDeviceClass, dd.bDeviceSubClass, dd.bDeviceProtocol,
        dd.bMaxPacketSize0, dd.bNumConfigurations);
}

void dumpEndpoint(std::string& out, const libusb_endpoint_descriptor& ep, int ifaceNum, int alt) {
    appendf(out,
        "      EP 0x%02x dir=%s type=%s mps=%d interval=%d\n",
        ep.bEndpointAddress,
        (ep.bEndpointAddress & 0x80) ? "IN" : "OUT",
        xferTypeName(ep.bmAttributes),
        ep.wMaxPacketSize, ep.bInterval);
    if (ep.extra_length > 0) {
        appendf(out, "        extra(%d bytes):", ep.extra_length);
        for (int i = 0; i < ep.extra_length && i < 64; ++i) {
            appendf(out, " %02x", ep.extra[i]);
        }
        out += "\n";
    }
    (void)ifaceNum; (void)alt;
}

void dumpInterfaceAlt(std::string& out, const libusb_interface_descriptor& alt) {
    appendf(out,
        "    Alt %d: class=%d/%d/%d numEPs=%d iIface=%d\n",
        alt.bAlternateSetting,
        alt.bInterfaceClass, alt.bInterfaceSubClass, alt.bInterfaceProtocol,
        alt.bNumEndpoints, alt.iInterface);
    if (alt.extra_length > 0) {
        appendf(out, "      classExtra(%d bytes):\n", alt.extra_length);
        // 32 字节/行 hex；upper bound 4096 保护
        const int kMax = std::min(alt.extra_length, 4096);
        for (int i = 0; i < kMax; i += 32) {
            appendf(out, "       %04x:", i);
            for (int j = i; j < i + 32 && j < kMax; ++j) {
                appendf(out, " %02x", alt.extra[j]);
            }
            out += "\n";
        }
    }
    for (int e = 0; e < alt.bNumEndpoints; ++e) {
        dumpEndpoint(out, alt.endpoint[e], alt.bInterfaceNumber, alt.bAlternateSetting);
    }
}

void dumpConfigDescriptor(std::string& out, libusb_config_descriptor* cfg) {
    appendf(out, "Config %d: numIfaces=%d wTotalLength=%d bmAttributes=0x%02x bMaxPower=%dmA\n",
            cfg->bConfigurationValue, cfg->bNumInterfaces,
            cfg->wTotalLength, cfg->bmAttributes, cfg->MaxPower * 2);
    for (int i = 0; i < cfg->bNumInterfaces; ++i) {
        const auto& iface = cfg->interface[i];
        if (iface.num_altsetting > 0) {
            appendf(out, "  Interface %d (alts=%d):\n",
                    iface.altsetting[0].bInterfaceNumber, iface.num_altsetting);
        }
        for (int a = 0; a < iface.num_altsetting; ++a) {
            dumpInterfaceAlt(out, iface.altsetting[a]);
        }
    }
}

} // anonymous

extern "C" JNIEXPORT jstring JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelUsbDescriptorDump(
        JNIEnv* env, jobject /*thiz*/, jint usbFd) {
    LOGI("berxelUsbDescriptorDump fd=%d", usbFd);

    libusb_context* ctx = nullptr;
    int rc = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (rc != LIBUSB_SUCCESS) {
        return env->NewStringUTF("ERR set_option(NO_DEVICE_DISCOVERY) failed");
    }
    if (libusb_init(&ctx) != 0) {
        return env->NewStringUTF("ERR libusb_init failed");
    }

    libusb_device_handle* handle = nullptr;
    rc = libusb_wrap_sys_device(ctx, static_cast<intptr_t>(usbFd), &handle);
    if (rc != LIBUSB_SUCCESS || handle == nullptr) {
        std::string err = "ERR wrap_sys_device rc=" + std::to_string(rc);
        libusb_exit(ctx);
        return env->NewStringUTF(err.c_str());
    }

    std::string out;
    out.reserve(4096);
    libusb_device* dev = libusb_get_device(handle);
    dumpDeviceDescriptor(out, dev);

    libusb_device_descriptor dd{};
    libusb_get_device_descriptor(dev, &dd);
    for (uint8_t c = 0; c < dd.bNumConfigurations; ++c) {
        libusb_config_descriptor* cfg = nullptr;
        if (libusb_get_config_descriptor(dev, c, &cfg) == 0 && cfg != nullptr) {
            dumpConfigDescriptor(out, cfg);
            libusb_free_config_descriptor(cfg);
        } else {
            appendf(out, "[config %d: get failed]\n", c);
        }
    }

    libusb_close(handle);
    libusb_exit(ctx);

    LOGI("descriptor dump %zu bytes", out.size());
    return env->NewStringUTF(out.c_str());
}

// ===== berxel/* — DeviceSession 长生命周期持有（M1.6.6 核心入口） =====
//
// 设计：Kotlin 拿到 sessionHandle (Long) 之后，所有后续操作（SonixInit / OpenStream /
// ReadFrame / CloseStream / CloseDevice）都带 handle，避免反复 init libusb_context +
// wrap_sys_device + claim_interface。所有错误码 < 0：
//   -2001 init / set_option 失败
//   -2002 wrap_sys_device 失败
//   -2003 claim_interface 失败
//   -2004 alloc session 失败
//   -2005 invalid session handle
//   -2006 control transfer 失败
// 正值：sessionHandle = reinterpret_cast<jlong>(pointer)

namespace gomob::berxel {

struct StreamingState; // fwd

struct DeviceSession {
    libusb_context* ctx = nullptr;
    libusb_device_handle* handle = nullptr;
    int vc_interface = -1;
    int vs_interface = -1;
    bool vc_kernel_was_active = false;
    bool vs_kernel_was_active = false;
    std::unique_ptr<BerxelProtocolSonix> proto;
    std::unique_ptr<StreamingState> stream;
};

// 单个 BULK transfer 的 owning struct（生命周期 = 会话）
struct BulkXfer {
    libusb_transfer* xfer = nullptr;
    std::vector<uint8_t> buffer;
    StreamingState* owner = nullptr;
    bool submitted = false;
    bool failed_terminal = false;  // 进入 NO_DEVICE / CANCELED 时停止再 submit
};

struct StreamingState {
    DeviceSession* session = nullptr;
    uint8_t bulk_in_ep = 0;
    int max_packet = 0;
    int transfer_size = 0;
    std::vector<std::unique_ptr<BulkXfer>> xfers;

    std::thread event_thread;
    std::atomic<bool> stop_flag{false};

    // 完成的帧队列：单元 = (vector<uint8_t> raw payload incl UVC header)
    std::mutex queue_mu;
    std::condition_variable queue_cv;
    std::deque<std::vector<uint8_t>> ready_frames;

    // stats
    std::atomic<uint64_t> total_callbacks{0};
    std::atomic<uint64_t> total_bytes{0};
    std::atomic<uint64_t> total_errors{0};
};

} // namespace gomob::berxel

namespace {

void LIBUSB_CALL bulkTransferCallback(libusb_transfer* xfer) {
    auto* slot = static_cast<gomob::berxel::BulkXfer*>(xfer->user_data);
    auto* st = slot->owner;
    st->total_callbacks++;
    if (xfer->status == LIBUSB_TRANSFER_COMPLETED && xfer->actual_length > 0) {
        st->total_bytes += xfer->actual_length;
        std::vector<uint8_t> copy(xfer->buffer, xfer->buffer + xfer->actual_length);
        {
            std::lock_guard<std::mutex> lk(st->queue_mu);
            if (st->ready_frames.size() > 32) st->ready_frames.pop_front();
            st->ready_frames.push_back(std::move(copy));
        }
        st->queue_cv.notify_one();
    } else if (xfer->status != LIBUSB_TRANSFER_COMPLETED) {
        st->total_errors++;
        // 前 4 次错误打详细 status；之后只计数，避免 logcat 洪水
        if (st->total_errors.load() <= 4) {
            LOGE("bulk cb status=%d actual=%d", xfer->status, xfer->actual_length);
        }
        if (xfer->status == LIBUSB_TRANSFER_NO_DEVICE ||
            xfer->status == LIBUSB_TRANSFER_CANCELLED) {
            slot->failed_terminal = true;
        }
    }
    // 重新提交（除非进 stop / terminal）
    if (!st->stop_flag.load() && !slot->failed_terminal) {
        int rc = libusb_submit_transfer(xfer);
        if (rc != 0) {
            LOGE("resubmit BULK rc=%d", rc);
            slot->failed_terminal = true;
        }
    } else {
        slot->submitted = false;
    }
}

// 跑 libusb 事件循环；stop_flag 起后退出
void eventLoop(gomob::berxel::StreamingState* st) {
    timeval tv{0, 100 * 1000};  // 100ms
    while (!st->stop_flag.load()) {
        int rc = libusb_handle_events_timeout(st->session->ctx, &tv);
        if (rc != 0 && rc != LIBUSB_ERROR_INTERRUPTED) {
            LOGE("handle_events rc=%d", rc);
        }
    }
}

} // anonymous

namespace {

int controlTransferAdapter(libusb_device_handle* handle,
                           uint8_t bmRequestType, uint8_t bRequest,
                           uint16_t wValue, uint16_t wIndex,
                           uint8_t* data, uint16_t wLength,
                           uint32_t timeout) {
    return libusb_control_transfer(handle, bmRequestType, bRequest, wValue, wIndex,
                                   data, wLength, static_cast<unsigned int>(timeout));
}

// 关 + 释放整个 session（claim 过的 interface 先 release，detach 过的 driver attach 回去）
// 先停 stream，再 release interfaces / detach handle / exit ctx。
void closeSession(gomob::berxel::DeviceSession* s) {
    if (!s) return;
    if (s->stream) {
        auto* st = s->stream.get();
        st->stop_flag.store(true);
        for (auto& slot : st->xfers) {
            if (slot && slot->xfer && slot->submitted) {
                libusb_cancel_transfer(slot->xfer);
            }
        }
        if (st->event_thread.joinable()) st->event_thread.join();
        for (auto& slot : st->xfers) {
            if (slot && slot->xfer) {
                libusb_free_transfer(slot->xfer);
                slot->xfer = nullptr;
            }
        }
        s->stream.reset();
    }
    if (s->handle) {
        if (s->vs_interface >= 0) {
            libusb_release_interface(s->handle, s->vs_interface);
            if (s->vs_kernel_was_active) {
                libusb_attach_kernel_driver(s->handle, s->vs_interface);
            }
        }
        if (s->vc_interface >= 0) {
            libusb_release_interface(s->handle, s->vc_interface);
            if (s->vc_kernel_was_active) {
                libusb_attach_kernel_driver(s->handle, s->vc_interface);
            }
        }
        libusb_close(s->handle);
    }
    if (s->ctx) libusb_exit(s->ctx);
    delete s;
}

// 单次 claim：检测 kernel driver → 必要时 detach → claim。失败返 libusb rc (<0)。
int claimWithDetach(libusb_device_handle* handle, int ifaceNum, bool* wasActive) {
    int kdActive = libusb_kernel_driver_active(handle, ifaceNum);
    *wasActive = (kdActive == 1);
    if (kdActive == 1) {
        int detRc = libusb_detach_kernel_driver(handle, ifaceNum);
        LOGI("detach_kernel_driver(%d) rc=%d", ifaceNum, detRc);
        if (detRc != 0 && detRc != LIBUSB_ERROR_NOT_FOUND) {
            return detRc;
        }
    }
    int rc = libusb_claim_interface(handle, ifaceNum);
    if (rc != 0) {
        LOGE("claim_interface(%d) rc=%d (%s)", ifaceNum, rc, libusb_error_name(rc));
    }
    return rc;
}

} // anonymous

// 失败返 0L（pointer 永远非 0），Kotlin 直接判 handle == 0L 即可；
// 详细 errno 通过 LOGE 落 logcat，调用方不靠返回值数值区分。
extern "C" JNIEXPORT jlong JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelOpenDeviceByFd(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jint usbFd, jint vcInterface, jint vsInterface) {
    LOGI("berxelOpenDeviceByFd fd=%d vc=%d vs=%d", usbFd, vcInterface, vsInterface);

    libusb_context* ctx = nullptr;
    if (libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY) != 0) {
        LOGE("set_option(NO_DEVICE_DISCOVERY) failed (-2001)");
        return 0L;
    }
    if (libusb_init(&ctx) != 0) {
        LOGE("libusb_init failed (-2001)");
        return 0L;
    }
    libusb_device_handle* handle = nullptr;
    int rc = libusb_wrap_sys_device(ctx, static_cast<intptr_t>(usbFd), &handle);
    if (rc != LIBUSB_SUCCESS || handle == nullptr) {
        LOGE("wrap_sys_device rc=%d (-2002)", rc);
        libusb_exit(ctx);
        return 0L;
    }

    auto* s = new (std::nothrow) gomob::berxel::DeviceSession();
    if (!s) {
        libusb_close(handle);
        libusb_exit(ctx);
        LOGE("alloc session failed (-2004)");
        return 0L;
    }
    s->ctx = ctx;
    s->handle = handle;

    if (vcInterface >= 0) {
        rc = claimWithDetach(handle, vcInterface, &s->vc_kernel_was_active);
        if (rc != 0) {
            LOGE("claim vc=%d failed rc=%d (-2003)", vcInterface, rc);
            closeSession(s);
            return 0L;
        }
        s->vc_interface = vcInterface;
    }
    if (vsInterface >= 0 && vsInterface != vcInterface) {
        rc = claimWithDetach(handle, vsInterface, &s->vs_kernel_was_active);
        if (rc != 0) {
            LOGE("claim vs=%d failed rc=%d (-2003)", vsInterface, rc);
            closeSession(s);
            return 0L;
        }
        s->vs_interface = vsInterface;
    }

    s->proto = std::make_unique<gomob::berxel::BerxelProtocolSonix>(handle, controlTransferAdapter);

    LOGI("session opened ptr=%p vc=%d vs=%d", (void*)s, vcInterface, vsInterface);
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT void JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelCloseDevice(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong sessionHandle) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    LOGI("berxelCloseDevice ptr=%p", (void*)s);
    closeSession(s);
}

// 绕过 UsbManager 直接 open /dev/bus/usb 路径，返 fd。
// 用法：Android 上 kernel UVC driver 会把 master 节点(class=14)从
// UsbManager.deviceList 过滤掉，但 device_permissions ACL 已经授给 app uid，
// 直接 open(/dev/bus/usb/XXX/YYY) 应该成功。失败返 -errno。
// 调用方拿到 fd 后传给 berxelOpenDeviceByFd 走 libusb_wrap_sys_device。
extern "C" JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelOpenUsbPath(
        JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return -1;
    int fd = ::open(path, O_RDWR);
    int err = errno;
    LOGI("berxelOpenUsbPath path=%s fd=%d errno=%d", path, fd, fd < 0 ? err : 0);
    env->ReleaseStringUTFChars(jpath, path);
    if (fd < 0) return -err;
    return fd;
}

// 会话级 asic_read（替换 per-call init/exit 版本，性能更好 + 不抢 ctx）
extern "C" JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelSessionAsicRead(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong sessionHandle, jint regAddr, jint timeoutMs) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    if (!s || !s->proto) return -2005;
    int v = s->proto->asic_read(static_cast<uint16_t>(regAddr & 0xffff),
                                static_cast<uint32_t>(timeoutMs));
    LOGI("session asic_read[0x%04x] = %d", regAddr & 0xffff, v);
    return v >= 0 ? v : -2006;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelSessionAsicWrite(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong sessionHandle, jint regAddr, jint value, jint timeoutMs) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    if (!s || !s->proto) return -2005;
    int rc = s->proto->asic_write(static_cast<uint16_t>(regAddr & 0xffff),
                                  static_cast<uint8_t>(value & 0xff),
                                  static_cast<uint32_t>(timeoutMs));
    return rc >= 0 ? 0 : -2006;
}

// 通用 batch_cmd —— 把 jbyteArray 透传给 Sonix。stream 启动序列会用 selector 0x19/0x1e。
extern "C" JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelSessionBatchCmd(
        JNIEnv* env, jobject /*thiz*/,
        jlong sessionHandle, jint selector, jbyteArray payload, jint timeoutMs) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    if (!s || !s->proto) return -2005;
    if (!payload) return -2006;
    jsize len = env->GetArrayLength(payload);
    if (len <= 0) return -2006;
    jbyte* buf = env->GetByteArrayElements(payload, nullptr);
    int rc = s->proto->batch_cmd(static_cast<uint8_t>(selector & 0xff),
                                 reinterpret_cast<const uint8_t*>(buf),
                                 static_cast<uint16_t>(len),
                                 static_cast<uint32_t>(timeoutMs));
    env->ReleaseByteArrayElements(payload, buf, JNI_ABORT);
    LOGI("session batch_cmd selector=0x%02x len=%d rc=%d", selector, len, rc);
    return rc >= 0 ? rc : -2006;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelSessionXuGetCur(
        JNIEnv* env, jobject /*thiz*/,
        jlong sessionHandle, jint selector, jint length, jint timeoutMs) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    if (!s || !s->proto) return nullptr;
    if (length <= 0 || length > 4096) return nullptr;
    std::vector<uint8_t> buf(length, 0);
    int rc = s->proto->xu_get_cur(static_cast<uint8_t>(selector & 0xff),
                                  buf.data(),
                                  static_cast<uint16_t>(length),
                                  static_cast<uint32_t>(timeoutMs));
    if (rc < 0) {
        LOGE("session xu_get_cur selector=0x%02x rc=%d", selector, rc);
        return nullptr;
    }
    jbyteArray out = env->NewByteArray(static_cast<jsize>(buf.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(buf.size()),
                            reinterpret_cast<const jbyte*>(buf.data()));
    return out;
}

// ===== UVC stream control + BULK transfer pool =====
//
// 启动序列（贴 UVC 1.1 spec §4.3.1.1）：
//   1. SET_CUR VS_PROBE_CONTROL (selector 0x01) 写 26-byte 初版 stream control
//   2. GET_CUR VS_PROBE_CONTROL 读回（server 可能修正某些字段）
//   3. SET_CUR VS_COMMIT_CONTROL (selector 0x02) 用 server 协商版
//   4. 分配 N×transfer，全部 submit
//
// 当前实现假设 stream interface 只有 alt 0（descriptor dump 确认 P100R3 companion + master
// 都是单 alt），所以**不调** set_interface_alt_setting。如果未来碰到多 alt 设备需补。

static int doProbeCommitNegotiate(libusb_device_handle* handle,
                                  int vsInterface,
                                  uint8_t bFormatIndex,
                                  uint8_t bFrameIndex,
                                  uint32_t dwFrameInterval,
                                  uint8_t outAdjusted[26]) {
    // 标准 UVC 1.1 short variant：26 字节 stream control block
    uint8_t buf[26] = {0};
    // bmHint LE — 1 = dwFrameInterval kept constant
    buf[0] = 0x01; buf[1] = 0x00;
    buf[2] = bFormatIndex;
    buf[3] = bFrameIndex;
    // dwFrameInterval LE @4
    buf[4]  = (uint8_t)(dwFrameInterval & 0xff);
    buf[5]  = (uint8_t)((dwFrameInterval >> 8) & 0xff);
    buf[6]  = (uint8_t)((dwFrameInterval >> 16) & 0xff);
    buf[7]  = (uint8_t)((dwFrameInterval >> 24) & 0xff);
    // 其余 (wKeyFrameRate / wPFrameRate / wCompQuality / wCompWindowSize / wDelay / dwMaxVideoFrameSize /
    //       dwMaxPayloadTransferSize) 全留 0，让 server 填

    const uint16_t kProbeSel = 0x0100;   // VS_PROBE_CONTROL << 8
    const uint16_t kCommitSel = 0x0200;  // VS_COMMIT_CONTROL << 8
    const uint16_t wIndex = static_cast<uint16_t>(vsInterface);

    // 1) SET_CUR PROBE
    int rc = libusb_control_transfer(handle, 0x21, 0x01, kProbeSel, wIndex, buf, 26, 1000);
    LOGI("probe SET_CUR rc=%d", rc);
    if (rc < 0) return rc;

    // 2) GET_CUR PROBE
    rc = libusb_control_transfer(handle, 0xa1, 0x81, kProbeSel, wIndex, buf, 26, 1000);
    LOGI("probe GET_CUR rc=%d adj fmt=%d frm=%d dwFI=0x%08x dwMax=0x%08x",
         rc, buf[2], buf[3],
         (uint32_t)buf[4] | ((uint32_t)buf[5] << 8) | ((uint32_t)buf[6] << 16) | ((uint32_t)buf[7] << 24),
         (uint32_t)buf[18] | ((uint32_t)buf[19] << 8) | ((uint32_t)buf[20] << 16) | ((uint32_t)buf[21] << 24));
    if (rc < 0) return rc;
    std::memcpy(outAdjusted, buf, 26);

    // 3) SET_CUR COMMIT
    rc = libusb_control_transfer(handle, 0x21, 0x01, kCommitSel, wIndex, buf, 26, 1000);
    LOGI("commit SET_CUR rc=%d", rc);
    if (rc < 0) return rc;

    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelOpenStream(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong sessionHandle,
        jint bulkInEndpoint,
        jint formatIndex, jint frameIndex, jint frameInterval100Ns,
        jint transferCount, jint transferSize) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    if (!s || !s->handle || s->vs_interface < 0) return -3001;
    if (s->stream) return -3002;  // 已 open

    uint8_t adj[26] = {0};
    int rc = doProbeCommitNegotiate(s->handle, s->vs_interface,
                                    static_cast<uint8_t>(formatIndex),
                                    static_cast<uint8_t>(frameIndex),
                                    static_cast<uint32_t>(frameInterval100Ns),
                                    adj);
    if (rc < 0) {
        LOGE("probe/commit failed rc=%d", rc);
        return -3003;
    }
    // 注意：实测 vivo 上 set_interface_alt_setting(vs, 0) **可能** 让 firmware 重置 streaming 状态，
    // 反而拿不到数据。Linux SDK trace 没显式 set_alt_setting (默认 alt 0)。先按 trace 不调。
    // 如未来需要按 set_alt_setting 走，从 Kotlin 端再加一个 flag 控制。
    // 如果调用方没给 transferSize，按 server 协商出的 dwMaxPayloadTransferSize 兜底
    if (transferSize <= 0) {
        transferSize = (int)adj[18] | ((int)adj[19] << 8) | ((int)adj[20] << 16);
        if (transferSize < 1024) transferSize = 16 * 1024;
    }
    if (transferCount <= 0) transferCount = 16;

    auto st = std::make_unique<gomob::berxel::StreamingState>();
    st->session = s;
    st->bulk_in_ep = static_cast<uint8_t>(bulkInEndpoint);
    st->transfer_size = transferSize;
    st->xfers.reserve(transferCount);

    for (int i = 0; i < transferCount; ++i) {
        auto slot = std::make_unique<gomob::berxel::BulkXfer>();
        slot->owner = st.get();
        slot->buffer.assign(transferSize, 0);
        slot->xfer = libusb_alloc_transfer(0);
        if (!slot->xfer) {
            LOGE("alloc_transfer #%d failed", i);
            return -3004;
        }
        libusb_fill_bulk_transfer(slot->xfer, s->handle, st->bulk_in_ep,
                                  slot->buffer.data(), transferSize,
                                  bulkTransferCallback, slot.get(), 5000);
        int subRc = libusb_submit_transfer(slot->xfer);
        if (subRc != 0) {
            LOGE("submit_transfer #%d rc=%d", i, subRc);
            libusb_free_transfer(slot->xfer);
            slot->xfer = nullptr;
            // 失败的 transfer 仍然要塞入 vector，以便 close 流程统一释放（虽然 xfer=nullptr）
        } else {
            slot->submitted = true;
        }
        st->xfers.push_back(std::move(slot));
    }

    st->event_thread = std::thread(eventLoop, st.get());
    LOGI("openStream ep=0x%02x size=%d count=%d", bulkInEndpoint, transferSize, transferCount);
    s->stream = std::move(st);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelCloseStream(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong sessionHandle) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    if (!s) return -3001;
    if (!s->stream) return 0;
    auto* st = s->stream.get();
    st->stop_flag.store(true);
    for (auto& slot : st->xfers) {
        if (slot && slot->xfer && slot->submitted) {
            libusb_cancel_transfer(slot->xfer);
        }
    }
    if (st->event_thread.joinable()) st->event_thread.join();
    for (auto& slot : st->xfers) {
        if (slot && slot->xfer) {
            libusb_free_transfer(slot->xfer);
            slot->xfer = nullptr;
        }
    }
    LOGI("closeStream callbacks=%llu bytes=%llu errors=%llu",
         (unsigned long long)st->total_callbacks.load(),
         (unsigned long long)st->total_bytes.load(),
         (unsigned long long)st->total_errors.load());
    s->stream.reset();
    return 0;
}

// 阻塞最多 timeoutMs 等一个 frame chunk；返 ByteArray 给 Kotlin。null = timeout。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelReadFrame(
        JNIEnv* env, jobject /*thiz*/, jlong sessionHandle, jint timeoutMs) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    if (!s || !s->stream) return nullptr;
    auto* st = s->stream.get();

    std::vector<uint8_t> frame;
    {
        std::unique_lock<std::mutex> lk(st->queue_mu);
        if (!st->queue_cv.wait_for(lk, std::chrono::milliseconds(timeoutMs),
                                   [&] { return !st->ready_frames.empty() || st->stop_flag.load(); })) {
            return nullptr;  // timeout
        }
        if (st->ready_frames.empty()) return nullptr;
        frame = std::move(st->ready_frames.front());
        st->ready_frames.pop_front();
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(frame.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(frame.size()),
                            reinterpret_cast<const jbyte*>(frame.data()));
    return out;
}

// 通用 control transfer JNI（手搓 wValue/wIndex/wLength），让 Kotlin 端自行组装
// 标准 UVC probe/commit、Sonix XU、任意 SET_CUR/GET_CUR。OUT 时 dataIn null；IN 时返字节数组。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelControlTransfer(
        JNIEnv* env, jobject /*thiz*/,
        jlong sessionHandle, jint bmRequestType, jint bRequest,
        jint wValue, jint wIndex, jbyteArray dataIn, jint wLengthIn,
        jint timeoutMs) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    if (!s || !s->handle) return nullptr;
    const bool isIn = (bmRequestType & 0x80) != 0;
    if (isIn) {
        if (wLengthIn <= 0 || wLengthIn > 65535) return nullptr;
        std::vector<uint8_t> buf(wLengthIn, 0);
        int rc = libusb_control_transfer(s->handle,
                                         static_cast<uint8_t>(bmRequestType),
                                         static_cast<uint8_t>(bRequest),
                                         static_cast<uint16_t>(wValue),
                                         static_cast<uint16_t>(wIndex),
                                         buf.data(), static_cast<uint16_t>(wLengthIn),
                                         static_cast<unsigned int>(timeoutMs));
        LOGI("ctrl IN bmRT=0x%02x bR=0x%02x wV=0x%04x wI=0x%04x wL=%d → rc=%d",
             bmRequestType, bRequest, wValue, wIndex, wLengthIn, rc);
        if (rc < 0) return nullptr;
        jbyteArray out = env->NewByteArray(rc);
        env->SetByteArrayRegion(out, 0, rc, reinterpret_cast<const jbyte*>(buf.data()));
        return out;
    }
    // OUT
    jbyte* data = nullptr;
    jsize len = 0;
    if (dataIn) {
        data = env->GetByteArrayElements(dataIn, nullptr);
        len = env->GetArrayLength(dataIn);
    }
    int rc = libusb_control_transfer(s->handle,
                                     static_cast<uint8_t>(bmRequestType),
                                     static_cast<uint8_t>(bRequest),
                                     static_cast<uint16_t>(wValue),
                                     static_cast<uint16_t>(wIndex),
                                     reinterpret_cast<uint8_t*>(data),
                                     static_cast<uint16_t>(len),
                                     static_cast<unsigned int>(timeoutMs));
    LOGI("ctrl OUT bmRT=0x%02x bR=0x%02x wV=0x%04x wI=0x%04x wL=%d → rc=%d",
         bmRequestType, bRequest, wValue, wIndex, len, rc);
    if (dataIn) env->ReleaseByteArrayElements(dataIn, data, JNI_ABORT);
    if (rc < 0) return nullptr;
    // OUT 没 data 回，但返个 0-byte 数组表示"成功"
    return env->NewByteArray(0);
}

// 同步 BULK IN 单次读取（返字节数组，给生产路径用）。
// 成功返新 ByteArray 长度 = actual_length；失败 / timeout 返 null。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelBulkSyncReadBytes(
        JNIEnv* env, jobject /*thiz*/,
        jlong sessionHandle, jint endpoint, jint length, jint timeoutMs) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    if (!s || !s->handle || length <= 0) return nullptr;
    std::vector<uint8_t> buf(length, 0);
    int actual = 0;
    int rc = libusb_bulk_transfer(s->handle, static_cast<uint8_t>(endpoint),
                                  buf.data(), length, &actual,
                                  static_cast<unsigned int>(timeoutMs));
    if (rc != 0 || actual <= 0) return nullptr;
    jbyteArray out = env->NewByteArray(actual);
    env->SetByteArrayRegion(out, 0, actual, reinterpret_cast<const jbyte*>(buf.data()));
    return out;
}

// 同步 BULK IN 单次读取 — 用于诊断：避开异步 transfer pool / event loop，
// 直接 libusb_bulk_transfer 一次。返 actual_length（>0 拿到数据，0=空回，<0=libusb err）。
extern "C" JNIEXPORT jint JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelBulkSyncRead(
        JNIEnv* /*env*/, jobject /*thiz*/,
        jlong sessionHandle, jint endpoint, jint length, jint timeoutMs) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    if (!s || !s->handle) return -3001;
    std::vector<uint8_t> buf(length, 0);
    int actual = 0;
    int rc = libusb_bulk_transfer(s->handle, static_cast<uint8_t>(endpoint),
                                  buf.data(), length, &actual,
                                  static_cast<unsigned int>(timeoutMs));
    LOGI("bulk_sync ep=0x%02x len=%d → rc=%d actual=%d", endpoint, length, rc, actual);
    if (rc == 0) return actual;
    return -1000 + rc;  // -1000 ± libusb rc，跟其它 negative 范围错开
}

// 流统计快照（callbacks / bytes / errors / queue depth），给 Kotlin 看
extern "C" JNIEXPORT jlongArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_berxelStreamStats(
        JNIEnv* env, jobject /*thiz*/, jlong sessionHandle) {
    auto* s = reinterpret_cast<gomob::berxel::DeviceSession*>(sessionHandle);
    if (!s || !s->stream) {
        jlong zeros[4] = {0, 0, 0, 0};
        jlongArray out = env->NewLongArray(4);
        env->SetLongArrayRegion(out, 0, 4, zeros);
        return out;
    }
    auto* st = s->stream.get();
    jlong v[4];
    v[0] = static_cast<jlong>(st->total_callbacks.load());
    v[1] = static_cast<jlong>(st->total_bytes.load());
    v[2] = static_cast<jlong>(st->total_errors.load());
    {
        std::lock_guard<std::mutex> lk(st->queue_mu);
        v[3] = static_cast<jlong>(st->ready_frames.size());
    }
    jlongArray out = env->NewLongArray(4);
    env->SetLongArrayRegion(out, 0, 4, v);
    return out;
}

// ===== calibration/* — 占位 =====

JNIEXPORT jfloatArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_calibDetectCharuco(
        JNIEnv* env, jobject /*thiz*/,
        jbyteArray gray, jint width, jint height, jintArray boardSpec) {
    if (env->GetArrayLength(gray) != width * height) {
        ThrowNativeException(env, 2, "gray length mismatch");
        return nullptr;
    }
    jbyte* grayData = env->GetByteArrayElements(gray, nullptr);
    jint* specData = env->GetIntArrayElements(boardSpec, nullptr);
    auto corners = gomob::calibration::DetectCharuco(
        reinterpret_cast<const uint8_t*>(grayData), width, height, specData);
    env->ReleaseByteArrayElements(gray, grayData, JNI_ABORT);
    env->ReleaseIntArrayElements(boardSpec, specData, JNI_ABORT);

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(corners.size()));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(corners.size()), corners.data());
    return result;
}

JNIEXPORT jdoubleArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_calibCalibrateCamera(
        JNIEnv* env, jobject /*thiz*/,
        jfloatArray corners, jintArray cornersPerImage,
        jint width, jint height, jintArray boardSpec) {
    jfloat* cornersData = env->GetFloatArrayElements(corners, nullptr);
    jint* cpiData = env->GetIntArrayElements(cornersPerImage, nullptr);
    jint* specData = env->GetIntArrayElements(boardSpec, nullptr);
    int imageCount = env->GetArrayLength(cornersPerImage);

    auto result = gomob::calibration::CalibrateCamera(
        cornersData, cpiData, imageCount, width, height, specData);

    env->ReleaseFloatArrayElements(corners, cornersData, JNI_ABORT);
    env->ReleaseIntArrayElements(cornersPerImage, cpiData, JNI_ABORT);
    env->ReleaseIntArrayElements(boardSpec, specData, JNI_ABORT);

    jdoubleArray jResult = env->NewDoubleArray(static_cast<jsize>(result.size()));
    env->SetDoubleArrayRegion(jResult, 0, static_cast<jsize>(result.size()), result.data());
    return jResult;
}

JNIEXPORT jdoubleArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_calibStereoCalibrate(
        JNIEnv* env, jobject /*thiz*/,
        jfloatArray colorCorners, jfloatArray depthCorners, jintArray cornersPerImage,
        jdoubleArray colorIntr, jdoubleArray depthIntr, jint width, jint height) {
    jfloat* ccData = env->GetFloatArrayElements(colorCorners, nullptr);
    jfloat* dcData = env->GetFloatArrayElements(depthCorners, nullptr);
    jint* cpiData = env->GetIntArrayElements(cornersPerImage, nullptr);
    jdouble* ciData = env->GetDoubleArrayElements(colorIntr, nullptr);
    jdouble* diData = env->GetDoubleArrayElements(depthIntr, nullptr);
    int imageCount = env->GetArrayLength(cornersPerImage);

    auto result = gomob::calibration::StereoCalibrate(
        ccData, dcData, cpiData, imageCount, ciData, diData, width, height);

    env->ReleaseFloatArrayElements(colorCorners, ccData, JNI_ABORT);
    env->ReleaseFloatArrayElements(depthCorners, dcData, JNI_ABORT);
    env->ReleaseIntArrayElements(cornersPerImage, cpiData, JNI_ABORT);
    env->ReleaseDoubleArrayElements(colorIntr, ciData, JNI_ABORT);
    env->ReleaseDoubleArrayElements(depthIntr, diData, JNI_ABORT);

    jdoubleArray jResult = env->NewDoubleArray(static_cast<jsize>(result.size()));
    env->SetDoubleArrayRegion(jResult, 0, static_cast<jsize>(result.size()), result.data());
    return jResult;
}

} // extern "C"
