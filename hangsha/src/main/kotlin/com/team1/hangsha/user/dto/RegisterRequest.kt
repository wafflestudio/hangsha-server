package com.team1.hangsha.user.dto

data class RegisterRequest(
    val email: String,
    val password: String,
    val username: String? = null
)