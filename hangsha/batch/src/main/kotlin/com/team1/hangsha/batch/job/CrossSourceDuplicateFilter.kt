package com.team1.hangsha.batch.job

import com.team1.hangsha.batch.ai.DuplicateCandidateGroup
import com.team1.hangsha.event.dto.core.CrawledDetailSession
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import com.team1.hangsha.event.model.Event
import com.team1.hangsha.event.repository.EventRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

internal data class DuplicateFilterResult(
    val events: List<CrawledProgramEvent>,
    val skipped: Int,
)

internal class CrossSourceDuplicateFilter(
    private val repository: EventRepository,
    private val findDuplicates: (List<DuplicateCandidateGroup>) -> Map<String, Long>,
) {
    fun filter(events: List<CrawledProgramEvent>): DuplicateFilterResult {
        val candidatesByDate = mutableMapOf<LocalDate, List<Event>>()
        val groups = events.flatMapIndexed { eventIndex, event ->
            if (event.isPeriodEvent == true) return@flatMapIndexed emptyList()
            event.detailSessions.mapIndexedNotNull { sessionIndex, session ->
                val start = session.startDateTimeOrNull() ?: return@mapIndexedNotNull null
                val date = start.toLocalDate()
                val candidates = selectPotentialDuplicateCandidates(
                    event = event,
                    eventStart = start,
                    candidates = candidatesByDate.getOrPut(date) {
                        repository.findExtraSnuEventsStartingOn(date.atStartOfDay(), date.plusDays(1).atStartOfDay())
                    },
                )
                candidates.takeIf { it.isNotEmpty() }?.let {
                    DuplicateCandidateGroup(
                        id = sessionKey(eventIndex, sessionIndex),
                        event = event.copy(detailSessions = listOf(session)),
                        candidates = it,
                    )
                }
            }
        }
        if (groups.isEmpty()) return DuplicateFilterResult(events, skipped = 0)
        val decisions = findDuplicates(groups)
        // Only an ID and candidate pair sent for this specific session may remove it.
        val duplicates = groups.mapNotNull { group ->
            val candidateId = decisions[group.id] ?: return@mapNotNull null
            if (group.candidates.any { it.id == candidateId }) group.id to candidateId else null
        }.toMap()
        if (duplicates.isEmpty()) return DuplicateFilterResult(events, skipped = 0)

        val filtered = events.mapIndexedNotNull { eventIndex, event ->
            val remaining = event.detailSessions.filterIndexed { sessionIndex, _ ->
                val key = sessionKey(eventIndex, sessionIndex)
                duplicates[key]?.let { println("[AI_DUPLICATE] skipped session=$key duplicateOfEventId=$it") }
                key !in duplicates
            }
            when {
                remaining.size == event.detailSessions.size -> event
                remaining.isEmpty() -> null
                else -> event.copy(
                    detailSessions = remaining,
                    activityStart = remaining.mapNotNull { it.startDate }.minOrNull(),
                    activityEnd = remaining.mapNotNull { it.endDate ?: it.startDate }.maxOrNull(),
                )
            }
        }
        return DuplicateFilterResult(filtered, skipped = events.size - filtered.size)
    }

    private fun sessionKey(eventIndex: Int, sessionIndex: Int): String = "$eventIndex:$sessionIndex"
}

private fun CrawledDetailSession.startDateTimeOrNull(): LocalDateTime? {
    val date = startDate ?: return null
    val time = startTime ?: return null
    return runCatching { LocalDate.parse(date).atTime(LocalTime.parse(time)) }.getOrNull()
}

internal fun selectPotentialDuplicateCandidates(
    event: CrawledProgramEvent,
    eventStart: LocalDateTime,
    candidates: List<Event>,
): List<Event> =
    candidates.asSequence()
        .map { candidate ->
            val titleSimilarity = bigramJaccard(event.title.orEmpty(), candidate.title)
            val minimumSimilarity = if (candidate.eventStart == eventStart) {
                MIN_TITLE_SIMILARITY_SAME_START
            } else {
                MIN_TITLE_SIMILARITY_SAME_DAY
            }
            Triple(candidate, titleSimilarity, minimumSimilarity)
        }
        .filter { (_, titleSimilarity, minimumSimilarity) -> titleSimilarity >= minimumSimilarity }
        .sortedByDescending { (_, titleSimilarity) -> titleSimilarity }
        .take(MAX_DUPLICATE_CANDIDATES)
        .map { (candidate) -> candidate }
        .toList()

private fun bigramJaccard(left: String, right: String): Double {
    val leftBigrams = left.normalizedForDuplicateCheck().bigrams()
    val rightBigrams = right.normalizedForDuplicateCheck().bigrams()
    if (leftBigrams.isEmpty() || rightBigrams.isEmpty()) return 0.0
    return leftBigrams.intersect(rightBigrams).size.toDouble() / leftBigrams.union(rightBigrams).size
}

private fun String.normalizedForDuplicateCheck(): String =
    lowercase()
        .replace(DUPLICATE_DATE_REGEX, " ")
        .replace(DUPLICATE_ORDINAL_REGEX, " ")
        .replace(DUPLICATE_RECRUITMENT_PREFIX_REGEX, " ")
        .replace(DUPLICATE_GENERIC_WORD_REGEX, " ")
        .filter { it.isLetterOrDigit() }

private fun String.bigrams(): Set<String> =
    if (length < 2) emptySet() else windowed(size = 2, step = 1).toSet()

private const val MIN_TITLE_SIMILARITY_SAME_START = 0.16
private const val MIN_TITLE_SIMILARITY_SAME_DAY = 0.58
private const val MAX_DUPLICATE_CANDIDATES = 3
private val DUPLICATE_DATE_REGEX = Regex("""20\d{2}[.년/-]?\s*\d{0,2}[.월/-]?\s*\d{0,2}[.일]?""")
private val DUPLICATE_ORDINAL_REGEX = Regex("""\b\d{1,2}(?:st|nd|rd|th)\b""")
private val DUPLICATE_RECRUITMENT_PREFIX_REGEX = Regex("""\[(?:참가자|수강생)?\s*모집]""")
private val DUPLICATE_GENERIC_WORD_REGEX = Regex("""서울대학교|행사|개최|안내|모집""")
