package io.gomob.feature.scan3d

import android.annotation.SuppressLint
import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.EntityManager
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.utils.ModelViewer
import com.google.android.filament.utils.Utils
import java.io.File
import java.nio.ByteBuffer

/**
 * 云端融合结果 GLB 回看视图。
 *
 * 用 filament-utils [ModelViewer] 加载 GLB（gltfio），自带相机轨道手势（旋转/缩放/平移）。
 * 无 IBL 资产，改加一盏方向光 + 中性清屏色保证带 PBR 材质的融合网格可见、不全黑。
 *
 * 资源管理（对标 [PointCloud3dView]）：[remember] 单实例，[LaunchedEffect] 仅 glbFile 变化时加载
 * （不随重组反复 readBytes/重载），[DisposableEffect] 离场时 [GlbSurfaceView.destroy] 停渲染 +
 * destroyModel 释放上一份 GLB 的 GPU 资源；load 内重载前先 destroyModel,杜绝模型堆积泄漏。
 */
@Composable
fun GlbModelView(
    glbFile: File,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = remember { GlbSurfaceView(context) }
    LaunchedEffect(glbFile) { view.load(glbFile) }
    DisposableEffect(view) {
        onDispose { view.destroy() }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class GlbSurfaceView(context: android.content.Context) : SurfaceView(context) {

    private val choreographer = Choreographer.getInstance()
    private val modelViewer: ModelViewer = ModelViewer(this)
    private var sunLight = 0
    private var loadedPath: String? = null
    private var running = false
    private var destroyed = false

    init {
        Utils.init()
        modelViewer.renderer.clearOptions = Renderer.ClearOptions().apply {
            clear = true
            clearColor = floatArrayOf(0.02f, 0.03f, 0.06f, 1.0f)
        }
        addSunLight()
        setOnTouchListener { _, event ->
            modelViewer.onTouchEvent(event)
            true
        }
    }

    fun load(glbFile: File) {
        if (destroyed) return
        if (loadedPath == glbFile.absolutePath && modelViewer.asset != null) return
        // 重载前先释放上一份 GLB 的 GPU 资源,避免每次加载堆积。
        modelViewer.destroyModel()
        val bytes = glbFile.readBytes()
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            rewind()
        }
        modelViewer.loadModelGlb(buffer)
        modelViewer.transformToUnitCube()
        loadedPath = glbFile.absolutePath
    }

    /**
     * 离场释放:停渲染循环 + 释放当前 GLB 的 GPU 资源(模型/纹理/几何) +
     * 销毁方向光实体与 Filament Engine。
     *
     * ModelViewer 自身不会在销毁时释放它创建的 Engine,只 destroyModel 会导致反复回看时
     * Engine + sunLight 持续累积泄漏。这里显式:从场景摘除 → 销毁 light 组件 + 实体 →
     * 最后 engine.destroy()(Engine 必须最后销毁,它持有上面所有资源的底层句柄)。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        running = false
        choreographer.removeFrameCallback(frameCallback)
        val engine = modelViewer.engine
        modelViewer.destroyModel()
        if (sunLight != 0) {
            modelViewer.scene.removeEntity(sunLight)
            engine.lightManager.destroy(sunLight)
            EntityManager.get().destroy(sunLight)
            sunLight = 0
        }
        // Engine 最后销毁:释放 renderer/scene/view/swapChain/camera 及全部 GPU 资源。
        engine.destroy()
    }

    private fun addSunLight() {
        val engine = modelViewer.engine
        sunLight = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 0.98f, 0.95f)
            .intensity(80_000.0f)
            .direction(0.35f, -1.0f, -0.45f)
            .castShadows(false)
            .build(engine, sunLight)
        modelViewer.scene.addEntity(sunLight)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            choreographer.postFrameCallback(this)
            modelViewer.render(frameTimeNanos)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (destroyed) return
        running = true
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        choreographer.removeFrameCallback(frameCallback)
    }
}
