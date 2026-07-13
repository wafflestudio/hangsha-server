package com.team1.hangsha.batch.ai

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.team1.hangsha.event.dto.core.CrawledDetailSession
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@Component
class EliceEventParserClient(
    private val objectMapper: ObjectMapper,
    @Value("\${elice.ml-api.event-parser.enabled:false}")
    private val enabled: Boolean,
    @Value("\${elice.ml-api.event-parser.url:}")
    private val url: String,
    @Value("\${elice.ml-api.event-parser.model:}")
    private val model: String,
    @Value("\${elice.ml-api.event-parser.api-key:}")
    private val apiKey: String,
) {
    private var configurationWarningLogged = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun enrich(events: List<CrawledProgramEvent>, batchSize: Int = 1): List<CrawledProgramEvent> {
        if (!isConfigured()) {
            if (!configurationWarningLogged) {
                println(
                    "[AI_PARSER] skipped: enabled=$enabled " +
                            "urlConfigured=${url.isNotBlank()} modelConfigured=${model.isNotBlank()} " +
                            "apiKeyConfigured=${apiKey.isNotBlank()}"
                )
                configurationWarningLogged = true
            }
            return events
        }
        if (events.isEmpty()) return events

        println("[AI_PARSER] parsing events=${events.size} batchSize=${batchSize.coerceAtLeast(1)}")

        return events.chunked(batchSize.coerceAtLeast(1)).flatMapIndexed { chunkIndex, chunk ->
            val parsedById = runCatching { parseChunk(chunk) }
                .onFailure { e ->
                    println("[AI_PARSER] chunk=$chunkIndex failed: ${e::class.simpleName} ${e.message}")
                }
                .getOrDefault(emptyMap())

            chunk.map { event ->
                val key = event.parserKey()
                val parsed = parsedById[key]
                if (parsed == null) {
                    println("[AI_PARSER] no parsed result for key=$key title=${event.title}")
                    event
                } else {
                    event.merge(parsed)
                }
            }
        }
    }

    private fun isConfigured(): Boolean =
        enabled && url.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    private fun parseChunk(events: List<CrawledProgramEvent>): Map<String, ParsedEvent> {
        val payload = buildPayload(events)
        val requestBody = objectMapper.writeValueAsString(payload).toRequestBody(JSON)
        val request = Request.Builder()
            .url(chatCompletionsUrl())
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .build()

        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Elice ML API failed. code=${response.code} body=${responseText.take(500)}")
            }

            return parseResponseText(responseText)
                .associateBy { it.id }
        }
    }

    private fun buildPayload(events: List<CrawledProgramEvent>): Map<String, Any?> =
        mapOf(
            "model" to model,
            "messages" to listOf(
                mapOf(
                    "role" to "system",
                    "content" to SYSTEM_PROMPT,
                ),
                mapOf(
                    "role" to "user",
                    "content" to objectMapper.writeValueAsString(
                        mapOf(
                            "currentDate" to LocalDate.now(SEOUL).toString(),
                            "events" to events.map { it.toPromptEvent() },
                        )
                    ),
                ),
            ),
            "max_completion_tokens" to 4096,
            "stream" to false,
            "response_format" to mapOf("type" to "json_object"),
        )

    private fun chatCompletionsUrl(): String {
        val trimmed = url.trim().trimEnd('/')
        return if (trimmed.endsWith("/v1/chat/completions")) {
            trimmed
        } else {
            "$trimmed/v1/chat/completions"
        }
    }

    private fun parseResponseText(text: String): List<ParsedEvent> {
        val content = extractModelContent(text)
        val json = extractJson(content)
        val root = objectMapper.readTree(json)
        val itemsNode = when {
            root.isArray -> root
            root.has("items") -> root.get("items")
            root.has("events") -> root.get("events")
            root.has("id") -> return listOf(objectMapper.convertValue(root, ParsedEvent::class.java))
            else -> throw IllegalArgumentException("AI response JSON must be an array or contain items/events")
        }

        return objectMapper.convertValue(itemsNode, object : TypeReference<List<ParsedEvent>>() {})
    }

    private fun extractModelContent(text: String): String {
        val trimmed = text.trim()
        return runCatching {
            val root = objectMapper.readTree(trimmed)
            findFirstText(root, "content")
                ?: findFirstText(root, "text")
                ?: findFirstText(root, "output_text")
                ?: trimmed
        }.getOrDefault(trimmed)
    }

    private fun findFirstText(node: JsonNode, fieldName: String): String? {
        if (node.isObject && node.has(fieldName) && node.get(fieldName).isTextual) {
            return node.get(fieldName).asText()
        }
        if (node.isObject || node.isArray) {
            val iterator = node.elements()
            while (iterator.hasNext()) {
                findFirstText(iterator.next(), fieldName)?.let { return it }
            }
        }
        return null
    }

    private fun extractJson(text: String): String {
        val withoutFence = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val arrayStart = withoutFence.indexOf('[')
        val objectStart = withoutFence.indexOf('{')
        val start = listOf(arrayStart, objectStart)
            .filter { it >= 0 }
            .minOrNull()
            ?: throw IllegalArgumentException("AI response does not contain JSON")

        val endChar = if (withoutFence[start] == '[') ']' else '}'
        val end = withoutFence.lastIndexOf(endChar)
        if (end < start) throw IllegalArgumentException("AI response JSON is incomplete")

        return withoutFence.substring(start, end + 1)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val SEOUL = ZoneId.of("Asia/Seoul")

        private const val SYSTEM_PROMPT = """
You are a semantic parser for Seoul National University event data.
Return JSON only. Do not use markdown.

For each input event, return exactly one item with this schema:
{
  "id": "same id from input",
  "organization": string or null,
  "category": one of ["교육(특강/세미나)", "공모전/경진대회", "현장학습/인턴", "사회공헌(봉사)", "학습/진로상담", "OpenLnL", "기타"],
  "applyStart": "yyyy-MM-ddTHH:mm:ss" or null,
  "applyEnd": "yyyy-MM-ddTHH:mm:ss" or null,
  "eventStart": "yyyy-MM-ddTHH:mm:ss" or null,
  "eventEnd": "yyyy-MM-ddTHH:mm:ss" or null,
  "warnings": string[]
}

Do not return status. The backend will infer recruitment status.
Use currentDate's year when a date omits the year.
If a date range crosses December to January, use the next year for January.

Organization policy:
1. Return only the event organizer or operator explicitly stated in the content.
2. Remove "서울대학교", "서울대", and "SNU" from the name.
3. If no organizer is explicitly stated, return null. Do not guess.

Period policy:
1. If listPeriodText is a single date, it is usually the event period.
2. If listPeriodText is a range and mainContentText contains "모집" or "신청", treat it as apply period.
3. If listPeriodText is a range and mainContentText does not contain "모집" or "신청", treat it as event period.
4. If the event period is needed, refine it from lines containing "시간", "기간", or "일시".
5. For apply period, prefer dates near "신청", "접수", "지원", "등록", "모집", "기한", "마감", or "까지".
6. Deadline-only expressions such as "(~7/7 (화) 15시까지)" or "신청 접수 부탁드립니다.(~7/7 (화) 15시까지)" mean applyEnd.
7. For event period, prefer dates near "일시", "일정", "행사", "교육", "강의", "세미나", "워크숍", "운영", or "활동기간".
8. If listPeriodText and explicit rule-based cues do not identify either apply period or event period, infer the most plausible value from mainContentText instead of leaving it null when a date is clearly present.
9. If the list range is treated as apply period, event period may be null only when mainContentText has no plausible event date.
"""
    }
}

