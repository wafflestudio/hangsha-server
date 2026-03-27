package com.team1.hangsha.category.repository

import com.team1.hangsha.category.model.CategoryGroup
import org.springframework.data.repository.CrudRepository

interface CategoryGroupRepository : CrudRepository<CategoryGroup, Long> {
    fun findByName(name: String): CategoryGroup?

    fun findAllByOrderBySortOrderAsc(): List<CategoryGroup>
}