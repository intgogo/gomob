package io.gomob.nativebridge.camera

/**
 * 相机消费者引用计数与延迟停流代际。
 *
 * 这里只持有极短的状态锁，不执行 USB/native 操作；主线程 acquire/release 不会被慢速停流阻塞。
 */
internal class CameraLeaseState {
    data class AcquireResult(
        val count: Int,
        val shouldStart: Boolean,
    )

    data class ReleaseResult(
        val count: Int,
        val stopToken: Long?,
        val underflow: Boolean,
    )

    private val stateLock = Any()
    private var count = 0
    private var generation = 0L

    val consumerCount: Int
        get() = synchronized(stateLock) { count }

    fun acquire(): AcquireResult = synchronized(stateLock) {
        generation++
        count++
        AcquireResult(count = count, shouldStart = count == 1)
    }

    fun release(): ReleaseResult = synchronized(stateLock) {
        if (count == 0) {
            return@synchronized ReleaseResult(count = 0, stopToken = null, underflow = true)
        }
        generation++
        count--
        ReleaseResult(
            count = count,
            stopToken = generation.takeIf { count == 0 },
            underflow = false,
        )
    }

    /** 旧宽限任务即使已经越过 delay，也不能消费后续 acquire/release 周期。 */
    fun canStop(stopToken: Long): Boolean = synchronized(stateLock) {
        count == 0 && generation == stopToken
    }
}
