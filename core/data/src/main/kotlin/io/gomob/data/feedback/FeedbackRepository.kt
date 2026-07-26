package io.gomob.data.feedback

import io.gomob.network.ApiException
import io.gomob.network.FeedbackApi
import io.gomob.network.FeedbackBoxDto
import io.gomob.network.FeedbackPointDto
import io.gomob.network.FeedbackSubmitRequest
import javax.inject.Inject
import javax.inject.Singleton

data class FeedbackBox(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val note: String,
    val points: List<FeedbackPathPoint> = emptyList(),
)

data class FeedbackPathPoint(
    val x: Double,
    val y: Double,
)

data class FeedbackResult(
    val id: String,
)

@Singleton
class FeedbackRepository @Inject constructor(
    private val api: FeedbackApi,
) {
    suspend fun submit(
        title: String,
        pageUrl: String,
        userAgent: String,
        imageDataUrl: String,
        annotatedDataUrl: String,
        boxes: List<FeedbackBox>,
    ): FeedbackResult {
        val resp = api.submit(
            FeedbackSubmitRequest(
                title = title,
                severity = "medium",
                category = "ui",
                pageUrl = pageUrl,
                userAgent = userAgent,
                imageDataUrl = imageDataUrl,
                annotatedDataUrl = annotatedDataUrl,
                boxes = boxes.map { box ->
                    FeedbackBoxDto(
                        x = box.x,
                        y = box.y,
                        w = box.w,
                        h = box.h,
                        note = box.note,
                        points = box.points.map { FeedbackPointDto(it.x, it.y) },
                    )
                },
            ),
        ).data ?: throw ApiException(50001, 500, "反馈提交响应缺数据")
        return FeedbackResult(resp.id)
    }
}
