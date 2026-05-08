package io.gomob.feature.scan3d

import android.content.Context
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.SurfaceOrientation
import com.google.android.filament.SwapChain
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.UiHelper
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.cos
import kotlin.math.sin

/**
 * 扫描中 TSDF 体素近表面的实时 3D 预览 / 完成后 mesh 实体面渲染。
 *
 * 渲染：Filament + OpenGL ES backend（手机 GPU 直渲）。
 *   - **Recording 模式**（[points] 非空且 [mesh] 为 null）：PrimitiveType.POINTS + unlit material，
 *     每点由 `gl_PointSize` 控制屏幕像素大小（[materials/point_cloud.mat]）
 *   - **Completed 模式**（[mesh] 非 null）：PrimitiveType.TRIANGLES + lit material + directional
 *     light，给扫描结果立体感（[materials/mesh_lit.mat]）
 *
 * 交互：单指拖动 orbit（围绕 grid 中心）；双指捏合缩放距离。
 *
 * 数据流：
 *   - [points] 变化 → [PointCloudSurfaceView.setPoints] 复用 vertex/index buffer 改 indexCount
 *   - [mesh] 变化 → [PointCloudSurfaceView.setMesh] 复用 mesh vertex/index buffer
 *   - 同一时间只有一个 entity 可见（另一个 indexCount=0），互不干扰
 *
 * 生命周期：
 *   - [PointCloud3dView] @Composable 内 remember 一个 [PointCloudSurfaceView]
 *   - DisposableEffect onDispose 调 [PointCloudSurfaceView.destroy]，释放 Engine 与 GL 资源
 *   - SurfaceView attach/detach 自动启停 Choreographer 渲染回调
 */
@Composable
fun PointCloud3dView(
    points: FloatArray,
    modifier: Modifier = Modifier,
    gridCenterZmm: Float = 750f,  // 默认值与 Scan3dRecordingScreen / SessionCreate 对齐 (grid z[0,1500]mm)
    mesh: ScanMeshData? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember { PointCloudSurfaceView(context, gridCenterZmm) }

    LaunchedEffect(mesh) {
        if (mesh != null) {
            view.setMesh(mesh.vertices, mesh.normals, mesh.indices)
        }
    }
    LaunchedEffect(points, mesh) {
        // mesh 在显示时点云隐藏（避免叠加）；mesh 为 null 时点云正常更新
        if (mesh == null) view.setPoints(points)
    }

    DisposableEffect(view) {
        onDispose { view.destroy() }
    }

    AndroidView(factory = { view }, modifier = modifier.fillMaxSize())
}

/**
 * 完成扫描后传给 [PointCloud3dView] 的 mesh 数据快照。
 *
 * 字段单位与 native 的 ExtractMesh 输出一致：vertices mm 世界系扁平 [x,y,z,...]；
 * normals 单位向量扁平 [nx,ny,nz,...]；indices 每 3 个 = 一个三角形（CCW，外法向）。
 * 所有数组长度严格匹配（vertices.size == normals.size，indices.size % 3 == 0）。
 */
data class ScanMeshData(
    val vertices: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
) {
    override fun equals(other: Any?): Boolean = other is ScanMeshData &&
        vertices.contentEquals(other.vertices) &&
        normals.contentEquals(other.normals) &&
        indices.contentEquals(other.indices)
    override fun hashCode(): Int =
        vertices.contentHashCode() * 31 + normals.contentHashCode() * 31 + indices.contentHashCode()
}

/**
 * 内部 SurfaceView 实现 — Filament Engine + Renderer 全套；Compose 通过 [PointCloud3dView] 包它。
 *
 * 设计思路：
 *   - Filament Engine 与 Renderer 与本 View 同生命周期；不与 Application 共享
 *     （扫描页用完即释放，避免长时间持有 GPU 资源）
 *   - **SurfaceView (不是 TextureView)** + UiHelper 把 Surface 生命周期同步给 Engine
 *   - Choreographer 驱动每帧 render — 60fps 时 ~16ms 一帧，5K 点 GPU 完全跑得动
 *   - 相机：手动 yaw/pitch/distance 计算 lookAt（自己掌控比 Manipulator 更省事）
 *
 * 为什么是 SurfaceView 而不是 TextureView：
 *   实测 TextureView 在 Compose 嵌套场景下踩 buffer 池共享坑：TextureView 把内容渲染到
 *   SurfaceTexture，Compose 的 hardware-accelerated layer 跟 SurfaceTexture 共享 GL context
 *   和 buffer 池；LiveStreamRow 每秒 6 次创建新 RGB/DEPTH Bitmap → Compose 上传 GL texture
 *   时复用了我们 SurfaceTexture 的 buffer 池 → 镜头变化时 Filament render 渲染到的 buffer
 *   实际是 Compose 写过的 RGB 摄像头帧 → GPU 把 RGB 字节当 RGBA 像素采样 → 整块画面变成
 *   RGB 摄像头画面被错位拉伸的横条纹（截图证据 finding_pointcloud3d_corruption_2026-05-07-truecause.png）。
 *
 *   SurfaceView 走独立 SurfaceFlinger surface，**不与 Compose RenderNode 共享 buffer 池**，
 *   彻底避开这个污染路径。代价是 z-order 固定在 base layer（Compose 文字角标仍可叠在上面）。
 */
