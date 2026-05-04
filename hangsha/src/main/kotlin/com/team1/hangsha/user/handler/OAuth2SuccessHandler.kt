package com.team1.hangsha.user.handler

import com.team1.hangsha.user.JwtTokenProvider
import com.team1.hangsha.user.AuthCookieSupport
import com.team1.hangsha.user.TokenHasher
import com.team1.hangsha.user.model.RefreshToken
import org.springframework.http.HttpHeaders
import com.team1.hangsha.user.repository.RefreshTokenRepository
import com.team1.hangsha.user.repository.UserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.stereotype.Component
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.util.UriComponentsBuilder

@Component
class OAuth2SuccessHandler(
    private val jwtTokenProvider: JwtTokenProvider,
    private val userRepository: UserRepository,

    private val refreshTokenRepository: RefreshTokenRepository,
    private val tokenHasher: TokenHasher,
    private val cookieSupport: AuthCookieSupport,
    @Value("\${jwt.refresh-expiration-ms}") private val refreshExpirationMs: Long,
    @Value("\${app.oauth2.front-redirect-uri}") private val frontRedirectUri: String,
) : SimpleUrlAuthenticationSuccessHandler() {

    override fun onAuthenticationSuccess(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authentication: Authentication
    ) {
        val oAuth2User = authentication.principal as OAuth2User
        val email = oAuth2User.name
        val isNewUser = oAuth2User.attributes["isNewUser"] as? Boolean ?: false
        val user = userRepository.findByEmail(email)
            ?: throw RuntimeException("User not found after OAuth2 login")

        val accessToken = jwtTokenProvider.createAccessToken(user.id!!, user.isAdmin)
        val refreshToken = jwtTokenProvider.createRefreshToken(user.id!!)
        saveRefresh(user.id!!, refreshToken)

        val cookie = cookieSupport.buildRefreshCookie(
            token = refreshToken,
            maxAgeSeconds = refreshExpirationMs / 1000
        )
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())

        val targetUrl = UriComponentsBuilder.fromUriString(frontRedirectUri)
            .queryParam("accessToken", accessToken)
            .queryParam("isNewUser", isNewUser)
            .build().toUriString()

        redirectStrategy.sendRedirect(request, response, targetUrl)
    }

    private fun saveRefresh(userId: Long, refreshToken: String) {
        val jti = jwtTokenProvider.getJti(refreshToken)
        val expiresAt = jwtTokenProvider.parseClaims(refreshToken).expiration.toInstant()

        refreshTokenRepository.save(
            RefreshToken(
                userId = userId,
                jti = jti,
                tokenHash = tokenHasher.hash(refreshToken),
                expiresAt = expiresAt,
                revokedAt = null,
            )
        )
    }
}
