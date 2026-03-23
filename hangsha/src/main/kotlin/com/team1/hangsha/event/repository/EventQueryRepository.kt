package com.team1.hangsha.event.repository

import com.team1.hangsha.event.model.Event
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max

@Repository
class EventQueryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun findInRange(
        fromStart: LocalDateTime,
        toEndExclusive: LocalDateTime,
        statusIds: List<Long>?,
        eventTypeIds: List<Long>?,
        orgIds: List<Long>?,
        userId: Long?,
    ): List<Event> {
        val sql = buildString {
            append(
                """
            SELECT e.*
            FROM events e
            WHERE (
              (e.event_start IS NOT NULL AND e.event_start < :toEndExclusive AND COALESCE(e.event_end, e.event_start) >= :fromStart)
              OR
              (e.apply_start IS NOT NULL AND e.apply_start < :toEndExclusive AND COALESCE(e.apply_end, e.apply_start) >= :fromStart)
            )
            """.trimIndent()
            )

            if (!statusIds.isNullOrEmpty()) append("\n  AND status_id IN (:statusIds)")
            if (!eventTypeIds.isNullOrEmpty()) append("\n  AND event_type_id IN (:eventTypeIds)")
            if (!orgIds.isNullOrEmpty()) append("\n  AND org_id IN (:orgIds)")

            appendExcludedKeywordsFilter(userId)

            append("\nORDER BY COALESCE(e.event_start, e.apply_start) ASC, e.id ASC")
        }

        val params = mutableMapOf<String, Any>(
            "fromStart" to Timestamp.valueOf(fromStart),
            "toEndExclusive" to Timestamp.valueOf(toEndExclusive),
        )
        if (!statusIds.isNullOrEmpty()) params["statusIds"] = statusIds
        if (!eventTypeIds.isNullOrEmpty()) params["eventTypeIds"] = eventTypeIds
        if (!orgIds.isNullOrEmpty()) params["orgIds"] = orgIds
        if (userId != null) params["userId"] = userId

        return jdbc.query(sql, params) { rs, _ -> rs.toEvent() }
    }

    fun countOnDay(
        date: LocalDate,
        statusIds: List<Long>?,
        eventTypeIds: List<Long>?,
        orgIds: List<Long>?,
        userId: Long?,
    ): Int {
        val dayStart = date.atStartOfDay()
        val dayEndExclusive = date.plusDays(1).atStartOfDay()

        val sql = buildString {
            append(
                """
            SELECT COUNT(*)
            FROM events e
            WHERE (
              (e.event_start IS NOT NULL AND e.event_start < :dayEnd AND COALESCE(e.event_end, e.event_start) >= :dayStart)
              OR
              (e.apply_start IS NOT NULL AND e.apply_start < :dayEnd AND COALESCE(e.apply_end, e.apply_start) >= :dayStart)
            )
            """.trimIndent()
            )
            if (!statusIds.isNullOrEmpty()) append("\n  AND status_id IN (:statusIds)")
            if (!eventTypeIds.isNullOrEmpty()) append("\n  AND event_type_id IN (:eventTypeIds)")
            if (!orgIds.isNullOrEmpty()) append("\n  AND org_id IN (:orgIds)")

            appendExcludedKeywordsFilter(userId)
        }

        val params = mutableMapOf<String, Any>(
            "dayStart" to Timestamp.valueOf(dayStart),
            "dayEnd" to Timestamp.valueOf(dayEndExclusive),
        )
        if (!statusIds.isNullOrEmpty()) params["statusIds"] = statusIds
        if (!eventTypeIds.isNullOrEmpty()) params["eventTypeIds"] = eventTypeIds
        if (!orgIds.isNullOrEmpty()) params["orgIds"] = orgIds
        if (userId != null) params["userId"] = userId

        return jdbc.queryForObject(sql, params, Int::class.java) ?: 0
    }

    fun findOnDayPaged(
        date: LocalDate,
        statusIds: List<Long>?,
        eventTypeIds: List<Long>?,
        orgIds: List<Long>?,
        page: Int,
        size: Int,
        userId: Long?,
    ): List<Event> {
        val safePage = max(1, page)
        val safeSize = max(1, size)
        val offset = (safePage - 1) * safeSize

        val dayStart = date.atStartOfDay()
        val dayEndExclusive = date.plusDays(1).atStartOfDay()

        val sql = buildString {
            append(
                """
            SELECT e.*
            FROM events e
            WHERE (
              (e.event_start IS NOT NULL AND e.event_start < :dayEnd AND COALESCE(e.event_end, e.event_start) >= :dayStart)
              OR
              (e.apply_start IS NOT NULL AND e.apply_start < :dayEnd AND COALESCE(e.apply_end, e.apply_start) >= :dayStart)
            )
            """.trimIndent()
            )
            if (!statusIds.isNullOrEmpty()) append("\n  AND status_id IN (:statusIds)")
            if (!eventTypeIds.isNullOrEmpty()) append("\n  AND event_type_id IN (:eventTypeIds)")
            if (!orgIds.isNullOrEmpty()) append("\n  AND org_id IN (:orgIds)")

            appendExcludedKeywordsFilter(userId)

            append("\nORDER BY COALESCE(e.event_start, e.apply_start) DESC, e.id DESC")
            append("\nLIMIT :limit OFFSET :offset")
        }

        val params = mutableMapOf<String, Any>(
            "dayStart" to Timestamp.valueOf(dayStart),
            "dayEnd" to Timestamp.valueOf(dayEndExclusive),
            "limit" to safeSize,
            "offset" to offset,
        )
        if (!statusIds.isNullOrEmpty()) params["statusIds"] = statusIds
        if (!eventTypeIds.isNullOrEmpty()) params["eventTypeIds"] = eventTypeIds
        if (!orgIds.isNullOrEmpty()) params["orgIds"] = orgIds
        if (userId != null) params["userId"] = userId

        return jdbc.query(sql, params) { rs, _ -> rs.toEvent() }
    }

    fun countByTitleContains(query: String, userId: Long?): Int {
        val sql = buildString {
            append(
                """
            SELECT COUNT(*)
            FROM events e
            WHERE e.title LIKE :q
            """.trimIndent()
            )

            appendExcludedKeywordsFilter(userId)
        }

        val params = mutableMapOf<String, Any>(
            "q" to "%$query%"
        )
        if (userId != null) params["userId"] = userId
        return jdbc.queryForObject(sql, params, Int::class.java) ?: 0
    }

    fun findByTitleContainsPaged(query: String, offset: Int, limit: Int, userId: Long?): List<Event> {
        val sql = buildString {
            append(
                """
            SELECT e.*
            FROM events e
            WHERE e.title LIKE :q
            """.trimIndent()
            )

            appendExcludedKeywordsFilter(userId)

            append("\nORDER BY COALESCE(e.event_start, e.apply_start) DESC, e.id DESC")
            append("\nLIMIT :limit OFFSET :offset")
        }

        val params = mutableMapOf<String, Any>(
            "q" to "%$query%",
            "limit" to max(0, limit),
            "offset" to max(0, offset),
        )
        if (userId != null) params["userId"] = userId
        return jdbc.query(sql, params) { rs, _ -> rs.toEvent() }
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

        capacity = getInt("capacity").let { if (wasNull()) null else it },
        applyCount = getInt("apply_count"),

        organization = getString("organization"),
        location = getString("location"),
        applyLink = getString("apply_link"),

        createdAt = getInstantOrNull("created_at"),
        updatedAt = getInstantOrNull("updated_at"),
    )
}

private fun StringBuilder.appendExcludedKeywordsFilter(userId: Long?) {
    if (userId == null) return

    append(
        """
        
          AND NOT EXISTS (
            SELECT 1
            FROM user_excluded_keywords uek
            WHERE uek.user_id = :userId
              AND (
                e.title LIKE CONCAT('%', uek.keyword, '%')
                OR COALESCE(e.organization, '') LIKE CONCAT('%', uek.keyword, '%')
                OR COALESCE(e.location, '') LIKE CONCAT('%', uek.keyword, '%')
                OR COALESCE(e.tags, '') LIKE CONCAT('%', uek.keyword, '%')
                OR COALESCE(e.main_content_html, '') LIKE CONCAT('%', uek.keyword, '%')
              )
          )
        """.trimIndent()
    )
}