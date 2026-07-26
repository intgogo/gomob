package io.gomob.scan.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackAnnotationTest {
    @Test
    fun normalizedPointClampsToScreenshotBounds() {
        assertEquals(FeedbackPoint(0f, 1f), normalizedFeedbackPoint(-10f, 250f, 100, 200))
    }

    @Test
    fun appendPointDropsDenseSamplesAndKeepsUsefulOnes() {
        val first = FeedbackPoint(0.1f, 0.1f)
        val dense = FeedbackPoint(0.101f, 0.101f)
        val useful = FeedbackPoint(0.2f, 0.2f)

        val withDense = appendFeedbackPoint(listOf(first), dense, minDistance = 0.01f)
        val withUseful = appendFeedbackPoint(withDense, useful, minDistance = 0.01f)

        assertEquals(listOf(first), withDense)
        assertEquals(listOf(first, useful), withUseful)
    }

    @Test
    fun circleStrokeProducesPaddedBoundsAndPassesValidation() {
        val points = listOf(
            FeedbackPoint(0.2f, 0.2f),
            FeedbackPoint(0.3f, 0.18f),
            FeedbackPoint(0.4f, 0.2f),
            FeedbackPoint(0.42f, 0.3f),
            FeedbackPoint(0.4f, 0.4f),
            FeedbackPoint(0.3f, 0.42f),
            FeedbackPoint(0.2f, 0.4f),
            FeedbackPoint(0.18f, 0.3f),
        )

        val bounds = requireNotNull(feedbackBounds(points, padding = 0.01f))

        assertEquals(0.17f, bounds.x, 0.0001f)
        assertEquals(0.17f, bounds.y, 0.0001f)
        assertEquals(0.26f, bounds.w, 0.0001f)
        assertEquals(0.26f, bounds.h, 0.0001f)
        assertTrue(isMeaningfulFeedbackStroke(points))
    }

    @Test
    fun tinyOrLinearStrokeIsRejected() {
        val tiny = List(8) { index -> FeedbackPoint(0.5f + index * 0.001f, 0.5f) }
        val line = List(8) { index -> FeedbackPoint(0.1f + index * 0.05f, 0.1f + index * 0.05f) }

        assertFalse(isMeaningfulFeedbackStroke(tiny))
        assertFalse(isMeaningfulFeedbackStroke(line))
    }
}
