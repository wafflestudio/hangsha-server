package com.team1.hangsha.event.controller

import com.team1.hangsha.event.dto.request.EventPatchRequest
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.event.service.EventSyncService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/admin/events")
class EventSyncController(
    private val eventSyncService: EventSyncService,
    private val eventRepository: EventRepository,
) {
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
}