private data class PromptEvent(
    val id: String,
    val title: String?,
    val majorTypes: List<String>,
    val listPeriodText: String?,
    val mainContentText: String?,
)

data class ParsedEvent(
    val id: String,
    val organization: String? = null,
    val category: String? = null,
    val applyStart: String? = null,
    val applyEnd: String? = null,
    val eventStart: String? = null,
    val eventEnd: String? = null,
    val warnings: List<String> = emptyList(),
)

private fun CrawledProgramEvent.toPromptEvent(): PromptEvent =
    PromptEvent(
        id = parserKey(),
        title = title,
        majorTypes = majorTypes,
        listPeriodText = listOfNotNull(
            buildRangeText(applyStart, applyEnd),
            buildRangeText(activityStart, activityEnd),
        ).joinToString(" / ").takeIf { it.isNotBlank() },
        mainContentText = mainContentHtml?.toPlainText()?.take(MAX_CONTENT_CHARS),
    )

private fun CrawledProgramEvent.merge(parsed: ParsedEvent): CrawledProgramEvent {
    val contentFallback = extractContentPeriodFallback(mainContentHtml)
    val organization = parsed.organization.cleanOrganization()
    val category = parsed.category?.clean()?.takeIf { it in PROGRAM_TYPES }
    val mergedMajorTypes = mergeMajorTypes(majorTypes, organization, category)

    val fallbackApplyStartDate = contentFallback.applyStart?.toLocalDate()?.toString()
    val fallbackApplyEndDate = contentFallback.applyEnd?.toLocalDate()?.toString()
    val applyEndDate = parsed.applyEnd.toLocalDateString() ?: applyEnd ?: fallbackApplyEndDate
    val applyStartDate = parsed.applyStart.toLocalDateString()
        ?: applyStart
        ?: fallbackApplyStartDate
        ?: applyEndDate?.let { LocalDate.now(ZoneId.of("Asia/Seoul")).toString() }
    val eventStartDateTime = parsed.eventStart.toLocalDateTimeOrNull()
        ?: contentFallback.eventStart.takeIf { activityStart == null }
    val eventEndDateTime = parsed.eventEnd.toLocalDateTimeOrNull()
        ?: contentFallback.eventEnd.takeIf { activityEnd == null }
    val eventStartDate = eventStartDateTime?.toLocalDate()?.toString() ?: activityStart
    val eventEndDate = eventEndDateTime?.toLocalDate()?.toString() ?: activityEnd
    val existingLocation = detailSessions.firstNotNullOfOrNull { it.location?.clean() }
    val aiDetailSession = buildDetailSession(eventStartDateTime, eventEndDateTime, existingLocation)

    return copy(
        majorTypes = mergedMajorTypes,
        applyStart = applyStartDate,
        applyEnd = applyEndDate,
        activityStart = eventStartDate,
        activityEnd = eventEndDate,
        status = inferStatus(applyStartDate, applyEndDate, eventStartDate, eventEndDate) ?: status,
        detailSessions = aiDetailSession?.let { listOf(it) } ?: detailSessions,
        isPeriodEvent = if (aiDetailSession != null) false else isPeriodEvent,
    )
}

