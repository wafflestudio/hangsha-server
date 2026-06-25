package com.team1.hangsha.search.outbox

import org.springframework.stereotype.Component

@Component
class EventSearchOutboxWriter(
    private val outboxRepository: EventSearchOutboxRepository,
) {
    fun upsert(eventId: Long) {
        outboxRepository.save(
            EventSearchOutbox(eventId = eventId, operation = EventSearchOutbox.Operation.UPSERT)
        )
    }

    fun delete(eventId: Long) {
        outboxRepository.save(
            EventSearchOutbox(eventId = eventId, operation = EventSearchOutbox.Operation.DELETE)
        )
    }
}
