package com.team1.hangsha.auth.dto

data class SocialLoginRequest(
    val provider: String,
    val code: String?, // web 필수, mobile null
    val accessToken: String?, // web null, mobile 필수
    val codeVerifier: String? // 구글 PKCE용
)

data class TokenResponse(
    val accessToken: String,
    val isNewUser: Boolean
)