// gomob native — JNI 入口集中点
// 设计约束: Kotlin 侧只通过 io.gomob.nativebridge.NativeBridge 调进来；
//          所有 native 模块（depth/fusion/reconstruction）的 JNI 导出都集中在本文件，
//          避免符号在多个 .cpp 散落，导致链接顺序难以控制。

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#define LOG_TAG "gomob_native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace gomob {
namespace depth {
    // depth/ 子模块对外签名
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
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_gomob_nativebridge_NativeBridge_version(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("gomob_native 0.1.0");
}

JNIEXPORT jfloatArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_depthToPointCloud(
        JNIEnv* env, jobject /*thiz*/,
        jshortArray depth, jint width, jint height,
        jdouble fx, jdouble fy, jdouble cx, jdouble cy) {
    jsize len = env->GetArrayLength(depth);
    if (len != width * height) {
        LOGE("depth length %d != %d*%d", len, width, height);
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

JNIEXPORT jbyteArray JNICALL
Java_io_gomob_nativebridge_NativeBridge_colorizePointCloud(
        JNIEnv* env, jobject /*thiz*/,
        jfloatArray points,
        jbyteArray rgb, jint rgbWidth, jint rgbHeight,
        jdouble rgbFx, jdouble rgbFy, jdouble rgbCx, jdouble rgbCy,
        jdoubleArray rotationRowMajor, jdoubleArray translation) {
    jsize pointBytes = env->GetArrayLength(points);
    jsize rgbBytes = env->GetArrayLength(rgb);
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

    (void)rgbBytes;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(colored.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(colored.size()),
                            reinterpret_cast<const jbyte*>(colored.data()));
    return result;
}

} // extern "C"
