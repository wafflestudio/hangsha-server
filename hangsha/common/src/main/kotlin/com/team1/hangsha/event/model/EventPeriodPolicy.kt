package com.team1.hangsha.event.model

import java.time.LocalDateTime

object EventPeriodPolicy {
    private val periodTitleKeywords = listOf(
        "공모전",
        "인턴십",
        "학생기자단",
    )

    fun isPeriodEvent(
        title: String,
        eventStart: LocalDateTime?,
        eventEnd: LocalDateTime?,
    ): Boolean {
        if (eventStart == null || eventEnd == null) {
            return true
        }

        if (periodTitleKeywords.any { keyword -> title.contains(keyword) }) {
            return true
        }

        return eventEnd.isAfter(eventStart.plusDays(7))
    }
}