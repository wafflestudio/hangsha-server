package com.team1.hangsha.user

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component

@Component
class AuthCookieSupport(
    @Value("\${auth.refresh-cookie.secure}")
    private val secure: Boolean,
    @Value("\${auth.refresh-cookie.same-site}")
    private val sameSite: String,
    @Value("\${auth.refresh-cookie.domain:}")
    private val domain: String,
) {
    fun buildRefreshCookie(token: String, maxAgeSeconds: Long): ResponseCookie {
        val builder = ResponseCookie.from("refreshToken", token)
            .httpOnly(true)
            .secure(secure)
            .sameSite(sameSite)
            .path("/api/v1/auth")
            .maxAge(maxAgeSeconds)

        if (domain.isNotBlank()) {
            builder.domain(domain)
        }

        return builder.build()
    }

    fun clearRefreshCookie(): ResponseCookie {
        val builder = ResponseCookie.from("refreshToken", "")
            .httpOnly(true)
            .secure(secure)
            .sameSite(sameSite)
            .path("/api/v1/auth")
            .maxAge(0)

        if (domain.isNotBlank()) {
            builder.domain(domain)
        }

        return builder.build()
    }
}