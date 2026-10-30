package com.team1.hangsha.review.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class AnonAliasRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * 잠금 읽기라 다른 트랜잭션이 방금 커밋한 번호도 보인다.
     * (일반 SELECT는 REPEATABLE READ 스냅숏이라 재시도해도 같은 결과만 본다.)
     */
    fun findSeq(eventId: Long, userId: Long): Int? =
        jdbc.query(
            """
            SELECT seq
            FROM review_anon_aliases
            WHERE event_id = :eventId AND user_id = :userId
            LOCK IN SHARE MODE
            """.trimIndent(),
            mapOf("eventId" to eventId, "userId" to userId),
        ) { rs, _ -> rs.getInt("seq") }.firstOrNull()

    /** 그 행사의 MAX(seq)+1로 발급한다. 동시에 같은 번호를 잡으면 UNIQUE(event_id, seq) 위반이 난다. */
    fun insertNext(eventId: Long, userId: Long): Int =
        jdbc.update(
            """
            INSERT INTO review_anon_aliases (event_id, user_id, seq)
            SELECT :eventId, :userId, COALESCE(MAX(seq), 0) + 1
            FROM review_anon_aliases
            WHERE event_id = :eventId
            """.trimIndent(),
            mapOf("eventId" to eventId, "userId" to userId),
        )
}
