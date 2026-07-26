package io.gomob.feature.scan3d

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Box
import com.google.android.filament.Camera
import com.google.android.filament.Colors
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Fence
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
import io.gomob.ui.feedback.FeedbackCaptureSurface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 *   - AndroidView onRelease 调 [PointCloudSurfaceView.destroy]，释放 Engine 与 GL 资源
 *   - SurfaceView attach/detach 自动调度/取消按需 Choreographer 渲染
 */
/** 视角预设：自由(全向轨道)/顶视/侧视/斜视。相对检测到的地面法向("上")定向。 */
enum class LaserViewPreset { FREE, TOP, SIDE, OBLIQUE }

private const val DEFAULT_POINT_CLOUD_BUDGET = 50_000

/**
 * Filament Engine 不是线程安全对象，且销毁必须回到创建线程。所有点云视图共用一个
 * owner Looper 串行提交命令，避免驱动初始化、点云打包和 Engine shutdown 阻塞主线程。
 */
private object PointCloudRenderDispatcher {
    private val thread = HandlerThread(
        "PointCloudFilamentOwner",
        Process.THREAD_PRIORITY_DISPLAY,
    ).apply { start() }

    val handler = Handler(thread.looper)

    fun isOwnerThread(): Boolean = Looper.myLooper() === thread.looper
}

