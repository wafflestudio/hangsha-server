package com.team1.hangsha.config

import jakarta.servlet.http.Cookie
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.stereotype.Component
import org.springframework.util.SerializationUtils
import java.util.Base64

@Component
class HttpCookieOAuth2AuthorizationRequestRepository : AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    companion object {
        const val OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME = "oauth2_auth_request"
        const val REDIRECT_URI_PARAM_COOKIE_NAME = "redirect_uri"
        const val COOKIE_EXPIRE_SECONDS = 180
    }

    override fun loadAuthorizationRequest(request: HttpServletRequest): OAuth2AuthorizationRequest? {
        val cookie = request.cookies?.find { it.name == OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME } ?: return null
        return deserialize(cookie)
    }

    override fun saveAuthorizationRequest(authorizationRequest: OAuth2AuthorizationRequest?, request: HttpServletRequest, response: HttpServletResponse) {
        if (authorizationRequest == null) {
            removeAuthorizationRequestCookies(request, response)
            return
        }
        val authCookie = Cookie(OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME, serialize(authorizationRequest)).apply {
            path = "/"
            isHttpOnly = true
            maxAge = COOKIE_EXPIRE_SECONDS
        }
        response.addCookie(authCookie)
    }

    override fun removeAuthorizationRequest(request: HttpServletRequest, response: HttpServletResponse): OAuth2AuthorizationRequest? {
        val authRequest = loadAuthorizationRequest(request)
        removeAuthorizationRequestCookies(request, response)
        return authRequest
    }

    fun removeAuthorizationRequestCookies(request: HttpServletRequest, response: HttpServletResponse) {
        val authCookie = Cookie(OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME, "").apply {
            path = "/"
            maxAge = 0
        }
        response.addCookie(authCookie)
    }

    private fun serialize(obj: Any): String = Base64.getUrlEncoder().encodeToString(SerializationUtils.serialize(obj))
    private fun deserialize(cookie: Cookie): OAuth2AuthorizationRequest = SerializationUtils.deserialize(Base64.getUrlDecoder().decode(cookie.value)) as OAuth2AuthorizationRequest
}