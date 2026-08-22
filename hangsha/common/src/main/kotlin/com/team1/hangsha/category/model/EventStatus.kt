package com.team1.hangsha.category.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("event_statuses")
data class EventStatus(
    @Id val id: Long? = null,
    val name: String,
    val sortOrder: Int = 0,
)
