package com.team1.hangsha.category.repository

import com.team1.hangsha.category.model.EventType
import org.springframework.data.repository.CrudRepository

interface EventTypeRepository : CrudRepository<EventType, Long> {
    fun findByName(name: String): EventType?
    fun findAllByOrderBySortOrderAsc(): List<EventType>
}
