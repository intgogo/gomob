package io.gomob.feature.scan3d

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VinAutoCaptureWorkflowTest {
    @Test
    fun `手动与自动竞态只能认领一次捕获`() {
        val manualFirst = VinAutoCaptureWorkflow()
        assertThat(manualFirst.tryStartCapture(VinCaptureOrigin.Manual)).isTrue()
        assertThat(manualFirst.tryStartCapture(VinCaptureOrigin.Auto)).isFalse()

        val autoFirst = VinAutoCaptureWorkflow()
        assertThat(autoFirst.tryStartCapture(VinCaptureOrigin.Auto)).isTrue()
        assertThat(autoFirst.tryStartCapture(VinCaptureOrigin.Manual)).isFalse()
    }

    @Test
    fun `判废或上传错误后自动请求被锁住但用户可以明确重拍`() {
        val workflow = VinAutoCaptureWorkflow()
        assertThat(workflow.tryStartCapture(VinCaptureOrigin.Auto)).isTrue()
        workflow.lockAfterCaptureFailure()

        assertThat(workflow.tryStartCapture(VinCaptureOrigin.Auto)).isFalse()
        assertThat(workflow.tryStartCapture(VinCaptureOrigin.Auto)).isFalse()
        assertThat(workflow.tryStartCapture(VinCaptureOrigin.Manual)).isTrue()
        assertThat(workflow.tryStartCapture(VinCaptureOrigin.Auto)).isFalse()
    }

    @Test
    fun `burst质量瞬时失败会重新武装自动捕获`() {
        val workflow = VinAutoCaptureWorkflow()
        assertThat(workflow.tryStartCapture(VinCaptureOrigin.Auto)).isTrue()
        workflow.rearmAfterTransientQualityFailure()

        assertThat(workflow.tryStartCapture(VinCaptureOrigin.Auto)).isTrue()
    }

    @Test
    fun `还原成功只自动识别一次且识别失败后仅允许手动重试`() {
        val workflow = VinAutoCaptureWorkflow()
        assertThat(workflow.tryStartCapture(VinCaptureOrigin.Auto)).isTrue()

        assertThat(workflow.onRestoreSuccess()).isTrue()
        assertThat(workflow.onRestoreSuccess()).isFalse()
        assertThat(workflow.tryStartRecognition()).isFalse()

        workflow.finishRecognition(success = false)
        assertThat(workflow.tryStartRecognition()).isTrue()
        workflow.finishRecognition(success = true)
        assertThat(workflow.tryStartRecognition()).isFalse()
    }

    @Test
    fun `重新扫描清除捕获和识别锁存`() {
        val workflow = VinAutoCaptureWorkflow()
        assertThat(workflow.tryStartCapture(VinCaptureOrigin.Auto)).isTrue()
        assertThat(workflow.onRestoreSuccess()).isTrue()
        workflow.finishRecognition(success = true)

        workflow.reset()

        assertThat(workflow.tryStartCapture(VinCaptureOrigin.Auto)).isTrue()
        assertThat(workflow.onRestoreSuccess()).isTrue()
    }
}
