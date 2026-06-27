package com.team1.hangsha.event.dto.response

import com.team1.hangsha.event.dto.core.EventDto

data class SearchEventResponse(
    val page: Int,
    val size: Int,
    val total: Int,
    val items: List<SearchEventItem>,
)

data class SearchEventItem(
    val event: EventDto,
    val highlight: SearchHighlight,
)

data class SearchHighlight(
    val title: String,
    val contentSnippet: String?,
)
