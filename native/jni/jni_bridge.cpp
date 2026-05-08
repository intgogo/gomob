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
#include <cstdint>
#include <string>
#include <vector>

#include "reconstruction/icp.h"

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
        const float* pose7);
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
        jdoubleArray intr, jfloatArray pose) {
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
    jdouble* intrData = env->GetDoubleArrayElements(intr, nullptr);
    jfloat* poseData = env->GetFloatArrayElements(pose, nullptr);
    int kfCount = gomob::reconstruction::SessionIngest(
        s, depthPtr, width, height,
        intrData[0], intrData[1], intrData[2], intrData[3],
        poseData);
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
