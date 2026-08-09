package com.team1.hangsha.user.verification

import org.springframework.stereotype.Component
import java.security.SecureRandom

@Component
class VerificationCodeGenerator {

    private val random = SecureRandom()

    fun generate(length: Int): String =
        buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }

    companion object {
        // 메일로 받은 코드를 사람이 보고 옮겨 적으므로 혼동되는 문자(O/0, I/1)는 제외한다.
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
