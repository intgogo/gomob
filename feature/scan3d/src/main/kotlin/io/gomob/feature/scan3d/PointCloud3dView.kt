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
 * 交互（模型查看器范式）：
 *   - 单指拖动：FREE=全向轨道(yaw+pitch)；顶/侧/斜=锁俯仰，仅绕地面"上"轴转台旋转(yaw)。
 *   - 双指：捏合缩放距离 + 双指平移(midpoint 位移)沿屏幕面平移取景中心。
 *   - 视角重置：resetSignal 自增触发，回到当前预设家位 + 清平移 + 重设包围球距离。
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
/** 视角预设：自由(全向轨道)/顶视/侧视/斜视。相对检测到的地面法向("上")定向。 */
enum class LaserViewPreset { FREE, TOP, SIDE, OBLIQUE }

@Composable
fun PointCloud3dView(
    points: FloatArray,
    modifier: Modifier = Modifier,
    gridCenterZmm: Float = 750f,  // 默认值与 Scan3dRecordingScreen / SessionCreate 对齐 (grid z[0,1500]mm)
    mesh: ScanMeshData? = null,
    // autoFit=true：按点云包围球自动取景（target=质心、distance 由半径推、far 随之扩）。
    // 用于坐标 X/Y 可能远离原点、Z 跨度可达数十米的整云（如激光融合/单元云）—— 固定
    // target(0,0,gridCenterZmm)+far=6000 会把整云切出视锥而全黑。拟合只在首帧/切云时做一次
    // （见 autoFitKey + needsFit），流式增量点不重拟合，避免冲掉用户手动视角。
    autoFit: Boolean = false,
    // 视角"上"方向（地面法向，单位向量）。默认世界 +Y（兼容 RGBD 预览）；激光页传地面法向把
    // 设备世界系的竖直倾斜(~5.6°)摆正。orbit/预设都相对该轴。
    upAxis: FloatArray = floatArrayOf(0f, 1f, 0f),
    // 视角预设：切换时跳到对应机位（顶/侧/斜）；FREE=全向轨道家位。拖拽始终可用。
    viewPreset: LaserViewPreset = LaserViewPreset.FREE,
    // 地面参考网格：在检测到的地面平面上叠半透网格（需 upAxis=地面法向 + groundD）。
    showGround: Boolean = false,
    groundD: Float = 0f, // 地面平面 offset: up·x + groundD = 0（mm）
    // 视角重置信号：每自增一次触发回到当前预设家位（清平移 + 重设包围球距离 + 重置角度）。
    resetSignal: Int = 0,
    // autoFit 重拟合键：值变化时请求下一帧重拟合取景。仅在「切换显示的云」等需要重新入镜时变（如
    // 激光页传当前选中的云 FUSED/A/B）；同一云增量生长时不变 → 不重拟合，保住用户手动视角。
    autoFitKey: Any? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember { PointCloudSurfaceView(context, gridCenterZmm, autoFit) }

    LaunchedEffect(upAxis.contentHashCode(), groundD, showGround) {
        view.setGround(upAxis, groundD, showGround)
    }
    LaunchedEffect(mesh) {
        if (mesh != null) {
            view.setMesh(mesh.vertices, mesh.normals, mesh.indices)
        }
    }
    // 须在 points 效应之前声明：切云时 autoFitKey 与 points 同时变，按声明序先请求重拟合再 setPoints。
    LaunchedEffect(autoFitKey) {
        view.requestRefit()
    }
    LaunchedEffect(points, mesh) {
        // mesh 在显示时点云隐藏（避免叠加）；mesh 为 null 时点云正常更新
        if (mesh == null) view.setPoints(points)
    }
    LaunchedEffect(viewPreset) {
        view.applyPreset(viewPreset)
    }
    LaunchedEffect(resetSignal) {
        if (resetSignal > 0) view.resetView()
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
    private val autoFit: Boolean = false,
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
                viewHeightPx = height.coerceAtLeast(1)
                lastAspect = width.toDouble() / height.coerceAtLeast(1)
                camera.setProjection(45.0, lastAspect, 50.0, currentFar(), Camera.Fov.VERTICAL)
                    }
        }
        attachTo(this@PointCloudSurfaceView)
    }

    private val material: Material
    private val materialInstance: MaterialInstance
    private val meshMaterial: Material
    private val meshMaterialInstance: MaterialInstance
    private val gridMaterialInstance: MaterialInstance // 地面网格用 unlit 同材质另开实例（暗灰）

    // 点云预分配上限：与 Scan3dRecordingViewModel.MAX_PREVIEW_VERTICES 对齐
    // 不每帧 destroy/recreate VB/IB —— 那会累积 stale handle 让 FEngine::loop 在 ~20s
    // 后撞到 "corrupted heap Handle" SIGABRT。改为一次性 alloc，setBufferAt 复用 + 用
    // RenderableManager.setGeometryAt 调整 index count。
    // 激光真扫掠后单元云 30万+、融合云 60万+（实测 job9 fused=619265）。上限太小时旧实现按
    // 前 N 截断 —— 而融合云是 [A 全部 + B 全部] 拼接，截前 N 会丢掉整个 B（用户反馈"镜头只剩
    // 一半/不对"）。改：上限提到 80 万覆盖当前融合云全量；超限时 setPoints 走 stride 均匀抽样
    // （非截尾），保全空间覆盖。预分配 vertex≈9.6MB + index≈3.2MB GPU，单 view 可接受。
    private val maxVertices = 800_000

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

    // 地面参考网格（LINES primitive，unlit）。预分配上限 maxGridVerts；setGround 时按地面基重建。
    private val maxGridVerts = 1024
    private val gridVertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(maxGridVerts).bufferCount(1)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .build(engine)
    private val gridIndexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(maxGridVerts).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
    private val gridEntity: Int = EntityManager.get().create()
    private var gridVertCount = 0
    private var gridUploadBuffer: ByteBuffer? = null // 保活异步上传 buffer

    // 相机轨道参数（围绕 grid 中心 (0, 0, gridCenterZmm)，或 autoFit 时围绕点云质心）。
    // yaw/pitch 相对地面正交基 {right, up, fwd}（up=地面法向）；非世界轴 —— 这样顶/侧视相对真实
    // 地面而非设备倾斜的世界系。
    private var yaw: Float = 0f               // 绕 up 轴方位
    private var pitch: Float = 0.35f          // 俯仰（正值=从上方看）
    private var distance: Float = 1500f       // 相机到 target 距离（mm）

    // 双指平移累计偏移（mm，世界系）。加到取景中心上，让用户把点云拖到画面任意位置。
    private var panX = 0f; private var panY = 0f; private var panZ = 0f
    // 当前预设：决定单指拖动是全向轨道(FREE)还是仅转台旋转(顶/侧/斜锁俯仰)。
    private var preset: LaserViewPreset = LaserViewPreset.FREE
    private var viewHeightPx = 1               // 视口高（px），双指平移把屏幕位移换算成世界位移用

    // 地面正交基：up=地面法向，right/fwd 张成地面。默认世界 +Y（兼容 RGBD）。
    private var upX = 0f; private var upY = 1f; private var upZ = 0f
    private var rgX = 1f; private var rgY = 0f; private var rgZ = 0f
    private var fwX = 0f; private var fwY = 0f; private var fwZ = 1f
    private var groundOffsetD = 0f            // 地面平面 offset（up·x + d = 0）
    private var groundVisible = false

    // autoFit 取景状态：首次拿到非空点云时按包围球拟合一次（target=质心、distance/far 由半径推），
    // 之后用户手势 orbit/zoom/pan 在此基础上微调，不再重拟合。
    private var hasFit = false
    // 用户是否已手动操作过视角（orbit/zoom/pan）。未操作时 autoFit 每帧跟随云生长取景；一旦用户上手
    // 就冻结，流式增量点不再重拟合，保住手动调好的位置/缩放/平移（实测反馈"更新点云时位置大小被重置"）。
    // 切换显示的云(requestRefit) / 清空重来 / 重置键 会清此标志，让新内容重新自动取景。
    private var userInteracted = false
    private var fitTargetX = 0f
    private var fitTargetY = 0f
    private var fitTargetZ = gridCenterZmm
    private var fitRadius = 500f
    private var lastAspect = 1.0

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

        // 地面网格用同一 unlit 材质另开实例（暗灰，pointSizePx 对 LINES 无效但需设）。
        gridMaterialInstance = material.createInstance().apply {
            setParameter("baseColor", 0.40f, 0.45f, 0.52f, 1f)
            setParameter("pointSizePx", 1f)
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

        // 地面网格 entity（LINES）：顺序索引 0,1,2,... 每相邻两点成一条线段。indexCount=0 起步。
        val gridIdx = ByteBuffer.allocateDirect(maxGridVerts * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until maxGridVerts) gridIdx.putInt(i)
        gridIdx.rewind()
        gridIndexBuffer.setBuffer(engine, gridIdx)
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.LINES, gridVertexBuffer, gridIndexBuffer, 0, 0)
            .material(0, gridMaterialInstance)
            .boundingBox(bbox)
            .culling(false).castShadows(false).receiveShadows(false)
            .build(engine, gridEntity)
        scene.addEntity(gridEntity)

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
        val total = cloud.size / 3
        val rm = engine.renderableManager
        // 切到点云模式：隐藏 mesh entity（indexCount=0）
        val meshInst = rm.getInstance(meshEntity)
        if (meshInst != 0) {
            rm.setGeometryAt(meshInst, 0, RenderableManager.PrimitiveType.TRIANGLES,
                             meshVertexBuffer, meshIndexBuffer, 0, 0)
        }
        val instance = rm.getInstance(pointEntity)
        if (total == 0) {
            if (instance != 0) {
                rm.setGeometryAt(instance, 0, RenderableManager.PrimitiveType.POINTS,
                                 vertexBuffer, indexBuffer, 0, 0)
            }
            userInteracted = false // 清空（新扫描复位/切到空云）→ 下批非空点云恢复自动取景
            return
        }

        // 超上限：按 stride 均匀抽样（每 stride 个取 1），保全空间覆盖；绝不前 N 截断（融合云
        // = A 全部 + B 全部拼接，截尾会整段丢 B）。stride=1 时全量上传。
        val stride = if (total > maxVertices) (total + maxVertices - 1) / maxVertices else 1
        val n = if (stride == 1) total else ((total + stride - 1) / stride).coerceAtMost(maxVertices)

        val byteCount = n * 12
        val vBuf = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())
        val fb = vBuf.asFloatBuffer()
        if (stride == 1) {
            fb.put(cloud, 0, n * 3)
        } else {
            var src = 0; var cnt = 0
            while (cnt < n) {
                val base = src * 3
                fb.put(cloud[base]); fb.put(cloud[base + 1]); fb.put(cloud[base + 2])
                src += stride; cnt++
            }
        }
        vBuf.rewind()
        retainPointUploadBuffer(vBuf)
        vertexBuffer.setBufferAt(engine, 0, vBuf, 0, byteCount)

        if (instance != 0) {
            rm.setGeometryAt(instance, 0, RenderableManager.PrimitiveType.POINTS,
                             vertexBuffer, indexBuffer, 0, n)
        }

        // autoFit：用户未上手时每帧按整云包围球跟随取景（采集时跟着云生长）；一旦用户手动操作过
        // (userInteracted) 即冻结，不再重拟合，保住手动视角（实测：每帧拟合会把位置/缩放/平移冲掉）。
        if (autoFit && !userInteracted) fitTo(cloud, total)
    }

    /** 切换显示的云时调：清"用户已交互"，让新云恢复自动取景跟随。 */
    fun requestRefit() {
        userInteracted = false
    }

    /** 远裁剪面：autoFit 后取 distance + 2R 余量覆盖整云深度；否则沿用默认 6000mm。 */
    private fun currentFar(): Double =
        if (hasFit) (distance + 2f * fitRadius + 200f).toDouble() else 6000.0

    /** 坐标是否在合理范围（有限且 |v|≤50m mm）。用于 autoFit 抗离群，与服务端 handlePts 阈值一致。 */
    private fun isSane(v: Float): Boolean = v.isFinite() && kotlin.math.abs(v) <= 50_000f

    /**
     * 按点云包围球拟合相机一次：target=质心，distance=R/sin(22.5°)·余量（让半径 R 球恰好入 45° 垂直
     * FOV），并据此扩远裁剪面。整云坐标可能 X/Y 远离原点、Z 跨数十米，固定取景会整云出锥；此法保证可见。
     */
    private fun fitTo(cloud: FloatArray, n: Int) {
        if (n <= 0) return
        // 抗离群：设备偶发吐坐标爆表(~1e37mm)的垃圾点，若纳入质心/半径会把相机 target 拉到 1e31mm
        // 外、真实云出锥变黑（用户反馈"镜头完全不对"真因）。服务端已在源头过滤，这里再兜一层：
        // 统计质心与半径时跳过非有限 / 超 SANE_MM 的点。SANE_MM=50m 远大于真实场景，不误杀。
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        var valid = 0
        var i = 0
        val lim = n * 3
        while (i < lim) {
            val x = cloud[i]; val y = cloud[i + 1]; val z = cloud[i + 2]
            if (isSane(x) && isSane(y) && isSane(z)) { cx += x; cy += y; cz += z; valid++ }
            i += 3
        }
        if (valid == 0) return
        val inv = 1.0 / valid
        cx *= inv; cy *= inv; cz *= inv
        var maxR2 = 0.0
        i = 0
        while (i < lim) {
            val x = cloud[i]; val y = cloud[i + 1]; val z = cloud[i + 2]
            if (!(isSane(x) && isSane(y) && isSane(z))) { i += 3; continue }
            val dx = x - cx; val dy = y - cy; val dz = z - cz
            val r2 = dx * dx + dy * dy + dz * dz
            if (r2 > maxR2) maxR2 = r2
            i += 3
        }
        fitTargetX = cx.toFloat(); fitTargetY = cy.toFloat(); fitTargetZ = cz.toFloat()
        fitRadius = kotlin.math.sqrt(maxR2).toFloat().coerceIn(100f, 100_000f)
        // d = R / sin(fov/2)，fov=45° → sin22.5°≈0.3827；×1.2 留边距。
        distance = (fitRadius / 0.3827f * 1.2f).coerceIn(300f, 80_000f)
        hasFit = true
        camera.setProjection(45.0, lastAspect, 50.0, currentFar(), Camera.Fov.VERTICAL)
        applyCamera()
        if (groundVisible) buildGrid() // 取景确定后按质心/半径铺地面网格
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
        // 取景中心 = 包围球质心(或 grid 中心) + 双指平移累计偏移。
        val tx = (if (hasFit) fitTargetX else 0f).toDouble() + panX
        val ty = (if (hasFit) fitTargetY else 0f).toDouble() + panY
        val tz = (if (hasFit) fitTargetZ else gridCenterZmm).toDouble() + panZ
        val sinP = sin(pitch.toDouble()); val cosP = cos(pitch.toDouble())
        val sinY = sin(yaw.toDouble());   val cosY = cos(yaw.toDouble())
        // 相机偏移在地面正交基 {right, up, fwd} 下展开：方位绕 up、俯仰抬离地面。
        val oR = distance * cosP * sinY
        val oU = distance * sinP
        val oF = distance * cosP * cosY
        val ex = tx + oR * rgX + oU * upX + oF * fwX
        val ey = ty + oR * rgY + oU * upY + oF * fwY
        val ez = tz + oR * rgZ + oU * upZ + oF * fwZ
        camera.lookAt(ex, ey, ez, tx, ty, tz, upX.toDouble(), upY.toDouble(), upZ.toDouble())
    }

    /** 设置地面平面：法向(=up)、offset、是否显示网格。重算正交基并按需重建网格。 */
    fun setGround(upAxis: FloatArray, d: Float, show: Boolean) {
        if (upAxis.size >= 3) {
            val len = kotlin.math.sqrt(upAxis[0] * upAxis[0] + upAxis[1] * upAxis[1] + upAxis[2] * upAxis[2])
            if (len > 1e-4f) {
                upX = upAxis[0] / len; upY = upAxis[1] / len; upZ = upAxis[2] / len
                // 取与 up 最不平行的世界轴做参考，叉乘出 right、fwd（正交基，张成地面）。
                val rfx: Float; val rfy: Float; val rfz: Float
                if (kotlin.math.abs(upZ) < 0.9f) { rfx = 0f; rfy = 0f; rfz = 1f } else { rfx = 1f; rfy = 0f; rfz = 0f }
                // right = up × ref
                var rx = upY * rfz - upZ * rfy; var ry = upZ * rfx - upX * rfz; var rz = upX * rfy - upY * rfx
                val rl = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
                rx /= rl; ry /= rl; rz /= rl
                rgX = rx; rgY = ry; rgZ = rz
                // fwd = right × up
                fwX = ry * upZ - rz * upY; fwY = rz * upX - rx * upZ; fwZ = rx * upY - ry * upX
            }
        }
        groundOffsetD = d
        groundVisible = show
        applyCamera()
        if (show && hasFit) buildGrid() else if (!show) hideGrid()
    }

    /**
     * 跳到视角预设（相对地面 up）。切预设同时清平移偏移，回到正中家位。
     * 之后单指拖动：FREE=全向轨道；顶/侧/斜=锁俯仰仅转台旋转(yaw)。
     */
    fun applyPreset(p: LaserViewPreset) {
        preset = p
        panX = 0f; panY = 0f; panZ = 0f
        when (p) {
            LaserViewPreset.TOP -> { yaw = 0f; pitch = 1.50f }        // ~86° 俯视（避开 90° 退化）
            LaserViewPreset.SIDE -> { yaw = 0f; pitch = 0.02f }       // 近水平侧看
            LaserViewPreset.OBLIQUE -> { yaw = 0.785f; pitch = 0.62f }// 方位45°/俯仰~35° 斜视
            LaserViewPreset.FREE -> { yaw = 0.35f; pitch = 0.42f }    // 3/4 家位
        }
        applyCamera()
    }

    /** 视角重置：回到当前预设家位（角度），清双指平移，并按包围球重设距离/远裁剪。 */
    fun resetView() {
        userInteracted = false // 重置 → 恢复自动取景跟随（再有增量点会重新拟合）
        if (autoFit && hasFit) {
            distance = (fitRadius / 0.3827f * 1.2f).coerceIn(300f, 80_000f)
            camera.setProjection(45.0, lastAspect, 50.0, currentFar(), Camera.Fov.VERTICAL)
        }
        applyPreset(preset) // 重置角度 + 清平移 + applyCamera
    }

    /** 在地面平面上铺参考网格（{right,fwd} 基，过质心投影点，按半径定范围，~0.5m 间距）。 */
    private fun buildGrid() {
        if (!hasFit) return
        // 质心投影到地面平面：c' = c - (up·c + d) * up
        val cu = upX * fitTargetX + upY * fitTargetY + upZ * fitTargetZ + groundOffsetD
        val gx = fitTargetX - cu * upX; val gy = fitTargetY - cu * upY; val gz = fitTargetZ - cu * upZ
        // 半径取包围球，网格半边 = radius，间距取使线数 ≤ ~20 的整 0.5m 倍数。
        val half = fitRadius.coerceIn(500f, 20_000f)
        var spacing = 500f
        while (half * 2f / spacing > 20f) spacing *= 2f
        val lines = (half / spacing).toInt()
        val verts = ArrayList<Float>(8 * (2 * lines + 1))
        var k = -lines
        while (k <= lines) {
            val off = k * spacing
            // 平行 fwd 的线（沿 right 偏移 off）：从 -half 到 +half 沿 fwd
            val ax = gx + rgX * off; val ay = gy + rgY * off; val az = gz + rgZ * off
            verts.add(ax - fwX * half); verts.add(ay - fwY * half); verts.add(az - fwZ * half)
            verts.add(ax + fwX * half); verts.add(ay + fwY * half); verts.add(az + fwZ * half)
            // 平行 right 的线（沿 fwd 偏移 off）：从 -half 到 +half 沿 right
            val bx = gx + fwX * off; val by = gy + fwY * off; val bz = gz + fwZ * off
            verts.add(bx - rgX * half); verts.add(by - rgY * half); verts.add(bz - rgZ * half)
            verts.add(bx + rgX * half); verts.add(by + rgY * half); verts.add(bz + rgZ * half)
            k++
        }
        var nv = verts.size / 3
        if (nv > maxGridVerts) nv = maxGridVerts / 2 * 2 // 保偶数（成对成线）
        val buf = ByteBuffer.allocateDirect(nv * 12).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        fb.put(verts.toFloatArray(), 0, nv * 3)
        buf.rewind()
        gridUploadBuffer = buf // 保活，防异步上传读已释放内存
        gridVertexBuffer.setBufferAt(engine, 0, buf, 0, nv * 12)
        gridVertCount = nv
        val inst = engine.renderableManager.getInstance(gridEntity)
        if (inst != 0) {
            engine.renderableManager.setGeometryAt(inst, 0, RenderableManager.PrimitiveType.LINES,
                gridVertexBuffer, gridIndexBuffer, 0, nv)
        }
    }

    private fun hideGrid() {
        val inst = engine.renderableManager.getInstance(gridEntity)
        if (inst != 0) {
            engine.renderableManager.setGeometryAt(inst, 0, RenderableManager.PrimitiveType.LINES,
                gridVertexBuffer, gridIndexBuffer, 0, 0)
        }
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
    private var lastMidX = 0f; private var lastMidY = 0f
    @Volatile private var pinching = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.x; lastY = ev.y
                pinching = false
                // 点云区域独占手势：禁止可滚动祖先（外层 Column/LazyColumn/Compose scroll）拦截，
                // 否则上下拖拽旋转会被滚动抢走（用户反馈"点云操作与上下滑动冲突"）。Compose 经
                // AndroidView interop 透传该请求给其 pointer 输入，停掉祖先 scrollable 的拦截。
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.pointerCount >= 2) {
                    pinching = true
                    lastSpan = pointerSpan(ev)
                    lastMidX = (ev.getX(0) + ev.getX(1)) * 0.5f
                    lastMidY = (ev.getY(0) + ev.getY(1)) * 0.5f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                userInteracted = true // 用户上手 → 冻结 autoFit，后续增量点不再重拟合冲掉手动视角
                if (pinching && ev.pointerCount >= 2) {
                    // 双指：捏合缩放(span) + 双指拖动平移(midpoint 位移)。
                    val span = pointerSpan(ev)
                    val midX = (ev.getX(0) + ev.getX(1)) * 0.5f
                    val midY = (ev.getY(0) + ev.getY(1)) * 0.5f
                    if (lastSpan > 0f) {
                        val ratio = lastSpan / span
                        val maxD = if (hasFit) (fitRadius * 8f).coerceAtLeast(5000f) else 5000f
                        distance = (distance * ratio).coerceIn(300f, maxD)
                        if (hasFit) camera.setProjection(45.0, lastAspect, 50.0, currentFar(), Camera.Fov.VERTICAL)
                    }
                    panByScreen(midX - lastMidX, midY - lastMidY)
                    lastSpan = span; lastMidX = midX; lastMidY = midY
                    applyCamera()
                } else {
                    val dx = ev.x - lastX; val dy = ev.y - lastY
                    yaw -= dx * 0.006f
                    // FREE=全向轨道(可俯仰)；顶/侧/斜=锁俯仰，只绕地面"上"轴转台旋转。
                    if (preset == LaserViewPreset.FREE) {
                        pitch += dy * 0.006f
                        pitch = pitch.coerceIn(-1.4f, 1.4f)
                    }
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 手势结束，恢复祖先拦截能力（页面其它区域可正常滚动）。
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }

    /**
     * 双指屏幕位移 → 取景中心世界平移。沿当前相机屏幕基(右/上)反向移动 target，让点云跟手：
     * 手指右移=点云右移。像素→mm 用当前距离下 45°FOV 的可见高度换算。
     */
    private fun panByScreen(dScreenX: Float, dScreenY: Float) {
        if (dScreenX == 0f && dScreenY == 0f) return
        val sinP = sin(pitch.toDouble()); val cosP = cos(pitch.toDouble())
        val sinY = sin(yaw.toDouble()); val cosY = cos(yaw.toDouble())
        // 屏幕右 = 视线方位绕 up 转出的水平向量 (cosY·right − sinY·fwd)。始终在地面平面内、单位长，
        // 顶视(pitch→90°)也不退化（不用 f×up，那在俯视时 f∥up 会塌成 0）。
        val sx = cosY * rgX - sinY * fwX
        val sy = cosY * rgY - sinY * fwY
        val sz = cosY * rgZ - sinY * fwZ
        // 相机偏移单位方向 d(target→eye)；前向 f = −d。屏幕上 = s × f = d × s。
        val dx = cosP * sinY * rgX + sinP * upX + cosP * cosY * fwX
        val dy = cosP * sinY * rgY + sinP * upY + cosP * cosY * fwY
        val dz = cosP * sinY * rgZ + sinP * upZ + cosP * cosY * fwZ
        val ux = dy * sz - dz * sy; val uy = dz * sx - dx * sz; val uz = dx * sy - dy * sx
        // 像素→世界：45° 垂直 FOV 下可见高度 ≈ 2·distance·tan(22.5°) ≈ 0.828·distance。
        val worldPerPx = 0.8284f * distance / viewHeightPx
        // 手指右移(dScreenX>0)→ target 沿屏幕右反向移动（点云右移）；手指下移→ target 沿屏幕上正向（点云下移）。
        val mvR = (-dScreenX * worldPerPx).toFloat()
        val mvU = (dScreenY * worldPerPx).toFloat()
        panX += (sx * mvR + ux * mvU).toFloat()
        panY += (sy * mvR + uy * mvU).toFloat()
        panZ += (sz * mvR + uz * mvU).toFloat()
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
        scene.removeEntity(gridEntity)
        scene.removeEntity(lightEntity)
        engine.entityManager.destroy(pointEntity)
        engine.entityManager.destroy(meshEntity)
        engine.entityManager.destroy(gridEntity)
        engine.entityManager.destroy(lightEntity)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        engine.destroyVertexBuffer(meshVertexBuffer)
        engine.destroyIndexBuffer(meshIndexBuffer)
        engine.destroyVertexBuffer(gridVertexBuffer)
        engine.destroyIndexBuffer(gridIndexBuffer)
        engine.destroyMaterialInstance(materialInstance)
        engine.destroyMaterialInstance(gridMaterialInstance) // 先于 material（同 material 派生）
        engine.destroyMaterial(material)
        engine.destroyMaterialInstance(meshMaterialInstance)
        engine.destroyMaterial(meshMaterial)
        pointUploadBuffers.clear()
        gridUploadBuffer = null
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
