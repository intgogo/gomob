package io.gomob.network

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

data class DiscoveredGateway(
    val name: String,
    val endpoint: ServerEndpoint,
    val service: String,
    val latencyMs: Long,
    val serverTimestampMs: Long?,
)

@Singleton
class GatewayDiscoveryClient @Inject constructor(
    private val json: Json,
) {
    suspend fun discover(timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<DiscoveredGateway> =
        withContext(Dispatchers.IO) {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(0))
                val payload = DISCOVERY_QUERY.toByteArray(Charsets.UTF_8)
                val sentAtNs = System.nanoTime()
                discoveryTargets().forEach { target ->
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT))
                    }
                }
                receiveResponses(socket, sentAtNs, timeoutMs)
            }
        }

    private fun receiveResponses(
        socket: DatagramSocket,
        sentAtNs: Long,
        timeoutMs: Long,
    ): List<DiscoveredGateway> {
        val deadlineNs = sentAtNs + max(timeoutMs, 1L) * 1_000_000L
        val buffer = ByteArray(1024)
        val byEndpoint = linkedMapOf<String, DiscoveredGateway>()
        while (true) {
            val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) break
            socket.soTimeout = min(remainingMs, RECEIVE_SLICE_MS).toInt().coerceAtLeast(1)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val gateway = parseResponse(packet, sentAtNs) ?: continue
            val key = gateway.endpoint.display()
            val existing = byEndpoint[key]
            if (existing == null || gateway.latencyMs < existing.latencyMs) {
                byEndpoint[key] = gateway
            }
        }
        return byEndpoint.values.sortedWith(
            compareBy<DiscoveredGateway> { it.latencyMs }.thenBy { it.endpoint.display() },
        )
    }

    private fun parseResponse(packet: DatagramPacket, sentAtNs: Long): DiscoveredGateway? {
        val response = runCatching {
            val text = packet.data.decodeToString(0, packet.length)
            json.decodeFromString(DiscoveryResponseDto.serializer(), text)
        }.getOrNull() ?: return null
        if (response.type != DISCOVERY_RESPONSE || response.httpPort !in 1..65535) return null
        val address = packet.address as? Inet4Address ?: return null
        val service = response.service.takeUnless { it.isBlank() } ?: "gomob-gateway"
        val name = response.name?.takeUnless { it.isBlank() } ?: service
        return DiscoveredGateway(
            name = name,
            endpoint = ServerEndpoint(address.hostAddress ?: return null, response.httpPort),
            service = service,
            latencyMs = max(0L, (System.nanoTime() - sentAtNs) / 1_000_000L),
            serverTimestampMs = response.serverTimestampMs,
        )
    }

    private fun discoveryTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        targets += InetAddress.getByName("255.255.255.255")
        if (isLikelyEmulator()) {
            // 模拟器访问宿主机的真实网关入口，便于 dev server 自发现。
            targets += InetAddress.getByName("10.0.2.2")
        }
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())
        interfaces.asSequence()
            .filter { iface -> runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false) }
            .flatMap { iface -> runCatching { iface.interfaceAddresses.asSequence() }.getOrDefault(emptySequence()) }
            .mapNotNull { it.broadcast }
            .filterIsInstance<Inet4Address>()
            .forEach { targets += it }
        return targets.toList()
    }

    private fun isLikelyEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)

    private companion object {
        const val DISCOVERY_QUERY = "gomob.discovery.v1"
        const val DISCOVERY_RESPONSE = "gomob.gateway.v1"
        const val DISCOVERY_PORT = 18809
        const val DEFAULT_TIMEOUT_MS = 1_500L
        const val RECEIVE_SLICE_MS = 250L
    }
}

@Serializable
private data class DiscoveryResponseDto(
    val type: String,
    val service: String = "",
    val name: String? = null,
    @SerialName("http_port") val httpPort: Int = 0,
    @SerialName("server_ts") val serverTimestampMs: Long? = null,
)
