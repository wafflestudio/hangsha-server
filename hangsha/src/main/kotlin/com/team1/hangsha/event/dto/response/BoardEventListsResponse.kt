package com.team1.hangsha.event.dto.response

import java.time.LocalDateTime

data class DiscussedEventItem(val id: Long, val title: String, val postCount: Long)
data class DiscussedEventResponse(val items: List<DiscussedEventItem>)
data class UpcomingEventItem(val id: Long, val title: String, val applyEnd: LocalDateTime)
data class UpcomingEventResponse(val items: List<UpcomingEventItem>)
