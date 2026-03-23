package com.team1.hangsha.event.service

import com.team1.hangsha.bookmark.repository.BookmarkRepository
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.event.dto.core.EventDto
import com.team1.hangsha.event.dto.response.Calendar.MonthEventResponse
import com.team1.hangsha.event.dto.response.DetailEventResponse
import com.team1.hangsha.event.dto.response.Calendar.DayEventResponse
import com.team1.hangsha.event.dto.response.TitleSearchEventResponse
import com.team1.hangsha.event.model.Event
import com.team1.hangsha.event.repository.EventQueryRepository
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.user.repository.UserInterestCategoryRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max

@Service
class EventService(
    private val eventRepository: EventRepository,
    private val eventQueryRepository: EventQueryRepository,
    private val userInterestCategoryRepository: UserInterestCategoryRepository,
    private val bookmarkRepository: BookmarkRepository,
) {

    fun getMonthEvents(
        from: LocalDate,
        to: LocalDate,
        statusIds: List<Long>?,
        eventTypeIds: List<Long>?,
        orgIds: List<Long>?,
        userId: Long?,
    ): MonthEventResponse {
        if (from.isAfter(to)) {
            throw DomainException(ErrorCode.INVALID_REQUEST, "from은 to보다 이후일 수 없습니다")
        }

        val fromStart = from.atStartOfDay()
        val toEndExclusive = to.plusDays(1).atStartOfDay()

        val events = eventQueryRepository.findInRange(
            fromStart = fromStart,
            toEndExclusive = toEndExclusive,
            statusIds = statusIds,
            eventTypeIds = eventTypeIds,
            orgIds = orgIds,
            userId = userId,
        )

        val interestPriorityByCategoryId = loadInterestMap(userId)

        // 날짜별 버킷: 행사 기간 또는 신청 기간 중 하나라도 그 날과 겹치면 포함
        val buckets = linkedMapOf<LocalDate, MutableList<Event>>().apply {
            var d = from
            while (!d.isAfter(to)) {
                this[d] = mutableListOf()
                d = d.plusDays(1)
            }
        }

        fun effectiveStart(e: Event): LocalDateTime =
            listOfNotNull(e.applyStart, e.eventStart).minOrNull() ?: fromStart

        fun effectiveEnd(e: Event): LocalDateTime =
            listOfNotNull(e.applyEnd, e.eventEnd).maxOrNull() ?: effectiveStart(e)

        fun addRangeToBuckets(event: Event, start: LocalDateTime?, end: LocalDateTime?) {
            val rangeStart = start ?: return
            val rangeEnd = end ?: rangeStart
            val s = rangeStart.toLocalDate().coerceAtLeast(from)
            val ee = rangeEnd.toLocalDate().coerceAtMost(to)
            if (s.isAfter(ee)) return

            var d = s
            while (!d.isAfter(ee)) {
                val dayBucket = buckets[d] ?: error("bucket missing for $d")
                if (dayBucket.none { it.id == event.id }) {
                    dayBucket.add(event)
                }
                d = d.plusDays(1)
            }
        }

        for (e in events) {
            addRangeToBuckets(e, e.eventStart, e.eventEnd)
            addRangeToBuckets(e, e.applyStart, e.applyEnd)
        }

        val auth = userId != null
        val bookmarkedIds: Set<Long> =
            if (auth) bookmarkRepository.findBookmarkedEventIdsIn(
                userId,
                events.mapNotNull { it.id }
            ) else emptySet()

        val byDate = buckets
            .filterValues { it.isNotEmpty() }
            .toSortedMap()
            .mapValues { (_, dayEvents) ->
                val sorted = dayEvents.sortedWith(
                    compareBy<Event> { effectiveStart(it) }.thenBy { it.id ?: Long.MAX_VALUE }
                )
                MonthEventResponse.DayBucket(
                    events = sorted.map { e ->
                        val matchedPriority = e.matchedInterestPriority(interestPriorityByCategoryId)
                        val isBookmarked = if (auth) bookmarkedIds.contains(requireNotNull(e.id)) else null
                        e.toDto(auth, matchedPriority, isBookmarked)
                    },
                )
            }
            .mapKeys { (date, _) -> date.toString() }

        return MonthEventResponse(
            range = MonthEventResponse.Range(from = from, to = to),
            byDate = byDate,
        )
    }

    fun getEventDetail(eventId: Long, userId: Long?): DetailEventResponse {
        val event = eventRepository.findById(eventId).orElseThrow {
            DomainException(ErrorCode.EVENT_NOT_FOUND)
        }

        val interestPriorityByCategoryId = loadInterestMap(userId)
        val matchedPriority = event.matchedInterestPriority(interestPriorityByCategoryId)
        val isBookmarked: Boolean? = userId?.let { bookmarkRepository.exists(it, eventId) }

        return event.toDetailResponse(auth = userId != null, matchedPriority = matchedPriority, isBookmarked = isBookmarked)
    }

    fun getDayEvents(
        date: LocalDate,
        page: Int,
        size: Int,
        statusIds: List<Long>?,
        eventTypeIds: List<Long>?,
        orgIds: List<Long>?,
        userId: Long?,
    ): DayEventResponse {
        val total = eventQueryRepository.countOnDay(date, statusIds, eventTypeIds, orgIds, userId)
        val events = eventQueryRepository.findOnDayPaged(date, statusIds, eventTypeIds, orgIds, page, size, userId)

        val interestPriorityByCategoryId = loadInterestMap(userId)
        val auth = userId != null
        val bookmarkedIds: Set<Long> =
            if (auth) bookmarkRepository.findBookmarkedEventIdsIn(
                userId,
                events.mapNotNull { it.id }
            ) else emptySet()

        val items = events.map { e ->
            val matchedPriority = e.matchedInterestPriority(interestPriorityByCategoryId)
            val isBookmarked = if (auth) bookmarkedIds.contains(requireNotNull(e.id)) else null
            e.toDto(auth, matchedPriority, isBookmarked)
        }

        return DayEventResponse(
            page = max(1, page),
            size = max(1, size),
            total = total,
            date = date,
            items = items,
        )
    }

    fun searchTitle(
        query: String,
        page: Int,
        size: Int,
        userId: Long?,
    ): TitleSearchEventResponse {
        val q = query.trim()
        if (q.isEmpty()) {
            throw DomainException(ErrorCode.INVALID_REQUEST, "query는 비어있을 수 없습니다")
        }

        val safePage = max(1, page)
        val safeSize = max(1, size)
        val offset = (safePage - 1) * safeSize

        val total = eventQueryRepository.countByTitleContains(q, userId)
        val events = eventQueryRepository.findByTitleContainsPaged(q, offset, safeSize, userId)

        val interestPriorityByCategoryId = loadInterestMap(userId)
        val auth = userId != null
        val bookmarkedIds: Set<Long> =
            if (auth) bookmarkRepository.findBookmarkedEventIdsIn(
                userId,
                events.mapNotNull { it.id }
            ) else emptySet()

        val items = events.map { e ->
            val matchedPriority = e.matchedInterestPriority(interestPriorityByCategoryId)
            val isBookmarked = if (auth) bookmarkedIds.contains(requireNotNull(e.id)) else null
            e.toDto(auth, matchedPriority, isBookmarked)
        }

        return TitleSearchEventResponse(
            page = safePage,
            size = safeSize,
            total = total,
            items = items,
        )
    }

    private fun loadInterestMap(userId: Long?): Map<Long, Int> {
        if (userId == null) return emptyMap()
        return userInterestCategoryRepository.findAllWithCategoryByUserId(userId)
            .associate { it.categoryId to it.priority }
    }
}

