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
                if (roamMode) camera.setProjection(roamFovDeg, lastAspect, 50.0, roamFar, Camera.Fov.VERTICAL)
                else camera.setProjection(45.0, lastAspect, 50.0, currentFar(), Camera.Fov.VERTICAL)
                    }
        }
        attachTo(this@PointCloudSurfaceView)
    }

    private val material: Material
    private val materialInstance: MaterialInstance
    private val colorMaterial: Material
    private val colorMaterialInstance: MaterialInstance
    private val meshMaterial: Material
    private val meshMaterialInstance: MaterialInstance
    private val gridMaterialInstance: MaterialInstance // 地面网格用 unlit 同材质另开实例（暗灰）

    // 点云预分配上限：与 Scan3dRecordingViewModel.MAX_PREVIEW_VERTICES 对齐
    // 不每帧 destroy/recreate VB/IB —— 那会累积 stale handle 让 FEngine::loop 在 ~20s
    // 后撞到 "corrupted heap Handle" SIGABRT。改为一次性 alloc，setBufferAt 复用 + 用
    // RenderableManager.setGeometryAt 调整 index count。
    // 激光全扫后融合云约 280~300 万点。这里必须全量上传，否则按 PCD 原始线束顺序 stride 抽样会在
    // 顶视/漫游里看成稀疏几条线。上限按当前真机全量云留余量；超过才进入兜底抽样。
    private val maxVertices = 3_500_000

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
    private var pointUploadBufferBytes = 0L
    private val pointUploadBufferCountLimit = 4
    private val pointUploadBufferByteLimit = 96L * 1024L * 1024L
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

    // 地面实体（TRIANGLES 大方块，暗色，沉 grid 下 8mm）。给漫游清晰落脚参照，缓解迷失方向。
    private val groundVertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(4).bufferCount(1)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .build(engine)
    private val groundIndexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(6).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
    private val groundEntity: Int = EntityManager.get().create()
    private var groundUploadBuffer: ByteBuffer? = null
    private lateinit var groundMaterialInstance: MaterialInstance

    // 标注路径（LINES，amber，铺地面上方 20mm 防 z-fight）。仿 grid 实体管理；pathMaterialInstance 在 init 赋值。
    private val maxPathVerts = 4096
    private val pathVertexBuffer: VertexBuffer = VertexBuffer.Builder()
        .vertexCount(maxPathVerts).bufferCount(1)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
        .build(engine)
    private val pathIndexBuffer: IndexBuffer = IndexBuffer.Builder()
        .indexCount(maxPathVerts).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine)
    private val pathEntity: Int = EntityManager.get().create()
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
    private var fitFarRadius = 500f
    private var lastAspect = 1.0
    @Volatile private var pointCloudInteractionEnabled = true

    // ───── 第一视角漫游（标注用）。orbit 字段不动；roamMode=false 时本段全不参与渲染/输入。 ─────
    private var roamMode = false
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
    private var pitchInvert = false
    // 取景中心(质心)在地面基的 (u,v)，世界原点系——与 projectTopView/worldBox 同源。
    private var originU = 0f; private var originV = 0f
    // 标注路径（世界原点系基坐标 [u0,v0,...]，与 projectTopView 同源，直接喂 worldBox）。
    private var annotating = false
    private val pathUV = ArrayList<Float>()
    private var lastSampleU = 0f; private var lastSampleV = 0f; private var hasSample = false

    init {
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
    fun setPoints(cloud: FloatArray, colors: IntArray? = null) {
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
        val rgb = colors
        val hasColors = rgb != null && rgb.size >= total
        val colorBuf = if (hasColors) ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()) else null
        if (stride == 1) {
            fb.put(cloud, 0, n * 3)
            if (colorBuf != null) {
                var i = 0
                while (i < n) {
                    putRgb(colorBuf, rgb!![i])
                    i++
                }
            }
        } else {
            var src = 0; var cnt = 0
            while (cnt < n) {
                val base = src * 3
                fb.put(cloud[base]); fb.put(cloud[base + 1]); fb.put(cloud[base + 2])
                if (colorBuf != null) putRgb(colorBuf, rgb!![src])
                src += stride; cnt++
            }
        }
        vBuf.rewind()
        retainPointUploadBuffer(vBuf)
        vertexBuffer.setBufferAt(engine, 0, vBuf, 0, byteCount)
        if (colorBuf != null) {
            colorBuf.rewind()
            retainPointUploadBuffer(colorBuf)
            vertexBuffer.setBufferAt(engine, 1, colorBuf, 0, n * 4)
        }

        if (instance != 0) {
            rm.setMaterialInstanceAt(instance, 0, if (colorBuf != null) colorMaterialInstance else materialInstance)
            rm.setGeometryAt(instance, 0, RenderableManager.PrimitiveType.POINTS,
                             vertexBuffer, indexBuffer, 0, n)
        }
        android.util.Log.i(TAG, "setPoints total=$total uploaded=$n stride=$stride color=${colorBuf != null}")

        // autoFit：用户未上手时每帧按整云包围球跟随取景（采集时跟着云生长）；一旦用户手动操作过
        // (userInteracted) 即冻结，不再重拟合，保住手动视角（实测：每帧拟合会把位置/缩放/平移冲掉）。
        if (autoFit && !userInteracted) fitTo(cloud, total)
    }

    private fun putRgb(buf: ByteBuffer, rgb: Int) {
        buf.put(((rgb ushr 16) and 0xff).toByte())
        buf.put(((rgb ushr 8) and 0xff).toByte())
        buf.put((rgb and 0xff).toByte())
        buf.put(0xff.toByte())
    }

    /** 切换显示的云时调：清"用户已交互"，让新云恢复自动取景跟随。 */
    fun requestRefit() {
        userInteracted = false
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
        val us = ArrayList<Float>(sampleCapacity)
        val vs = ArrayList<Float>(sampleCapacity)
        val hs = ArrayList<Float>(sampleCapacity)
        var i = 0
        val lim = n * 3
        while (i < lim) {
            val x = cloud[i]; val y = cloud[i + 1]; val z = cloud[i + 2]
            if (isSane(x) && isSane(y) && isSane(z)) {
                us.add(x * rgX + y * rgY + z * rgZ)
                vs.add(x * fwX + y * fwY + z * fwZ)
                hs.add(x * upX + y * upY + z * upZ)
            }
            i += 3 * stride
        }
        if (us.isEmpty()) return
        fun band(values: ArrayList<Float>): Pair<Float, Float> {
            val a = values.toFloatArray()
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
        pointUploadBufferBytes += buffer.capacity().toLong()
        while (
            pointUploadBuffers.size > pointUploadBufferCountLimit ||
            pointUploadBufferBytes > pointUploadBufferByteLimit
        ) {
            val removed = pointUploadBuffers.removeFirst()
            pointUploadBufferBytes -= removed.capacity().toLong()
        }
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
        roamMode = true
        setPointCloudInteractionEnabled(false)
        lastFrameNanos = 0L
        originU = centerU
        originV = centerV
        roamEyeH = -groundOffsetD + eyeHeight.coerceIn(900f, 2200f)
        roamRadius = radius.coerceAtLeast(500f)
        walkU = 0f
        walkV = 0f                     // 起点已在主体外侧，走动位移从 0 开始积分
        roamYaw = 0f; roamPitch = 0f   // 水平起视，转头由漫游 HUD 接管
        roamFar = (2f * roamRadius + 3000f).toDouble().coerceAtLeast(6000.0)
        camera.setProjection(roamFovDeg, lastAspect, 50.0, roamFar, Camera.Fov.VERTICAL)
        applyCamera()
    }

    fun exitRoamMode() {
        roamMode = false
        lastFrameNanos = 0L
        setPointCloudInteractionEnabled(true)
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
    }

    /** 右摇杆转身/抬头低头输入：yawRate 横轴(+右转)、pitchRate 纵轴(+上)，−1..1。主线程写、渲染回调读。 */
    fun setLookInput(yawRate: Float, pitchRate: Float) {
        lookYawRate = yawRate; lookPitchRate = pitchRate
    }

    /** 转头/抬头的增量入口。pitch clamp ±1.4 避开近竖直退化。 */
    fun applyLook(dYaw: Float, dPitch: Float) {
        roamYaw += dYaw
        val p = if (pitchInvert) -dPitch else dPitch
        roamPitch = (roamPitch + p).coerceIn(-1.40f, 1.40f)
        if (roamMode) applyCamera()
    }

    fun setPitchInvert(on: Boolean) { pitchInvert = on }

    /** 开/关标注。起标时把当前脚下点作为路径首点。 */
    fun setAnnotating(on: Boolean) {
        annotating = on
        if (on && !hasSample) {
            lastSampleU = originU + walkU; lastSampleV = originV + walkV
            pathUV.add(lastSampleU); pathUV.add(lastSampleV); hasSample = true
            rebuildPath()
        }
    }

    /** 清空标注路径。 */
    fun resetPath() {
        pathUV.clear(); hasSample = false; pathVertCount = 0
        val inst = engine.renderableManager.getInstance(pathEntity)
        if (inst != 0) {
            engine.renderableManager.setGeometryAt(inst, 0, RenderableManager.PrimitiveType.LINES,
                pathVertexBuffer, pathIndexBuffer, 0, 0)
        }
    }

    /** 标注路径采样快照（世界原点系基坐标 [u0,v0,...]，与 projectTopView/worldBox 同源）。 */
    fun pathSamplesUV(): FloatArray = pathUV.toFloatArray()
    fun pathSampleCount(): Int = pathUV.size / 2

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
            distance = (fitRadius / 0.3827f * 0.95f).coerceIn(300f, 60_000f)
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
            if (roamMode) integrateRoam(frameTimeNanos)
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
        pointUploadBuffers.clear()
        pointUploadBufferBytes = 0L
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

        uiHelper.detach()
        swapChain?.let { engine.destroySwapChain(it); swapChain = null }
        engine.destroy()
    }
}
