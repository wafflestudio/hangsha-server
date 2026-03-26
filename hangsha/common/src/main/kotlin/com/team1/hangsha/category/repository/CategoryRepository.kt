package com.team1.hangsha.category.repository

import com.team1.hangsha.category.model.Category
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param

interface CategoryRepository : CrudRepository<Category, Long> {
    fun findByGroupIdAndName(groupId: Long, name: String): Category?

    @Query(
        """
        select count(*) from categories
        where id in (:ids)
        """
    )
    fun countByIds(@Param("ids") ids: List<Long>): Int

    fun findAllByGroupIdOrderBySortOrderAsc(groupId: Long): List<Category>

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM categories WHERE group_id = :groupId")
    fun findMaxSortOrderByGroupId(@Param("groupId") groupId: Long): Int
}