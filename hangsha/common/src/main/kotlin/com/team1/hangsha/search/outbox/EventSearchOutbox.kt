package com.team1.hangsha.search.outbox

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("event_search_outbox")
data class EventSearchOutbox(
    @Id
    val id: Long? = null,
    val eventId: Long,
    val operation: Operation,
    val status: Status = Status.PENDING,
    @CreatedDate
    val createdAt: Instant? = null,
    val processedAt: Instant? = null,
) {
    enum class Operation { UPSERT, DELETE }
    enum class Status { PENDING, DONE, FAILED }
}
