package com.team1.hangsha.event.dto.core

// events.json을 그대로 받는 DTO

data class CrawledProgramEvent(
    val dataSeq: String? = null,
    val majorTypes: List<String> = emptyList(), // [org, eventType]
    val title: String? = null,
    val status: String? = null,
    val operationMode: String? = null,
    val applyStart: String? = null,
    val applyEnd: String? = null,
    val activityStart: String? = null,
    val activityEnd: String? = null,
    val applyCount: Int? = null,
    val capacity: Int? = null,
    val imageUrl: String? = null,
    val tags: List<String> = emptyList(),
    val mainContentHtml: String? = null,
    val detailSessions: List<CrawledDetailSession> = emptyList(),
)

data class CrawledDetailSession(
    val round: Int? = null,
    val location: String? = null,
    val startDate: String? = null,
    val endDate: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
)