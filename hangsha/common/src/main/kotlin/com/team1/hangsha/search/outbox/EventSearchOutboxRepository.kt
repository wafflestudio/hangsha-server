package com.team1.hangsha.search.outbox

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import java.time.Instant

interface EventSearchOutboxRepository : CrudRepository<EventSearchOutbox, Long> {

    @Query("SELECT * FROM event_search_outbox WHERE status = 'PENDING' ORDER BY id ASC LIMIT :limit")
    fun findPendingOrderedById(@Param("limit") limit: Int): List<EventSearchOutbox>

    @Modifying
    @Query("UPDATE event_search_outbox SET status = :status, processed_at = :processedAt WHERE id = :id")
    fun updateStatus(
        @Param("id") id: Long,
        @Param("status") status: String,
        @Param("processedAt") processedAt: Instant,
    ): Int
}
