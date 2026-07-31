package com.team1.hangsha.admin

import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.user.JwtTokenProvider
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
class AdminAuthService(
    private val jwtTokenProvider: JwtTokenProvider,
    private val environment: Environment,
) {
    fun issueAccessToken(code: String): String {
        val expected = environment.getProperty("admin.access-code", "").trim()
        val actual = code.trim()

        if (expected.isBlank() || !constantTimeEquals(expected, actual)) {
            throw DomainException(ErrorCode.AUTH_INVALID_CREDENTIALS, "관리자 접근 코드가 올바르지 않습니다")
        }

        return jwtTokenProvider.createAccessToken(ADMIN_SYSTEM_USER_ID, isAdmin = true)
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean {
        val expectedBytes = expected.toByteArray(StandardCharsets.UTF_8)
        val actualBytes = actual.toByteArray(StandardCharsets.UTF_8)

        return MessageDigest.isEqual(expectedBytes, actualBytes)
    }

    companion object {
        private const val ADMIN_SYSTEM_USER_ID = 0L
    }
}
