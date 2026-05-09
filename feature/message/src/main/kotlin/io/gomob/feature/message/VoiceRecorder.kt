package io.gomob.feature.message

import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

internal data class VoiceRecordResult(
    val uri: Uri,
    val durationSec: Int,
)

internal class VoiceRecorder(
    private val cacheDir: File,
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAtMs: Long = 0L

    val recording: Boolean get() = recorder != null

    fun start() {
        if (recording) return
        val dir = File(cacheDir, "voice_records").also { it.mkdirs() }
        val file = File.createTempFile("gomob_voice_", ".m4a", dir)
        val next = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(96_000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        outputFile = file
        recorder = next
        startedAtMs = SystemClock.elapsedRealtime()
    }

    fun stop(): VoiceRecordResult {
        val current = recorder ?: throw IllegalStateException("录音未开始")
        val file = outputFile ?: throw IllegalStateException("录音文件缺失")
        val durationSec = ((SystemClock.elapsedRealtime() - startedAtMs + 999) / 1000).toInt()
        runCatching { current.stop() }
            .onFailure {
                release()
                file.delete()
                throw IllegalStateException("录音时间过短，请重新录制", it)
            }
        current.release()
        recorder = null
        outputFile = null
        return VoiceRecordResult(
            uri = Uri.fromFile(file),
            durationSec = durationSec.coerceAtLeast(1),
        )
    }

    fun cancel() {
        val file = outputFile
        release()
        file?.delete()
    }

    private fun release() {
        recorder?.runCatching { release() }
        recorder = null
        outputFile = null
        startedAtMs = 0L
    }
}

@Composable
internal fun rememberVoiceRecorder(): VoiceRecorder {
    val context = LocalContext.current
    val recorder = remember(context) { VoiceRecorder(context.cacheDir) }
    DisposableEffect(recorder) {
        onDispose { recorder.cancel() }
    }
    return recorder
}
