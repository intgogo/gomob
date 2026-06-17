package io.gomob.nativebridge

import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Kotlin 侧到 native 的唯一入口。
 *
 * 设计约定：
 * - 所有 JNI 方法集中在本类，业务层禁止散点 `System.loadLibrary` / 散点 `external fun`
 * - native 内部模块（depth / fusion / reconstruction / vin / calibration）通过函数前缀区分
 * - 出错走 [NativeException]（含 errorCode + 文本），不靠 -1 / null 之类哑值
 * - **大数据缓冲**走 [ByteBuffer.allocateDirect]（DirectByteBuffer），native 用
 *   `GetDirectBufferAddress` 直接读，**不**走 JNI 数组拷贝；小元数据用普通数组
 *
 * Why "single entry point"：JNI 边界只有一道，上层只 import 这个 object，避免 JNI 散落
 * 到处导致符号污染、加载顺序问题、生命周期错乱（详见 docs/architecture/03-jni-boundary.md）。
 */
object NativeBridge {

    init {
        // libusb-1.0 是 gomob_native 的运行时依赖（berxel/ Sonix XU 协议层链接它）。
        // 显式先 load，避免某些 OEM 上 implicit dlopen 找不到 SONAME=libusb-1.0.so。
        System.loadLibrary("usb-1.0")
        System.loadLibrary("gomob_native")
    }

    /** 库版本（编译时打入）。Smoke 用：能跑到这里说明 .so 加载成功 + 链接齐备。 */
    external fun version(): String

    // ===== depth/* =====

    /**
     * 把 16bit 深度帧反投影成相机坐标系点云（mm）。返回扁平 [x0,y0,z0, x1,y1,z1, ...]，
     * 长度 = 3 × width × height（深度=0 的像素直接出 (0,0,0)，调用方按需过滤）。
     */
    external fun depthToPointCloud(
        depth: ShortArray,
        width: Int, height: Int,
        fx: Double, fy: Double,
        cx: Double, cy: Double,
    ): FloatArray

    // ===== fusion/* =====

    /**
     * 给定 iHawk Color↔Depth 的 stereo 外参 (R, t)，把 depth 系点云投到 color 像素坐标
     * 取色，返回每点 RGB 序列（[r0,g0,b0, r1,g1,b1, ...]，长度 = 3 × pointCount）。
     *
     * @param rotationRowMajor 行优先 3×3，**Depth 系 → Color 系**（注意方向）
     * @param translation 3×1 mm
     */
    external fun colorizePointCloud(
        points: FloatArray,
        rgb: ByteArray,
        rgbWidth: Int, rgbHeight: Int,
        rgbFx: Double, rgbFy: Double, rgbCx: Double, rgbCy: Double,
        rotationRowMajor: DoubleArray,
        translation: DoubleArray,
    ): ByteArray

    // ===== reconstruction/* — 三维外廓扫描重建管线 =====
    //
    // 详见 docs/architecture/04-reconstruction-pipeline.md。
    // 核心思想：用户转一圈 → 每帧 ICP 配准当前帧到关键帧累积体 → TSDF 体素积分 →
    //          停止后 Marching Cubes 出 mesh + 关键帧纹理烘焙。

    /**
     * 增量 ICP 配准。把 [srcPoints] 对齐到 [dstPoints]（或 session 累积体），
     * 返回配准后的位姿 [tx, ty, tz, qx, qy, qz, qw]（7 个 float，单位 mm + 单位四元数）。
     *
     * @param srcPoints 当前帧点云（FloatArray 扁平 [x,y,z, ...]）
     * @param dstPoints 参考点云
     * @param initialPose 7 元素初值 [tx,ty,tz,qx,qy,qz,qw]，可用上一帧位姿
     */
    external fun icpRegister(
        srcPoints: FloatArray,
        dstPoints: FloatArray,
        initialPose: FloatArray,
    ): FloatArray

    /**
     * 创建一个扫描会话。返回 native session handle（Long），后续 Ingest/Finalize 用。
     *
     * @param voxelSizeMm TSDF 体素边长（默认 4mm，物体 ≤ 60cm 时；小物体可调 2mm）
     * @param gridExtentMm TSDF 网格边长（mm），决定可重建空间立方体大小
     * @param gridCenterZMm grid 沿世界 z 轴的中心偏移；手持扫描 pose=identity 时物体在
     *   相机前方 +z 方向 25–80cm 处，传 400.0f 把 grid 中心放到 z=400mm 让物体落进 grid。
     *   若上层传精确世界系 pose 让物体落在原点附近，可传 0.0f。
     */
    external fun scanSessionCreate(
        voxelSizeMm: Float,
        gridExtentMm: Float,
        gridCenterZMm: Float,
    ): Long

