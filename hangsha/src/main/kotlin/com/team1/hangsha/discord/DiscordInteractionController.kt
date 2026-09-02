package com.team1.hangsha.discord

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.JsonProcessingException
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.event.dto.request.EventCreateRequest
import com.team1.hangsha.event.dto.request.EventPatchRequest
import com.team1.hangsha.event.service.EventSyncService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Discord application-command adapter. Business rules intentionally stay in
 * EventSyncService so this behaves exactly like the admin event page.
 */
@RestController
@RequestMapping("/api/v1/discord")
class DiscordInteractionController(
    private val signatureVerifier: DiscordSignatureVerifier,
    private val objectMapper: ObjectMapper,
    private val eventSyncService: EventSyncService,
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
            APPLICATION_COMMAND -> handleCommand(interaction)
            else -> response("지원하지 않는 Discord interaction입니다.")
        }
    }

    private fun handleCommand(interaction: JsonNode): Map<String, Any> = try {
        val data = interaction.path("data")
        when (data.path("name").asText()) {
            "event-create" -> {
                val req = readPayload(data, EventCreateRequest::class.java)
                val result = eventSyncService.createEvent(req)
                response("생성 완료: 행사 #${result["eventId"]}")
            }
            "event-patch" -> {
                val eventId = option(data, "event_id").toLongOrNull()
                    ?: return response("event_id는 숫자여야 합니다.")
                val req = readPayload(data, EventPatchRequest::class.java)
                val result = eventSyncService.patchEvent(eventId, req)
                response("수정 완료: 행사 #${result["eventId"]}")
            }
            "event-delete" -> {
                val eventId = option(data, "event_id").toLongOrNull()
                    ?: return response("event_id는 숫자여야 합니다.")
                val result = eventSyncService.deleteEvent(eventId)
                response("삭제 완료: 행사 #${result["deletedEventId"]}")
            }
            else -> response("지원하지 않는 명령입니다. event-create, event-patch, event-delete를 사용하세요.")
        }
    } catch (e: DomainException) {
        response(e.message ?: e.errorCode.message)
    } catch (e: JsonProcessingException) {
        response("payload JSON 형식이 올바르지 않습니다.")
    } catch (e: IllegalArgumentException) {
        response("payload 형식이 올바르지 않습니다: ${e.message}")
    }

    private fun <T> readPayload(data: JsonNode, type: Class<T>): T {
        val payload = option(data, "payload")
        if (payload.isBlank()) throw IllegalArgumentException("payload가 필요합니다.")
        return objectMapper.readValue(payload, type)
    }

    private fun option(data: JsonNode, name: String): String = data.path("options")
        .firstOrNull { it.path("name").asText() == name }
        ?.path("value")?.asText().orEmpty()

    private fun response(content: String): Map<String, Any> = mapOf(
        "type" to CHANNEL_MESSAGE_WITH_SOURCE,
        "data" to mapOf("content" to content, "flags" to EPHEMERAL),
    )

    private companion object {
        const val PING = 1
        const val APPLICATION_COMMAND = 2
        const val PONG = 1
        const val CHANNEL_MESSAGE_WITH_SOURCE = 4
        const val EPHEMERAL = 1 shl 6
    }
}
