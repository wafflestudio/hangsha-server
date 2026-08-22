package com.team1.hangsha.event.controller

import com.team1.hangsha.event.dto.response.Calendar.MonthEventResponse
import com.team1.hangsha.event.dto.response.Calendar.DayEventResponse
import com.team1.hangsha.event.dto.response.SearchEventResponse
import com.team1.hangsha.event.dto.response.DetailEventResponse
import com.team1.hangsha.event.dto.response.EventCountResponse
import com.team1.hangsha.event.service.EventService
import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.model.User
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.format.annotation.DateTimeFormat.ISO
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/events")
class EventController(
    private val eventService: EventService,
) {

    @GetMapping("/month")
    fun month(
        @Parameter(hidden = true) @LoggedInUser user: User?,
        @RequestParam("from") @DateTimeFormat(iso = ISO.DATE) from: LocalDate,
        @RequestParam("to") @DateTimeFormat(iso = ISO.DATE) to: LocalDate,
        @RequestParam("statusId", required = false) statusIds: List<Long>?,
        @RequestParam("eventTypeId", required = false) eventTypeIds: List<Long>?,
        @RequestParam("orgId", required = false) orgIds: List<Long>?,
        @RequestParam("applyExcludedKeywords", defaultValue = "true") applyExcludedKeywords: Boolean,
        @RequestParam("excludedKeyword", required = false) excludedKeywords: List<String>?,
    ): MonthEventResponse =
        eventService.getMonthEvents(
            from = from,
            to = to,
            statusIds = statusIds,
            eventTypeIds = eventTypeIds,
            orgIds = orgIds,
            userId = user?.id,
            applyExcludedKeywords = applyExcludedKeywords,
            excludedKeywords = excludedKeywords,
        )

    @GetMapping("/count")
    fun count(
        @Parameter(hidden = true) @LoggedInUser user: User?,
        @RequestParam("from") @DateTimeFormat(iso = ISO.DATE) from: LocalDate,
        @RequestParam("to") @DateTimeFormat(iso = ISO.DATE) to: LocalDate,
        @RequestParam("statusId", required = false) statusIds: List<Long>?,
        @RequestParam("eventTypeId", required = false) eventTypeIds: List<Long>?,
        @RequestParam("orgId", required = false) orgIds: List<Long>?,
        @RequestParam("applyExcludedKeywords", defaultValue = "true") applyExcludedKeywords: Boolean,
    ): EventCountResponse =
        eventService.countEvents(
            from = from,
            to = to,
            statusIds = statusIds,
            eventTypeIds = eventTypeIds,
            orgIds = orgIds,
            userId = user?.id,
            applyExcludedKeywords = applyExcludedKeywords,
        )

    @GetMapping("/{eventId}")
    fun detail(
        @Parameter(hidden = true) @LoggedInUser user: User?,
        @PathVariable eventId: Long,
    ): DetailEventResponse =
        eventService.getEventDetail(eventId, user?.id)

    @GetMapping("/day")
    fun day(
        @Parameter(hidden = true) @LoggedInUser user: User?,
        @RequestParam("date") @DateTimeFormat(iso = ISO.DATE) date: LocalDate,
        @RequestParam("page", defaultValue = "1") page: Int,
        @RequestParam("size", defaultValue = "20") size: Int,
        @RequestParam("statusId", required = false) statusIds: List<Long>?,
        @RequestParam("eventTypeId", required = false) eventTypeIds: List<Long>?,
        @RequestParam("orgId", required = false) orgIds: List<Long>?,
        @RequestParam("applyExcludedKeywords", defaultValue = "true") applyExcludedKeywords: Boolean,
        @RequestParam("excludedKeyword", required = false) excludedKeywords: List<String>?,
    ): DayEventResponse =
        eventService.getDayEvents(
            date = date,
            page = page,
            size = size,
            statusIds = statusIds,
            eventTypeIds = eventTypeIds,
            orgIds = orgIds,
            userId = user?.id,
            applyExcludedKeywords = applyExcludedKeywords,
            excludedKeywords = excludedKeywords,
        )

    @GetMapping("/search")
    fun search(
        @Parameter(hidden = true) @LoggedInUser user: User?,
        @RequestParam("query") query: String,
        @RequestParam("page", defaultValue = "1") page: Int,
        @RequestParam("size", defaultValue = "20") size: Int,
        @RequestParam("statusId", required = false) statusIds: List<Long>?,
        @RequestParam("eventTypeId", required = false) eventTypeIds: List<Long>?,
        @RequestParam("orgId", required = false) orgIds: List<Long>?,
    ): SearchEventResponse =
        eventService.search(
            query = query,
            page = page,
            size = size,
            statusIds = statusIds,
            eventTypeIds = eventTypeIds,
            orgIds = orgIds,
            userId = user?.id,
        )
}
