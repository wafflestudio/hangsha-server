package com.team1.hangsha.user.dto

data class MobileRefreshRequest(
    val refreshToken: String
)

data class MobileTokenResponse(
    val accessToken: String,
    val refreshToken: String
)

data class MobileLogoutRequest(
    val refreshToken: String
)
