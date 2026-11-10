package com.ml.shubham0204.facenet_android.coprocessor

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** API token helpers. Pure JVM so they are unit tested. */
object TokenUtils {
    private const val TOKEN_BYTES = 32

    /** 256-bit random token, URL-safe Base64 without padding (43 chars). */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Constant-time comparison; an empty expected token never matches. */
    fun matches(
        provided: String?,
        expected: String,
    ): Boolean {
        if (provided == null || expected.isEmpty()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }
}
