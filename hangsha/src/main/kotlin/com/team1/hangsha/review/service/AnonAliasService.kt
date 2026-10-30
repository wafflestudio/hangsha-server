package com.team1.hangsha.review.service

import com.team1.hangsha.review.repository.AnonAliasRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 행사별 익명 번호. 그 행사에 처음 익명 글을 쓸 때 한 번 발급하고, 이후 그 행사의 모든 익명 글이 재사용한다.
 * 저장해 두므로 앞선 글이 지워져도 번호가 밀리지 않는다.
 */
@Service
class AnonAliasService(
    private val anonAliasRepository: AnonAliasRepository,
) {
    @Transactional
    fun ensureAlias(eventId: Long, userId: Long): Int {
        repeat(MAX_ATTEMPTS) {
            anonAliasRepository.findSeq(eventId, userId)?.let { return it }
            try {
                anonAliasRepository.insertNext(eventId, userId)
            } catch (_: DuplicateKeyException) {
                // 다른 요청이 같은 번호(또는 같은 사람의 번호)를 먼저 잡았다. 다시 읽고 재시도.
            }
        }
        return anonAliasRepository.findSeq(eventId, userId)
            ?: throw IllegalStateException("익명 번호 발급 실패: eventId=$eventId")
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
    }
}
