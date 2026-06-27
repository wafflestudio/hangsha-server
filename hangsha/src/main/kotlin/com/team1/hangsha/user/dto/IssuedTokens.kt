package com.team1.hangsha.user.dto

import org.springframework.http.ResponseCookie

data class IssuedTokens(
    val accessToken: String,
    val refreshCookie: ResponseCookie,
    val refreshToken: String
)