private fun mergeMajorTypes(existing: List<String>, organization: String?, category: String?): List<String> {
    val org = (organization ?: existing.getOrNull(0))?.takeIf { it.isNotBlank() }
    val type = (category ?: existing.getOrNull(1))?.takeIf { it.isNotBlank() }

    return when {
        type != null -> listOf(org.orEmpty(), type)
        org != null -> listOf(org)
        else -> emptyList()
    }
}

private fun buildDetailSession(
    start: LocalDateTime?,
    end: LocalDateTime?,
    location: String?,
): CrawledDetailSession? {
    if (start == null && end == null) return null
    val safeStart = start ?: end ?: return null
    val safeEnd = end ?: safeStart
    val hasTime = safeStart.toLocalTime() != LocalTime.MIN || safeEnd.toLocalTime() != LocalTime.of(23, 59, 59)
    if (!hasTime && safeStart.toLocalDate() != safeEnd.toLocalDate()) return null

    return CrawledDetailSession(
        round = 1,
        location = location,
        startDate = safeStart.toLocalDate().toString(),
        endDate = safeEnd.toLocalDate().toString(),
        startTime = safeStart.toLocalTime().format(HH_MM),
        endTime = safeEnd.toLocalTime().format(HH_MM),
    )
}

private fun inferStatus(
    applyStart: String?,
    applyEnd: String?,
    eventStart: String?,
    eventEnd: String?,
): String? {
    val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
    val applyStartDate = applyStart.toLocalDateOrNull()
    val applyEndDate = applyEnd.toLocalDateOrNull()

    if (applyStartDate != null && applyEndDate != null) {
        return when {
            applyStartDate.isAfter(today) -> "모집대기"
            !applyEndDate.isBefore(today) -> "모집중"
            else -> "모집마감"
        }
    }

    val eventEndDate = eventEnd.toLocalDateOrNull() ?: eventStart.toLocalDateOrNull()
    return eventEndDate?.let {
        if (!it.isBefore(today)) "모집중" else "모집마감"
    }
}

private fun String?.toPlainText(): String =
    this?.let { Jsoup.parse(it).text().replace(Regex("\\s+"), " ").trim() }.orEmpty()

