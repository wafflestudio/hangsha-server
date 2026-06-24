package com.team1.hangsha.search.outbox

import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.search.ManticoreIndexService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class OutboxWorker(
    private val outboxRepository: EventSearchOutboxRepository,
    private val manticoreIndexService: ManticoreIndexService,
    private val eventRepository: EventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 5000)
    fun processOutbox() {
        val pending = outboxRepository.findPendingOrderedById(100)
        if (pending.isEmpty()) return

        for (entry in pending) {
            val entryId = requireNotNull(entry.id)
            try {
                when (entry.operation) {
                    EventSearchOutbox.Operation.UPSERT -> {
                        val event = eventRepository.findVisibleById(entry.eventId)
                        if (event != null) manticoreIndexService.indexEvent(event)
                        else manticoreIndexService.deleteEvent(entry.eventId)
                    }
                    EventSearchOutbox.Operation.DELETE ->
                        manticoreIndexService.deleteEvent(entry.eventId)
                }
                outboxRepository.updateStatus(entryId, "DONE", Instant.now())
                log.debug("outbox processed id={} eventId={} op={}", entryId, entry.eventId, entry.operation)
            } catch (e: Exception) {
                outboxRepository.updateStatus(entryId, "FAILED", Instant.now())
                log.error("outbox failed id={} eventId={}: {}", entryId, entry.eventId, e.message)
            }
        }
    }
}
