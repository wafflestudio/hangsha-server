package com.team1.hangsha.memo.repository

import com.team1.hangsha.memo.model.Memo
import org.springframework.data.repository.CrudRepository
import org.springframework.data.jdbc.repository.query.Query
import java.util.Optional

interface MemoRepository : CrudRepository<Memo, Long> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: Long): List<Memo>

    fun findByUserIdAndEventId(userId: Long, eventId: Long): Optional<Memo>

    @Query(
        """
        SELECT DISTINCT m.*
        FROM memos m
        JOIN memo_tags mt ON mt.memo_id = m.id
        WHERE m.user_id = :userId
          AND mt.tag_id = :tagId
        ORDER BY m.created_at DESC, m.id DESC
        """
    )
    fun findAllByUserIdAndTagId(
        userId: Long,
        tagId: Long
    ): List<Memo>
}
