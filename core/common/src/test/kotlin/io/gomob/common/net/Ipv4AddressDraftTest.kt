package io.gomob.common.net

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Ipv4AddressDraftTest {
    @Test
    fun fromText_normalizesValidAddress() {
        val draft = Ipv4AddressDraft.from("192.168.001.010")

        assertThat(draft.normalizedOrNull()).isEqualTo("192.168.1.10")
    }

    @Test
    fun updateOctet_keepsOnlyThreeDigits() {
        val draft = Ipv4AddressDraft()
            .updateOctet(0, "12a34")
            .updateOctet(1, "168")
            .updateOctet(2, "0")
            .updateOctet(3, "1")

        assertThat(draft.display()).isEqualTo("123.168.0.1")
    }

    @Test
    fun validationRejectsIncompleteAddress() {
        val draft = Ipv4AddressDraft.from("192.168.1")

        assertThat(draft.validationError("网关 IP")).isEqualTo("网关 IP 需填写 4 段")
        assertThat(draft.normalizedOrNull()).isNull()
    }

    @Test
    fun validationRejectsOutOfRangeOctet() {
        val draft = Ipv4AddressDraft.from("192.168.300.1")

        assertThat(draft.validationError("网关 IP")).isEqualTo("网关 IP 每段需在 0-255")
        assertThat(draft.normalizedOrNull()).isNull()
    }

    @Test
    fun validationRejectsExtraSegments() {
        val draft = Ipv4AddressDraft.from("192.168.0.1.5")

        assertThat(draft.validationError("网关 IP")).isEqualTo("网关 IP 需填写 4 段")
        assertThat(draft.normalizedOrNull()).isNull()
    }

    @Test
    fun validationRejectsNonDigitCharactersFromText() {
        val draft = Ipv4AddressDraft.from("192.168.x.1")

        assertThat(draft.validationError("网关 IP")).isEqualTo("网关 IP 只能包含数字")
        assertThat(draft.normalizedOrNull()).isNull()
    }
}
