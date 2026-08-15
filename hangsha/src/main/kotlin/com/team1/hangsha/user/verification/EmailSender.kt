package com.team1.hangsha.user.verification

import java.time.Duration

/**
 * 인증 메일 발송 수단.
 *
 * 구현체를 갈아끼울 수 있게 인터페이스로 둔다.
 * OCI Email Delivery 발송은 IAM 정책(use email-family)이 나온 뒤에 붙일 수 있어,
 * 그전까지는 [LoggingEmailSender]로 전체 흐름을 로컬에서 검증한다.
 */
interface EmailSender {
    fun sendVerificationCode(to: String, code: String, ttl: Duration)
}