@Composable
fun PointCloud3dView(
    points: FloatArray,
    colors: IntArray? = null,
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
    // GPU/DirectBuffer 的硬预算；普通 RGBD 预览默认 5 万，激光融合页显式传 262144。
    pointBudget: Int = DEFAULT_POINT_CLOUD_BUDGET,
    // 当前 Filament 相机的真实 view-projection 快照。用于少量世界系工程标注投影，不参与点云计算。
    onProjectionChanged: ((CameraProjectionSnapshot) -> Unit)? = null,
    // false 时保留 Engine/Surface，只停止渲染与交互。激光分镜/融合切换用它避免反复建销 Engine。
    renderingEnabled: Boolean = true,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = remember(gridCenterZmm, autoFit, pointBudget) {
        PointCloudSurfaceView(context, gridCenterZmm, autoFit, pointBudget)
    }

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
    LaunchedEffect(points, colors, mesh) {
        // mesh 在显示时点云隐藏（避免叠加）；mesh 为 null 时点云正常更新
        if (mesh == null) view.setPoints(points, colors)
    }
    LaunchedEffect(viewPreset) {
        view.applyPreset(viewPreset)
    }
    LaunchedEffect(resetSignal) {
        if (resetSignal > 0) view.resetView()
    }

    LaunchedEffect(view, onProjectionChanged) {
        view.setProjectionListener(onProjectionChanged)
    }
    LaunchedEffect(view, renderingEnabled) {
        view.setRenderingEnabled(renderingEnabled)
    }
    AndroidView(
        factory = { view },
        update = {
            it.alpha = if (renderingEnabled) 1f else 0f
            it.isEnabled = renderingEnabled
            it.setRenderingEnabled(renderingEnabled)
        },
        onRelease = {
            it.setProjectionListener(null)
            it.destroy()
        },
        modifier = modifier.fillMaxSize(),
    )
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
 *   - Choreographer 合并数据/相机/Surface 变化后按需 render；只有漫游连续输入时逐帧 render
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
    pointBudget: Int = DEFAULT_POINT_CLOUD_BUDGET,
) : SurfaceView(context), FeedbackCaptureSurface {

    companion object {
        private const val TAG = "PointCloud3dView"
    }

    private val ownerHandler = PointCloudRenderDispatcher.handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val destroyRequested = AtomicBoolean(false)
    private val surfaceGeneration = AtomicInteger(0)
    private val surfaceLifecycleLock = Any()
    @Volatile private var surfaceActiveOrCreating = false
    @Volatile private var initialized = false
    @Volatile private var attachedToWindow = false
    private var destroyed = false

    private lateinit var engine: Engine
    private lateinit var renderer: Renderer
    private lateinit var scene: Scene
    private lateinit var view: View
    private var cameraEntity: Int = 0
    private lateinit var camera: Camera

    private var swapChain: SwapChain? = null
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
        renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: android.view.Surface) {
                if (destroyRequested.get()) return
                val generation = surfaceGeneration.incrementAndGet()
                drainSurfaceSynchronously()
                ownerHandler.post {
                    attachSurfaceInternal(surface, generation)
                }
            }
            override fun onDetachedFromSurface() {
                surfaceGeneration.incrementAndGet()
                drainSurfaceSynchronously()
                ownerHandler.post {
                    if (!initialized || destroyed) return@post
                    renderRequested = false
                    cancelRender()
                }
            }
            override fun onResized(width: Int, height: Int) {
                ownerHandler.post {
                    if (!initialized || destroyed || destroyRequested.get()) return@post
                    view.viewport = Viewport(0, 0, width, height)
                    viewWidthPx = width.coerceAtLeast(1)
                    viewHeightPx = height.coerceAtLeast(1)
                    lastAspect = width.toDouble() / height.coerceAtLeast(1)
                    if (roamMode) camera.setProjection(roamFovDeg, lastAspect, 50.0, roamFar, Camera.Fov.VERTICAL)
                    else camera.setProjection(45.0, lastAspect, 50.0, currentFar(), Camera.Fov.VERTICAL)
                    markProjectionDirty()
                }
            }
        }
    }

    init {
        // 初始化命令先入队；Surface/数据回调随后只会排在它后面，避免访问半初始化资源。
        ownerHandler.post { initializeFilament(context.applicationContext) }
        uiHelper.attachTo(this@PointCloudSurfaceView)
    }

    private fun assertOwnerThread() {
        check(PointCloudRenderDispatcher.isOwnerThread()) {
            "Filament API 必须运行在 PointCloudFilamentOwner"
        }
    }

    private fun runOwnerBlocking(block: () -> Unit) {
        if (PointCloudRenderDispatcher.isOwnerThread()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var failure: Throwable? = null
        ownerHandler.postAtFrontOfQueue {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        failure?.let { throw it }
    }

    private fun attachSurfaceInternal(surface: android.view.Surface, generation: Int) {
        assertOwnerThread()
        if (
            !initialized || destroyed || destroyRequested.get() ||
            generation != surfaceGeneration.get() || !surface.isValid
        ) return
        synchronized(surfaceLifecycleLock) {
            if (generation != surfaceGeneration.get() || destroyRequested.get()) return
            surfaceActiveOrCreating = true
        }
        try {
            destroySwapChainAndWait()
            if (
                !destroyed && !destroyRequested.get() &&
                generation == surfaceGeneration.get() && surface.isValid
            ) {
                swapChain = engine.createSwapChain(surface)
                // Surface 重建即使尺寸未变也必须发布新 generation 的投影，不能沿用旧快照。
                markProjectionDirty()
            }
        } finally {
            synchronized(surfaceLifecycleLock) {
                surfaceActiveOrCreating = swapChain != null
            }
        }
    }

    /** Surface 回收前只在确有 SwapChain/创建中的时候等待 owner drain；冷初始化退出不阻塞主线程。 */
    private fun drainSurfaceSynchronously() {
        val mustWait = synchronized(surfaceLifecycleLock) { surfaceActiveOrCreating }
        if (!mustWait) return
        val latch = CountDownLatch(1)
        ownerHandler.postAtFrontOfQueue {
            try {
                if (initialized && !destroyed) {
                    renderRequested = false
                    cancelRender()
                    destroySwapChainAndWait()
                }
            } finally {
                synchronized(surfaceLifecycleLock) {
                    surfaceActiveOrCreating = false
                }
                latch.countDown()
            }
        }
        latch.await()
    }

    /**
     * Android 会在 surfaceDestroyed 返回后立即回收原生 Surface；Filament 官方合同要求先销毁
     * SwapChain 并完整 drain，否则 driver 可能继续 swap 到 abandoned BufferQueue。
     */
    private fun destroySwapChainAndWait() {
        assertOwnerThread()
        val sc = swapChain ?: return
        swapChain = null
        engine.destroySwapChain(sc)
        engine.flushAndWait()
        inFlightFence?.let { fence ->
            engine.destroyFence(fence)
            inFlightFence = null
            inFlightProjection = null
            engine.flush()
        }
    }

    private lateinit var material: Material
    private lateinit var materialInstance: MaterialInstance
    private lateinit var colorMaterial: Material
    private lateinit var colorMaterialInstance: MaterialInstance
    private lateinit var meshMaterial: Material
    private lateinit var meshMaterialInstance: MaterialInstance
    private lateinit var gridMaterialInstance: MaterialInstance // 地面网格用 unlit 同材质另开实例（暗灰）

    // 点云预分配上限由调用方契约决定；默认与 Scan3dRecordingViewModel 的 5 万点一致。
    // 不每帧 destroy/recreate VB/IB —— 那会累积 stale handle 让 FEngine::loop 在 ~20s
    // 后撞到 "corrupted heap Handle" SIGABRT。改为一次性 alloc，setBufferAt 复用 + 用
    // RenderableManager.setGeometryAt 调整 index count。
    private val maxVertices = pointBudget.also {
        require(it in 1..1_000_000) { "pointBudget 须为 1..1000000，得 $it" }
    }

    // 点云上传用两个固定 DirectByteBuffer 槽。Filament 完成回调前槽不可复用；两槽都忙时只保留
    // 最新请求，等任一槽释放后重试，既不丢最终帧也不继续排队分配。上传内存因此严格固定。
    //
    // Filament `vertexBuffer.setBufferAt(engine, slot, buffer)` 是异步上传：JNI 调用返回时
    // driver thread 可能还没读完 Java DirectByteBuffer 的 native 内存。局部变量出作用域后，
    // DirectByteBuffer 可被 GC/cleaner 回收；点数少、上传少时不容易撞上，点云变密后就会
    // 读到失效/混合内存，画面表现为彩色条纹、棋盘块或整片几何炸开。
    //
    // 镜头不变时点云稳定，每次写入字节几乎相同，race 看不出；镜头大变时 cloud 内容差异大，
    // race 立刻暴露。所以"镜头变化大才花屏"完全对应这个并发 bug。
    //
    private class PointUploadSlot(maxVertices: Int) {
        val positions: ByteBuffer = ByteBuffer.allocateDirect(maxVertices * 12).order(ByteOrder.nativeOrder())
        var colors: ByteBuffer? = null
        var inFlightParts: Int = 0
    }

    private val pointUploadLock = Any()
    private val pointUploadCallbackHandler = ownerHandler
    private lateinit var pointUploadSlots: Array<PointUploadSlot>
    private var nextPointUploadSlot = 0
    private lateinit var pointIndexUploadBuffer: ByteBuffer
    private var meshPositionUploadBuffer: ByteBuffer? = null
    private var meshTangentUploadBuffer: ByteBuffer? = null
    private var meshIndexUploadBuffer: ByteBuffer? = null

    // mesh 预分配上限：MC 输出不去重，每三角形 3 顶点独立。200³ grid × 30% 等值面带 ≈ 200K 顶点；
    // 取 256K 留余量。256K × 12B(pos) + 256K × 16B(tangent) + 256K × 4B(idx) ≈ 8MB GPU，
    // 手机端可接受。超过上限时 setMesh 会截断 + 打 warn log。
    private val maxMeshVertices = 256_000

    private lateinit var vertexBuffer: VertexBuffer
    private lateinit var indexBuffer: IndexBuffer
    private var pointEntity: Int = 0

    // mesh 用 POSITION (float3) + TANGENTS (float4 quaternion) 双 attribute；TANGENTS 由
    // SurfaceOrientation.getQuatsAsFloat 把 normal 编码进去（lit shading model 从 quaternion
    // 解出 N 做光照）。两个 attribute 走两个 buffer slot，setBufferAt(0/1) 分别更新。
    private lateinit var meshVertexBuffer: VertexBuffer
    private lateinit var meshIndexBuffer: IndexBuffer
    private var meshEntity: Int = 0

    // directional light — lit material 没 IBL 时唯一光源；从右上前方照下来给 mesh 立体感
    private var lightEntity: Int = 0

    // 地面参考网格（LINES primitive，unlit）。预分配上限 maxGridVerts；setGround 时按地面基重建。
    private val maxGridVerts = 1024
    private lateinit var gridVertexBuffer: VertexBuffer
    private lateinit var gridIndexBuffer: IndexBuffer
    private var gridEntity: Int = 0
    private var gridVertCount = 0
    private var gridUploadBuffer: ByteBuffer? = null // 保活异步上传 buffer

    // 地面实体（TRIANGLES 大方块，暗色，沉 grid 下 8mm）。给漫游清晰落脚参照，缓解迷失方向。
    private lateinit var groundVertexBuffer: VertexBuffer
    private lateinit var groundIndexBuffer: IndexBuffer
    private var groundEntity: Int = 0
    private var groundUploadBuffer: ByteBuffer? = null
    private lateinit var groundMaterialInstance: MaterialInstance

    // 标注路径（LINES，amber，铺地面上方 20mm 防 z-fight）。仿 grid 实体管理；pathMaterialInstance 在 init 赋值。
    private val maxPathVerts = 4096
    private lateinit var pathVertexBuffer: VertexBuffer
    private lateinit var pathIndexBuffer: IndexBuffer
    private var pathEntity: Int = 0
    private var pathVertCount = 0
    private var pathUploadBuffer: ByteBuffer? = null
    private lateinit var pathMaterialInstance: MaterialInstance

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
    private var viewWidthPx = 1                // 投影回调使用，与 Filament viewport 同步
    private var viewHeightPx = 1               // 视口高（px），双指平移把屏幕位移换算成世界位移用

    @Volatile private var projectionListener: ((CameraProjectionSnapshot) -> Unit)? = null
    private var projectionDirty = true
    private var projectionRevision = 0L
    private val projectionMatrix = DoubleArray(16)
    private val viewMatrix = DoubleArray(16)

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
    private var fitFarRadius = 500f
    private var lastAspect = 1.0
    @Volatile private var pointCloudInteractionEnabled = true

    // ───── 第一视角漫游（标注用）。orbit 字段不动；roamMode=false 时本段全不参与渲染/输入。 ─────
    @Volatile private var roamMode = false
    private var roamEyeH = 1600f             // 漫游眼点的绝对 h 坐标（floorH + 人眼离地高度）
    private var roamRadius = 1500f           // 鲁棒包围半径（定远裁剪 + 走动范围；抗离群坏数据）
    private var roamYaw = 0f                  // 头朝向（绕 up；0=朝 +fwd0/取景中心）
    private var roamPitch = 0f               // 抬头/低头（rad，clamp ±1.4 避退化）
    private var walkU = 0f                    // 沿 right0 的地面位移（相对取景中心投影）
    private var walkV = 0f                    // 沿 fwd0
    @Volatile private var moveStrafe = 0f     // 摇杆右(+)
    @Volatile private var moveForward = 0f    // 摇杆前(+)
    @Volatile private var moveMag = 0f        // 摇杆幅度 0..1（部分推=慢走）
    @Volatile private var lookYawRate = 0f    // 右摇杆转身：横轴(+右转)，每帧连续积分
    @Volatile private var lookPitchRate = 0f  // 右摇杆抬头低头：纵轴(+上)，每帧连续积分
    private var moveSpeedMmPerSec = 1100f
    private val turnSpeedRadPerSec = 2.0f     // 右摇杆满偏转速 ≈115°/s
    private val roamFovDeg = 50.0
    private var roamFar = 8000.0
    private var lastFrameNanos = 0L           // 帧 dt 源；0=首帧（dt=0 不跳）
    @Volatile private var pitchInvert = false
    // 取景中心(质心)在地面基的 (u,v)，世界原点系——与 projectTopView/worldBox 同源。
    private var originU = 0f; private var originV = 0f
    // 标注路径（世界原点系基坐标 [u0,v0,...]，与 projectTopView 同源，直接喂 worldBox）。
    private var annotating = false
    private val pathUV = ArrayList<Float>()
    @Volatile private var pathSnapshot = FloatArray(0)
    private var lastSampleU = 0f; private var lastSampleV = 0f; private var hasSample = false

    private fun initializeFilament(context: Context) {
        assertOwnerThread()
        if (destroyRequested.get()) {
            destroyed = true
            return
        }

        try {
            // native 库加载、驱动 barrier 和全部 GPU 资源初始化只允许发生在 owner 线程。
            Utils.init()
            engine = Engine.create()
            renderer = engine.createRenderer().apply {
                clearOptions = Renderer.ClearOptions().apply {
                    clear = true
                    discard = true
                    clearColor = floatArrayOf(0f, 0f, 0f, 1f)
                }
            }
            scene = engine.createScene()
            view = engine.createView().apply {
                scene = this@PointCloudSurfaceView.scene
                antiAliasing = View.AntiAliasing.NONE
                blendMode = View.BlendMode.OPAQUE
                isPostProcessingEnabled = false
            }
            cameraEntity = EntityManager.get().create()
            camera = engine.createCamera(cameraEntity).apply {
                setProjection(45.0, 1.0, 50.0, 6000.0, Camera.Fov.VERTICAL)
            }

            pointUploadSlots = Array(2) { PointUploadSlot(maxVertices) }
            vertexBuffer = VertexBuffer.Builder()
                .vertexCount(maxVertices)
                .bufferCount(2)
                .attribute(
                    VertexBuffer.VertexAttribute.POSITION, 0,
                    VertexBuffer.AttributeType.FLOAT3, 0, 12,
                )
                .attribute(
                    VertexBuffer.VertexAttribute.COLOR, 1,
                    VertexBuffer.AttributeType.UBYTE4, 0, 4,
                )
                .normalized(VertexBuffer.VertexAttribute.COLOR)
                .build(engine)
            meshVertexBuffer = VertexBuffer.Builder()
                .vertexCount(maxMeshVertices)
                .bufferCount(2)
                .attribute(
                    VertexBuffer.VertexAttribute.POSITION, 0,
                    VertexBuffer.AttributeType.FLOAT3, 0, 12,
                )
                .attribute(
                    VertexBuffer.VertexAttribute.TANGENTS, 1,
                    VertexBuffer.AttributeType.FLOAT4, 0, 16,
                )
                .build(engine)
            meshIndexBuffer = IndexBuffer.Builder()
                .indexCount(maxMeshVertices)
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .build(engine)
            gridVertexBuffer = VertexBuffer.Builder()
                .vertexCount(maxGridVerts)
                .bufferCount(1)
                .attribute(
                    VertexBuffer.VertexAttribute.POSITION, 0,
                    VertexBuffer.AttributeType.FLOAT3, 0, 12,
                )
                .build(engine)
            gridIndexBuffer = IndexBuffer.Builder()
                .indexCount(maxGridVerts)
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .build(engine)
            groundVertexBuffer = VertexBuffer.Builder()
                .vertexCount(4)
                .bufferCount(1)
                .attribute(
                    VertexBuffer.VertexAttribute.POSITION, 0,
                    VertexBuffer.AttributeType.FLOAT3, 0, 12,
                )
                .build(engine)
            groundIndexBuffer = IndexBuffer.Builder()
                .indexCount(6)
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .build(engine)
            pathVertexBuffer = VertexBuffer.Builder()
                .vertexCount(maxPathVerts)
                .bufferCount(1)
                .attribute(
                    VertexBuffer.VertexAttribute.POSITION, 0,
                    VertexBuffer.AttributeType.FLOAT3, 0, 12,
                )
                .build(engine)
            pathIndexBuffer = IndexBuffer.Builder()
                .indexCount(maxPathVerts)
                .bufferType(IndexBuffer.Builder.IndexType.UINT)
                .build(engine)

            pointEntity = EntityManager.get().create()
            meshEntity = EntityManager.get().create()
            lightEntity = EntityManager.get().create()
            gridEntity = EntityManager.get().create()
            groundEntity = EntityManager.get().create()
            pathEntity = EntityManager.get().create()
            choreographer = Choreographer.getInstance()

            android.util.Log.i(
                TAG,
                "Engine 创建 budget=$maxVertices owner=${Thread.currentThread().name} main=${Looper.myLooper() == Looper.getMainLooper()}",
            )
        view.camera = camera

        // 加载点云 unlit material（扫描时编出的 .filamat，详见 src/main/materials/point_cloud.mat）
        material = loadMaterial(context, "materials/point_cloud.filamat")
        materialInstance = material.createInstance().apply {
            // 浅蓝点云（与 Compose Canvas 版本视觉一致）
            setParameter("baseColor", 0.49f, 0.72f, 1f, 1f)
            setParameter("pointSizePx", 6f)
        }
        // 102 相机纹理投影后的彩色点云：同样 unlit，只把 PCD rgb 字段作为 vertex color。
        colorMaterial = loadMaterial(context, "materials/point_cloud_color.filamat")
        colorMaterialInstance = colorMaterial.createInstance().apply {
            setParameter("baseColor", 1f, 1f, 1f, 1f)
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

        // 地面实体 entity（TRIANGLES 方块）：固定索引 0,1,2,0,2,3；indexCount=0 起步，buildGrid 填 4 角。
        // 暗色不抢点云；culling(false) 双面 → 俯视也可见。
        groundMaterialInstance = material.createInstance().apply {
            setParameter("baseColor", 0.10f, 0.13f, 0.18f, 1f)
            setParameter("pointSizePx", 1f)
        }
        val groundIdx = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder())
        intArrayOf(0, 1, 2, 0, 2, 3).forEach { groundIdx.putInt(it) }
        groundIdx.rewind()
        groundIndexBuffer.setBuffer(engine, groundIdx)
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, groundVertexBuffer, groundIndexBuffer, 0, 0)
            .material(0, groundMaterialInstance)
            .boundingBox(bbox)
            .culling(false).castShadows(false).receiveShadows(false)
            .build(engine, groundEntity)
        scene.addEntity(groundEntity)

        // 标注路径 entity（LINES，amber）：顺序索引；indexCount=0 起步，走动采样时填。
        pathMaterialInstance = material.createInstance().apply {
            setParameter("baseColor", 1f, 0.773f, 0.239f, 1f) // amber，与车位框黄一致
            setParameter("pointSizePx", 1f)
        }
        val pathIdx = ByteBuffer.allocateDirect(maxPathVerts * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until maxPathVerts) pathIdx.putInt(i)
        pathIdx.rewind()
        pathIndexBuffer.setBuffer(engine, pathIdx)
        RenderableManager.Builder(1)
            .geometry(0, RenderableManager.PrimitiveType.LINES, pathVertexBuffer, pathIndexBuffer, 0, 0)
            .material(0, pathMaterialInstance)
            .boundingBox(bbox)
            .culling(false).castShadows(false).receiveShadows(false)
            .build(engine, pathEntity)
        scene.addEntity(pathEntity)

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

            initialized = true
            applyCamera()
            if (destroyRequested.get()) destroyInternal()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Filament 初始化失败 budget=$maxVertices", t)
            destroyed = true
            if (this::engine.isInitialized && engine.isValid) {
                engine.destroy()
            }
        }
    }

    private fun loadMaterial(context: Context, assetPath: String): Material {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            .put(bytes).apply { flip() }
        return Material.Builder().payload(buf, buf.remaining()).build(engine)
    }

    private data class PendingPointCloud(
        val cloud: FloatArray,
        val colors: IntArray?,
    )

    private val pendingPointLock = Any()
    private var pendingPointCloud: PendingPointCloud? = null
    private var pointCommandScheduled = false
    private val pointCommand: Runnable = Runnable {
        val request = synchronized(pendingPointLock) {
            pendingPointCloud
        }
        var consumed = request == null
        try {
            if (request != null && initialized && !destroyed && !destroyRequested.get()) {
                consumed = setPointsInternal(request.cloud, request.colors)
            } else {
                consumed = true
            }
        } catch (t: Throwable) {
            // 单次上传失败不能杀死全局 owner Looper，否则后续 Surface drain 将无法执行。
            android.util.Log.e(TAG, "setPoints 后台命令失败", t)
            consumed = true
        } finally {
            val reschedule = synchronized(pendingPointLock) {
                if (consumed && pendingPointCloud === request) {
                    pendingPointCloud = null
                }
                pointCommandScheduled = false
                if (consumed && pendingPointCloud != null && !destroyRequested.get()) {
                    pointCommandScheduled = true
                    true
                } else {
                    false
                }
            }
            if (reschedule) ownerHandler.post(pointCommand)
        }
    }

    /**
     * 推入新一批点云。**复用** vertex/index buffer + RenderableManager entity，只改 vertex 数据
     * + index count，避免每 500ms destroy/recreate 累积 stale GPU handle（实测 1.57 在 ~20s 后撞
     * "corrupted heap Handle" SIGABRT）。
     *
     * 关键稳定性约束：
     *   - 两个固定 DirectByteBuffer 槽只在 Filament 完成回调后复用
     *   - 只写 + 只上传前 n × 12 字节；indexCount=n 时 GPU 只读前 n 个 vertex，多余 slot
     *     不被采样，不需要 anchor 填充
     */
    fun setPoints(cloud: FloatArray, colors: IntArray? = null) {
        if (destroyRequested.get()) return
        synchronized(pendingPointLock) {
            pendingPointCloud = PendingPointCloud(cloud, colors)
        }
        schedulePointCommandIfPending()
    }

    private fun schedulePointCommandIfPending() {
        val schedule = synchronized(pendingPointLock) {
            if (
                pendingPointCloud != null && !pointCommandScheduled &&
                !destroyRequested.get()
            ) {
                pointCommandScheduled = true
                true
            } else false
        }
        if (schedule) ownerHandler.post(pointCommand)
    }

    /** 返回 false 表示上传槽仍忙，请求留在 latest-wins 槽中等待完成回调重试。 */
    private fun setPointsInternal(cloud: FloatArray, colors: IntArray? = null): Boolean {
        assertOwnerThread()
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
            renderedVertexCount = 0
            if (instance != 0) {
                rm.setGeometryAt(instance, 0, RenderableManager.PrimitiveType.POINTS,
                                 vertexBuffer, indexBuffer, 0, 0)
            }
            userInteracted = false // 清空（新扫描复位/切到空云）→ 下批非空点云恢复自动取景
            requestRender()
            return true
        }

        val n = total.coerceAtMost(maxVertices)
        renderedVertexCount = n
        val rgb = colors
        val hasColors = rgb != null && rgb.size >= total
        val uploadSlot = acquirePointUploadSlot(hasColors)
        if (uploadSlot == null) {
            android.util.Log.d(TAG, "setPoints 等待：两个 GPU 上传槽仍在途 total=$total")
            return false
        }

        val byteCount = n * 12
        val vBuf = uploadSlot.positions
        vBuf.clear()
        val fb = vBuf.asFloatBuffer()
        fb.clear()
        val colorBuf = if (hasColors) {
            uploadSlot.colors ?: ByteBuffer.allocateDirect(maxVertices * 4)
                .order(ByteOrder.nativeOrder())
                .also { uploadSlot.colors = it }
        } else {
            null
        }
        colorBuf?.clear()
        val colorInts = colorBuf?.asIntBuffer()?.apply { clear() }
        if (n == total) {
            // 完成态 API 已按预算返回精确点数；bulk copy 避免主/owner 线程逐 float JNI 写入。
            fb.put(cloud, 0, n * 3)
            if (colorInts != null) {
                var i = 0
                while (i < n) {
                    colorInts.put(packRgbaNative(rgb!![i]))
                    i++
                }
            }
        } else {
            var out = 0
            while (out < n) {
                val src = stratifiedPointIndex(out, n, total)
                val base = src * 3
                fb.put(cloud[base])
                fb.put(cloud[base + 1])
                fb.put(cloud[base + 2])
                colorInts?.put(packRgbaNative(rgb!![src]))
                out++
            }
        }
        vBuf.position(0)
        vBuf.limit(byteCount)
        colorBuf?.run {
            position(0)
            limit(n * 4)
        }
        val expectedParts = if (colorBuf != null) 2 else 1
        var submittedParts = 0
        try {
            vertexBuffer.setBufferAt(
                engine, 0, vBuf, 0, byteCount, pointUploadCallbackHandler,
                Runnable { releasePointUploadPart(uploadSlot) },
            )
            submittedParts++
            if (colorBuf != null) {
                vertexBuffer.setBufferAt(
                    engine, 1, colorBuf, 0, n * 4, pointUploadCallbackHandler,
                    Runnable { releasePointUploadPart(uploadSlot) },
                )
                submittedParts++
            }
        } catch (e: Throwable) {
            repeat(expectedParts - submittedParts) { releasePointUploadPart(uploadSlot) }
            throw e
        }

        if (instance != 0) {
            rm.setMaterialInstanceAt(instance, 0, if (colorBuf != null) colorMaterialInstance else materialInstance)
            rm.setGeometryAt(instance, 0, RenderableManager.PrimitiveType.POINTS,
                             vertexBuffer, indexBuffer, 0, n)
        }
        android.util.Log.i(TAG, "setPoints total=$total uploaded=$n color=${colorBuf != null}")

        // autoFit：用户未上手时每帧按整云包围球跟随取景（采集时跟着云生长）；一旦用户手动操作过
        // (userInteracted) 即冻结，不再重拟合，保住手动视角（实测：每帧拟合会把位置/缩放/平移冲掉）。
        if (autoFit && !userInteracted) fitTo(cloud, total) else requestRender()
        return true
    }

    /** 把 0xRRGGBB 编成内存字节 RR GG BB FF，供 UBYTE4 颜色 attribute 直接读取。 */
    private fun packRgbaNative(rgb: Int): Int = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
        -0x1000000 or ((rgb and 0xff) shl 16) or (rgb and 0xff00) or ((rgb ushr 16) and 0xff)
    } else {
        (rgb shl 8) or 0xff
    }

    private fun acquirePointUploadSlot(hasColors: Boolean): PointUploadSlot? = synchronized(pointUploadLock) {
        repeat(pointUploadSlots.size) { offset ->
            val index = (nextPointUploadSlot + offset) % pointUploadSlots.size
            val slot = pointUploadSlots[index]
            if (slot.inFlightParts == 0) {
                slot.inFlightParts = if (hasColors) 2 else 1
                nextPointUploadSlot = (index + 1) % pointUploadSlots.size
                return@synchronized slot
            }
        }
        null
    }

    private fun releasePointUploadPart(slot: PointUploadSlot) {
        val becameFree = synchronized(pointUploadLock) {
            if (slot.inFlightParts > 0) slot.inFlightParts--
            slot.inFlightParts == 0
        }
        if (becameFree) schedulePointCommandIfPending()
    }

    private fun stratifiedPointIndex(outputIndex: Int, outputCount: Int, sourceCount: Int): Int {
        if (outputCount == sourceCount) return outputIndex
        val start = (outputIndex.toLong() * sourceCount / outputCount).toInt()
        val end = ((outputIndex + 1L) * sourceCount / outputCount).toInt()
        val width = end - start
        if (width <= 1) return start
        var seed = (outputIndex + 1L) xor (sourceCount.toLong() shl 32) xor outputCount.toLong()
        seed += -7046029254386353131L
        seed = (seed xor (seed ushr 30)) * -4658895280553007687L
        seed = (seed xor (seed ushr 27)) * -7723592293110705685L
        seed = seed xor (seed ushr 31)
        return start + java.lang.Long.remainderUnsigned(seed, width.toLong()).toInt()
    }

    /** 切换显示的云时调：清"用户已交互"，让新云恢复自动取景跟随。 */
    fun requestRefit() {
        if (destroyRequested.get()) return
        ownerHandler.post {
            if (initialized && !destroyed) userInteracted = false
        }
    }

    /** 远裁剪面：取景按主体半径，far 仍按全量有效点半径，避免远处点被直接裁掉。 */
    private fun currentFar(): Double =
        if (hasFit) (distance + 2f * fitFarRadius + 500f).toDouble() else 6000.0

    /** 坐标是否在合理范围（有限且 |v|≤50m mm）。用于 autoFit 抗离群，与服务端 handlePts 阈值一致。 */
    private fun isSane(v: Float): Boolean = v.isFinite() && kotlin.math.abs(v) <= 50_000f

    /**
     * 主体优先拟合相机：target/radius 用地面基下 2–98% 分位数主体范围，避免背景/远端点把整车缩成
     * 小模型；far 另按全量有效点扩展，保留远处点的可见性。
     */
    private fun fitTo(cloud: FloatArray, n: Int) {
        if (n <= 0) return
        val maxSamples = 12_000
        val stride = if (n > maxSamples) (n + maxSamples - 1) / maxSamples else 1
        val sampleCapacity = if (n < maxSamples) n else maxSamples
        val us = FloatArray(sampleCapacity)
        val vs = FloatArray(sampleCapacity)
        val hs = FloatArray(sampleCapacity)
        var sampleCount = 0
        var i = 0
        val lim = n * 3
        while (i < lim) {
            val x = cloud[i]; val y = cloud[i + 1]; val z = cloud[i + 2]
            if (isSane(x) && isSane(y) && isSane(z) && sampleCount < sampleCapacity) {
                us[sampleCount] = x * rgX + y * rgY + z * rgZ
                vs[sampleCount] = x * fwX + y * fwY + z * fwZ
                hs[sampleCount] = x * upX + y * upY + z * upZ
                sampleCount++
            }
            i += 3 * stride
        }
        if (sampleCount == 0) return
        fun band(values: FloatArray): Pair<Float, Float> {
            val a = values.copyOf(sampleCount)
            a.sort()
            val last = a.lastIndex
            fun q(p: Float) = a[(last * p).toInt().coerceIn(0, last)]
            return q(0.02f) to q(0.98f)
        }
        val (u0, u1) = band(us)
        val (v0, v1) = band(vs)
        val (h0, h1) = band(hs)
        val cU = (u0 + u1) * 0.5f
        val cV = (v0 + v1) * 0.5f
        val cH = (h0 + h1) * 0.5f
        val hU = ((u1 - u0) * 0.5f).coerceAtLeast(300f)
        val hV = ((v1 - v0) * 0.5f).coerceAtLeast(300f)
        val hH = ((h1 - h0) * 0.5f).coerceAtLeast(120f)
        val cx = cU * rgX + cV * fwX + cH * upX
        val cy = cU * rgY + cV * fwY + cH * upY
        val cz = cU * rgZ + cV * fwZ + cH * upZ
        var farR2 = 0f
        i = 0
        while (i < lim) {
            val x = cloud[i]; val y = cloud[i + 1]; val z = cloud[i + 2]
            if (!(isSane(x) && isSane(y) && isSane(z))) { i += 3; continue }
            val dx = x - cx; val dy = y - cy; val dz = z - cz
            val r2 = dx * dx + dy * dy + dz * dz
            if (r2 > farR2) farR2 = r2
            i += 3
        }
        fitTargetX = cx; fitTargetY = cy; fitTargetZ = cz
        fitRadius = kotlin.math.sqrt((hU * hU + hV * hV + hH * hH).toDouble()).toFloat().coerceIn(250f, 40_000f)
        fitFarRadius = kotlin.math.sqrt(farR2.toDouble()).toFloat().coerceIn(fitRadius, 100_000f)
        // d = R / sin(fov/2)，fov=45° → sin22.5°≈0.3827；主体优先取景只留少量边距。
        distance = (fitRadius / 0.3827f * 0.95f).coerceIn(300f, 60_000f)
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
        if (destroyRequested.get()) return
        ownerHandler.post {
            if (initialized && !destroyed && !destroyRequested.get()) {
                setMeshInternal(vertices, normals, indices)
            }
        }
    }

    private fun setMeshInternal(vertices: FloatArray, normals: FloatArray, indices: IntArray) {
        assertOwnerThread()
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
            requestRender()
            return
        }
        val n = rawN.coerceAtMost(maxMeshVertices)
        renderedVertexCount = n
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
        requestRender()
    }

    private fun applyCamera() {
        if (roamMode) { applyRoamCamera(); return }
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
        markProjectionDirty()
    }

    /** 第一视角相机：眼=起点(u,v)+走动位移+hEye·up；朝向由 yaw(绕 up)+pitch 决定。 */
    private fun applyRoamCamera() {
        val uW = originU + walkU
        val vW = originV + walkV
        val hEye = roamEyeH
        val ex = uW * rgX + vW * fwX + hEye * upX
        val ey = uW * rgY + vW * fwY + hEye * upY
        val ez = uW * rgZ + vW * fwZ + hEye * upZ
        val cy = cos(roamYaw.toDouble()).toFloat(); val sy = sin(roamYaw.toDouble()).toFloat()
        val cp = cos(roamPitch.toDouble()).toFloat(); val sp = sin(roamPitch.toDouble()).toFloat()
        // 水平朝向 = cy·fwd0 + sy·right0（yaw=0 朝 +fwd0）；整体 = cp·水平 + sp·up。
        val fx = cp * (cy * fwX + sy * rgX) + sp * upX
        val fy = cp * (cy * fwY + sy * rgY) + sp * upY
        val fz = cp * (cy * fwZ + sy * rgZ) + sp * upZ
        camera.lookAt(
            ex.toDouble(), ey.toDouble(), ez.toDouble(),
            (ex + fx * 1000f).toDouble(), (ey + fy * 1000f).toDouble(), (ez + fz * 1000f).toDouble(),
            upX.toDouble(), upY.toDouble(), upZ.toDouble(),
        )
        markProjectionDirty()
    }

    fun setProjectionListener(listener: ((CameraProjectionSnapshot) -> Unit)?) {
        projectionListener = listener
        if (listener != null && !destroyRequested.get()) {
            ownerHandler.post {
                if (initialized && !destroyed) markProjectionDirty()
            }
        }
    }

    private fun markProjectionDirty() {
        projectionDirty = true
        requestRender()
    }

    private data class SubmittedProjection(
        val snapshot: CameraProjectionSnapshot,
        val surfaceGeneration: Int,
    )

    private fun takeSubmittedProjectionSnapshot(): SubmittedProjection? {
        if (!projectionDirty) return null
        projectionDirty = false
        if (projectionListener == null) return null
        camera.getProjectionMatrix(projectionMatrix)
        camera.getViewMatrix(viewMatrix)
        projectionRevision++
        return SubmittedProjection(
            snapshot = CameraProjectionSnapshot(
                viewProjection = multiplyColumnMajor4x4(projectionMatrix, viewMatrix),
                viewportWidthPx = viewWidthPx,
                viewportHeightPx = viewHeightPx,
                revision = projectionRevision,
            ),
            surfaceGeneration = surfaceGeneration.get(),
        )
    }

    private fun publishSubmittedProjection(submitted: SubmittedProjection?) {
        if (
            submitted == null || destroyed ||
            submitted.surfaceGeneration != surfaceGeneration.get()
        ) return
        val listener = projectionListener ?: return
        mainHandler.post {
            if (
                !destroyRequested.get() && projectionListener === listener &&
                submitted.surfaceGeneration == surfaceGeneration.get()
            ) {
                listener(submitted.snapshot)
            }
        }
    }

    // ───── 第一视角漫游对外接口 ─────
    // 注：原调用方（漫游标注屏）已随「工位界面重做」移除，当前无端侧调用者；作为点云视图的通用漫游能力
    // 暂保留（roamMode 默认 false，运行期完全惰性、不影响主渲染）。后续若确认不再用可整段清理。

    /**
     * 进入第一视角漫游：落在给定起点(centerU,centerV)，眼高 eyeHeight 为离地高度。
     * 起点由屏幕用 2–98 百分位**鲁棒**算（抗离群/坏数据——均值质心会被远端垃圾点拉进空域致点云出视）。
     * radius=鲁棒包围半径，定远裁剪 + 走动范围。须在 setGround + setPoints 后调。
     */
    fun enterRoamMode(centerU: Float, centerV: Float, eyeHeight: Float, radius: Float) {
        if (destroyRequested.get()) return
        ownerHandler.post {
            if (!initialized || destroyed) return@post
            roamMode = true
            pointCloudInteractionEnabled = false
            lastFrameNanos = 0L
            originU = centerU
            originV = centerV
            roamEyeH = -groundOffsetD + eyeHeight.coerceIn(900f, 2200f)
            roamRadius = radius.coerceAtLeast(500f)
            walkU = 0f
            walkV = 0f
            roamYaw = 0f
            roamPitch = 0f
            roamFar = (2f * roamRadius + 3000f).toDouble().coerceAtLeast(6000.0)
            camera.setProjection(roamFovDeg, lastAspect, 50.0, roamFar, Camera.Fov.VERTICAL)
            applyCamera()
        }
    }

    fun exitRoamMode() {
        if (destroyRequested.get()) return
        ownerHandler.post {
            if (!initialized || destroyed) return@post
            roamMode = false
            lastFrameNanos = 0L
            pointCloudInteractionEnabled = true
        }
    }

    /** 开关普通点云查看手势（orbit / pinch / pan）。漫游期点云只展示，移动由 HUD 控件接管。 */
    fun setPointCloudInteractionEnabled(enabled: Boolean) {
        pointCloudInteractionEnabled = enabled
        if (!enabled) {
            pinching = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    /** 摇杆输入：strafe 右(+)、forward 前(+)、magnitude 0..1（部分推=慢走）。主线程写、渲染回调读。 */
    fun setMoveInput(strafe: Float, forward: Float, magnitude: Float) {
        moveStrafe = strafe; moveForward = forward; moveMag = magnitude
        if (!destroyRequested.get()) ownerHandler.post {
            if (initialized && !destroyed) requestRender()
        }
    }

    /** 右摇杆转身/抬头低头输入：yawRate 横轴(+右转)、pitchRate 纵轴(+上)，−1..1。主线程写、渲染回调读。 */
    fun setLookInput(yawRate: Float, pitchRate: Float) {
        lookYawRate = yawRate; lookPitchRate = pitchRate
        if (!destroyRequested.get()) ownerHandler.post {
            if (initialized && !destroyed) requestRender()
        }
    }

    /** 转头/抬头的增量入口。pitch clamp ±1.4 避开近竖直退化。 */
    fun applyLook(dYaw: Float, dPitch: Float) {
        if (destroyRequested.get()) return
        ownerHandler.post {
            if (!initialized || destroyed) return@post
            roamYaw += dYaw
            val p = if (pitchInvert) -dPitch else dPitch
            roamPitch = (roamPitch + p).coerceIn(-1.40f, 1.40f)
            if (roamMode) applyCamera()
        }
    }

    fun setPitchInvert(on: Boolean) { pitchInvert = on }

    /** 开/关标注。起标时把当前脚下点作为路径首点。 */
    fun setAnnotating(on: Boolean) {
        if (destroyRequested.get()) return
        ownerHandler.post {
            if (!initialized || destroyed) return@post
            annotating = on
            if (on && !hasSample) {
                lastSampleU = originU + walkU
                lastSampleV = originV + walkV
                pathUV.add(lastSampleU)
                pathUV.add(lastSampleV)
                pathSnapshot = pathUV.toFloatArray()
                hasSample = true
                rebuildPath()
            }
        }
    }

    /** 清空标注路径。 */
    fun resetPath() {
        if (destroyRequested.get()) return
        ownerHandler.post {
            if (!initialized || destroyed) return@post
            pathUV.clear()
            pathSnapshot = FloatArray(0)
            hasSample = false
            pathVertCount = 0
            val inst = engine.renderableManager.getInstance(pathEntity)
            if (inst != 0) {
                engine.renderableManager.setGeometryAt(inst, 0, RenderableManager.PrimitiveType.LINES,
                    pathVertexBuffer, pathIndexBuffer, 0, 0)
            }
            requestRender()
        }
    }

    /** 标注路径采样快照（世界原点系基坐标 [u0,v0,...]，与 projectTopView/worldBox 同源）。 */
    fun pathSamplesUV(): FloatArray = pathSnapshot.copyOf()
    fun pathSampleCount(): Int = pathSnapshot.size / 2

    /** 每帧积分走动（doFrame 调）。dt 来自 frameTimeNanos，clamp 50ms 防后台恢复时瞬移。 */
    private fun integrateRoam(frameTimeNanos: Long) {
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat()
        lastFrameNanos = frameTimeNanos
        val dtc = dt.coerceIn(0f, 0.05f)
        if (dtc <= 0f) return
        var dirty = false
        // 右摇杆转身/抬头：连续转视，站着不走也能转（look-pad 拖动是叠加的离散补充）。
        val ly = lookYawRate; val lp = lookPitchRate
        if (ly != 0f || lp != 0f) {
            roamYaw += ly * turnSpeedRadPerSec * dtc
            val dp = (if (pitchInvert) -lp else lp) * turnSpeedRadPerSec * dtc
            roamPitch = (roamPitch + dp).coerceIn(-1.40f, 1.40f)
            dirty = true
        }
        // 左摇杆走动。
        val s = moveStrafe; val f = moveForward; val mag = moveMag
        if (mag > 0f && (s != 0f || f != 0f)) {
            val cy = cos(roamYaw.toDouble()).toFloat(); val sy = sin(roamYaw.toDouble()).toFloat()
            // 前=cy·fwd0+sy·right0 → (u,v)=(sy,cy)；右=cy·right0−sy·fwd0 → (u,v)=(cy,−sy)。
            var du = f * sy + s * cy
            var dv = f * cy - s * sy
            val m = kotlin.math.sqrt(du * du + dv * dv)
            if (m > 1e-4f) { du /= m; dv /= m } // 归一方向，对角不超速
            val step = moveSpeedMmPerSec * dtc * mag.coerceIn(0f, 1f)
            val lim = (roamRadius * 3f).coerceAtLeast(3000f)
            walkU = (walkU + du * step).coerceIn(-lim, lim) // 别走丢云
            walkV = (walkV + dv * step).coerceIn(-lim, lim)
            dirty = true
            if (annotating) maybeSamplePath()
        }
        if (dirty) applyCamera()
    }

    /** 走动中按位移 ≥150mm 采一个路径点，重建 LINES。 */
    private fun maybeSamplePath() {
        val curU = originU + walkU; val curV = originV + walkV
        val moved = kotlin.math.hypot((curU - lastSampleU).toDouble(), (curV - lastSampleV).toDouble())
        if (hasSample && moved < 150.0) return
        if (pathUV.size / 2 >= maxPathVerts / 2) return // 满了停采（车位足迹不会到这）
        pathUV.add(curU); pathUV.add(curV)
        pathSnapshot = pathUV.toFloatArray()
        lastSampleU = curU; lastSampleV = curV; hasSample = true
        rebuildPath()
    }

    /** 按 pathUV 重建路径 LINES（成对顶点；每点投影到地面上方 20mm）。异步上传 buffer 保活。 */
    private fun rebuildPath() {
        val inst = engine.renderableManager.getInstance(pathEntity)
        if (inst == 0) return
        val k = pathUV.size / 2
        if (k < 2) {
            engine.renderableManager.setGeometryAt(inst, 0, RenderableManager.PrimitiveType.LINES,
                pathVertexBuffer, pathIndexBuffer, 0, 0)
            return
        }
        val nv = (2 * (k - 1)).coerceAtMost(maxPathVerts / 2 * 2)
        val hLift = -groundOffsetD + 20f
        val buf = ByteBuffer.allocateDirect(nv * 12).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        var written = 0; var i = 0
        while (i < k - 1 && written + 2 <= nv) {
            val au = pathUV[i * 2]; val av = pathUV[i * 2 + 1]
            val bu = pathUV[(i + 1) * 2]; val bv = pathUV[(i + 1) * 2 + 1]
            fb.put(au * rgX + av * fwX + hLift * upX); fb.put(au * rgY + av * fwY + hLift * upY); fb.put(au * rgZ + av * fwZ + hLift * upZ)
            fb.put(bu * rgX + bv * fwX + hLift * upX); fb.put(bu * rgY + bv * fwY + hLift * upY); fb.put(bu * rgZ + bv * fwZ + hLift * upZ)
            written += 2; i++
        }
        buf.rewind()
        pathUploadBuffer = buf // 保活，防异步上传读已释放内存
        pathVertexBuffer.setBufferAt(engine, 0, buf, 0, nv * 12)
        pathVertCount = nv
        engine.renderableManager.setGeometryAt(inst, 0, RenderableManager.PrimitiveType.LINES,
            pathVertexBuffer, pathIndexBuffer, 0, nv)
        requestRender()
    }

    /** 设置地面平面：法向(=up)、offset、是否显示网格。重算正交基并按需重建网格。 */
    fun setGround(upAxis: FloatArray, d: Float, show: Boolean) {
        if (destroyRequested.get()) return
        val axis = upAxis.copyOf()
        ownerHandler.post {
            if (initialized && !destroyed && !destroyRequested.get()) {
                setGroundInternal(axis, d, show)
            }
        }
    }

    private fun setGroundInternal(upAxis: FloatArray, d: Float, show: Boolean) {
        assertOwnerThread()
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
        if (destroyRequested.get()) return
        ownerHandler.post {
            if (initialized && !destroyed && !destroyRequested.get()) applyPresetInternal(p)
        }
    }

    private fun applyPresetInternal(p: LaserViewPreset) {
        assertOwnerThread()
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
        if (destroyRequested.get()) return
        ownerHandler.post {
            if (initialized && !destroyed && !destroyRequested.get()) resetViewInternal()
        }
    }

    private fun resetViewInternal() {
        assertOwnerThread()
        userInteracted = false // 重置 → 恢复自动取景跟随（再有增量点会重新拟合）
        if (autoFit && hasFit) {
            distance = (fitRadius / 0.3827f * 0.95f).coerceIn(300f, 60_000f)
            camera.setProjection(45.0, lastAspect, 50.0, currentFar(), Camera.Fov.VERTICAL)
        }
        applyPresetInternal(preset) // 重置角度 + 清平移 + applyCamera
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

        // 地面实体方块：地面投影质心为心，±half 沿 rg/fw 铺面，沉 8mm 让 grid 线浮其上不 z-fight。
        val lower = 8f
        val qx = gx - upX * lower; val qy = gy - upY * lower; val qz = gz - upZ * lower
        val qb = ByteBuffer.allocateDirect(4 * 12).order(ByteOrder.nativeOrder())
        val qfb = qb.asFloatBuffer()
        // 4 角：(-rg,-fw)(+rg,-fw)(+rg,+fw)(-rg,+fw)，索引 0,1,2,0,2,3 拼两三角。
        for ((sr, sf) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
            qfb.put(qx + rgX * half * sr + fwX * half * sf)
            qfb.put(qy + rgY * half * sr + fwY * half * sf)
            qfb.put(qz + rgZ * half * sr + fwZ * half * sf)
        }
        qb.rewind()
        groundUploadBuffer = qb // 保活异步上传
        groundVertexBuffer.setBufferAt(engine, 0, qb, 0, 4 * 12)
        val ginst = engine.renderableManager.getInstance(groundEntity)
        if (ginst != 0) {
            engine.renderableManager.setGeometryAt(ginst, 0, RenderableManager.PrimitiveType.TRIANGLES,
                groundVertexBuffer, groundIndexBuffer, 0, 6)
        }
    }

    private fun hideGrid() {
        val inst = engine.renderableManager.getInstance(gridEntity)
        if (inst != 0) {
            engine.renderableManager.setGeometryAt(inst, 0, RenderableManager.PrimitiveType.LINES,
                gridVertexBuffer, gridIndexBuffer, 0, 0)
        }
        val ginst = engine.renderableManager.getInstance(groundEntity)
        if (ginst != 0) {
            engine.renderableManager.setGeometryAt(ginst, 0, RenderableManager.PrimitiveType.TRIANGLES,
                groundVertexBuffer, groundIndexBuffer, 0, 0)
        }
    }

    // ───── Choreographer 按需渲染 ─────
    //
    // 旧实现为规避 TextureView 与 Compose 共享 SurfaceTexture buffer 的历史花屏，每个 vsync
    // 都提交一次 Filament。当前已经使用独立 SurfaceView，Compose 不会改写其 buffer；静态点云
    // 继续 60Hz 重画只会占满模拟器/低端机图形队列，并反向阻塞主窗口 HWUI 绘制导致输入 ANR。
    // 数据、相机或 Surface 变化时只提交一帧；连续漫游输入才逐帧续约。多个变化合并为同一 vsync。
    private lateinit var choreographer: Choreographer
    // 真机上限 30fps；本仓模拟器的 ranchu/llvmpipe 对 262K 点单帧约 100ms，融合视图限 5fps。
    // 主视图会在融合云与 A 云之间复用，所以按当前可见点数而非预分配预算选帧间隔。
    private fun renderIntervalNanos(): Long = when {
        android.os.Build.HARDWARE == "ranchu" && renderedVertexCount >= 200_000 ->
            TimeUnit.MILLISECONDS.toNanos(200)
        android.os.Build.HARDWARE == "ranchu" ->
            TimeUnit.MILLISECONDS.toNanos(100)
        else -> TimeUnit.MILLISECONDS.toNanos(33)
    }
    private var renderedVertexCount = 0
    private var frameCallbackScheduled = false
    private var renderRequested = false
    private var lastRenderAttemptNanos = 0L
    private var inFlightFence: Fence? = null
    private var inFlightProjection: SubmittedProjection? = null
    @Volatile private var renderingEnabledRequested = true
    private var renderingEnabled = true
    private var feedbackCapturePaused = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            assertOwnerThread()
            frameCallbackScheduled = false
            if (destroyed || !attachedToWindow) return

            // endFrame 只是异步提交。Fence 真正完成后才移动 Compose 尺寸层，并与下一次 Filament
            // 提交错开一个 UI 帧，避免 llvmpipe OpenGL 与 HWUI Vulkan 同时争抢宿主图形队列。
            val projectionPublished = pollSubmittedFrame()
            if (inFlightFence != null) {
                scheduleFrameCallback()
                return
            }
            if (projectionPublished) {
                if (renderRequested || needsContinuousRender()) scheduleFrameCallback()
                return
            }
            if (feedbackCapturePaused || !renderingEnabled) return
            if (!renderRequested && !needsContinuousRender()) return
            renderRequested = false
            if (roamMode) integrateRoam(frameTimeNanos)
            val sc = swapChain
            var rendered = false
            if (sc != null) {
                lastRenderAttemptNanos = frameTimeNanos
                if (renderer.beginFrame(sc, frameTimeNanos)) {
                    renderer.render(view)
                    renderer.endFrame()
                    rendered = true
                    inFlightProjection = takeSubmittedProjectionSnapshot()
                    inFlightFence = engine.createFence()
                    engine.flush()
                }
            }
            // beginFrame 被拒时按当前点数帧间隔退避，不能退化成每个 vsync 探测。
            if (sc != null && !rendered) renderRequested = true
            if (needsContinuousRender()) renderRequested = true
            if (inFlightFence != null || renderRequested) scheduleFrameCallback()
        }
    }

    /** 非阻塞轮询上一 Filament 帧；返回 true 表示本回合发布过投影，下一帧须错开。 */
    private fun pollSubmittedFrame(): Boolean {
        val fence = inFlightFence ?: return false
        val status = fence.wait(Fence.Mode.DONT_FLUSH, 0L)
        if (status == Fence.FenceStatus.TIMEOUT_EXPIRED) return false
        if (status == Fence.FenceStatus.ERROR) {
            android.util.Log.w(TAG, "Filament 帧 Fence 返回 ERROR")
        }
        engine.destroyFence(fence)
        inFlightFence = null
        val snapshot = inFlightProjection
        inFlightProjection = null
        publishSubmittedProjection(snapshot)
        return snapshot != null
    }

    private fun needsContinuousRender(): Boolean = roamMode && (
        (moveMag > 0f && (moveStrafe != 0f || moveForward != 0f)) ||
            lookYawRate != 0f || lookPitchRate != 0f
        )

    private fun requestRender() {
        assertOwnerThread()
        if (destroyed) return
        renderRequested = true
        scheduleFrameCallback()
    }

    private fun scheduleFrameCallback() {
        assertOwnerThread()
        if (frameCallbackScheduled || destroyed || !attachedToWindow) return
        if ((feedbackCapturePaused || !renderingEnabled) && inFlightFence == null) return
        frameCallbackScheduled = true
        val now = System.nanoTime()
        val delayNanos = if (lastRenderAttemptNanos == 0L) {
            0L
        } else {
            (lastRenderAttemptNanos + renderIntervalNanos() - now).coerceAtLeast(0L)
        }
        if (delayNanos == 0L) {
            choreographer.postFrameCallback(frameCallback)
        } else {
            val delayMillis = (delayNanos + 999_999L) / 1_000_000L
            choreographer.postFrameCallbackDelayed(frameCallback, delayMillis)
        }
    }

    private fun cancelRender() {
        assertOwnerThread()
        frameCallbackScheduled = false
        choreographer.removeFrameCallback(frameCallback)
    }

    fun setRenderingEnabled(enabled: Boolean) {
        if (destroyRequested.get() || renderingEnabledRequested == enabled) return
        renderingEnabledRequested = enabled
        ownerHandler.post {
            if (!initialized || destroyed) return@post
            renderingEnabled = enabled
            if (enabled) {
                requestRender()
            } else {
                renderRequested = false
                if (inFlightFence != null) scheduleFrameCallback() else cancelRender()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachedToWindow = true
        if (!destroyRequested.get()) ownerHandler.post {
            if (initialized && !destroyed) requestRender()
        }
    }

    override fun onDetachedFromWindow() {
        attachedToWindow = false
        super.onDetachedFromWindow()
        ownerHandler.post {
            if (initialized && !destroyed) cancelRender()
        }
    }

    override fun pauseForFeedbackCapture() {
        if (destroyRequested.get()) return
        runOwnerBlocking {
            if (!initialized || destroyed) return@runOwnerBlocking
            feedbackCapturePaused = true
            cancelRender()
            if (!engine.flushAndWait(TimeUnit.SECONDS.toNanos(5))) {
                throw IllegalStateException("点云渲染帧同步超时")
            }
        }
    }

    override fun resumeAfterFeedbackCapture() {
        if (destroyRequested.get()) return
        ownerHandler.post {
            if (!initialized || destroyed) return@post
            feedbackCapturePaused = false
            requestRender()
        }
    }

    // ───── 手势 ─────
    private var lastX = 0f; private var lastY = 0f
    private var lastSpan = 0f
    private var lastMidX = 0f; private var lastMidY = 0f
    @Volatile private var pinching = false

    private val gestureLock = Any()
    private var pendingOrbitDx = 0f
    private var pendingOrbitDy = 0f
    private var pendingScale = 1f
    private var pendingPanX = 0f
    private var pendingPanY = 0f
    private var gestureCommandScheduled = false
    private val gestureCommand: Runnable = Runnable {
        val values = synchronized(gestureLock) {
            val snapshot = floatArrayOf(
                pendingOrbitDx,
                pendingOrbitDy,
                pendingScale,
                pendingPanX,
                pendingPanY,
            )
            pendingOrbitDx = 0f
            pendingOrbitDy = 0f
            pendingScale = 1f
            pendingPanX = 0f
            pendingPanY = 0f
            snapshot
        }
        try {
            if (initialized && !destroyed && !destroyRequested.get()) {
                userInteracted = true
                val scale = values[2]
                if (scale != 1f) {
                    val maxD = if (hasFit) (fitRadius * 8f).coerceAtLeast(5000f) else 5000f
                    distance = (distance * scale).coerceIn(300f, maxD)
                    if (hasFit) camera.setProjection(45.0, lastAspect, 50.0, currentFar(), Camera.Fov.VERTICAL)
                }
                if (values[3] != 0f || values[4] != 0f) panByScreen(values[3], values[4])
                if (values[0] != 0f || values[1] != 0f) {
                    yaw -= values[0] * 0.006f
                    if (preset == LaserViewPreset.FREE) {
                        pitch = (pitch + values[1] * 0.006f).coerceIn(-1.4f, 1.4f)
                    }
                }
                applyCamera()
            }
        } finally {
            val reschedule = synchronized(gestureLock) {
                val hasPending = pendingOrbitDx != 0f || pendingOrbitDy != 0f ||
                    pendingScale != 1f || pendingPanX != 0f || pendingPanY != 0f
                if (!hasPending) gestureCommandScheduled = false
                hasPending
            }
            if (reschedule) ownerHandler.post(gestureCommand)
        }
    }

    private fun enqueueOrbit(dx: Float, dy: Float) {
        val schedule = synchronized(gestureLock) {
            pendingOrbitDx += dx
            pendingOrbitDy += dy
            if (gestureCommandScheduled) false else {
                gestureCommandScheduled = true
                true
            }
        }
        if (schedule) ownerHandler.post(gestureCommand)
    }

    private fun enqueuePinch(scale: Float, panX: Float, panY: Float) {
        val schedule = synchronized(gestureLock) {
            pendingScale *= scale
            pendingPanX += panX
            pendingPanY += panY
            if (gestureCommandScheduled) false else {
                gestureCommandScheduled = true
                true
            }
        }
        if (schedule) ownerHandler.post(gestureCommand)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!renderingEnabledRequested || destroyRequested.get()) return false
        if (roamMode || !pointCloudInteractionEnabled) {
            pinching = false
            parent?.requestDisallowInterceptTouchEvent(false)
            return false
        }
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
                if (pinching && ev.pointerCount >= 2) {
                    // 双指：捏合缩放(span) + 双指拖动平移(midpoint 位移)。
                    val span = pointerSpan(ev)
                    val midX = (ev.getX(0) + ev.getX(1)) * 0.5f
                    val midY = (ev.getY(0) + ev.getY(1)) * 0.5f
                    val ratio = if (lastSpan > 0f && span > 1e-4f) lastSpan / span else 1f
                    enqueuePinch(ratio, midX - lastMidX, midY - lastMidY)
                    lastSpan = span; lastMidX = midX; lastMidY = midY
                } else {
                    val dx = ev.x - lastX; val dy = ev.y - lastY
                    lastX = ev.x; lastY = ev.y
                    enqueueOrbit(dx, dy)
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
        if (!destroyRequested.compareAndSet(false, true)) return
        renderingEnabledRequested = false
        attachedToWindow = false
        projectionListener = null
        surfaceGeneration.incrementAndGet()
        synchronized(pendingPointLock) {
            pendingPointCloud = null
        }
        synchronized(gestureLock) {
            pendingOrbitDx = 0f
            pendingOrbitDy = 0f
            pendingScale = 1f
            pendingPanX = 0f
            pendingPanY = 0f
        }

        // detach 的 Surface 回调只在确有 SwapChain 时同步 drain；其余 GPU 资源异步回 owner 销毁。
        uiHelper.detach()
        drainSurfaceSynchronously()
        ownerHandler.post {
            if (initialized && !destroyed) destroyInternal()
        }
    }

    private fun destroyInternal() {
        assertOwnerThread()
        if (destroyed) return
        destroyed = true
        renderingEnabled = false
        renderRequested = false
        cancelRender()
        destroySwapChainAndWait()
        synchronized(surfaceLifecycleLock) {
            surfaceActiveOrCreating = false
        }

        scene.removeEntity(pointEntity)
        scene.removeEntity(meshEntity)
        scene.removeEntity(gridEntity)
        scene.removeEntity(groundEntity)
        scene.removeEntity(pathEntity)
        scene.removeEntity(lightEntity)
        engine.entityManager.destroy(pointEntity)
        engine.entityManager.destroy(meshEntity)
        engine.entityManager.destroy(gridEntity)
        engine.entityManager.destroy(groundEntity)
        engine.entityManager.destroy(pathEntity)
        engine.entityManager.destroy(lightEntity)
        engine.destroyVertexBuffer(vertexBuffer)
        engine.destroyIndexBuffer(indexBuffer)
        engine.destroyVertexBuffer(meshVertexBuffer)
        engine.destroyIndexBuffer(meshIndexBuffer)
        engine.destroyVertexBuffer(gridVertexBuffer)
        engine.destroyIndexBuffer(gridIndexBuffer)
        engine.destroyVertexBuffer(groundVertexBuffer)
        engine.destroyIndexBuffer(groundIndexBuffer)
        engine.destroyVertexBuffer(pathVertexBuffer)
        engine.destroyIndexBuffer(pathIndexBuffer)
        engine.destroyMaterialInstance(materialInstance)
        engine.destroyMaterialInstance(gridMaterialInstance) // 先于 material（同 material 派生）
        engine.destroyMaterialInstance(groundMaterialInstance) // 先于 material（同 material 派生）
        engine.destroyMaterialInstance(pathMaterialInstance) // 先于 material（同 material 派生）
        engine.destroyMaterial(material)
        engine.destroyMaterialInstance(colorMaterialInstance)
        engine.destroyMaterial(colorMaterial)
        engine.destroyMaterialInstance(meshMaterialInstance)
        engine.destroyMaterial(meshMaterial)
        gridUploadBuffer = null
        groundUploadBuffer = null
        pathUploadBuffer = null
        meshPositionUploadBuffer = null
        meshTangentUploadBuffer = null
        meshIndexUploadBuffer = null

        engine.destroyCameraComponent(cameraEntity)
        engine.entityManager.destroy(cameraEntity)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyRenderer(renderer)
        engine.flush()

        // Filament 1.57.1 在 Android 上要求 shutdown 回到创建 Engine 的线程；异线程会触发
        // "Engine::shutdown() called from the wrong thread"。前面的 Surface drain 已消除慢 join 根因。
        engine.destroy()
        initialized = false
        android.util.Log.i(
            TAG,
            "Engine 销毁完成 budget=$maxVertices owner=${Thread.currentThread().name} main=${Looper.myLooper() == Looper.getMainLooper()}",
        )
    }
}
