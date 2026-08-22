package com.team1.hangsha.user.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.annotation.CreatedDate
import java.time.Instant

@Table("legacy_user_interest_categories")
data class UserInterestCategory(
    @Id
    val id: Long? = null,
    val userId: Long,
    val categoryId: Long,
    val priority: Int,
    @CreatedDate
    val createdAt: Instant? = null,
)
