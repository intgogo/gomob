package io.gomob.scan

import android.content.Context
import android.security.keystore.KeyStoreException
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** 从工厂 provisioning 的 Android Keystore 密文读取 VIN 服务 API Key。 */
internal object VinApiKeyProvisioning {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "vin-rubbing-api-key"
    private const val FILE_NAME = "vin/api-key.bin"
    private const val IV_BYTES = 12

    fun read(context: Context): String {
        val encrypted = File(context.filesDir, FILE_NAME).takeIf(File::exists)
            ?: error("VIN API Key 尚未完成工厂 provisioning")
        val payload = encrypted.readBytes()
        require(payload.size > IV_BYTES) { "VIN API Key provisioning 文件已损坏" }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
            ?: throw KeyStoreException("VIN API Key Keystore 条目不存在")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, entry.secretKey, GCMParameterSpec(128, payload, 0, IV_BYTES))
        return cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES)
            .toString(Charsets.UTF_8)
            .trim()
            .also { require(KEY_PATTERN.matches(it)) { "VIN API Key provisioning 值格式非法" } }
    }

    private val KEY_PATTERN = Regex("[A-Za-z0-9._-]{1,64}:[^\\s:]{16,256}")
}
