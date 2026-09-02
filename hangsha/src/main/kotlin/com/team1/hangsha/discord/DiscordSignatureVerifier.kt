package com.team1.hangsha.discord

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

@Component
class DiscordSignatureVerifier(
    @Value("\${discord.application-public-key:}") private val applicationPublicKey: String,
) {
    fun verify(timestamp: String?, signature: String?, body: ByteArray): Boolean {
        if (applicationPublicKey.isBlank() || timestamp.isNullOrBlank() || signature.isNullOrBlank()) return false

        return runCatching {
            val rawKey = applicationPublicKey.hexToBytes()
            require(rawKey.size == ED25519_PUBLIC_KEY_SIZE)
            val key = KeyFactory.getInstance("Ed25519").generatePublic(
                X509EncodedKeySpec(ED25519_X509_PREFIX + rawKey),
            )
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(timestamp.toByteArray(Charsets.UTF_8))
                update(body)
                verify(signature.hexToBytes())
            }
        }.getOrDefault(false)
    }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private companion object {
        const val ED25519_PUBLIC_KEY_SIZE = 32
        val ED25519_X509_PREFIX = byteArrayOf(
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
        )
    }
}
