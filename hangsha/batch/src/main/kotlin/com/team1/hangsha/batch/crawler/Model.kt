package com.team1.hangsha.batch.crawler

import kotlinx.serialization.Serializable

@Serializable
data class ProgramEvent(
    val dataSeq: String? = null,

    val majorTypes: List<String> = emptyList(),
    val title: String? = null,
    val status: String? = null,

    val operationMode: String? = null,

    val applyStart: String? = null,    // "2026-01-07"
    val applyEnd: String? = null,      // "2026-01-13"
    val activityStart: String? = null, // null 가능
    val activityEnd: String? = null,   // null 가능

    val applyCount: Int? = null,
    val capacity: Int? = null,

    val imageUrl: String? = null,
    val mainContentHtml: String? = null,

    val tags: List<String> = emptyList(),

    val detailSessions: List<DetailSession> = emptyList()
)

@Serializable
data class DetailSession(
    val round: Int? = null,
    val location: String? = null,
    val startDate: String? = null,      // "2026-01-15"
    val endDate: String? = null,        // "2026-01-16"
    val startTime: String? = null,      // "09:30"
    val endTime: String? = null,        // "18:00"
)