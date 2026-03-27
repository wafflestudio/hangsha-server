package com.team1.hangsha.event.dto.request

import java.time.LocalDateTime

data class EventPatchRequest(
    val title: String? = null,
    val imageUrl: String? = null,
    val operationMode: String? = null,

    val tags: List<String>? = null,
    val mainContentHtml: String? = null,

    val statusId: Long? = null,
    val eventTypeId: Long? = null,
    val orgId: Long? = null,

    val applyStart: LocalDateTime? = null,
    val applyEnd: LocalDateTime? = null,
    val eventStart: LocalDateTime? = null,
    val eventEnd: LocalDateTime? = null,

    val capacity: Int? = null,
    val applyCount: Int? = null,

    val organization: String? = null,
    val location: String? = null,
    val applyLink: String? = null,
)