    /**
     * 喂一帧深度 + pose，session 内部 TSDF 累积。
     *
     * @param handle 来自 [scanSessionCreate]
     * @param depthBuffer Direct ByteBuffer（16bit mm depth），由 reader 线程零拷贝
     * @param width depth 宽
     * @param height depth 高
     * @param intr [fx, fy, cx, cy]（depth 镜头内参）
     * @param pose 7 元素位姿 [tx,ty,tz,qx,qy,qz,qw]
     * @param confidence 可选 per-pixel 置信 Direct ByteBuffer（uint8，与 depth 同 W×H）。
     *   非 null 时 native 按 conf/255 软加权 TSDF 积分 + 加权 ICP（低置信弱回波/散斑弱像素降权）；
     *   null 时退化为均权（旧行为）。来源 [io.gomob.model.DepthFrame.confidence]。
     * @return 累积的关键帧数
     */
    external fun scanSessionIngest(
        handle: Long,
        depthBuffer: ByteBuffer,
        width: Int, height: Int,
        intr: DoubleArray,
        pose: FloatArray,
        confidence: ByteBuffer? = null,
    ): Int

    /**
     * 提取 mesh + 点云，写到 [outDir] 下 cloud.ply / mesh.gltf。返回简要统计（顶点数 / 面数 / 关键帧数）。
     */
    external fun scanSessionFinalize(handle: Long, outDir: String): IntArray

    /** 释放 session handle。 */
    external fun scanSessionClose(handle: Long)

    /**
     * 扫描中实时预览：取 TSDF 近表面体素中心点云子采样。
     *
     * 返回扁平 [x0,y0,z0, x1,y1,z1, ...] 至多 [maxVertices] 个点（实际可能更少）。
     * 单位 mm，世界系。返回长度可能为 0（扫描刚开始 / 完全无观测）。
     *
     * 不跑 Marching Cubes（高频调用会拖累 ingest）；UI 端把这些点投到 2D Canvas 给用户看进度即可。
     * handle 已 close → 返长度 0 数组（不抛异常）。
     */
    external fun scanSessionPeekVertices(handle: Long, maxVertices: Int): FloatArray

    /**
     * finalize 完成后拉 mesh 顶点（扁平 [x,y,z,...] mm 世界系）— 与 [scanSessionMeshNormals]
     * / [scanSessionMeshIndices] 配合用 lit material 渲染实体面。
     *
     * 数据生命周期：handle close 前持有，close 后清空；finalize 前调用返长度 0 数组。
     */
    external fun scanSessionMeshVertices(handle: Long): FloatArray

    /** finalize 完成后拉 mesh 顶点法向（扁平 [nx,ny,nz,...] 单位向量），见 [scanSessionMeshVertices]。 */
    external fun scanSessionMeshNormals(handle: Long): FloatArray

    /** finalize 完成后拉 mesh 三角形索引（每 3 个 = 一个三角形 CCW），见 [scanSessionMeshVertices]。 */
    external fun scanSessionMeshIndices(handle: Long): IntArray

    // ===== vin/* — VIN 数码拓印 =====
    //
    // 详见 docs/architecture/08-vin-rectify-design.md。

    /**
     * 单帧 RGBD → VIN 正射拓印图。
     *
     * @param colorBgr Color 帧 BGR888（YUYV 已转过；DirectByteBuffer 零拷贝）
     * @param depth16Mm Depth 帧 16bit mm（已 setRegistrationEnable 对齐到 Color 像素坐标；
     *                  DirectByteBuffer 零拷贝）
     * @param colorIntr [fx,fy,cx,cy,k1,k2,p1,p2,k3] 9 元素
     * @param roiBox    [u_min, v_min, u_max, v_max] 像素坐标，VIN 区域
     * @param config    [ortho_distance_mm, pixel_size_mm, out_w, out_h]
     * @return [VinRectifyNative] 含 PNG 字节 + 拟合元数据
     */
    external fun vinRectify(
        colorBgr: ByteBuffer, colorWidth: Int, colorHeight: Int,
        depth16Mm: ByteBuffer, depthWidth: Int, depthHeight: Int,
        colorIntr: DoubleArray,
        roiBox: IntArray,
        config: FloatArray,
    ): VinRectifyNative

