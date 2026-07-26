package io.gomob.data.scan

import com.google.common.truth.Truth.assertThat
import io.gomob.network.LaserLatestScanResponse
import org.junit.Test

class LaserLatestScanMappingTest {
    @Test
    fun foundFalseMapsToEmpty() {
        assertThat(LaserLatestScanResponse(found = false).toDomainOrNull()).isNull()
    }

    @Test
    fun completedResponseMapsToSummary() {
        val summary = LaserLatestScanResponse(
            found = true,
            scanId = 73L,
            status = "done",
            points = 900_001,
            backgroundCaptured = true,
        ).toDomainOrNull()

        assertThat(summary).isEqualTo(LaserLatestScan(73L, "done", 900_001, backgroundCaptured = true))
    }

    @Test
    fun malformedFoundResponseFailsInsteadOfInventingData() {
        val missingId = runCatching {
            LaserLatestScanResponse(found = true, status = "done").toDomainOrNull()
        }.exceptionOrNull()
        val invalidId = runCatching {
            LaserLatestScanResponse(found = true, scanId = 0L, status = "done").toDomainOrNull()
        }.exceptionOrNull()

        assertThat(missingId).isInstanceOf(IllegalStateException::class.java)
        assertThat(invalidId).isInstanceOf(IllegalStateException::class.java)
    }
}
