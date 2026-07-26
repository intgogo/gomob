package io.gomob.feature.scan3d

import kotlin.math.roundToInt

// VINCreator 原厂契约：HLSD8 4160×832、mode25 深度 640×128，双路都严格为 5:1。
internal const val VINCREATOR_STREAM_ASPECT = 5f
internal const val VINCREATOR_VIEWPORT_ASPECT = VINCREATOR_STREAM_ASPECT
internal const val VINCREATOR_RESTORE_W = 4425
internal const val VINCREATOR_RESTORE_H = 600
internal const val VINCREATOR_RESTORE_ASPECT = 4425f / 600f

internal data class VinFitImageRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/** 返回 ContentScale.Fit 后图像在预览槽内的真实矩形。 */
internal fun vinFitImageRect(viewportWidth: Float, viewportHeight: Float, imageAspect: Float): VinFitImageRect {
    require(viewportWidth >= 0f && viewportHeight >= 0f) { "预览槽尺寸不能为负" }
    require(imageAspect > 0f) { "图像宽高比必须大于 0" }
    if (viewportWidth == 0f || viewportHeight == 0f) return VinFitImageRect(0f, 0f, 0f, 0f)
    val viewportAspect = viewportWidth / viewportHeight
    val imageWidth: Float
    val imageHeight: Float
    if (viewportAspect > imageAspect) {
        imageHeight = viewportHeight
        imageWidth = imageHeight * imageAspect
    } else {
        imageWidth = viewportWidth
        imageHeight = imageWidth / imageAspect
    }
    return VinFitImageRect(
        // Compose Alignment.Center 先把剩余空间的中心偏移取整；Canvas 必须复用同一像素语义。
        left = ((viewportWidth - imageWidth) / 2f).roundToInt().toFloat(),
        top = ((viewportHeight - imageHeight) / 2f).roundToInt().toFloat(),
        width = imageWidth,
        height = imageHeight,
    )
}

/** 把 Compose 实际内缩框转换成图像域归一化 ROI，供绘制、质量门和 harness 共同消费。 */
internal fun vinPreviewRoi(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    imageAspect: Float,
    insetPx: Float,
): VinPreviewRoi? {
    if (viewportWidthPx <= 0f || viewportHeightPx <= 0f || imageAspect <= 0f || insetPx < 0f) return null
    val imageRect = vinFitImageRect(viewportWidthPx, viewportHeightPx, imageAspect)
    if (imageRect.width <= 0f || imageRect.height <= 0f) return null
    val inset = minOf(insetPx, imageRect.width / 2f, imageRect.height / 2f)
    if (inset * 2f >= imageRect.width || inset * 2f >= imageRect.height) return null
    return VinPreviewRoi(
        left = inset / imageRect.width,
        top = inset / imageRect.height,
        right = 1f - inset / imageRect.width,
        bottom = 1f - inset / imageRect.height,
    )
}
