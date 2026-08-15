package com.team1.hangsha.user.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import java.time.Instant

data class SendEmailCodeRequest(
    @field:NotBlank
    @field:Email
    val email: String,
)

data class SendEmailCodeResponse(
    /** 인증번호 만료 시각. 화면의 카운트다운은 이 값을 기준으로 계산한다. */
    val expiresAt: Instant,
)

data class VerifyEmailCodeRequest(
    @field:NotBlank
    @field:Email
    val email: String,

    @field:NotBlank
    val code: String,
)

data class VerifyEmailCodeResponse(
    /** 계정 생성 요청에 그대로 넘겨야 하는 인증 완료 증표. */
    val signupToken: String,
    val expiresAt: Instant,
)
