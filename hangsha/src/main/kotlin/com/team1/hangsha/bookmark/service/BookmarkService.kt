package com.team1.hangsha.bookmark.service

import com.team1.hangsha.bookmark.repository.BookmarkRepository
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.event.dto.core.EventDto
import com.team1.hangsha.event.dto.response.BookmarkedEventResponse
import com.team1.hangsha.event.model.Event
import com.team1.hangsha.event.repository.EventRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.max

@Service
class BookmarkService(
    private val eventRepository: EventRepository,
    private val bookmarkRepository: BookmarkRepository,
) {

    @Transactional
    fun addBookmark(userId: Long, eventId: Long) {
        if (!eventRepository.existsById(eventId)) {
            throw DomainException(ErrorCode.EVENT_NOT_FOUND)
        }

        bookmarkRepository.insertIgnore(userId, eventId)
    }

    @Transactional
    fun removeBookmark(userId: Long, eventId: Long) {
        if (!eventRepository.existsById(eventId)) {
            throw DomainException(ErrorCode.EVENT_NOT_FOUND)
        }

        bookmarkRepository.delete(userId, eventId)
    }

    @Transactional(readOnly = true)
    fun listMyBookmarks(userId: Long, page: Int, size: Int): BookmarkedEventResponse {
        val safePage = max(1, page)
        val safeSize = max(1, size)
        val offset = (safePage - 1) * safeSize

        val total = bookmarkRepository.countByUserId(userId)
        val items = bookmarkRepository.findBookmarkedEventsPaged(userId, offset, safeSize)
            .map { it.toEventDtoBookmarked() }

        return BookmarkedEventResponse(
            page = safePage,
            size = safeSize,
            total = total,
            items = items,
        )
    }
}

private fun Event.toEventDtoBookmarked(): EventDto = EventDto(
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
    isInterested = null,
    matchedInterestPriority = null,
    isBookmarked = true,
)
