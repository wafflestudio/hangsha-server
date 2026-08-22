package com.team1.hangsha.category.repository

import com.team1.hangsha.category.model.Organization
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository

interface OrganizationRepository : CrudRepository<Organization, Long> {
    fun findByName(name: String): Organization?
    fun findAllByOrderBySortOrderAsc(): List<Organization>

    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM organizations")
    fun findMaxSortOrder(): Int

    @Query("""
        SELECT o.* FROM organizations o
        WHERE (SELECT COUNT(*) FROM events e WHERE e.org_id = o.id AND e.admin_deleted = FALSE) >= :minimumEventCount
        ORDER BY o.sort_order ASC
    """)
    fun findAllWithMinimumEventCount(minimumEventCount: Int): List<Organization>
}