internal class PointCloudSurfaceView(
    context: Context,
    private val gridCenterZmm: Float,
) : SurfaceView(context) {

    companion object {
        init {
            // 加载 libfilament-jni.so（filament-android），filament-utils-android Utils.init() 也调用
            Utils.init()
        }
        private const val TAG = "PointCloud3dView"
    }

    private val engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer().apply {
        clearOptions = Renderer.ClearOptions().apply {
            clear = true
            discard = true
            clearColor = floatArrayOf(0f, 0f, 0f, 1f)
        }
    }
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView().apply {
        scene = this@PointCloudSurfaceView.scene
        // 抗锯齿默认 FXAA，点云不需要 — 关掉减开销
        antiAliasing = View.AntiAliasing.NONE
        // OPAQUE blendMode：Renderer.ClearOptions 强制黑底清屏 + 像素全 alpha=1 提交，让 SurfaceFlinger 走 OPAQUE
        // 合成快路径。默认 TRANSLUCENT 会逐像素 alpha 混合，与 Compose 父 Box 的 background
        // 在每次重组时叠出不稳定结果 → 体感"立即闪烁"。
        blendMode = View.BlendMode.OPAQUE
        // 关后处理（bloom/TAA/SSAO/dithering）：点云预览不需要，且后处理对每帧时间敏感，
        // GPU 时间抖动会与 Choreographer vsync 错位 → 偶发掉帧/闪烁。
        isPostProcessingEnabled = false
    }
    private val cameraEntity: Int = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity).apply {
        // grid 范围 800mm 立方，z[0,800]；相机默认放在 grid 前方 +z 看向中心
        setProjection(45.0, 1.0, 50.0, 6000.0, Camera.Fov.VERTICAL)
    }

    private var swapChain: SwapChain? = null
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
        renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: android.view.Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface)
            }
            override fun onDetachedFromSurface() {
                swapChain?.let { engine.destroySwapChain(it); swapChain = null }
            }
            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
                val aspect = width.toDouble() / height.coerceAtLeast(1)
                camera.setProjection(45.0, aspect, 50.0, 6000.0, Camera.Fov.VERTICAL)
                    }
        }
        attachTo(this@PointCloudSurfaceView)
    }

    private val material: Material
    private val materialInstance: MaterialInstance
    private val meshMaterial: Material
    private val meshMaterialInstance: MaterialInstance

    // 点云预分配上限：与 Scan3dRecordingViewModel.MAX_PREVIEW_VERTICES 对齐
    // 不每帧 destroy/recreate VB/IB —— 那会累积 stale handle 让 FEngine::loop 在 ~20s
    // 后撞到 "corrupted heap Handle" SIGABRT。改为一次性 alloc，setBufferAt 复用 + 用
    // RenderableManager.setGeometryAt 调整 index count。
    private val maxVertices = 10000

    // 点云上传：每次 setPoints 分配新 DirectByteBuffer，**不能立刻丢引用**。
    //
    // Filament `vertexBuffer.setBufferAt(engine, slot, buffer)` 是异步上传：JNI 调用返回时
    // driver thread 可能还没读完 Java DirectByteBuffer 的 native 内存。局部变量出作用域后，
    // DirectByteBuffer 可被 GC/cleaner 回收；点数少、上传少时不容易撞上，点云变密后就会
    // 读到失效/混合内存，画面表现为彩色条纹、棋盘块或整片几何炸开。
    //
    // 镜头不变时点云稳定，每次写入字节几乎相同，race 看不出；镜头大变时 cloud 内容差异大，
    // race 立刻暴露。所以"镜头变化大才花屏"完全对应这个并发 bug。
    //
    // 修法：每次上传用新 buffer，并在 [pointUploadBuffers] 中至少保活若干轮；mesh 的三类
    // buffer 则字段持有到下一次 setMesh / destroy。这样既不复用正在被 driver 读取的内存，
    // 也不让局部 DirectByteBuffer 过早释放。
    private val pointUploadBuffers = ArrayDeque<ByteBuffer>()
    private val pointUploadBufferLimit = 8
    private lateinit var pointIndexUploadBuffer: ByteBuffer
    private var meshPositionUploadBuffer: ByteBuffer? = null
    private var meshTangentUploadBuffer: ByteBuffer? = null
    private var meshIndexUploadBuffer: ByteBuffer? = null

    // mesh 预分配上限：MC 输出不去重，每三角形 3 顶点独立。200³ grid × 30% 等值面带 ≈ 200K 顶点；
    // 取 256K 留余量。256K × 12B(pos) + 256K × 16B(tangent) + 256K × 4B(idx) ≈ 8MB GPU，
    // 手机端可接受。超过上限时 setMesh 会截断 + 打 warn log。
    private val maxMeshVertices = 256_000

    private val vertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(maxVertices)
        .bufferCount(1)
        .attribute(
            VertexBuffer.VertexAttribute.POSITION, 0,
            VertexBuffer.AttributeType.FLOAT3, 0, 12,
        )
        .build(engine)
    private val indexBuffer: IndexBuffer
    private val pointEntity: Int = EntityManager.get().create()

    // mesh 用 POSITION (float3) + TANGENTS (float4 quaternion) 双 attribute；TANGENTS 由
    // SurfaceOrientation.getQuatsAsFloat 把 normal 编码进去（lit shading model 从 quaternion
    // 解出 N 做光照）。两个 attribute 走两个 buffer slot，setBufferAt(0/1) 分别更新。
    private val meshVertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(maxMeshVertices)
        .bufferCount(2)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0,
                   VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1,
                   VertexBuffer.AttributeType.FLOAT4, 0, 16)
        .build(engine)
    private val meshIndexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(maxMeshVertices)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)
    private val meshEntity: Int = EntityManager.get().create()

    // directional light — lit material 没 IBL 时唯一光源；从右上前方照下来给 mesh 立体感
    private val lightEntity: Int = EntityManager.get().create()

    // 相机轨道参数（围绕 grid 中心 (0, 0, gridCenterZmm)）
    private var yaw: Float = 0f               // 绕世界 +y 轴
    private var pitch: Float = 0.35f          // 绕世界 +x 轴（俯角；正值=从上方看）
    private var distance: Float = 1500f       // 相机到 target 距离（mm）

    init {
        view.camera = camera

        // 加载点云 unlit material（扫描时编出的 .filamat，详见 src/main/materials/point_cloud.mat）
        material = loadMaterial(context, "materials/point_cloud.filamat")
        materialInstance = material.createInstance().apply {
            // 浅蓝点云（与 Compose Canvas 版本视觉一致）
            setParameter("baseColor", 0.49f, 0.72f, 1f, 1f)
            setParameter("pointSizePx", 6f)
        }

        // 加载 mesh lit material（扫描完成后渲染实体面，详见 src/main/materials/mesh_lit.mat）
        meshMaterial = loadMaterial(context, "materials/mesh_lit.filamat")
        meshMaterialInstance = meshMaterial.createInstance().apply {
            // 浅蓝灰偏暖（lit shading 在 directional light 下高光面会更亮，base 不要太鲜艳）
            setParameter("baseColor", 0.55f, 0.7f, 0.85f, 1f)
        }

        // 预填点云 IndexBuffer 0..maxVertices-1（POINTS primitive 用顺序索引就够）
        pointIndexUploadBuffer = ByteBuffer.allocateDirect(maxVertices * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until maxVertices) pointIndexUploadBuffer.putInt(i)
        pointIndexUploadBuffer.rewind()
        indexBuffer = IndexBuffer.Builder()
            .indexCount(maxVertices)
            .bufferType(IndexBuffer.Builder.IndexType.UINT)
            .build(engine)
        indexBuffer.setBuffer(engine, pointIndexUploadBuffer)

        val halfExtent = 500f
        val bbox = Box(0f, 0f, gridCenterZmm, halfExtent, halfExtent, halfExtent)

        // 点云 entity：indexCount=0 起步（无可见 primitive，等首批点）
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.POINTS,
                      vertexBuffer, indexBuffer, 0, 0)
            .material(0, materialInstance)
            .boundingBox(bbox)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, pointEntity)
        scene.addEntity(pointEntity)

        // mesh entity：同样 indexCount=0 起步，setMesh 时填入实际 vertex/index/tangent 数据
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES,
                      meshVertexBuffer, meshIndexBuffer, 0, 0)
            .material(0, meshMaterialInstance)
            .boundingBox(bbox)
            .culling(false)
            .castShadows(false)
            .receiveShadows(false)
            .build(engine, meshEntity)
        scene.addEntity(meshEntity)

        // directional light: 从右上前方照下来 — mesh 立体感主要靠这个；lit material 没 IBL
        // 时 fragment 光照仅来自 directional / point / spot，缺光会全黑
        // intensity 30K lux 介于 indoor 和 noon sun 之间，色温约 6500K（标准白）
        val sunRgb = Colors.cct(6500f)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(sunRgb[0], sunRgb[1], sunRgb[2])
            .intensity(30_000f)
            .direction(-0.4f, -0.8f, -0.4f)  // 朝下偏左前
            .castShadows(false)
            .build(engine, lightEntity)
        scene.addEntity(lightEntity)

        applyCamera()
    }

    private fun loadMaterial(context: Context, assetPath: String): Material {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            .put(bytes).apply { flip() }
        return Material.Builder().payload(buf, buf.remaining()).build(engine)
    }

    /**
     * 推入新一批点云。**复用** vertex/index buffer + RenderableManager entity，只改 vertex 数据
     * + index count，避免每 500ms destroy/recreate 累积 stale GPU handle（实测 1.57 在 ~20s 后撞
     * "corrupted heap Handle" SIGABRT）。
     *
     * 关键稳定性约束：
     *   - 每次上传新 DirectByteBuffer，并保活最近几轮，避免 Filament 异步上传读到已释放内存
     *   - 只写 + 只上传前 n × 12 字节；indexCount=n 时 GPU 只读前 n 个 vertex，多余 slot
     *     不被采样，不需要 anchor 填充
     */
    fun setPoints(cloud: FloatArray) {
        val n = (cloud.size / 3).coerceAtMost(maxVertices)
        val rm = engine.renderableManager
        // 切到点云模式：隐藏 mesh entity（indexCount=0）
        val meshInst = rm.getInstance(meshEntity)
        if (meshInst != 0) {
            rm.setGeometryAt(meshInst, 0, RenderableManager.PrimitiveType.TRIANGLES,
                             meshVertexBuffer, meshIndexBuffer, 0, 0)
        }
        val instance = rm.getInstance(pointEntity)
        if (n == 0) {
            if (instance != 0) {
                rm.setGeometryAt(instance, 0, RenderableManager.PrimitiveType.POINTS,
                                 vertexBuffer, indexBuffer, 0, 0)
            }
            return
        }

        val byteCount = n * 12
        val vBuf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        val fb = vBuf.asFloatBuffer()
        fb.put(cloud, 0, n * 3)
        vBuf.rewind()
        retainPointUploadBuffer(vBuf)
        vertexBuffer.setBufferAt(engine, 0, vBuf, 0, byteCount)

        if (instance != 0) {
            rm.setGeometryAt(instance, 0, RenderableManager.PrimitiveType.POINTS,
                             vertexBuffer, indexBuffer, 0, n)
        }
    }

    /**
     * 切到 mesh 实体面渲染模式 —— 上传 vertex / tangent / index 到对应 buffer，调整
     * RenderableManager indexCount，并隐藏点云 entity。
     *
     * tangent 用 Filament SurfaceOrientation 把 normal 数组编码成 4 float quaternion
     * （lit shading model 从 quaternion 解出 N 做光照）。
     *
     * vertices/normals/indices 长度限制：
     *   - vertices.size == normals.size（每顶点 3 float position + 3 float normal）
     *   - indices.size % 3 == 0（TRIANGLES 模式）
     *   - 顶点超 maxMeshVertices → 截断到上限并 log warn
     */
    fun setMesh(vertices: FloatArray, normals: FloatArray, indices: IntArray) {
        val rm = engine.renderableManager
        // 切到 mesh 模式：隐藏点云 entity
        val pointInst = rm.getInstance(pointEntity)
        if (pointInst != 0) {
            rm.setGeometryAt(pointInst, 0, RenderableManager.PrimitiveType.POINTS,
                             vertexBuffer, indexBuffer, 0, 0)
        }
        val meshInst = rm.getInstance(meshEntity)
        if (meshInst == 0) return

        val rawN = vertices.size / 3
        if (rawN == 0 || indices.isEmpty() || normals.size != vertices.size) {
            android.util.Log.w(TAG, "setMesh 跳过：v=${vertices.size} n=${normals.size} i=${indices.size}")
            rm.setGeometryAt(meshInst, 0, RenderableManager.PrimitiveType.TRIANGLES,
                             meshVertexBuffer, meshIndexBuffer, 0, 0)
            return
        }
        val n = rawN.coerceAtMost(maxMeshVertices)
        if (n < rawN) {
            android.util.Log.w(TAG, "mesh 顶点超上限 $rawN > $maxMeshVertices，截断到 $maxMeshVertices")
        }
        // indices 截断：不能超 maxMeshVertices；同时只保留指向 [0, n) 顶点的有效三角形
        val maxIndices = (indices.size / 3 * 3).coerceAtMost(maxMeshVertices / 3 * 3)
        var idxValid = 0
        run {
            // 三个一组扫一遍，全部 < n 才接受
            var i = 0
            while (i + 2 < maxIndices) {
                if (indices[i] < n && indices[i + 1] < n && indices[i + 2] < n) {
                    idxValid += 3
                }
                i += 3
            }
        }

        // 1) POSITION buffer
        val posBuf = ByteBuffer.allocateDirect(n * 12).order(ByteOrder.nativeOrder())
        posBuf.asFloatBuffer().put(vertices, 0, n * 3)
        posBuf.rewind()
        meshPositionUploadBuffer = posBuf
        meshVertexBuffer.setBufferAt(engine, 0, posBuf)

        // 2) TANGENTS buffer：normal → quaternion via SurfaceOrientation
        val normalBuf = ByteBuffer.allocateDirect(n * 12).order(ByteOrder.nativeOrder())
        normalBuf.asFloatBuffer().put(normals, 0, n * 3)
        normalBuf.rewind()
        val tangentBuf = ByteBuffer.allocateDirect(n * 16).order(ByteOrder.nativeOrder())
        val so = SurfaceOrientation.Builder()
            .vertexCount(n)
            .normals(normalBuf)
            .build()
        try {
            so.getQuatsAsFloat(tangentBuf)
        } finally {
            so.destroy()
        }
        tangentBuf.rewind()
        meshTangentUploadBuffer = tangentBuf
        meshVertexBuffer.setBufferAt(engine, 1, tangentBuf)

        // 3) IndexBuffer：紧凑写入有效三角形索引
        val idxBuf = ByteBuffer.allocateDirect(idxValid * 4).order(ByteOrder.nativeOrder())
        run {
            var i = 0
            while (i + 2 < maxIndices) {
                val a = indices[i]; val b = indices[i + 1]; val c = indices[i + 2]
                if (a < n && b < n && c < n) {
                    idxBuf.putInt(a); idxBuf.putInt(b); idxBuf.putInt(c)
                }
                i += 3
            }
        }
        idxBuf.rewind()
        meshIndexUploadBuffer = idxBuf
        if (idxValid > 0) {
            // setBuffer 要求 byte count 与 indexCount * 4 一致；只写 idxValid 部分
            meshIndexBuffer.setBuffer(engine, idxBuf, 0, idxValid * 4)
        }

        rm.setGeometryAt(meshInst, 0, RenderableManager.PrimitiveType.TRIANGLES,
                         meshVertexBuffer, meshIndexBuffer, 0, idxValid)
        android.util.Log.i(TAG, "setMesh v=$n indices=$idxValid (raw v=$rawN i=${indices.size})")
    }

    private fun retainPointUploadBuffer(buffer: ByteBuffer) {
        pointUploadBuffers.addLast(buffer)
        while (pointUploadBuffers.size > pointUploadBufferLimit) {
            pointUploadBuffers.removeFirst()
        }
    }

    private fun applyCamera() {
        val tx = 0.0; val ty = 0.0; val tz = gridCenterZmm.toDouble()
        val sinP = sin(pitch.toDouble()); val cosP = cos(pitch.toDouble())
        val sinY = sin(yaw.toDouble());   val cosY = cos(yaw.toDouble())
        val ex = tx + distance * cosP * sinY
        val ey = ty + distance * sinP
        val ez = tz + distance * cosP * cosY
        camera.lookAt(ex, ey, ez, tx, ty, tz, 0.0, 1.0, 0.0)
    }

    // ───── Choreographer 渲染循环（每帧 render） ─────
    //
    // 必须每个 vsync 都强制 renderer.render，不能用 dirty flag 偷懒。
    //
    // 之前 dirty flag 实现造成画面花屏的真因：Compose 的 hardware-accelerated layer 跟
    // TextureView 的 SurfaceTexture 共享 GL context；LiveStreamRow 每秒 6 次创建新 RGB
    // Bitmap → Compose 上传新 GL texture，**复用了我们 SurfaceTexture 的 buffer 池**。
    // dirty=false 时 Filament 不 render，SurfaceTexture buffer 长时间不被 Filament 覆盖
    // → 镜头变化触发 markDirty 再 render 时，buffer 内容已被 Compose 写成 RGB 摄像头帧的
    // 字节 → GPU 把 RGB 字节当 RGBA 像素采样 → PointCloudPreview 显示成 RGB 摄像头画面
    // 被错位拉伸成的横条纹（详见 finding_pointcloud3d_corruption_2026-05-07.md）。
    //
    // 修法：每个 vsync 都 render 一次，让 Filament 持续主导 SurfaceTexture buffer，Compose
    // 不能复用。代价是 60fps 的 GL command 流量，但实测在 Adreno 619 上稳定运行（GL state
    // 累积错乱不是真问题，跟 dirty flag 引入的 buffer 污染搞混了）。
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val sc = swapChain
            if (sc != null && uiHelper.isReadyToRender) {
                if (renderer.beginFrame(sc, frameTimeNanos)) {
                    renderer.render(view)
                    renderer.endFrame()
                }
            }
            choreographer.postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        choreographer.removeFrameCallback(frameCallback)
    }

    // ───── 手势 ─────
    private var lastX = 0f; private var lastY = 0f
    private var lastSpan = 0f
    @Volatile private var pinching = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x; lastY = ev.y
                pinching = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2) {
                    pinching = true
                    lastSpan = pointerSpan(ev)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pinching && ev.pointerCount >= 2) {
                    val span = pointerSpan(ev)
                    if (lastSpan > 0f) {
                        val ratio = lastSpan / span
                        distance = (distance * ratio).coerceIn(300f, 5000f)
                        applyCamera()
                    }
                    lastSpan = span
                } else {
                    val dx = ev.x - lastX; val dy = ev.y - lastY
                    yaw   -= dx * 0.006f
                    pitch += dy * 0.006f
                    pitch = pitch.coerceIn(-1.4f, 1.4f)
                    lastX = ev.x; lastY = ev.y
                    applyCamera()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.pointerCount <= 2) {
                    pinching = false
                    // 找到剩余那一指，重置 lastX/Y 避免跳变
                    val remaining = (0 until ev.pointerCount).first { it != ev.actionIndex }
                    lastX = ev.getX(remaining); lastY = ev.getY(remaining)
                }
            }
        }
        return true
    }

    private fun pointerSpan(ev: MotionEvent): Float {
        if (ev.pointerCount < 2) return 0f
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun destroy() {
        choreographer.removeFrameCallback(frameCallback)

        scene.removeEntity(pointEntity)
        scene.removeEntity(meshEntity)
        scene.removeEntity(lightEntity)
        engine.entityManager.destroy(pointEntity)
        engine.entityManager.destroy(meshEntity)
        engine.entityManager.destroy(lightEntity)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        engine.destroyVertexBuffer(meshVertexBuffer)
        engine.destroyIndexBuffer(meshIndexBuffer)
        engine.destroyMaterialInstance(materialInstance)
        engine.destroyMaterial(material)
        engine.destroyMaterialInstance(meshMaterialInstance)
        engine.destroyMaterial(meshMaterial)
        pointUploadBuffers.clear()
        meshPositionUploadBuffer = null
        meshTangentUploadBuffer = null
        meshIndexUploadBuffer = null

        engine.destroyCameraComponent(cameraEntity)
        engine.entityManager.destroy(cameraEntity)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)

        uiHelper.detach()
        swapChain?.let { engine.destroySwapChain(it); swapChain = null }
        engine.destroy()
    }
}