    // ===== calibration/* — iHawk Color/Depth 标定 =====
    //
    // 详见 docs/architecture/05-calibration-pipeline.md。
    // SDK 出厂参数 + setRegistrationEnable 不达标时走这条路；OpenCV cv::aruco + cv::stereoCalibrate。

    /**
     * Charuco 角点检测。返回 N×4 扁平数组 [u, v, marker_id, charuco_id]；零角点返长度 0 数组。
     *
     * @param gray 单通道灰度（Color 转 gray 或 Depth 当 intensity）
     * @param boardSpec [rows, cols, dict_id, square_size_mm × 100, marker_size_mm × 100]
     *                  （后两项 ×100 转整数避坑）
     */
    external fun calibDetectCharuco(
        gray: ByteArray, width: Int, height: Int,
        boardSpec: IntArray,
    ): FloatArray

    /**
     * 单目内参标定。
     *
     * @param corners 多张图角点扁平 [img0_corners, img1_corners, ...]
     * @param cornersPerImage 每张图角点数
     * @return [fx, fy, cx, cy, k1, k2, p1, p2, k3, rms]
     */
    external fun calibCalibrateCamera(
        corners: FloatArray, cornersPerImage: IntArray,
        width: Int, height: Int, boardSpec: IntArray,
    ): DoubleArray

    /**
     * Stereo 外参标定（iHawk Color↔Depth）。
     *
     * @return [r00..r22, tx, ty, tz, rms]（13 个 double，rotation 行优先）
     */
    external fun calibStereoCalibrate(
        colorCorners: FloatArray, depthCorners: FloatArray,
        cornersPerImage: IntArray,
        colorIntr: DoubleArray, depthIntr: DoubleArray,
        width: Int, height: Int,
    ): DoubleArray

    // ===== berxel/* — Sonix XU 协议（M1.6.5 复现层；M1.6.6 NDK port 入口） =====

    /**
     * Berxel iHawk P100R3 companion chip 单寄存器读（Sonix XU selector 0x01）。
     *
     * 走 libusb_wrap_sys_device 接管 Android `UsbDeviceConnection.getFileDescriptor()` 拿到的
     * usbfs fd，claim 指定 interface（通常 0 = control / XU 所在接口）后调
     * BerxelProtocolSonix.asic_read。**调用方负责**：
     * 1. 已通过 [android.hardware.usb.UsbManager] 拿到该设备的 USB 权限；
     * 2. 当前没有别的 owner（包括 Berxel SDK 自身）正在 claim 同一 interface。
     *
     * @param usbFd UsbDeviceConnection.getFileDescriptor() 返回的整数 fd
     * @param interfaceNumber 要 claim 的 USB interface 号（companion 节点上 XU Unit 3 在 Interface 0）
     * @param regAddr 16-bit ASIC 寄存器地址（如 0x10D0 / 0x10D8 / 0x10D9）
     * @param timeoutMs 单次 control transfer 超时，建议 1000
     * @return 寄存器值 ∈ [0, 255]；< 0 错误码：
     *   - -1001 libusb init / set_option 失败
     *   - -1002 libusb_wrap_sys_device 失败（fd 无效 / 没权限）
     *   - -1003 libusb_claim_interface 失败（被占用 / interface 不存在）
     *   - -1004 asic_read 自身失败（USB stall / 超时 / firmware 拒绝）
     */
    external fun berxelSonixAsicRead(
        usbFd: Int,
        interfaceNumber: Int,
        regAddr: Int,
        timeoutMs: Int,
    ): Int

    /**
     * Dump USB descriptor (device + active config + interfaces + altsettings + endpoints) to
     * 多行可读字符串。M1.6.6 入口铺地：所有后续 stream control 都要拿这个的真理源。
     *
     * @param usbFd UsbDeviceConnection.getFileDescriptor() 返回的整数 fd
     * @return 多行 dump 字符串；以 "ERR " 开头的串说明 init/wrap 失败
     */
    external fun berxelUsbDescriptorDump(usbFd: Int): String

