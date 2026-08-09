package com.team1.hangsha.user.verification

import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.user.TokenHasher
import com.team1.hangsha.user.model.AuthProvider
import com.team1.hangsha.user.repository.UserIdentityRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * 회원가입 이메일 인증.
 *
 * 화면 흐름상 비밀번호는 인증을 통과한 뒤에 입력받으므로,
 * 인증 단계에서 비밀번호를 보관하지 않는다. 저장소에는 이메일과 코드 해시만 남는다.
 */
@Service
class EmailVerificationService(
    private val store: EmailVerificationStore,
    private val codeGenerator: VerificationCodeGenerator,
    private val emailSender: EmailSender,
    private val tokenHasher: TokenHasher,
    private val userIdentityRepository: UserIdentityRepository,
    private val properties: EmailVerificationProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 인증번호를 발급해 메일로 보낸다. "인증번호 다시 받기"도 이 경로를 그대로 탄다.
     * @return 인증번호 만료 시각 (화면 카운트다운용)
     */
    fun sendCode(rawEmail: String): Instant {
        val email = normalize(rawEmail)

        if (userIdentityRepository.existsByProviderAndEmail(AuthProvider.LOCAL, email)) {
            throw DomainException(ErrorCode.USER_EMAIL_ALREADY_EXISTS)
        }

        if (!store.tryAcquireResendSlot(email, properties.resendCooldown)) {
            throw DomainException(ErrorCode.EMAIL_VERIFICATION_RESEND_COOLDOWN)
        }

        val code = codeGenerator.generate(properties.codeLength)
        store.save(email, tokenHasher.hash(code), properties.ttl)

        try {
            emailSender.sendVerificationCode(email, code, properties.ttl)
        } catch (e: Exception) {
            // 발송이 실패했는데 쿨다운에 묶어두면 사용자가 아무것도 못 한다.
            store.releaseResendSlot(email)
            store.delete(email)
            log.error("인증 메일 발송 실패 email={}", email, e)
            throw DomainException(ErrorCode.EMAIL_SEND_FAILED, cause = e)
        }

        return Instant.now().plus(properties.ttl)
    }

    /**
     * 인증번호를 검증하고, 통과하면 계정 생성에 쓸 signupToken을 발급한다.
     */
    fun verifyCode(rawEmail: String, code: String): VerifiedSignup {
        val email = normalize(rawEmail)
        val pending = store.find(email) ?: throw DomainException(ErrorCode.EMAIL_VERIFICATION_EXPIRED)

        // 맞았는지 보기 전에 먼저 올린다. 틀린 시도가 카운트되지 않으면 제한이 무의미하다.
        val attempts = store.incrementAttempts(email)
        if (attempts > properties.maxAttempts) {
            store.delete(email)
            throw DomainException(ErrorCode.EMAIL_VERIFICATION_TOO_MANY_ATTEMPTS)
        }

        if (!tokenHasher.matches(code.trim(), pending.codeHash)) {
            throw DomainException(ErrorCode.EMAIL_VERIFICATION_CODE_MISMATCH)
        }

        store.delete(email)
        val token = store.issueSignupToken(email, properties.signupTokenTtl)
        return VerifiedSignup(
            signupToken = token,
            expiresAt = Instant.now().plus(properties.signupTokenTtl),
        )
    }

    /**
     * 계정 생성 직전에 호출한다. 토큰이 유효하고 해당 이메일에 발급된 것인지 확인만 한다.
     * 토큰 삭제는 계정 생성이 끝난 뒤 [consumeSignupToken]으로 따로 한다.
     */
    fun requireVerified(rawEmail: String, signupToken: String) {
        val email = normalize(rawEmail)
        val verifiedEmail = store.findSignupTokenEmail(signupToken)
            ?: throw DomainException(ErrorCode.EMAIL_VERIFICATION_REQUIRED)

        if (verifiedEmail != email) {
            throw DomainException(ErrorCode.EMAIL_VERIFICATION_REQUIRED)
        }
    }

    fun consumeSignupToken(signupToken: String) {
        store.deleteSignupToken(signupToken)
    }

    /** 발송과 검증에서 같은 키를 쓰도록 이메일 표기를 통일한다. */
    private fun normalize(email: String): String = email.trim().lowercase()

    data class VerifiedSignup(
        val signupToken: String,
        val expiresAt: Instant,
    )
}
