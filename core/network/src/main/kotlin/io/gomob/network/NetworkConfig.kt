package io.gomob.network

object NetworkConfig {
    /**
     * Base URL — 当前硬编码本机 dev 服务器。
     *
     * Emulator 通过 `adb reverse tcp:8808 tcp:8808` 把设备 127.0.0.1:8808 转回宿主 8808。
     * 真机（ADB Wi-Fi）也用 adb reverse；如果不能 reverse 就改 hostIp:8808。
     *
     * 生产从 DataStore "网络设置" 的网关 IP / 端口动态构造。
     */
    fun baseUrl(): String = "http://127.0.0.1:8808/"
}