    // ─── 会话级 API（M1.6.6 主体）─────────────────────────────────────────
    //
    // 流程：openDeviceByFd → (任意顺序) sessionAsicRead / sessionBatchCmd / openStream...
    //       → closeDevice。所有 session 操作复用同一个 libusb_context + handle，
    //       不每次重复 init / wrap，避免 vivo 等机器上反复 detach kernel HID 的开销。
    //
    // 错误码：返回值 < 0 时 -2001..-2006 见 native/jni/jni_bridge.cpp 注释。

    /**
     * 打开一个 device 会话。claim VC + VS interface，必要时 detach Android kernel HID 驱动。
     *
     * @param usbFd UsbDeviceConnection.getFileDescriptor()
     * @param vcInterface VideoControl interface 号（companion=0, master=0）。<0 跳过
     * @param vsInterface VideoStreaming interface 号（companion=1, master=1）。<0 跳过
     * @return sessionHandle (非 0) 或 **0L 表示失败**（细节看 logcat tag `gomob_native`）
     */
    external fun berxelOpenDeviceByFd(usbFd: Int, vcInterface: Int, vsInterface: Int): Long

    /** 关闭会话：释放 interface + 把 kernel driver attach 回去 + 销毁 ctx。idempotent。 */
    external fun berxelCloseDevice(sessionHandle: Long)

    /**
     * 绕过 UsbManager 直接 open /dev/bus/usb/BBB/DDD 拿 fd。
     *
     * Why：Android kernel 把 UVC class 设备（master 节点）从 `usbManager.deviceList`
     * 过滤掉了，但 device_permissions ACL 已经授给 app uid，原生 open() 应该成功。
     * Linux uvcvideo driver 占着 interface 0/1 — 后续 [berxelOpenDeviceByFd] 里
     * libusb_kernel_driver_active + detach 流程会解掉。
     *
     * @return fd ≥ 0 或 -errno（EACCES=-13 / ENOENT=-2 / ...）
     */
    external fun berxelOpenUsbPath(path: String): Int

    /** 会话级 ASIC read。返回 [0,255] 或 -2005/-2006。 */
    external fun berxelSessionAsicRead(sessionHandle: Long, regAddr: Int, timeoutMs: Int): Int

    /** 会话级 ASIC write。返回 0 / 负错误。 */
    external fun berxelSessionAsicWrite(sessionHandle: Long, regAddr: Int, value: Int, timeoutMs: Int): Int

    /**
     * 会话级 Sonix vendor batch_cmd（selector 0x19/0x1e/... 上传 stream_ctrl block / firmware params）。
     * @return 传输的字节数 >= 0，或 -2005/-2006
     */
    external fun berxelSessionBatchCmd(
        sessionHandle: Long,
        selector: Int,
        payload: ByteArray,
        timeoutMs: Int,
    ): Int

    /**
     * 会话级 Sonix vendor XU_GET_CUR（读 selector 指定的状态块）。
     * @return ByteArray 长度 = length，失败返 null
     */
    external fun berxelSessionXuGetCur(
        sessionHandle: Long,
        selector: Int,
        length: Int,
        timeoutMs: Int,
    ): ByteArray?

    /**
     * 打开 BULK 视频流：UVC probe/commit 协商 + 分配 transfer pool + 提交 + 起 event 线程。
     *
     * 调用前提：session 已 [berxelOpenDeviceByFd] 成功并按需跑完 init sequence。
     *
     * @param sessionHandle from [berxelOpenDeviceByFd]
     * @param bulkInEndpoint BULK IN endpoint address（companion=0x82, master=0x81）
     * @param formatIndex UVC bFormatIndex（companion YUYV=1, master MJPEG=1）
     * @param frameIndex UVC bFrameIndex（看 descriptor 选档）
     * @param frameInterval100Ns dwFrameInterval，100ns 单位（45fps≈222222=0x3640E）
     * @param transferCount 并发 BULK transfer 数量，建议 16；<=0 取默认 16
     * @param transferSize 每个 transfer 缓冲字节；<=0 用 server 协商的 dwMaxPayloadTransferSize
     * @return 0 / 负错误码（-3001..-3004）
     */
    external fun berxelOpenStream(
        sessionHandle: Long,
        bulkInEndpoint: Int,
        formatIndex: Int,
        frameIndex: Int,
        frameInterval100Ns: Int,
        transferCount: Int,
        transferSize: Int,
    ): Int

