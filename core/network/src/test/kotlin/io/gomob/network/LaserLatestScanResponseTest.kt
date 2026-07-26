package io.gomob.network

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test

class LaserLatestScanResponseTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun emptyResponseDoesNotRequireScanFields() {
        val response = json.decodeFromString<LaserLatestScanResponse>("""{"found":false}""")

        assertThat(response.found).isFalse()
        assertThat(response.scanId).isNull()
        assertThat(response.status).isNull()
        assertThat(response.points).isNull()
    }

    @Test
    fun completedResponseReadsOnlyRealSummaryFields() {
        val response = json.decodeFromString<LaserLatestScanResponse>(
            """{"found":true,"scan_id":42,"status":"done","points":712345,"session_key":"server-only"}""",
        )

        assertThat(response.found).isTrue()
        assertThat(response.scanId).isEqualTo(42L)
        assertThat(response.status).isEqualTo("done")
        assertThat(response.points).isEqualTo(712345)
        assertThat(response.backgroundCaptured).isFalse()
    }

    @Test
    fun backgroundCaptureFlagIsPreserved() {
        val response = json.decodeFromString<LaserLatestScanResponse>(
            """{"found":true,"scan_id":43,"status":"done","points":5000,"background_captured":true}""",
        )

        assertThat(response.backgroundCaptured).isTrue()
    }

    @Test
    fun missingFoundFailsInsteadOfPretendingEmpty() {
        val failure = runCatching {
            json.decodeFromString<LaserLatestScanResponse>("""{"scan_id":42,"status":"done"}""")
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(SerializationException::class.java)
    }
}
