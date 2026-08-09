package com.team1.hangsha.user.verification

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 메일을 실제로 보내지 않고 로그로만 남기는 구현체.
 * 로컬에서 메일함 없이 인증 흐름을 끝까지 테스트하기 위한 것이다.
 *
 * `email.sender=oci` 로 바꾸면 실제 발송 구현체가 대신 등록된다.
 */
@Component
@ConditionalOnProperty(
    prefix = "email",
    name = ["sender"],
    havingValue = "logging",
    matchIfMissing = true,
)
class LoggingEmailSender : EmailSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendVerificationCode(to: String, code: String, ttl: Duration) {
        log.info(
            "[MAIL:DRY-RUN] 인증번호 발송 (실제로 보내지 않음) to={} code={} 유효시간={}분",
            to,
            code,
            ttl.toMinutes(),
        )
    }
}
