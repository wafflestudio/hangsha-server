package com.team1.hangsha.bookmark.repository

import com.team1.hangsha.event.model.Event
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDateTime

@Repository
class BookmarkRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /** 이미 있으면 무시(중복 북마크 방지) --> 새로 추가된 경우에만 1 리턴 */
    fun insertIgnore(userId: Long, eventId: Long): Int {
        val sql = """
            INSERT IGNORE INTO bookmarks (user_id, event_id)
            VALUES (:userId, :eventId)
        """.trimIndent()

        return jdbc.update(sql, mapOf("userId" to userId, "eventId" to eventId))
    }

    fun delete(userId: Long, eventId: Long): Int {
        val sql = """
            DELETE FROM bookmarks
            WHERE user_id = :userId AND event_id = :eventId
        """.trimIndent()

        return jdbc.update(sql, mapOf("userId" to userId, "eventId" to eventId))
    }

    fun exists(userId: Long, eventId: Long): Boolean {
        val sql = """
            SELECT 1
            FROM bookmarks
            WHERE user_id = :userId AND event_id = :eventId
            LIMIT 1
        """.trimIndent()

        val rows = jdbc.query(sql, mapOf("userId" to userId, "eventId" to eventId)) { _, _ -> 1 }
        return rows.isNotEmpty()
    }

    fun countByUserId(userId: Long): Int {
        val sql = """
            SELECT COUNT(*)
            FROM bookmarks b
            JOIN events e ON e.id = b.event_id
            WHERE b.user_id = :userId
              AND e.admin_deleted = false
        """.trimIndent()

        return jdbc.queryForObject(sql, mapOf("userId" to userId), Int::class.java) ?: 0
    }

    fun findBookmarkedEventsPaged(userId: Long, offset: Int, limit: Int): List<Event> {
        val sql = """
            SELECT e.*
            FROM bookmarks b
            JOIN events e ON e.id = b.event_id
            WHERE b.user_id = :userId
              AND e.admin_deleted = false
            ORDER BY b.created_at DESC, b.id DESC
            LIMIT :limit OFFSET :offset
        """.trimIndent()

        val params = mapOf(
            "userId" to userId,
            "limit" to kotlin.math.max(0, limit),
            "offset" to kotlin.math.max(0, offset),
        )

        return jdbc.query(sql, params) { rs, _ -> rs.toEvent() }
    }

    fun findBookmarkedEventIdsIn(userId: Long, eventIds: List<Long>): Set<Long> {
        if (eventIds.isEmpty()) return emptySet()

        val sql = """
            SELECT b.event_id
            FROM bookmarks b
            WHERE b.user_id = :userId
              AND b.event_id IN (:eventIds)
        """.trimIndent()

        val params = mapOf(
            "userId" to userId,
            "eventIds" to eventIds,
        )

        return jdbc.query(sql, params) { rs, _ -> rs.getLong("event_id") }.toSet()
    }
}

private fun ResultSet.getLocalDateTimeOrNull(column: String): LocalDateTime? =
    getTimestamp(column)?.toLocalDateTime()

private fun ResultSet.getInstantOrNull(column: String): java.time.Instant? =
    getTimestamp(column)?.toInstant()

private fun ResultSet.toEvent(): Event {
    return Event(
        id = getLong("id").let { if (wasNull()) null else it },
        title = getString("title"),
        imageUrl = getString("image_url"),
        operationMode = getString("operation_mode"),

        tags = getString("tags"),
        mainContentHtml = getString("main_content_html"),

        statusId = getLong("status_id").let { if (wasNull()) null else it },
        eventTypeId = getLong("event_type_id").let { if (wasNull()) null else it },
        orgId = getLong("org_id").let { if (wasNull()) null else it },

        applyStart = getLocalDateTimeOrNull("apply_start"),
        applyEnd = getLocalDateTimeOrNull("apply_end"),
        eventStart = getLocalDateTimeOrNull("event_start"),
        eventEnd = getLocalDateTimeOrNull("event_end"),

        isPeriodEvent = getBoolean("is_period_event"),
        adminOverriddenFields = getString("admin_overridden_fields"),
        adminDeleted = getBoolean("admin_deleted"),

        capacity = getInt("capacity").let { if (wasNull()) null else it },
        applyCount = getInt("apply_count"),

        organization = getString("organization"),
        location = getString("location"),
        applyLink = getString("apply_link"),

        createdAt = getInstantOrNull("created_at"),
        updatedAt = getInstantOrNull("updated_at"),
    )
}
