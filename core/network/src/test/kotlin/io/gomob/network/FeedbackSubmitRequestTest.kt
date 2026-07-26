package io.gomob.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class FeedbackSubmitRequestTest {
    @Test
    fun categoryAndSeverityAreAlwaysSerialized() {
        val request = FeedbackSubmitRequest(
            title = "App 问题反馈 · 首页",
            severity = "medium",
            category = "ui",
            pageUrl = "gomob://app/首页",
            userAgent = "test-agent",
            imageDataUrl = "data:image/png;base64,AA==",
            annotatedDataUrl = "data:image/png;base64,AA==",
            boxes = emptyList(),
        )

        val encoded = Json.encodeToString(request)

        assertThat(encoded).contains("\"severity\":\"medium\"")
        assertThat(encoded).contains("\"category\":\"ui\"")
    }
}
