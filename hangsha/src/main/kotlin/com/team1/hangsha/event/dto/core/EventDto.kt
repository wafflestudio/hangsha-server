package com.team1.hangsha.event.dto.core

import com.team1.hangsha.tag.dto.core.TagDto
import java.time.LocalDateTime

data class EventDto(
    val id: Long,
    val title: String,
    val imageUrl: String? = null,
    val operationMode: String? = null,

    val statusId: Long? = null,
    val eventTypeId: Long? = null,
    val orgId: Long? = null,

    val applyStart: LocalDateTime? = null,
    val applyEnd: LocalDateTime? = null,
    val eventStart: LocalDateTime? = null,
    val eventEnd: LocalDateTime? = null,
    val isPeriodEvent: Boolean = false,

    val capacity: Int? = null,
    val applyCount: Int,

    val organization: String? = null,
    val location: String? = null,
    val applyLink: String? = null,

    val tags: String? = null,

    // @TODO: personalization (지금은 미구현 → null 유지)
    val isInterested: Boolean? = null,
    val matchedInterestPriority: Int? = null,
    val isBookmarked: Boolean? = null,
)
