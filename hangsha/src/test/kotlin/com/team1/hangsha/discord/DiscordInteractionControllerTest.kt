package com.team1.hangsha.discord

import com.fasterxml.jackson.databind.ObjectMapper
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.event.dto.core.EventDto
import com.team1.hangsha.event.dto.request.EventCreateRequest
import com.team1.hangsha.event.dto.request.EventPatchRequest
import com.team1.hangsha.event.dto.response.DetailEventResponse
import com.team1.hangsha.event.dto.response.SearchEventItem
import com.team1.hangsha.event.dto.response.SearchEventResponse
import com.team1.hangsha.event.dto.response.SearchHighlight
import com.team1.hangsha.event.service.EventService
import com.team1.hangsha.event.service.EventSyncService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

class DiscordInteractionControllerTest {
    private val mapper = ObjectMapper()
    private val signature = mock(DiscordSignatureVerifier::class.java)
    private val deduplicator = mock(DiscordInteractionDeduplicator::class.java)
    private val events = mock(EventService::class.java)
    private val writes = mock(EventSyncService::class.java)
    private val controller = DiscordInteractionController(signature, deduplicator, mapper, writes, events)

    private fun command(name: String, options: Map<String, Any> = emptyMap(), validSignature: Boolean = true): Map<*, *> {
        val body = mapper.writeValueAsBytes(mapOf(
            "type" to 2, "id" to "interaction-1",
            "data" to mapOf("name" to name, "options" to options.map { mapOf("name" to it.key, "value" to it.value) }),
        ))
        `when`(signature.verify("timestamp", "signature", body)).thenReturn(validSignature)
        `when`(deduplicator.claim("interaction-1")).thenReturn(true)
        return controller.interact("signature", "timestamp", body)["data"] as Map<*, *>
    }

    @Test
    fun `search reuses public search with pagination and all filters`() {
        `when`(events.search("장학금", 2, 5, listOf(1L), listOf(2L), listOf(3L), null)).thenReturn(
            SearchEventResponse(2, 5, 12, listOf(SearchEventItem(
                EventDto(id = 42, title = "장학금 안내", applyCount = 0), SearchHighlight("", null),
            ))),
        )
        val data = command("event-search", mapOf("query" to " 장학금 ", "page" to 2,
            "status_id" to 1, "event_type_id" to 2, "org_id" to 3))
        val content = data["content"] as String
        assertTrue(content.contains("#42"))
        assertTrue(content.contains("2/3페이지"))
        assertTrue(content.contains("page:3"))
        assertEquals(64, data["flags"])
        assertEquals(mapOf("parse" to emptyList<String>()), data["allowed_mentions"])
        verify(events).search("장학금", 2, 5, listOf(1L), listOf(2L), listOf(3L), null)
        verifyNoInteractions(writes)
    }

    @Test
    fun `detail exposes existing event ID and readable HTML content`() {
        `when`(events.getEventDetail(42, null)).thenReturn(DetailEventResponse(
            id = 42, title = "특강", applyCount = 2, organization = "학생처", location = "문화관",
            applyLink = "https://example.com/apply", detail = "<p>행사 <b>안내</b></p>",
        ))
        val content = command("event-detail", mapOf("event_id" to 42))["content"] as String
        assertTrue(content.contains("행사 #42"))
        assertTrue(content.contains("문화관"))
        assertTrue(content.contains("https://example.com/apply"))
        assertTrue(content.contains("행사 안내"))
        assertFalse(content.contains("<p>"))
        verifyNoInteractions(writes)
    }

    @Test
    fun `missing or deleted detail uses existing domain error`() {
        `when`(events.getEventDetail(42, null)).thenThrow(DomainException(ErrorCode.EVENT_NOT_FOUND))
        assertEquals(ErrorCode.EVENT_NOT_FOUND.message, command("event-detail", mapOf("event_id" to 42))["content"])
    }

    @Test
    fun `empty search and out of range pages give usable replies`() {
        `when`(events.search("없는행사", 1, 5, null, null, null, null))
            .thenReturn(SearchEventResponse(1, 5, 0, emptyList()))
        assertTrue((command("event-search", mapOf("query" to "없는행사"))["content"] as String).contains("검색 결과가 없습니다"))
        assertTrue(DiscordEventMessages.search(SearchEventResponse(3, 5, 6, emptyList())).contains("마지막 페이지는 2"))
    }

