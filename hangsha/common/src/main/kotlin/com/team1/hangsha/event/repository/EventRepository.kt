package com.team1.hangsha.event.repository

import com.team1.hangsha.event.model.Event
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface EventRepository : CrudRepository<Event, Long> {
    fun existsByApplyLink(applyLink: String): Boolean

    @Query(
        """
    select *
    from events
    where apply_link = :applyLink
      and COALESCE(event_start, apply_start) = :keyStart
      and COALESCE(event_end,   apply_end)   = :keyEnd
    order by id desc
    limit 1
    """
    )
    fun findLatestByApplyLinkAndKeyPeriod(
        @Param("applyLink") applyLink: String,
        @Param("keyStart") keyStart: LocalDateTime?,
        @Param("keyEnd") keyEnd: LocalDateTime?,
    ): Event?

    @Modifying
    @Query(
        """
    UPDATE events
    SET status_id = :closedStatusId
    WHERE status_id = :recruitingStatusId
      AND apply_end IS NOT NULL
      AND apply_end < :now
    """
    )
    fun closeExpiredRecruitingEvents(
        @Param("recruitingStatusId") recruitingStatusId: Long,
        @Param("closedStatusId") closedStatusId: Long,
        @Param("now") now: LocalDateTime,
    ): Int

    @Modifying
    @Query("DELETE FROM events")
    fun deleteAllEventsRaw(): Int
}