private fun Event.matchedInterestPriority(priorityByCategoryId: Map<Long, Int>): Int? {
    if (priorityByCategoryId.isEmpty()) return null
    val p1 = statusId?.let { priorityByCategoryId[it] }
    val p2 = eventTypeId?.let { priorityByCategoryId[it] }
    val p3 = orgId?.let { priorityByCategoryId[it] }
    return listOfNotNull(p1, p2, p3).minOrNull()
}

private fun Event.toDto(auth: Boolean, matchedPriority: Int?, isBookmarked: Boolean?): EventDto {
    val isInterested = if (auth) matchedPriority != null else null
    val matched = if (auth) matchedPriority else null

    return EventDto(
        id = requireNotNull(id),
        title = title,
        imageUrl = imageUrl,
        operationMode = operationMode,
        statusId = statusId,
        eventTypeId = eventTypeId,
        orgId = orgId,
        applyStart = applyStart,
        applyEnd = applyEnd,
        eventStart = eventStart,
        eventEnd = eventEnd,
        capacity = capacity,
        applyCount = applyCount,
        organization = organization,
        location = location,
        applyLink = applyLink,
        tags = tags,
        isInterested = isInterested,
        matchedInterestPriority = matched,
        isBookmarked = isBookmarked,
    )
}

private fun Event.toDetailResponse(auth: Boolean, matchedPriority: Int?, isBookmarked: Boolean?): DetailEventResponse {
    val isInterested = if (auth) matchedPriority != null else null
    val matched = if (auth) matchedPriority else null

    return DetailEventResponse(
        id = requireNotNull(id),
        title = title,
        imageUrl = imageUrl,
        operationMode = operationMode,
        statusId = statusId,
        eventTypeId = eventTypeId,
        orgId = orgId,
        applyStart = applyStart,
        applyEnd = applyEnd,
        eventStart = eventStart,
        eventEnd = eventEnd,
        capacity = capacity,
        applyCount = applyCount,
        organization = organization,
        location = location,
        applyLink = applyLink,
        tags = tags,
        isInterested = isInterested,
        matchedInterestPriority = matched,
        isBookmarked = isBookmarked,
        detail = mainContentHtml,
    )
}
