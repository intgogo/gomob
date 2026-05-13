package io.gomob.feature.message

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

internal fun createMessageCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "message_captures").also { it.mkdirs() }
    val file = File.createTempFile("gomob_capture_", ".jpg", dir)
    return FileProvider.getUriForFile(context, messageCaptureAuthority(context), file)
}

internal fun deleteMessageCapture(context: Context, uri: Uri) {
    runCatching { context.contentResolver.delete(uri, null, null) }
}

private fun messageCaptureAuthority(context: Context): String =
    "${context.packageName}.message.fileprovider"
