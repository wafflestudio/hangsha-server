package com.team1.hangsha.category.service

import com.team1.hangsha.category.dto.core.CategoryItemDto
import com.team1.hangsha.category.repository.EventTypeRepository
import com.team1.hangsha.category.repository.OrganizationRepository
import com.team1.hangsha.category.repository.EventStatusRepository
import org.springframework.stereotype.Service

@Service
class CategoryService(
    private val eventStatusRepository: EventStatusRepository,
    private val eventTypeRepository: EventTypeRepository,
    private val organizationRepository: OrganizationRepository,
) {
    fun getEventStatuses(): List<CategoryItemDto> = eventStatusRepository.findAllByOrderBySortOrderAsc().map {
        CategoryItemDto(requireNotNull(it.id), it.name, it.sortOrder)
    }

    fun getEventTypes(): List<CategoryItemDto> = eventTypeRepository.findAllByOrderBySortOrderAsc().map {
        CategoryItemDto(requireNotNull(it.id), it.name, it.sortOrder)
    }

    fun getOrganizations(): List<CategoryItemDto> = organizationRepository.findAllWithMinimumEventCount(2).map {
        CategoryItemDto(requireNotNull(it.id), it.name, it.sortOrder)
    }
}
