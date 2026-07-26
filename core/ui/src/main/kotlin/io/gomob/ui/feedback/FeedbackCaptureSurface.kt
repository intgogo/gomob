package io.gomob.ui.feedback

/**
 * 独立 Surface 参与页面反馈截图时的渲染冻结契约。
 *
 * 两个方法都由主线程调用。实现必须停止继续提交新帧，并在截图结束后恢复原渲染状态，避免
 * Window 与 Surface 的两次 PixelCopy 跨帧拼接。
 */
interface FeedbackCaptureSurface {
    fun pauseForFeedbackCapture()

    fun resumeAfterFeedbackCapture()
}
