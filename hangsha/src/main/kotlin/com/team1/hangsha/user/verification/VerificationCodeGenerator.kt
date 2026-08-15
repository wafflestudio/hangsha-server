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
        // 숫자만 쓴다. 대소문자 구분이 사라져 입력 실수가 줄고,
        // 모바일에서 숫자 키패드를 띄울 수 있다(inputmode="numeric").
        private const val ALPHABET = "0123456789"
    }
}
