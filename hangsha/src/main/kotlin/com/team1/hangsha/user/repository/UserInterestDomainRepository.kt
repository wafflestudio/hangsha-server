package com.team1.hangsha.user.repository

import com.team1.hangsha.user.model.InterestCategoryType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

data class InterestCategoryRow(
    val type: InterestCategoryType,
    val categoryId: Long,
    val name: String,
    val sortOrder: Int,
    val priority: Int,
)

@Repository
class UserInterestDomainRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun findAllByUserId(userId: Long): List<InterestCategoryRow> = jdbc.query(
        """
        SELECT 'EVENT_STATUS' AS category_type, es.id AS category_id, es.name, es.sort_order, u.priority
        FROM user_interest_event_statuses u JOIN event_statuses es ON es.id = u.event_status_id
        WHERE u.user_id = :userId
        UNION ALL
        SELECT 'EVENT_TYPE', et.id, et.name, et.sort_order, u.priority
        FROM user_interest_event_types u JOIN event_types et ON et.id = u.event_type_id
        WHERE u.user_id = :userId
        UNION ALL
        SELECT 'ORGANIZATION', o.id, o.name, o.sort_order, u.priority
        FROM user_interest_organizations u JOIN organizations o ON o.id = u.organization_id
        WHERE u.user_id = :userId
        ORDER BY priority ASC
        """.trimIndent(), mapOf("userId" to userId)
    ) { rs, _ ->
        InterestCategoryRow(
            type = InterestCategoryType.valueOf(rs.getString("category_type")),
            categoryId = rs.getLong("category_id"), name = rs.getString("name"),
            sortOrder = rs.getInt("sort_order"), priority = rs.getInt("priority"),
        )
    }

    fun replaceAll(userId: Long, items: List<InterestCategoryRow>) {
        jdbc.update("DELETE FROM user_interest_event_statuses WHERE user_id = :userId", mapOf("userId" to userId))
        jdbc.update("DELETE FROM user_interest_event_types WHERE user_id = :userId", mapOf("userId" to userId))
        jdbc.update("DELETE FROM user_interest_organizations WHERE user_id = :userId", mapOf("userId" to userId))
        items.forEach { item ->
            val sql = when (item.type) {
                InterestCategoryType.EVENT_STATUS -> "INSERT INTO user_interest_event_statuses (user_id, event_status_id, priority) VALUES (:userId, :id, :priority)"
                InterestCategoryType.EVENT_TYPE -> "INSERT INTO user_interest_event_types (user_id, event_type_id, priority) VALUES (:userId, :id, :priority)"
                InterestCategoryType.ORGANIZATION -> "INSERT INTO user_interest_organizations (user_id, organization_id, priority) VALUES (:userId, :id, :priority)"
            }
            jdbc.update(sql, mapOf("userId" to userId, "id" to item.categoryId, "priority" to item.priority))
        }
    }

    fun delete(userId: Long, type: InterestCategoryType, categoryId: Long): Int {
        val (table, column) = when (type) {
            InterestCategoryType.EVENT_STATUS -> "user_interest_event_statuses" to "event_status_id"
            InterestCategoryType.EVENT_TYPE -> "user_interest_event_types" to "event_type_id"
            InterestCategoryType.ORGANIZATION -> "user_interest_organizations" to "organization_id"
        }
        return jdbc.update("DELETE FROM $table WHERE user_id = :userId AND $column = :categoryId", mapOf("userId" to userId, "categoryId" to categoryId))
    }
}
