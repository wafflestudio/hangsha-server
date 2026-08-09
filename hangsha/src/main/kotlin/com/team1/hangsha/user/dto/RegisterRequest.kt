package com.team1.hangsha.user.dto

data class RegisterRequest(
    val email: String,
    val password: String,
    /** 이메일 인증(/auth/email/verify-code) 응답으로 받은 값. */
    val signupToken: String,
    val username: String? = null
)