    @Test
    fun `invalid options never call event services`() {
        for (options in listOf(mapOf("query" to " "), mapOf("query" to "특강", "page" to 0),
            mapOf("query" to "특강", "page" to 201), mapOf("query" to "특강", "org_id" to -1))) {
            assertTrue((command("event-search", options)["content"] as String).contains("입력 형식"))
        }
        command("event-detail", mapOf("event_id" to "abc"))
        command("event-detail")
        verifyNoInteractions(events, writes)
    }

    @Test
    fun `unsigned requests cannot read events`() {
        assertThrows(ResponseStatusException::class.java) {
            command("event-detail", mapOf("event_id" to 42), validSignature = false)
        }
        verifyNoInteractions(events, writes, deduplicator)
    }

    @Test
    fun `existing delete still uses the supplied event ID`() {
        `when`(writes.deleteEvent(42)).thenReturn(mapOf("ok" to true, "deletedEventId" to 42L))
        assertEquals("삭제 완료: 행사 #42", command("event-delete", mapOf("event_id" to 42))["content"])
        verify(writes).deleteEvent(42)
        verifyNoInteractions(events)
    }

    @Test
    fun `create maps Discord fields without a JSON payload`() {
        val expected = EventCreateRequest(
            title = "AI 특강",
            mainContentHtml = "행사 설명",
            eventTypeId = 2,
            orgId = 3,
            applyStart = LocalDateTime.parse("2026-10-01T09:00:00"),
            applyEnd = LocalDateTime.parse("2026-10-09T23:59:59"),
            eventStart = LocalDateTime.parse("2026-10-10T14:00:00"),
            eventEnd = LocalDateTime.parse("2026-10-10T16:00:00"),
            isPeriodEvent = false,
            organization = "컴퓨터공학부",
            location = "301동",
            applyLink = "https://example.com/apply",
        )
        `when`(writes.createEvent(expected)).thenReturn(mapOf("ok" to true, "eventId" to 77L))

        val data = command("event-create", mapOf(
            "title" to "AI 특강",
            "main_content" to "행사 설명",
            "event_type_id" to 2,
            "org_id" to 3,
            "apply_start" to "2026-10-01T09:00:00",
            "apply_end" to "2026-10-09T23:59:59",
            "event_start" to "2026-10-10T14:00:00",
            "event_end" to "2026-10-10T16:00:00",
            "is_period_event" to false,
            "organization" to "컴퓨터공학부",
            "location" to "301동",
            "apply_link" to "https://example.com/apply",
        ))

        assertEquals("생성 완료: 행사 #77", data["content"])
        verify(writes).createEvent(expected)
    }

    @Test
    fun `patch maps only supplied Discord fields`() {
        val expected = EventPatchRequest(
            title = "수정된 행사",
            isPeriodEvent = true,
            applyLink = "https://example.com/new",
        )
        `when`(writes.patchEvent(77, expected)).thenReturn(mapOf("ok" to true, "eventId" to 77L))

        val data = command("event-patch", mapOf(
            "event_id" to 77,
            "title" to "수정된 행사",
            "is_period_event" to true,
            "apply_link" to "https://example.com/new",
        ))

        assertEquals("수정 완료: 행사 #77", data["content"])
        verify(writes).patchEvent(77, expected)
    }

    @Test
    fun `long content stays within Discord limit while preserving every search ID`() {
        val longText = "긴본문".repeat(2000)
        val detail = DiscordEventMessages.detail(DetailEventResponse(
            id = 42, title = longText, applyCount = 0, organization = longText, location = longText,
            operationMode = longText, tags = longText, applyLink = longText, detail = longText,
        ))
        assertTrue(detail.length <= 2000)
        val search = DiscordEventMessages.search(SearchEventResponse(1, 5, 10, (1L..5L).map {
            SearchEventItem(EventDto(id = it, title = longText, organization = longText, applyCount = 0), SearchHighlight("", longText))
        }))
        assertTrue(search.length <= 2000)
        (1L..5L).forEach { assertTrue(search.contains("#$it")) }
        assertTrue(search.contains("page:2"))
    }
}
