package com.team1.hangsha.batch.job

import com.team1.hangsha.event.dto.core.CrawledDetailSession
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import com.team1.hangsha.event.model.Event
import com.team1.hangsha.event.repository.EventRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CrossSourceDuplicateFilterTest {
    private val repository = Mockito.mock(EventRepository::class.java)
    private val firstStart = LocalDateTime.parse("2026-10-20T13:00:00")
    private val first = session(firstStart, round = 1)
    private val second = session(firstStart.plusDays(1), round = 2)
    private val event = CrawledProgramEvent(
        dataSeq = "program", title = "인공지능 연구 세미나", activityStart = first.startDate,
        activityEnd = second.endDate, detailSessions = listOf(first, second),
    )

    @Test
    fun `keeps a new session when only the first session is a duplicate`() {
        candidatesOn(firstStart, listOf(candidate(1, firstStart)))
        candidatesOn(firstStart.plusDays(1), emptyList())
        val filter = CrossSourceDuplicateFilter(repository) { groups ->
            assertEquals(listOf(first), groups.single().event.detailSessions)
            mapOf(groups.single().id to 1L)
        }

        val result = filter.filter(listOf(event))

        val kept = result.events.single()
        assertEquals(listOf(second), kept.detailSessions)
        assertEquals(second.startDate, kept.activityStart)
        assertEquals(second.endDate, kept.activityEnd)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `checks every session date and removes a program only when all sessions match`() {
        candidatesOn(firstStart, listOf(candidate(1, firstStart)))
        candidatesOn(firstStart.plusDays(1), listOf(candidate(2, firstStart.plusDays(1))))
        val filter = CrossSourceDuplicateFilter(repository) { groups ->
            assertEquals(listOf(first, second), groups.flatMap { it.event.detailSessions })
            groups.associate { it.id to requireNotNull(it.candidates.single().id) }
        }

        val result = filter.filter(listOf(event))

        assertTrue(result.events.isEmpty())
        assertEquals(1, result.skipped)
    }

    @Test
    fun `preserves unknown and malformed session times and period-only events`() {
        val unknownTime = event.copy(detailSessions = listOf(first.copy(startTime = null)))
        val malformedTime = event.copy(detailSessions = listOf(first.copy(startTime = "unknown")))
        val unknownDate = event.copy(detailSessions = listOf(first.copy(startDate = null)))
        val period = event.copy(isPeriodEvent = true)
        val noSessions = event.copy(detailSessions = emptyList())
        val events = listOf(unknownTime, malformedTime, unknownDate, period, noSessions)
        val filter = CrossSourceDuplicateFilter(repository) { error("No timed sessions to compare") }

        assertEquals(events, filter.filter(events).events)
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun `keeps an unknown-time session alongside a confirmed duplicate`() {
        val unknown = second.copy(startTime = null)
        candidatesOn(firstStart, listOf(candidate(1, firstStart)))
        val filter = CrossSourceDuplicateFilter(repository) { groups -> mapOf(groups.single().id to 1L) }

        val kept = filter.filter(listOf(event.copy(detailSessions = listOf(first, unknown)))).events.single()

        assertEquals(listOf(unknown), kept.detailSessions)
    }

    @Test
    fun `ignores unknown decision identifiers and candidate IDs`() {
        candidatesOn(firstStart, listOf(candidate(1, firstStart)))
        val single = event.copy(detailSessions = listOf(first))
        val filter = CrossSourceDuplicateFilter(repository) { groups ->
            mapOf(groups.single().id to 999L, "not-an-input" to 1L)
        }

        assertEquals(listOf(single), filter.filter(listOf(single)).events)
    }

    @Test
    fun `one stored session cannot remove two input sessions on the same day`() {
        candidatesOn(firstStart, listOf(candidate(1, firstStart)))
        val later = first.copy(round = 2, startTime = "15:00", endTime = "16:00")
        val sameDayProgram = event.copy(detailSessions = listOf(first, later))
        val filter = CrossSourceDuplicateFilter(repository) { groups -> groups.associate { it.id to 1L } }

        assertEquals(listOf(sameDayProgram), filter.filter(listOf(sameDayProgram)).events)
    }

    @Test
    fun `empty AI decisions preserve all input events`() {
        candidatesOn(firstStart, listOf(candidate(1, firstStart)))
        val single = event.copy(detailSessions = listOf(first))
        val filter = CrossSourceDuplicateFilter(repository) { emptyMap() }

        assertEquals(listOf(single), filter.filter(listOf(single)).events)
    }

    @Test
    fun `shares a day query and assigns different IDs even when source keys are equal`() {
        candidatesOn(firstStart, listOf(candidate(1, firstStart)))
        val single = event.copy(detailSessions = listOf(first))
        val filter = CrossSourceDuplicateFilter(repository) { groups ->
            assertEquals(2, groups.map { it.id }.toSet().size)
            mapOf(groups.first().id to 1L)
        }

        assertEquals(listOf(single), filter.filter(listOf(single, single)).events)
        val day = firstStart.toLocalDate().atStartOfDay()
        Mockito.verify(repository, Mockito.times(1)).findExtraSnuEventsStartingOn(day, day.plusDays(1))
    }

    @Test
    fun `finds a relevant event after the first thirty rows of a busy day`() {
        val earlier = (1L..30L).map { candidate(it, firstStart.minusHours(1), "무관한 공지 $it") }
        candidatesOn(firstStart, earlier + candidate(31, firstStart))
        val single = event.copy(detailSessions = listOf(first))
        val filter = CrossSourceDuplicateFilter(repository) { groups ->
            assertEquals(31L, groups.single().candidates.first().id)
            mapOf(groups.single().id to 31L)
        }

        assertTrue(filter.filter(listOf(single)).events.isEmpty())
    }

    @Test
    fun `a strong title match remains ahead of unrelated events with exact start times`() {
        val candidates = (1L..3L).map { candidate(it, firstStart, "다른 행사 $it") } +
            candidate(4, firstStart.plusMinutes(10))

        assertEquals(4L, selectPotentialDuplicateCandidates(event, firstStart, candidates).first().id)
    }

    private fun candidatesOn(start: LocalDateTime, candidates: List<Event>) {
        val day = start.toLocalDate().atStartOfDay()
        Mockito.`when`(repository.findExtraSnuEventsStartingOn(day, day.plusDays(1))).thenReturn(candidates)
    }

    private fun candidate(id: Long, start: LocalDateTime, title: String = requireNotNull(event.title)) =
        Event(id = id, title = title, eventStart = start, eventEnd = start.plusHours(1))

    private fun session(start: LocalDateTime, round: Int) = CrawledDetailSession(
        round = round, startDate = start.toLocalDate().toString(), endDate = start.toLocalDate().toString(),
        startTime = "13:00", endTime = "14:00",
    )
}
