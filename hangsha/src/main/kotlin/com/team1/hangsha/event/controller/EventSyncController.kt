package com.team1.hangsha.event.controller

import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import com.team1.hangsha.event.dto.request.EventPatchRequest
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.event.service.EventSyncService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/admin/events")
class EventSyncController(
    private val eventSyncService: EventSyncService,
    private val eventRepository: EventRepository,
    private val objectMapper: ObjectMapper,
) {
    @PostMapping("/sync")
    fun sync(
        @RequestBody events: List<CrawledProgramEvent>,
    ): Map<String, Any> {
        return runSync(events)
    }

    @PostMapping("/sync", consumes = ["multipart/form-data"])
    fun syncByFile(
        @RequestParam("file") file: MultipartFile,
    ): Map<String, Any> {
        file.inputStream.use { input ->
            objectMapper.factory.createParser(input).use { parser ->
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "file must be a JSON array")
                }

                var total = 0
                var upserted = 0
                var skipped = 0
                val buffer = ArrayList<CrawledProgramEvent>(SYNC_BATCH_SIZE)

                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    val event = objectMapper.readValue(parser, CrawledProgramEvent::class.java)
                    buffer.add(event)

                    if (buffer.size >= SYNC_BATCH_SIZE) {
                        val result = eventSyncService.sync(buffer)
                        total += result.total
                        upserted += result.upserted
                        skipped += result.skipped
                        buffer.clear()
                    }
                }

                if (buffer.isNotEmpty()) {
                    val result = eventSyncService.sync(buffer)
                    total += result.total
                    upserted += result.upserted
                    skipped += result.skipped
                }

                return mapOf(
                    "ok" to true,
                    "total" to total,
                    "upserted" to upserted,
                    "skipped" to skipped,
                )
            }
        }
    }

    private fun runSync(events: List<CrawledProgramEvent>): Map<String, Any> {
        val result = eventSyncService.sync(events)
        return mapOf(
            "ok" to true,
            "total" to result.total,
            "upserted" to result.upserted,
            "skipped" to result.skipped,
        )
    }

    @DeleteMapping("/delete")
    fun deleteAll(): Map<String, Any> {
        val deleted = eventRepository.deleteAllEventsRaw()
        return mapOf("ok" to true, "deleted" to deleted)
    }

    @PatchMapping("/{eventId}")
    fun patchEvent(
        @PathVariable eventId: Long,
        @RequestBody req: EventPatchRequest,
    ): Map<String, Any> = eventSyncService.patchEvent(eventId, req)

    @DeleteMapping("/{eventId}")
    fun deleteEvent(
        @PathVariable eventId: Long,
    ): Map<String, Any> = eventSyncService.deleteEvent(eventId)

    companion object {
        private const val SYNC_BATCH_SIZE = 500
    }
}
