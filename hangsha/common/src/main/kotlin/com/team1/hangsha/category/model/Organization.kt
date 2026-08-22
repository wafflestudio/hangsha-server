package com.team1.hangsha.category.model

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("organizations")
data class Organization(
    @Id val id: Long? = null,
    val name: String,
    val sortOrder: Int = 0,
    @CreatedDate val createdAt: Instant? = null,
)
