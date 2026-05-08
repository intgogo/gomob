package io.gomob.common.net

/**
 * IPv4 输入草稿。UI 只能改四段 octet，保存前再规范化成 "a.b.c.d"。
 */
data class Ipv4AddressDraft(
    val first: String = "",
    val second: String = "",
    val third: String = "",
    val fourth: String = "",
    private val segmentCount: Int = EXPECTED_SEGMENT_COUNT,
    private val containsInvalidCharacters: Boolean = false,
) {
    val octets: List<String>
        get() = listOf(first, second, third, fourth)

    fun display(): String = octets.joinToString(".")

    fun updateOctet(index: Int, raw: String): Ipv4AddressDraft {
        if (raw.contains('.')) return from(raw)
        val value = raw.filter(Char::isDigit).take(MAX_OCTET_LENGTH)
        return when (index) {
            0 -> copy(first = value, segmentCount = EXPECTED_SEGMENT_COUNT, containsInvalidCharacters = false)
            1 -> copy(second = value, segmentCount = EXPECTED_SEGMENT_COUNT, containsInvalidCharacters = false)
            2 -> copy(third = value, segmentCount = EXPECTED_SEGMENT_COUNT, containsInvalidCharacters = false)
            3 -> copy(fourth = value, segmentCount = EXPECTED_SEGMENT_COUNT, containsInvalidCharacters = false)
            else -> this
        }
    }

    fun normalizedOrNull(): String? {
        if (validationError() != null) return null
        return octets.map { it.toInt().toString() }.joinToString(".")
    }

    fun validationError(label: String = "IP"): String? {
        if (octets.all { it.isBlank() }) return "请输入$label"
        if (containsInvalidCharacters) return "$label 只能包含数字"
        if (segmentCount != EXPECTED_SEGMENT_COUNT || octets.any { it.isBlank() }) return "$label 需填写 4 段"
        val numbers = octets.map { it.toIntOrNull() }
        if (numbers.any { it == null }) return "$label 只能包含数字"
        if (numbers.any { it !in 0..255 }) return "$label 每段需在 0-255"
        return null
    }

    companion object {
        private const val MAX_OCTET_LENGTH = 3
        private const val EXPECTED_SEGMENT_COUNT = 4

        fun from(text: String): Ipv4AddressDraft {
            val parts = text.trim().split('.')
            return Ipv4AddressDraft(
                first = parts.getOrNull(0).orEmpty().cleanOctet(),
                second = parts.getOrNull(1).orEmpty().cleanOctet(),
                third = parts.getOrNull(2).orEmpty().cleanOctet(),
                fourth = parts.getOrNull(3).orEmpty().cleanOctet(),
                segmentCount = parts.size,
                containsInvalidCharacters = parts.any { part -> part.any { !it.isDigit() } },
            )
        }

        private fun String.cleanOctet(): String =
            filter(Char::isDigit).take(MAX_OCTET_LENGTH)
    }
}