private fun String?.clean(): String? =
    this?.trim()?.takeIf { it.isNotBlank() }

private fun String?.cleanOrganization(): String? {
    val value = clean() ?: return null
    return value
        .replace(Regex("""(?i)\bSNU\b"""), "")
        .replace("서울대학교", "")
        .replace("서울대", "")
        .replace(Regex("""^[\s·ㆍ./()_-]+"""), "")
        .replace(Regex("""[\s·ㆍ./()_-]+$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .takeIf { it.isNotBlank() }
}

private fun String?.toLocalDateString(): String? =
    toLocalDateTimeOrNull()?.toLocalDate()?.toString() ?: toLocalDateOrNull()?.toString()

private fun String?.toLocalDateTimeOrNull(): LocalDateTime? {
    val value = clean() ?: return null
    return runCatching { LocalDateTime.parse(value) }
        .recoverCatching { LocalDate.parse(value).atStartOfDay() }
        .getOrNull()
}

private fun String?.toLocalDateOrNull(): LocalDate? {
    val value = clean() ?: return null
    return runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}

private fun buildRangeText(start: String?, end: String?): String? {
    val s = start.clean()
    val e = end.clean()
    return when {
        s != null && e != null && s != e -> "$s ~ $e"
        s != null -> s
        e != null -> e
        else -> null
    }
}

private fun extractContentPeriodFallback(html: String?): ContentPeriodFallback {
    val chunks = html.toContentChunks()
    if (chunks.isEmpty()) return ContentPeriodFallback()

    val apply = findBestContentPeriod(chunks, ContentPeriodKind.APPLY)
    val event = findBestContentPeriod(chunks, ContentPeriodKind.EVENT)
    return ContentPeriodFallback(
        applyStart = apply?.start,
        applyEnd = apply?.end,
        eventStart = event?.start,
        eventEnd = event?.end,
    )
}

private fun String?.toContentChunks(): List<String> =
    this?.replace(Regex("""(?i)<br\s*/?>"""), "\n")
        ?.replace(Regex("""(?i)</(p|div|li|tr|h[1-6])>"""), "\n")
        ?.lines()
        ?.map { Jsoup.parse(it).text().replace(Regex("""\s+"""), " ").trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

private fun findBestContentPeriod(chunks: List<String>, kind: ContentPeriodKind): ContentPeriod? {
    var best: Pair<ContentPeriod, Int>? = null

    chunks.forEachIndexed { index, line ->
        val candidateText = listOfNotNull(line, chunks.getOrNull(index + 1))
            .joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        val score = scoreContentPeriodCandidate(candidateText, kind)
        if (score <= 0) return@forEachIndexed

        val period = parseContentPeriod(candidateText, kind) ?: return@forEachIndexed
        val weightedScore = score + periodSpecificityScore(period)
        val currentBest = best
        if (currentBest == null || weightedScore > currentBest.second) {
            best = period to weightedScore
        }
    }

    return best?.first
}

private fun scoreContentPeriodCandidate(text: String, kind: ContentPeriodKind): Int {
    if (!CONTENT_DATE_TOKEN_REGEX.containsMatchIn(text)) return 0

    return when (kind) {
        ContentPeriodKind.APPLY -> {
            if (!APPLY_CUE_REGEX.containsMatchIn(text)) return 0
            var score = 6
            if (APPLY_DEADLINE_CUE_REGEX.containsMatchIn(text)) score += 3
            if (SELECTION_CUE_REGEX.containsMatchIn(text)) score -= 4
            score
        }

        ContentPeriodKind.EVENT -> {
            if (!EVENT_CUE_REGEX.containsMatchIn(text)) return 0
            if (APPLY_CUE_REGEX.containsMatchIn(text) && !STRONG_EVENT_CUE_REGEX.containsMatchIn(text)) return 0
            var score = 6
            if (CONTENT_TIME_RANGE_REGEX.containsMatchIn(text)) score += 2
            if (APPLY_CUE_REGEX.containsMatchIn(text)) score -= 4
            if (SELECTION_CUE_REGEX.containsMatchIn(text)) score -= 3
            score
        }
    }
}

private fun periodSpecificityScore(period: ContentPeriod): Int {
    var score = 0
    if (period.start != null && period.end != null) score += 2
    if (period.start?.toLocalTime()?.let { it != LocalTime.MIDNIGHT } == true) score += 1
    if (period.end?.toLocalTime()?.let { it != LocalTime.of(23, 59, 59) } == true) score += 1
    return score
}

private fun parseContentPeriod(raw: String, kind: ContentPeriodKind): ContentPeriod? {
    val dates = extractContentDates(raw)
    if (dates.isEmpty()) return null

    val startDate = dates.first()
    val endDate = normalizeContentEndDate(startDate, dates.getOrNull(1), hasExplicitContentEndYear(raw)) ?: startDate
    val timeRange = parseContentTimeRange(raw)
    val singleTime = parseContentSingleTime(raw)
    val startTime = timeRange?.first ?: singleTime ?: LocalTime.MIDNIGHT
    val endTime = timeRange?.second ?: when {
        timeRange != null -> timeRange.first
        singleTime != null -> singleTime
        else -> LocalTime.of(23, 59, 59)
    }

    if (dates.size >= 2) {
        return ContentPeriod(
            start = startDate.atTime(startTime),
            end = endDate.atTime(endTime),
        )
    }

    return when (kind) {
        ContentPeriodKind.APPLY -> {
            if (APPLY_START_CUE_REGEX.containsMatchIn(raw) && !APPLY_DEADLINE_CUE_REGEX.containsMatchIn(raw)) {
                ContentPeriod(start = startDate.atTime(startTime), end = null)
            } else {
                ContentPeriod(start = null, end = startDate.atTime(endTime))
            }
        }

        ContentPeriodKind.EVENT -> ContentPeriod(
            start = startDate.atTime(startTime),
            end = startDate.atTime(endTime),
        )
    }
}

private fun extractContentDates(raw: String): List<LocalDate> {
    val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
    var previousMonth: Int? = null

    return CONTENT_DATE_TOKEN_REGEX.findAll(raw)
        .mapNotNull { match ->
            val yearGroup = match.groups["year"]?.value ?: match.groups["dotYear"]?.value
            val monthGroup = match.groups["month"]?.value ?: match.groups["dotMonth"]?.value
            val dayGroup = match.groups["day"]?.value ?: match.groups["dotDay"]?.value ?: return@mapNotNull null

            val month = monthGroup?.toIntOrNull() ?: previousMonth ?: return@mapNotNull null
            val explicitYear = yearGroup?.toIntOrNull()
            val year = explicitYear ?: inferContentYear(today.year, today.monthValue, month)
            val day = dayGroup.toIntOrNull() ?: return@mapNotNull null
            previousMonth = month

            runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
        .toList()
}

private fun inferContentYear(defaultYear: Int, currentMonth: Int, parsedMonth: Int): Int =
    if (currentMonth == 12 && parsedMonth == 1) defaultYear + 1 else defaultYear

private fun normalizeContentEndDate(start: LocalDate?, end: LocalDate?, hasExplicitEndYear: Boolean): LocalDate? {
    if (start == null || end == null) return end
    if (end >= start || hasExplicitEndYear) return end
    return runCatching { end.plusYears(1) }.getOrNull() ?: end
}

private fun hasExplicitContentEndYear(raw: String): Boolean =
    Regex("""20\d{2}""").findAll(raw).toList().size >= 2

private fun parseContentTimeRange(raw: String): Pair<LocalTime, LocalTime>? {
    val colonRange = CONTENT_TIME_RANGE_REGEX.find(raw)
    if (colonRange != null) {
        val start = localTimeOrNull(colonRange.groupValues[1].toInt(), colonRange.groupValues[2].toInt())
        val end = localTimeOrNull(colonRange.groupValues[3].toInt(), colonRange.groupValues[4].toInt())
        if (start != null && end != null) return start to end
    }

    val koreanRange = KOREAN_TIME_RANGE_REGEX.find(raw) ?: return null
    val startAmpm = koreanRange.groupValues[1].takeIf { it.isNotBlank() }
    val start = parseKoreanTime(
        ampm = startAmpm,
        hourRaw = koreanRange.groupValues[2],
        minuteRaw = koreanRange.groupValues[3],
    )
    val end = parseKoreanTime(
        ampm = koreanRange.groupValues[4].takeIf { it.isNotBlank() },
        hourRaw = koreanRange.groupValues[5],
        minuteRaw = koreanRange.groupValues[6],
        fallbackAmpm = startAmpm,
    )
    return if (start != null && end != null) start to end else null
}

private fun parseContentSingleTime(raw: String): LocalTime? {
    val colon = Regex("""(\d{1,2}):(\d{2})""").find(raw)
    if (colon != null) {
        return localTimeOrNull(colon.groupValues[1].toInt(), colon.groupValues[2].toInt())
    }

    val korean = KOREAN_TIME_REGEX.find(raw) ?: return null
    return parseKoreanTime(
        ampm = korean.groupValues[1].takeIf { it.isNotBlank() },
        hourRaw = korean.groupValues[2],
        minuteRaw = korean.groupValues[3],
    )
}

private fun parseKoreanTime(
    ampm: String?,
    hourRaw: String,
    minuteRaw: String?,
    fallbackAmpm: String? = null,
): LocalTime? {
    val hourValue = hourRaw.toIntOrNull() ?: return null
    val minute = minuteRaw?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
    val effectiveAmpm = ampm ?: fallbackAmpm
    val hour = when {
        effectiveAmpm == "오후" && hourValue < 12 -> hourValue + 12
        effectiveAmpm == "오전" && hourValue == 12 -> 0
        else -> hourValue
    }
    return localTimeOrNull(hour, minute)
}

private fun localTimeOrNull(hour: Int, minute: Int): LocalTime? {
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime.of(hour, minute)
}

private data class ContentPeriodFallback(
    val applyStart: LocalDateTime? = null,
    val applyEnd: LocalDateTime? = null,
    val eventStart: LocalDateTime? = null,
    val eventEnd: LocalDateTime? = null,
)

private data class ContentPeriod(
    val start: LocalDateTime?,
    val end: LocalDateTime?,
)

private enum class ContentPeriodKind {
    APPLY,
    EVENT,
}

private fun CrawledProgramEvent.parserKey(): String =
    dataSeq?.takeIf { it.isNotBlank() } ?: applyLink?.takeIf { it.isNotBlank() } ?: title.orEmpty()

private val APPLY_CUE_REGEX = Regex("""신청|접수|지원|등록|모집""")
private val APPLY_DEADLINE_CUE_REGEX = Regex("""기한|마감|까지|마감일|접수\s*부탁""")
private val APPLY_START_CUE_REGEX = Regex("""시작|개시|부터|오픈""")
private val EVENT_CUE_REGEX = Regex("""일시|일정|행사|교육|강의|세미나|워크숍|운영|활동\s*기간|교육\s*기간|강의\s*시간|기간|시간""")
private val STRONG_EVENT_CUE_REGEX = Regex("""일시|일정|행사|교육|강의|세미나|워크숍|운영|활동\s*기간|교육\s*기간|강의\s*시간""")
private val SELECTION_CUE_REGEX = Regex("""선발|발표|결과|확정|보고서|수료증""")
private val CONTENT_TIME_RANGE_REGEX = Regex("""(\d{1,2}):(\d{2})\s*(?:-|–|~)\s*(\d{1,2}):(\d{2})""")
private val KOREAN_TIME_REGEX = Regex("""(오전|오후)?\s*(\d{1,2})시(?:\s*(\d{1,2})분)?""")
private val KOREAN_TIME_RANGE_REGEX = Regex(
    """(오전|오후)?\s*(\d{1,2})시(?:\s*(\d{1,2})분)?\s*(?:-|–|~|부터)\s*(오전|오후)?\s*(\d{1,2})시(?:\s*(\d{1,2})분)?"""
)
private val CONTENT_DATE_TOKEN_REGEX = Regex(
    """(?:(?<year>20\d{2})\s*년\s*)?(?:(?<month>\d{1,2})\s*월\s*)?(?<day>\d{1,2})\s*일|(?:(?<dotYear>20\d{2})\s*[.]\s*)?(?<dotMonth>\d{1,2})\s*[./]\s*(?<dotDay>\d{1,2})\s*[.]?"""
)

private val PROGRAM_TYPES = setOf(
    "교육(특강/세미나)",
    "공모전/경진대회",
    "현장학습/인턴",
    "사회공헌(봉사)",
    "학습/진로상담",
    "OpenLnL",
    "기타",
)

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val MAX_CONTENT_CHARS = 6000
