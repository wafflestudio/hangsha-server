package com.team1.hangsha.category.repository

import com.team1.hangsha.category.model.EventStatus
import org.springframework.data.repository.CrudRepository

interface EventStatusRepository : CrudRepository<EventStatus, Long> {
    fun findByName(name: String): EventStatus?
    fun findAllByOrderBySortOrderAsc(): List<EventStatus>
}
