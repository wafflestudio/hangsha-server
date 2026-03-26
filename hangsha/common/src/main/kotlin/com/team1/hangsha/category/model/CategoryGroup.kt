package com.team1.hangsha.category.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table

@Table("category_groups")
data class CategoryGroup(
    @Id
    val id: Long? = null,
    val name: String,
    val sortOrder: Int = 0
)