    /** 关闭视频流：cancel transfer + join event thread + free。idempotent。 */
    external fun berxelCloseStream(sessionHandle: Long): Int

    /** 阻塞读一个完成的 BULK transfer chunk（**不是**完整帧；上层按 UVC payload header 拼）。 */
    external fun berxelReadFrame(sessionHandle: Long, timeoutMs: Int): ByteArray?

    /** [callbacks, totalBytes, totalErrors, queueDepth] 的快照。 */
    external fun berxelStreamStats(sessionHandle: Long): LongArray

    /**
     * 同步 BULK IN 单次读 —— 绕过 transfer pool / event loop，直接 libusb_bulk_transfer 一次。
     * 用于诊断"firmware 是否真不发数据" vs "我们的 async pool 有 bug"。
     *
     * @return >=0 拿到的字节数；-3001 invalid handle；其它负值 = -1000+libusb_error
     */
    external fun berxelBulkSyncRead(
        sessionHandle: Long, endpoint: Int, length: Int, timeoutMs: Int,
    ): Int

    /**
     * 同步 BULK IN 单次读（生产路径）。返字节数组（actual_length 长度），失败 / timeout 返 null。
     * 用于 [io.gomob.nativebridge.berxel.BerxelNativeStack.pullChunk]。
     */
    external fun berxelBulkSyncReadBytes(
        sessionHandle: Long, endpoint: Int, length: Int, timeoutMs: Int,
    ): ByteArray?

    /**
     * 通用 control transfer。bmRequestType MSB=1 (0x80) 时是 IN，dataIn 忽略，length=wLengthIn；
     * MSB=0 时是 OUT，dataIn=payload，wLengthIn 忽略（用 dataIn.size）。
     * IN 成功返 ByteArray 长度 = 实际读取字节；OUT 成功返长度 0 的 ByteArray；失败均返 null。
     */
    external fun berxelControlTransfer(
        sessionHandle: Long,
        bmRequestType: Int, bRequest: Int,
        wValue: Int, wIndex: Int,
        dataIn: ByteArray?, wLengthIn: Int,
        timeoutMs: Int,
    ): ByteArray?

    // ── 厂商无关相机统一入口（M6.8b，双相机自动识别）──
    // 按 vid:pid 经 native CameraRegistry 分发到 driver，两相机出同一 depthMm 契约。
    // eYs3D RS-D550(0x3438:0x0206) + Berxel P100R3(0x0603:0x001f,双节点 master+companion) 都经此入口
    // （Berxel M6.8b ④ 已并入 BerxelDriver，color+depth 真机 PASS，旧 berxelDual* legacy 已删）。

    /**
     * 按 vid:pid 打开相机（registry 选 driver → open_fd → start）。
     * fds = usbfs fd 数组（eYs3D 单节点 1 个；Berxel 双节点 2 个 master+companion）。
     * configJson = driver 特定配置字节（档位/控制覆盖），可空数组。返回会话句柄；失败 0L。
     */
    external fun cameraOpenByFds(vid: Int, pid: Int, fds: IntArray, configJson: ByteArray): Long

    /**
     * ★★★ Java ApcCamera 路径绑定（2026-06-15 主路）：dlopen libUVCCamera.so（RTLD_LOCAL）+ 手调其 JNI_OnLoad，
     * 让 vendor 把 `com.esp.android.usb.camera.core.{UVCCamera,ApcCamera}` 的全部 native 方法 RegisterNatives 到
     * gomob 复制进来的同名 Java 类上，并 setVM（回调线程用）。**必须在 `new ApcCamera()` 之前调一次**
     * （nativeCreate 是字段初始化，须先绑定）。幂等，返回 JNI 版本号（>0 成功）/ 0 失败。
     * ★ 不走 System.loadLibrary（避免 libusb100 符号遮蔽 gomob libusb-1.0，见 finding 续35）。
     */
    external fun bindEys3dVendorJni(): Int

    /** 对 usbfs fd 做一次 USB 端口 reset（清 eYs3D 流引擎残留）。reset 致重枚举 → 本 fd 失效，
     *  调用方须 close 旧 connection 后重新 openDevice 取新 fd 再开流。成功返 true。 */
    external fun cameraResetByFd(fd: Int): Boolean

    /** 停止 + 释放会话（句柄 = ICameraSession*，cameraStop 是唯一释放点）。 */
    external fun cameraStop(handle: Long)

