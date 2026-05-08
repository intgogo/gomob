package io.gomob.realtime

import javax.inject.Inject

class RealtimeReconnectPolicy @Inject constructor() {
    fun delayMillis(attempt: Int): Long {
        val normalized = attempt.coerceAtLeast(0)
        val seconds = 1 shl normalized.coerceAtMost(5)
        return seconds.coerceAtMost(30).toLong() * 1_000L
    }
}
