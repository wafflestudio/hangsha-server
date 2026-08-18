package com.team1.hangsha.memo.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime

/** 메모 + 메모가 달린 행사 정보 한 줄 */
data class MemoWithEventRow(
    val id: Long,
    val eventId: Long,
    val content: String,
    val createdAt: Instant?,
    val updatedAt: Instant?,

    val eventTitle: String?,
    val eventTypeId: Long?,
    val applyEnd: LocalDateTime?,
    val orgId: Long?,
    val orgName: String?,
    val isBookmarked: Boolean,
)

data class MemoTagRow(
    val memoId: Long,
    val tagId: Long,
    val tagName: String,
)

@Repository
class MemoQueryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * 유저의 메모를 행사 정보(마감일/주최기관/북마크 여부)와 함께 조회한다.
     * events/categories/bookmarks는 LEFT JOIN이라 행사가 지워졌거나 주최기관 미분류여도 메모는 그대로 나온다.
     */
    fun findMemosWithEventByUserId(userId: Long): List<MemoWithEventRow> {
        val sql = """
            SELECT m.id             AS id,
                   m.event_id       AS event_id,
                   m.content        AS content,
                   m.created_at     AS created_at,
                   m.updated_at     AS updated_at,
                   e.title          AS event_title,
                   e.event_type_id  AS event_type_id,
                   e.apply_end      AS apply_end,
                   c.id             AS org_id,
                   c.name           AS org_name,
                   (b.id IS NOT NULL) AS is_bookmarked
            FROM memos m
            LEFT JOIN events e ON e.id = m.event_id
            LEFT JOIN categories c ON c.id = e.org_id
            LEFT JOIN bookmarks b ON b.event_id = m.event_id AND b.user_id = :userId
            WHERE m.user_id = :userId
            ORDER BY m.created_at DESC, m.id DESC
        """.trimIndent()

        return jdbc.query(sql, mapOf("userId" to userId)) { rs, _ -> rs.toMemoWithEventRow() }
    }

    fun findTagsByMemoIds(memoIds: List<Long>): List<MemoTagRow> {
        if (memoIds.isEmpty()) return emptyList()

        val sql = """
            SELECT mt.memo_id AS memo_id,
                   t.id       AS tag_id,
                   t.name     AS tag_name
            FROM memo_tags mt
            JOIN tags t ON t.id = mt.tag_id
            WHERE mt.memo_id IN (:memoIds)
            ORDER BY mt.memo_id, t.id
        """.trimIndent()

        return jdbc.query(sql, mapOf("memoIds" to memoIds)) { rs, _ ->
            MemoTagRow(
                memoId = rs.getLong("memo_id"),
                tagId = rs.getLong("tag_id"),
                tagName = rs.getString("tag_name"),
            )
        }
    }
}

private fun ResultSet.getLongOrNull(column: String): Long? =
    getLong(column).let { if (wasNull()) null else it }

private fun ResultSet.toMemoWithEventRow(): MemoWithEventRow = MemoWithEventRow(
    id = getLong("id"),
    eventId = getLong("event_id"),
    content = getString("content"),
    createdAt = getTimestamp("created_at")?.toInstant(),
    updatedAt = getTimestamp("updated_at")?.toInstant(),

    eventTitle = getString("event_title"),
    eventTypeId = getLongOrNull("event_type_id"),
    applyEnd = getTimestamp("apply_end")?.toLocalDateTime(),
    orgId = getLongOrNull("org_id"),
    orgName = getString("org_name"),
    isBookmarked = getBoolean("is_bookmarked"),
)
