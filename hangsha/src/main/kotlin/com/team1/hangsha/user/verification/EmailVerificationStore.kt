package com.team1.hangsha.user.verification

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * 인증 대기 상태를 Valkey(Redis 호환)에 보관한다.
 *
 * 인증 코드는 수 분 뒤 사라져야 하는 휘발성 데이터라 TTL이 있는 저장소를 쓴다.
 * MySQL에 뒀다면 만료 컬럼과 정리 배치가 필요했겠지만, 여기서는 만료를 저장소가 처리한다.
 *
 * 이메일은 키에 그대로 쓰지 않고 해시한다. Valkey에 requirepass가 걸려 있지 않아
 * SCAN으로 키를 훑으면 가입 시도한 이메일 목록이 그대로 드러나기 때문이다.
 */
@Component
class EmailVerificationStore(
    private val redis: StringRedisTemplate,
) {
    data class Pending(val codeHash: String, val attempts: Int)

    // --- 재발송 쿨다운 -------------------------------------------------

    /** 쿨다운 슬롯을 잡으면 true. 이미 잡혀 있으면(=쿨다운 중) false. */
    fun tryAcquireResendSlot(email: String, cooldown: Duration): Boolean =
        redis.opsForValue().setIfAbsent(cooldownKey(email), "1", cooldown) == true

    /** 메일 발송에 실패했을 때 쿨다운을 풀어 즉시 재시도할 수 있게 한다. */
    fun releaseResendSlot(email: String) {
        redis.delete(cooldownKey(email))
    }

    // --- 인증 코드 -----------------------------------------------------

    fun save(email: String, codeHash: String, ttl: Duration) {
        val key = codeKey(email)
        // 재발송이면 이전 코드와 시도 횟수를 버리고 새로 시작한다.
        redis.delete(key)
        redis.opsForHash<String, String>().putAll(
            key,
            mapOf(FIELD_CODE_HASH to codeHash, FIELD_ATTEMPTS to "0"),
        )
        redis.expire(key, ttl)
    }

    fun find(email: String): Pending? {
        val entries = redis.opsForHash<String, String>().entries(codeKey(email))
        val codeHash = entries[FIELD_CODE_HASH] ?: return null
        return Pending(
            codeHash = codeHash,
            attempts = entries[FIELD_ATTEMPTS]?.toIntOrNull() ?: 0,
        )
    }

    /** 시도 횟수를 원자적으로 올리고 증가 후 값을 돌려준다. */
    fun incrementAttempts(email: String): Long =
        redis.opsForHash<String, String>().increment(codeKey(email), FIELD_ATTEMPTS, 1L)

    fun delete(email: String) {
        redis.delete(codeKey(email))
    }

    // --- 인증 완료 증표(signupToken) --------------------------------------

    fun issueSignupToken(email: String, ttl: Duration): String {
        val token = UUID.randomUUID().toString()
        redis.opsForValue().set(signupTokenKey(token), email, ttl)
        return token
    }

    /** 토큰에 묶인 이메일. 없으면 만료됐거나 발급된 적 없는 토큰이다. */
    fun findSignupTokenEmail(token: String): String? =
        redis.opsForValue().get(signupTokenKey(token))

    /**
     * 계정 생성이 끝난 뒤에만 호출한다.
     * 조회 시점에 지워버리면 비밀번호 검증에 걸렸을 때 다시 인증해야 해서다.
     */
    fun deleteSignupToken(token: String) {
        redis.delete(signupTokenKey(token))
    }

    // --- 키 ------------------------------------------------------------

    private fun codeKey(email: String) = "$PREFIX:${hash(email)}"
    private fun cooldownKey(email: String) = "$PREFIX:cooldown:${hash(email)}"
    private fun signupTokenKey(token: String) = "signup-token:$token"

    private fun hash(email: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(email.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val PREFIX = "email-verify"
        private const val FIELD_CODE_HASH = "codeHash"
        private const val FIELD_ATTEMPTS = "attempts"
    }
}
