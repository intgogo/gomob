package io.gomob.feature.message

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File

internal fun Bitmap.writeMessageCapture(context: Context): Uri {
    val dir = File(context.cacheDir, "message_captures").also { it.mkdirs() }
    val file = File.createTempFile("gomob_capture_", ".jpg", dir)
    file.outputStream().use { out ->
        compress(Bitmap.CompressFormat.JPEG, 92, out)
    }
    return Uri.fromFile(file)
}
