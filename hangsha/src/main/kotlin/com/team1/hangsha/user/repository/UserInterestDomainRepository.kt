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
        SELECT CASE
                 WHEN u.event_status_id IS NOT NULL THEN 'EVENT_STATUS'
                 WHEN u.event_type_id IS NOT NULL THEN 'EVENT_TYPE'
                 ELSE 'ORGANIZATION'
               END AS category_type,
               COALESCE(u.event_status_id, u.event_type_id, u.organization_id) AS category_id,
               COALESCE(es.name, et.name, o.name) AS name,
               COALESCE(es.sort_order, et.sort_order, o.sort_order) AS sort_order,
               u.priority
        FROM user_interest_categories u
        LEFT JOIN event_statuses es ON es.id = u.event_status_id
        LEFT JOIN event_types et ON et.id = u.event_type_id
        LEFT JOIN organizations o ON o.id = u.organization_id
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
        jdbc.update("DELETE FROM user_interest_categories WHERE user_id = :userId", mapOf("userId" to userId))
        items.forEach { item ->
            val sql = when (item.type) {
                InterestCategoryType.EVENT_STATUS -> "INSERT INTO user_interest_categories (user_id, event_status_id, priority) VALUES (:userId, :id, :priority)"
                InterestCategoryType.EVENT_TYPE -> "INSERT INTO user_interest_categories (user_id, event_type_id, priority) VALUES (:userId, :id, :priority)"
                InterestCategoryType.ORGANIZATION -> "INSERT INTO user_interest_categories (user_id, organization_id, priority) VALUES (:userId, :id, :priority)"
            }
            jdbc.update(sql, mapOf("userId" to userId, "id" to item.categoryId, "priority" to item.priority))
        }
    }

    fun add(userId: Long, type: InterestCategoryType, categoryId: Long, priority: Int) {
        val column = when (type) {
            InterestCategoryType.EVENT_STATUS -> "event_status_id"
            InterestCategoryType.EVENT_TYPE -> "event_type_id"
            InterestCategoryType.ORGANIZATION -> "organization_id"
        }
        jdbc.update(
            "INSERT INTO user_interest_categories (user_id, $column, priority) VALUES (:userId, :categoryId, :priority)",
            mapOf("userId" to userId, "categoryId" to categoryId, "priority" to priority),
        )
    }

    fun delete(userId: Long, type: InterestCategoryType, categoryId: Long): Int {
        val column = when (type) {
            InterestCategoryType.EVENT_STATUS -> "event_status_id"
            InterestCategoryType.EVENT_TYPE -> "event_type_id"
            InterestCategoryType.ORGANIZATION -> "organization_id"
        }
        return jdbc.update("DELETE FROM user_interest_categories WHERE user_id = :userId AND $column = :categoryId", mapOf("userId" to userId, "categoryId" to categoryId))
    }
}
