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
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    fun enrich(events: List<CrawledProgramEvent>, batchSize: Int = 1): List<CrawledProgramEvent> {
        if (!isConfigured()) return events
        if (events.isEmpty()) return events

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

Period policy:
1. If listPeriodText is a single date, it is usually the event period.
2. If listPeriodText is a range and mainContentText contains "모집" or "신청", treat it as apply period.
3. If listPeriodText is a range and mainContentText does not contain "모집" or "신청", treat it as event period.
4. If the event period is needed, refine it from lines containing "시간", "기간", or "일시".
5. If the list range is treated as apply period, event period may be null.
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
    val organization = parsed.organization?.clean()
    val category = parsed.category?.clean()?.takeIf { it in PROGRAM_TYPES }
    val mergedMajorTypes = mergeMajorTypes(majorTypes, organization, category)

    val applyEndDate = parsed.applyEnd.toLocalDateString() ?: applyEnd
    val applyStartDate = parsed.applyStart.toLocalDateString()
        ?: applyStart
        ?: applyEndDate?.let { LocalDate.now(ZoneId.of("Asia/Seoul")).toString() }
    val eventStartDateTime = parsed.eventStart.toLocalDateTimeOrNull()
    val eventEndDateTime = parsed.eventEnd.toLocalDateTimeOrNull()
    val eventStartDate = eventStartDateTime?.toLocalDate()?.toString() ?: activityStart
    val eventEndDate = eventEndDateTime?.toLocalDate()?.toString() ?: activityEnd
    val aiDetailSession = buildDetailSession(eventStartDateTime, eventEndDateTime)

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
    val org = organization ?: existing.getOrNull(0)
    val type = category ?: existing.getOrNull(1)
    return listOfNotNull(org, type).filter { it.isNotBlank() }
}

private fun buildDetailSession(start: LocalDateTime?, end: LocalDateTime?): CrawledDetailSession? {
    if (start == null && end == null) return null
    val safeStart = start ?: end ?: return null
    val safeEnd = end ?: safeStart
    val hasTime = safeStart.toLocalTime() != LocalTime.MIN || safeEnd.toLocalTime() != LocalTime.of(23, 59, 59)
    if (!hasTime && safeStart.toLocalDate() != safeEnd.toLocalDate()) return null

    return CrawledDetailSession(
        round = 1,
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

private fun CrawledProgramEvent.parserKey(): String =
    dataSeq?.takeIf { it.isNotBlank() } ?: applyLink?.takeIf { it.isNotBlank() } ?: title.orEmpty()

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