    /**
     * 取最新 metric depthMm 帧写进 directBuffer（容量需 >= w*h*2）。
     * 返回写入字节数；无新帧 0；buffer 不足 -1。outInfo(>=4)=[width,height,serial,hostNs]。两相机同契约。
     */
    external fun cameraPollDepthMm(handle: Long, directBuffer: java.nio.ByteBuffer, outInfo: LongArray): Int

    /** 取最新 color 帧字节（consume-once，无新帧 null）。eYs3D=YUYV/Berxel=MJPEG，按 capabilities 解码。 */
    external fun cameraPollColor(handle: Long): ByteArray?

    /** 会话统计 [colorFrames, depthFrames, dropped, errors, state]。 */
    external fun cameraStats(handle: Long): LongArray

    /** 取最新逐像素 confidence(uint8, W*H) 写 directBuffer。返回字节数 / 0 无 / -1 不足。
     *  outInfo(>=4)=[w,h,serial,hostNs]。无 conf 的相机(eYs3D)恒返 0。 */
    external fun cameraPollDepthConf(handle: Long, directBuffer: java.nio.ByteBuffer, outInfo: LongArray): Int

    /** 取最新 IR/phase 灰度(uint8, W*H) 写 directBuffer。返回字节数 / 0 无 / -1 不足。 */
    external fun cameraPollIrGrey(handle: Long, directBuffer: java.nio.ByteBuffer, outInfo: LongArray): Int

    /** 厂商扩展诊断统计(driver 自定义 int64 序列;Berxel=16 项,eYs3D=空数组)。 */
    external fun cameraExtendedStats(handle: Long): LongArray

    /** 调试:dump 最新 depth transport 原始字节到 path。返回写入字节数。 */
    external fun cameraDumpRawDepth(handle: Long, path: String): Int

    /** 调试:dump 最新 color 原始预览帧到 path。Berxel 为 MJPEG。返回写入字节数。 */
    external fun cameraDumpRawColor(handle: Long, path: String): Int

    /** 语义深度控制（负值=不改）→ 各 driver 内部翻 XU。返回是否生效。 */
    external fun cameraSetControls(
        handle: Long,
        confThr: Float,
        temporal: Int,
        spatial: Int,
        ae: Int,
        gain: Int,
        irCurrent: Int,
    ): Boolean

    /** 不开流即取设备能力 JSON（vendor、model、has_color/depth/ir、depth_is_metric_onchip、depth 档位），供 UI 显型号。 */
    external fun cameraCapabilitiesJson(vid: Int, pid: Int): String
}

/**
 * VIN 拓印 native 返回结果。
 *
 * 字段：
 * - [pngBytes] 拓印图 PNG 编码字节
 * - [planeNormalAndD] [nx, ny, nz, d] 平面方程 n·P + d = 0
 * - [planeStats] [rms_residual_mm, inlier_ratio]
 */
data class VinRectifyNative(
    val pngBytes: ByteArray,
    val planeNormalAndD: FloatArray,
    val planeStats: FloatArray,
    val outputWidth: Int,
    val outputHeight: Int,
) {
    override fun equals(other: Any?): Boolean = other is VinRectifyNative &&
        pngBytes.contentEquals(other.pngBytes) &&
        planeNormalAndD.contentEquals(other.planeNormalAndD) &&
        planeStats.contentEquals(other.planeStats) &&
        outputWidth == other.outputWidth && outputHeight == other.outputHeight
    override fun hashCode(): Int = pngBytes.contentHashCode() * 31 +
        planeNormalAndD.contentHashCode() * 31 +
        planeStats.contentHashCode() * 31 + outputWidth * 31 + outputHeight
}

class NativeException(val errorCode: Int, message: String) : RuntimeException(message)

/** native 错误码常量（与 jni_bridge.cpp 对齐）。 */
object NativeError {
    const val NOT_IMPLEMENTED = 1
    const val INVALID_ARG = 2
    const val ALLOC_FAIL = 3
    const val SDK_ERROR = 4
    const val PLANE_FIT_FAIL = 100
    const val ICP_NOT_CONVERGED = 101
    const val SESSION_HANDLE_INVALID = 102
    const val CHARUCO_NOT_DETECTED = 200
    const val CALIB_RMS_TOO_HIGH = 201
}
