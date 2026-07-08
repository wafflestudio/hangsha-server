package com.team1.hangsha.user.dto

data class AndroidRefreshRequest(
    val refreshToken: String
)

data class AndroidTokenResponse(
    val accessToken: String,
    val refreshToken: String
)

data class AndroidLogoutRequest(
    val refreshToken: String
)