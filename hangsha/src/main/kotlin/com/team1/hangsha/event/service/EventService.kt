package com.team1.hangsha.event.service

import com.team1.hangsha.bookmark.repository.BookmarkRepository
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.event.dto.core.EventDto
import com.team1.hangsha.event.dto.response.Calendar.MonthEventResponse
import com.team1.hangsha.event.dto.response.DetailEventResponse
import com.team1.hangsha.event.dto.response.EventCountResponse
import com.team1.hangsha.event.dto.response.Calendar.DayEventResponse
import com.team1.hangsha.event.dto.response.SearchEventItem
import com.team1.hangsha.event.dto.response.SearchEventResponse
import com.team1.hangsha.event.dto.response.SearchHighlight
import com.team1.hangsha.event.model.Event
import com.team1.hangsha.event.repository.EventQueryRepository
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.search.ManticoreSearchService
import com.team1.hangsha.search.SearchHighlighter
import com.team1.hangsha.user.repository.UserExcludedKeywordRepository
import com.team1.hangsha.user.repository.UserInterestCategoryRepository
import org.jsoup.Jsoup
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
    private val manticoreSearchService: ManticoreSearchService,
    private val userExcludedKeywordRepository: UserExcludedKeywordRepository,
) {

    fun getMonthEvents(
        from: LocalDate,
        to: LocalDate,
        statusIds: List<Long>?,
        eventTypeIds: List<Long>?,
        orgIds: List<Long>?,
        userId: Long?,
        applyExcludedKeywords: Boolean = true,
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
            applyExcludedKeywords = applyExcludedKeywords,
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

        fun sortStart(e: Event): LocalDateTime =
            e.eventStart ?: e.applyStart ?: fromStart

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
            if (e.isPeriodEvent) {
                addRangeToBuckets(e, e.applyStart, e.applyEnd)
            } else {
                addRangeToBuckets(e, e.eventStart, e.eventEnd)
            }
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
                    compareBy<Event> { it.matchedInterestPriority(interestPriorityByCategoryId) ?: Int.MAX_VALUE }
                        .thenBy { sortStart(it) }
                        .thenBy { it.id ?: Long.MAX_VALUE }
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


    fun countEvents(
        from: LocalDate,
        to: LocalDate,
        statusIds: List<Long>?,
        eventTypeIds: List<Long>?,
        orgIds: List<Long>?,
        userId: Long?,
        applyExcludedKeywords: Boolean = true,
    ): EventCountResponse {
        if (from.isAfter(to)) {
            throw DomainException(ErrorCode.INVALID_REQUEST, "from은 to보다 이후일 수 없습니다")
        }

        val count = eventQueryRepository.countInRange(
            fromStart = from.atStartOfDay(),
            toEndExclusive = to.plusDays(1).atStartOfDay(),
            statusIds = statusIds,
            eventTypeIds = eventTypeIds,
            orgIds = orgIds,
            userId = userId,
            applyExcludedKeywords = applyExcludedKeywords,
        )
        return EventCountResponse(count = count)
    }

    fun getEventDetail(eventId: Long, userId: Long?): DetailEventResponse {
        val event = eventRepository.findVisibleById(eventId)
            ?: throw DomainException(ErrorCode.EVENT_NOT_FOUND)

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
        applyExcludedKeywords: Boolean = true,
    ): DayEventResponse {
        val total = eventQueryRepository.countOnDay(
            date, statusIds, eventTypeIds, orgIds, userId, applyExcludedKeywords
        )
        val events = eventQueryRepository.findOnDayPaged(
            date, statusIds, eventTypeIds, orgIds, page, size, userId, applyExcludedKeywords
        )

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

    fun search(
        query: String,
        page: Int,
        size: Int,
        statusIds: List<Long>?,
        eventTypeIds: List<Long>?,
        orgIds: List<Long>?,
        userId: Long?,
    ): SearchEventResponse {
        val q = query.trim()
        if (q.isEmpty()) throw DomainException(ErrorCode.INVALID_REQUEST, "query는 비어있을 수 없습니다")

        val result = manticoreSearchService.searchUnified(q)

        val safePage = max(1, page)
        val safeSize = max(1, size)
        val offset = (safePage - 1) * safeSize

        val allEvents = sortByDeadline(
            eventQueryRepository.findVisibleByIds(
                ids = result.eventIds,
                statusIds = statusIds,
                eventTypeIds = eventTypeIds,
                orgIds = orgIds,
            )
        )

        // main_content_html 태그 제거 텍스트 캐시: 제외 필터와 하이라이트에서 재사용 (이벤트당 최대 1회 파싱)
        val contentTextCache = HashMap<Long, String?>()
        fun contentTextOf(e: Event): String? =
            contentTextCache.getOrPut(requireNotNull(e.id)) {
                e.mainContentHtml?.let { Jsoup.parse(it).text() }
            }

        // 1. 키워드 제외: 로그인 유저의 제외 키워드가 title에 포함되면 제거
        val excludedKeywords: List<String> =
            if (userId != null) userExcludedKeywordRepository
                .findAllByUserIdOrderByCreatedAtDescIdDesc(userId)
                .map { it.keyword.lowercase() }
                .filter { it.isNotBlank() }
            else emptyList()

        fun isExcluded(e: Event): Boolean {
            if (excludedKeywords.isEmpty()) return false
            val title = e.title.lowercase()
            return excludedKeywords.any { title.contains(it) }
        }

        val filteredEvents = allEvents.filterNot { isExcluded(it) }
        val events = filteredEvents.drop(offset).take(safeSize)

        val auth = userId != null
        val interestPriorityByCategoryId = loadInterestMap(userId)
        val bookmarkedIds: Set<Long> =
            if (auth) bookmarkRepository.findBookmarkedEventIdsIn(
                userId!!, events.mapNotNull { it.id }
            ) else emptySet()

        val items = events.map { e ->
            val matchedPriority = e.matchedInterestPriority(interestPriorityByCategoryId)
            val isBookmarked = if (auth) bookmarkedIds.contains(requireNotNull(e.id)) else null
            val rawContent = contentTextOf(e)

            SearchEventItem(
                event = e.toDto(auth, matchedPriority, isBookmarked),
                highlight = SearchHighlight(
                    title = SearchHighlighter.highlightWithFallback(
                        text = e.title,
                        primary = result.rawWords,
                        fallback = result.kiwiTokens,
                    ),
                    contentSnippet = rawContent?.let {
                        SearchHighlighter.extractSnippetWithFallback(
                            content = it,
                            primary = result.rawWords,
                            fallback = result.kiwiTokens,
                        )
                    },
                ),
            )
        }

        return SearchEventResponse(page = safePage, size = safeSize, total = filteredEvents.size, items = items)
    }

    /**
     * 검색 결과 정렬.
     *
     * 마감임박순(아직 안 지난 행사 오름차순) -> 이미 지난 행사(내림차순) -> 정렬 키가 없는 행사.
     * "지났다"의 기준은 오늘 00:00 이므로, 오늘 날짜에 걸친 행사는 시각과 무관하게 상단에 남는다.
     */
    private fun sortByDeadline(events: List<Event>): List<Event> {
        val cutoff = LocalDate.now().atStartOfDay()

        val upcoming = mutableListOf<Pair<Event, LocalDateTime>>()
        val past = mutableListOf<Pair<Event, LocalDateTime>>()
        val undated = mutableListOf<Event>()

        for (e in events) {
            val key = searchSortKey(e)
            when {
                key == null -> undated += e
                key.isBefore(cutoff) -> past += e to key
                else -> upcoming += e to key
            }
        }

        // 동점이면 id 내림차순(최신 등록 우선)으로 tie-break
        return upcoming.sortedWith(
            compareBy<Pair<Event, LocalDateTime>> { it.second }.thenByDescending { it.first.id }
        ).map { it.first } +
            past.sortedWith(
                compareByDescending<Pair<Event, LocalDateTime>> { it.second }.thenByDescending { it.first.id }
            ).map { it.first } +
            undated.sortedByDescending { it.id }
    }

    /** 정렬 키: applyEnd > eventStart > applyStart > eventEnd (앞이 null이면 다음 것) */
    private fun searchSortKey(e: Event): LocalDateTime? =
        e.applyEnd ?: e.eventStart ?: e.applyStart ?: e.eventEnd

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
        isPeriodEvent = isPeriodEvent,
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
        isPeriodEvent = isPeriodEvent,
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
