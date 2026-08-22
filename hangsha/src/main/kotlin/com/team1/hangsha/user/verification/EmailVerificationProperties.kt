package com.team1.hangsha.user.verification

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "email.verification")
data class EmailVerificationProperties(
    /** 인증번호 유효시간. 화면의 카운트다운(10:00)과 같은 값이어야 한다. */
    val ttl: Duration = Duration.ofMinutes(10),
    /** "인증번호 다시 받기" 재발송 쿨다운. 발송 한도를 한 명이 소진하는 것을 막는다. */
    val resendCooldown: Duration = Duration.ofSeconds(60),
    /** 인증번호 입력 허용 횟수. 초과하면 폐기하고 재발송을 요구한다. */
    val maxAttempts: Int = 5,
    /** 인증번호 자리수. 화면의 입력칸 개수와 같아야 한다. */
    val codeLength: Int = 6,
    /** 인증 통과 후 발급하는 signupToken의 유효시간. */
    val signupTokenTtl: Duration = Duration.ofMinutes(30),
)
