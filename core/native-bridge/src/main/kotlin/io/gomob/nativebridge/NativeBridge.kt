package io.gomob.nativebridge

/**
 * Kotlin 侧到 native 的唯一入口。
 *
 * 设计约定:
 *  - 所有 JNI 方法集中在本类，业务层禁止散点 System.loadLibrary / 散点 native 声明
 *  - native 内部模块（depth/fusion/reconstruction）通过函数前缀区分
 *  - 出错走 NativeException（含 errorCode + 文本），不靠 -1 / null 之类哑值
 *
 * Why: gogame "数据驱动 + 单一真理源" 思想在端侧的体现 — JNI 边界只有一道，
 * 上层永远只 import 这个类，避免 JNI 散落到处都是导致符号污染和加载顺序问题。
 */
object NativeBridge {

    init {
        System.loadLibrary("gomob_native")
    }

    /** 库版本（编译时打入）。Smoke 用：能跑到这里说明 .so 加载成功。 */
    external fun version(): String

    // ---- depth/* ----

    /** 把原始深度帧转为相机坐标系点云（单位毫米）。返回扁平 [x0,y0,z0, x1,y1,z1, ...]。 */
    external fun depthToPointCloud(
        depth: ShortArray,
        width: Int,
        height: Int,
        fx: Double, fy: Double,
        cx: Double, cy: Double,
    ): FloatArray

    // ---- fusion/* ----

    /** 给定外参，把深度点云投影到 RGB 像素坐标，回填颜色。 */
    external fun colorizePointCloud(
        points: FloatArray,
        rgb: ByteArray,
        rgbWidth: Int,
        rgbHeight: Int,
        rgbFx: Double, rgbFy: Double,
        rgbCx: Double, rgbCy: Double,
        rotationRowMajor: DoubleArray,
        translation: DoubleArray,
    ): ByteArray
}

class NativeException(val errorCode: Int, message: String) : RuntimeException(message)
