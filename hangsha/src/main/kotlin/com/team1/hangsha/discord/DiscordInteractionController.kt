package com.team1.hangsha.discord

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.event.dto.request.EventCreateRequest
import com.team1.hangsha.event.dto.request.EventPatchRequest
import com.team1.hangsha.event.service.EventSyncService
import com.team1.hangsha.event.service.EventService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

/**
 * Discord application-command adapter. Business rules intentionally stay in
 * EventSyncService so this behaves exactly like the admin event page.
 */
@RestController
@RequestMapping("/api/v1/discord")
class DiscordInteractionController(
    private val signatureVerifier: DiscordSignatureVerifier,
    private val deduplicator: DiscordInteractionDeduplicator,
    private val objectMapper: ObjectMapper,
    private val eventSyncService: EventSyncService,
    private val eventService: EventService,
) {
    @PostMapping("/interactions", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun interact(
        @RequestHeader("X-Signature-Ed25519", required = false) signature: String?,
        @RequestHeader("X-Signature-Timestamp", required = false) timestamp: String?,
        @RequestBody body: ByteArray,
    ): Map<String, Any> {
        if (!signatureVerifier.verify(timestamp, signature, body)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Discord signature")
        }

        val interaction = objectMapper.readTree(body)
        return when (interaction.path("type").asInt()) {
            PING -> mapOf("type" to PONG)
            APPLICATION_COMMAND -> {
                if (!deduplicator.claim(interaction.path("id").asText())) {
                    response("이미 처리된 요청입니다.")
                } else {
                    handleCommand(interaction)
                }
            }
            else -> response("지원하지 않는 Discord interaction입니다.")
        }
    }

    private fun handleCommand(interaction: JsonNode): Map<String, Any> = try {
        val data = interaction.path("data")
        when (data.path("name").asText()) {
            "event-detail" -> {
                val eventId = positiveId(data, "event_id")
                    ?: return response("event_id가 필요합니다.")
                response(DiscordEventMessages.detail(eventService.getEventDetail(eventId, null)))
            }
            "event-search" -> {
                val query = option(data, "query").trim()
                require(query.isNotEmpty() && query.length <= 200) { "query는 1~200자로 입력하세요." }
                val pageText = option(data, "page")
                val page = if (pageText.isEmpty()) 1 else pageText.toIntOrNull()
                require(page != null && page in 1..200) { "page는 1~200 사이의 정수여야 합니다." }
                val result = eventService.search(
                    query = query,
                    page = page,
                    size = 5,
                    statusIds = positiveId(data, "status_id")?.let(::listOf),
                    eventTypeIds = positiveId(data, "event_type_id")?.let(::listOf),
                    orgIds = positiveId(data, "org_id")?.let(::listOf),
                    userId = null,
                )
                response(DiscordEventMessages.search(result))
            }
            "event-create" -> {
                val title = option(data, "title").trim()
                require(title.isNotEmpty()) { "title이 필요합니다." }
                val req = EventCreateRequest(
                    title = title,
                    mainContentHtml = optional(data, "main_content"),
                    eventTypeId = positiveId(data, "event_type_id"),
                    orgId = positiveId(data, "org_id"),
                    applyStart = dateTime(data, "apply_start"),
                    applyEnd = dateTime(data, "apply_end"),
                    eventStart = dateTime(data, "event_start"),
                    eventEnd = dateTime(data, "event_end"),
                    isPeriodEvent = boolean(data, "is_period_event"),
                    organization = optional(data, "organization"),
                    location = optional(data, "location"),
                    applyLink = optional(data, "apply_link"),
                )
                val result = eventSyncService.createEvent(req)
                response("생성 완료: 행사 #${result["eventId"]}")
            }
            "event-patch" -> {
                val eventId = option(data, "event_id").toLongOrNull()
                    ?: return response("event_id는 숫자여야 합니다.")
                val req = EventPatchRequest(
                    title = optional(data, "title"),
                    mainContentHtml = optional(data, "main_content"),
                    eventTypeId = positiveId(data, "event_type_id"),
                    orgId = positiveId(data, "org_id"),
                    applyStart = dateTime(data, "apply_start"),
                    applyEnd = dateTime(data, "apply_end"),
                    eventStart = dateTime(data, "event_start"),
                    eventEnd = dateTime(data, "event_end"),
                    isPeriodEvent = boolean(data, "is_period_event"),
                    organization = optional(data, "organization"),
                    location = optional(data, "location"),
                    applyLink = optional(data, "apply_link"),
                )
                val result = eventSyncService.patchEvent(eventId, req)
                response("수정 완료: 행사 #${result["eventId"]}")
            }
            "event-delete" -> {
                val eventId = option(data, "event_id").toLongOrNull()
                    ?: return response("event_id는 숫자여야 합니다.")
                val result = eventSyncService.deleteEvent(eventId)
                response("삭제 완료: 행사 #${result["deletedEventId"]}")
            }
            else -> response("지원하지 않는 명령입니다. event-search, event-detail, event-create, event-patch, event-delete를 사용하세요.")
        }
    } catch (e: DomainException) {
        response(e.message ?: e.errorCode.message)
    } catch (e: IllegalArgumentException) {
        response("입력 형식이 올바르지 않습니다: ${e.message}")
    }

    private fun positiveId(data: JsonNode, name: String): Long? {
        val text = option(data, name)
        if (text.isEmpty()) return null
        val id = text.toLongOrNull()
        require(id != null && id > 0) { "$name 는 양의 정수여야 합니다." }
        return id
    }

    private fun optional(data: JsonNode, name: String): String? = option(data, name)
        .trim().takeIf { it.isNotEmpty() }

    private fun dateTime(data: JsonNode, name: String): LocalDateTime? {
        val value = optional(data, name) ?: return null
        return runCatching { LocalDateTime.parse(value) }
            .getOrElse { throw IllegalArgumentException("$name 는 2026-10-10T14:00:00 형식이어야 합니다.") }
    }

    private fun boolean(data: JsonNode, name: String): Boolean? {
        val node = data.path("options").firstOrNull { it.path("name").asText() == name } ?: return null
        return node.path("value").takeUnless { it.isMissingNode || it.isNull }?.asBoolean()
    }

    private fun option(data: JsonNode, name: String): String = data.path("options")
        .firstOrNull { it.path("name").asText() == name }
        ?.path("value")?.asText().orEmpty()

    private fun response(content: String): Map<String, Any> = mapOf(
        "type" to CHANNEL_MESSAGE_WITH_SOURCE,
        "data" to mapOf(
            "content" to content.take(2000),
            "flags" to EPHEMERAL,
            "allowed_mentions" to mapOf("parse" to emptyList<String>()),
        ),
    )

    private companion object {
        const val PING = 1
        const val APPLICATION_COMMAND = 2
        const val PONG = 1
        const val CHANNEL_MESSAGE_WITH_SOURCE = 4
        const val EPHEMERAL = 1 shl 6
    }
}
