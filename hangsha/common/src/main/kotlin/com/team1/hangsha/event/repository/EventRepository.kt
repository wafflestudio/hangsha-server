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
    WHERE admin_deleted = false
      AND status_id = :recruitingStatusId
      AND apply_end IS NOT NULL
      AND apply_end < :now
      AND (
        admin_overridden_fields IS NULL
        OR JSON_CONTAINS(admin_overridden_fields, JSON_QUOTE('statusId')) = 0
      )
    """
    )
    fun closeExpiredRecruitingEvents(
        @Param("recruitingStatusId") recruitingStatusId: Long,
        @Param("closedStatusId") closedStatusId: Long,
        @Param("now") now: LocalDateTime,
    ): Int

    @Query(
        """
    select count(*) > 0
    from events
    where apply_link = :applyLink
      and admin_deleted = true
    """
    )
    fun existsAdminDeletedByApplyLink(
        @Param("applyLink") applyLink: String,
    ): Boolean

    @Query(
        """
    select *
    from events
    where id = :eventId
      and admin_deleted = false
    limit 1
    """
    )
    fun findVisibleById(
        @Param("eventId") eventId: Long,
    ): Event?

    @Modifying
    @Query(
        """
    update events
    set admin_deleted = true
    where id = :eventId
    """
    )
    fun softDeleteById(
        @Param("eventId") eventId: Long,
    ): Int

    @Modifying
    @Query("DELETE FROM events")
    fun deleteAllEventsRaw(): Int
}