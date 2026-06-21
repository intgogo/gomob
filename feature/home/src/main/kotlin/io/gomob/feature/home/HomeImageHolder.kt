package io.gomob.feature.home

import android.graphics.Bitmap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内的拍摄图缓存。
 *
 * Compose 路由参数只能传字符串，Bitmap 直接走 SavedStateHandle 会序列化爆掉。
 * 这里用一个内存 map 按 token 传 Bitmap，由消费端在 LaunchedEffect 里 take 出去后清空，
 * 避免泄漏。
 */
internal object HomeImageHolder {
    private val store = ConcurrentHashMap<String, Bitmap>()

    fun put(bitmap: Bitmap): String {
        val token = UUID.randomUUID().toString()
        store[token] = bitmap
        return token
    }

    fun take(token: String): Bitmap? = store.remove(token)
}
