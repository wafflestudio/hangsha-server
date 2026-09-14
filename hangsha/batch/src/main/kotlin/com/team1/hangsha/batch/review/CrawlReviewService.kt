package com.team1.hangsha.batch.review

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 새로 동기화된 행사를 사람이 검수할 수 있도록 Discord에 요약한다.
 *
 * cursor는 배치 시작 시점의 최대 event id로 최초 초기화한다. 따라서 기능을
 * 처음 배포해도 과거 행사를 한꺼번에 알리지 않고, 그 실행에서 새로 삽입된
 * 행사만 대상으로 삼는다.
 */
@Service
@ConditionalOnProperty(name = ["job"], havingValue = "extra-snu-sync", matchIfMissing = true)
class CrawlReviewService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${crawl_review_discord_webhook_uri:}") private val discordWebhookUri: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val httpClient = OkHttpClient()
    private val skipped = mutableListOf<SkippedCrawl>()

    @Transactional
    fun beginRun() {
        skipped.clear()
        val exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM crawl_review_cursors WHERE name = ?",
            Long::class.java,
            CURSOR_NAME,
        ) ?: 0L
        if (exists == 0L) {
            val currentMaxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM events",
                Long::class.java,
            ) ?: 0L
            jdbcTemplate.update(
                "INSERT INTO crawl_review_cursors (name, last_event_id) VALUES (?, ?)",
                CURSOR_NAME,
                currentMaxId,
            )
            log.info("Initialized crawl review cursor at event id={}", currentMaxId)
        }
    }

    fun recordSkipped(source: String, title: String?, applyLink: String?, reason: String) {
        skipped += SkippedCrawl(source, title?.trim().orEmpty(), applyLink, reason)
    }

    /** Sends first; cursor advances only after a successful Discord request. */
    @Transactional
    fun reportAndAdvance() {
        val cursor = jdbcTemplate.queryForObject(
            "SELECT last_event_id FROM crawl_review_cursors WHERE name = ?",
            Long::class.java,
            CURSOR_NAME,
        ) ?: return
        val events = loadNewEvents(cursor)
        val report = CrawlReviewFormatter.format(events, skipped.toList())

        if (discordWebhookUri.isBlank()) {
            log.warn(
                "Crawl review webhook is empty (crawl_review_discord_webhook_uri). " +
                    "Report was not delivered; cursor remains at {}.\n{}",
                cursor,
                report,
            )
            return
        }

        sendToDiscord(report)
        val lastId = events.maxOfOrNull { it.id } ?: cursor
        jdbcTemplate.update(
            "UPDATE crawl_review_cursors SET last_event_id = ? WHERE name = ?",
            lastId,
            CURSOR_NAME,
        )
        log.info("Crawl review delivered: newEvents={}, skipped={}, cursor={}", events.size, skipped.size, lastId)
    }

    private fun loadNewEvents(cursor: Long): List<ReviewEvent> =
        jdbcTemplate.query(
            """
            SELECT id, title, apply_start, apply_end, event_start, event_end,
                   is_period_event, organization, location, apply_link, main_content_html
            FROM events
            WHERE id > ? AND admin_deleted = false
            ORDER BY id ASC
            """.trimIndent(),
            { rs, _ ->
                ReviewEvent(
                    id = rs.getLong("id"),
                    title = rs.getString("title"),
                    applyStart = rs.getTimestamp("apply_start")?.toLocalDateTime(),
                    applyEnd = rs.getTimestamp("apply_end")?.toLocalDateTime(),
                    eventStart = rs.getTimestamp("event_start")?.toLocalDateTime(),
                    eventEnd = rs.getTimestamp("event_end")?.toLocalDateTime(),
                    isPeriodEvent = rs.getBoolean("is_period_event"),
                    organization = rs.getString("organization"),
                    location = rs.getString("location"),
                    applyLink = rs.getString("apply_link"),
                    mainContentHtml = rs.getString("main_content_html"),
                )
            },
            cursor,
        )

    private fun sendToDiscord(report: String) {
        report.chunked(DISCORD_LIMIT).forEach { chunk ->
            sendChunkWithRetry(chunk)
        }
    }

    private fun sendChunkWithRetry(chunk: String) {
        val payload = objectMapper.writeValueAsString(
            DiscordPayload(chunk, DiscordAllowedMentions(emptyList()))
        )
        val request = Request.Builder()
            .url(discordWebhookUri)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        while (true) {
            val retryAfterMillis = httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) return
                check(response.code == 429) { "Discord webhook failed: HTTP ${response.code}" }

                val body = response.body?.string()
                val retryAfterSeconds = body?.let { responseBody ->
                    runCatching {
                        objectMapper.readTree(responseBody)["retry_after"]?.asDouble()
                    }.getOrNull()
                } ?: response.header("Retry-After")?.toDoubleOrNull()
                    ?: DEFAULT_RETRY_AFTER_SECONDS

                (retryAfterSeconds * 1_000).toLong().coerceAtLeast(MIN_RETRY_DELAY_MILLIS)
            }

            log.warn("Discord webhook rate limited; retrying after {} ms", retryAfterMillis)
            Thread.sleep(retryAfterMillis)
        }
    }

    data class DiscordPayload(
        val content: String,
        @get:JsonProperty("allowed_mentions") val allowedMentions: DiscordAllowedMentions,
    )

    data class DiscordAllowedMentions(val parse: List<String>)

    private companion object {
        const val CURSOR_NAME = "extra-snu-sync"
        const val DISCORD_LIMIT = 1_900
        const val DEFAULT_RETRY_AFTER_SECONDS = 1.0
        const val MIN_RETRY_DELAY_MILLIS = 100L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

data class ReviewEvent(
    val id: Long,
    val title: String,
    val applyStart: LocalDateTime?,
    val applyEnd: LocalDateTime?,
    val eventStart: LocalDateTime?,
    val eventEnd: LocalDateTime?,
    val isPeriodEvent: Boolean,
    val organization: String?,
    val location: String?,
    val applyLink: String?,
    val mainContentHtml: String?,
)

data class SkippedCrawl(
    val source: String,
    val title: String,
    val applyLink: String?,
    val reason: String,
)

object CrawlReviewFormatter {
    private val recruitingTitle = Regex("모집|신청|지원|접수")
    private val periodTitle = Regex("공모전|인턴십|학생기자단")
    private val supportedLink = Regex(
        """^https://(extra\.snu\.ac\.kr/ptfol/pgm/view\.do\?[^#]*dataSeq=|www\.snu\.ac\.kr/snunow/events\?[^#]*bbsidx=)"""
    )
    private val zone = ZoneId.of("Asia/Seoul")

    fun format(events: List<ReviewEvent>, skipped: List<SkippedCrawl>): String = buildString {
        val groups = events.groupBy { it.applyLink ?: "event:${it.id}" }
        val flagged = groups.values.count { group -> group.flatMap(::issuesFor).isNotEmpty() }
        appendLine("[행사 크롤링 검수]")
        appendLine("신규 원문 ${groups.size}건 / 저장 행사 ${events.size}건 / 확인 필요 ${flagged}건 / 저장 누락 ${skipped.size}건")

        if (groups.isNotEmpty()) {
            appendLine()
            appendLine("[신규 행사]")
            groups.values.forEach { group -> appendEventGroup(group) }
        }
        if (skipped.isNotEmpty()) {
            appendLine()
            appendLine("[크롤링 중 저장 누락]")
            skipped.distinct().forEach { item ->
                appendLine("- ${item.title.ifBlank { "(제목 없음)" } } (${item.source}): ${item.reason}")
                item.applyLink?.let { appendLine("  $it") }
            }
        }
    }.trimEnd()

    private fun StringBuilder.appendEventGroup(group: List<ReviewEvent>) {
        val first = group.first()
        val issues = group.flatMap(::issuesFor).distinct()
        appendLine("- #${group.joinToString(", #") { it.id.toString() }} ${first.title}")
        appendLine("  행사: ${group.joinToString(" / ") { formatPeriod(it.eventStart, it.eventEnd) }}")
        appendLine("  신청: ${formatPeriod(first.applyStart, first.applyEnd)}")
        first.organization?.takeIf { it.isNotBlank() }?.let { appendLine("  주최: $it") }
        first.location?.takeIf { it.isNotBlank() }?.let { appendLine("  장소: $it") }
        issues.forEach { appendLine("  확인: $it") }
        first.applyLink?.let { appendLine("  $it") }
    }

    private fun issuesFor(event: ReviewEvent): List<String> = buildList {
        if (event.applyStart != null && event.applyEnd != null && event.applyStart > event.applyEnd) add("신청 시작일이 종료일보다 늦음")
        if (event.eventStart != null && event.eventEnd != null && event.eventStart > event.eventEnd) add("행사 시작일이 종료일보다 늦음")
        if ((event.applyStart == null) != (event.applyEnd == null)) add("신청 기간의 시작/종료 중 하나만 있음")
        if ((event.eventStart == null) != (event.eventEnd == null)) add("행사 기간의 시작/종료 중 하나만 있음")
        if (!event.isPeriodEvent && event.eventStart != null && event.eventEnd != null && event.eventEnd > event.eventStart.plusDays(7)) add("일반 행사인데 기간이 7일을 초과함")
        if (!event.isPeriodEvent && periodTitle.containsMatchIn(event.title)) add("공모전·인턴십·학생기자단 제목인데 period event가 아님")
        if (event.applyStart != null && event.eventEnd != null && event.applyStart > event.eventEnd) add("행사가 끝난 뒤 신청이 시작됨")
        if (recruitingTitle.containsMatchIn(event.title) && samePeriod(event.applyStart, event.applyEnd, event.eventStart, event.eventEnd)) add("신청 기간과 행사 기간이 동일함")
        if (event.eventEnd?.isBefore(LocalDateTime.now(zone)) == true && event.applyEnd?.isBefore(LocalDateTime.now(zone)) != false) add("새로 수집됐지만 신청·행사 기간이 모두 지남")
        if (event.title.isBlank() || event.title.length < 2) add("제목이 비어 있거나 지나치게 짧음")
        if (event.title.contains('<') || !hasBalancedBrackets(event.title)) add("제목의 HTML/괄호 표현이 깨졌을 수 있음")
        if (!event.applyLink.isNullOrBlank() && !supportedLink.containsMatchIn(event.applyLink)) add("지원하지 않는 원문 링크 형식")
        if (event.applyLink.isNullOrBlank()) add("원문 링크가 없음")
        val contentText = event.mainContentHtml?.let { Jsoup.parseBodyFragment(it).text().trim() }.orEmpty()
        if (contentText.isBlank()) add("본문 텍스트가 없음(이미지 공지인지 원문 확인 필요)")
    }

    private fun samePeriod(
        applyStart: LocalDateTime?, applyEnd: LocalDateTime?, eventStart: LocalDateTime?, eventEnd: LocalDateTime?,
    ): Boolean = applyStart != null && applyEnd != null && applyStart == eventStart && applyEnd == eventEnd

    private fun hasBalancedBrackets(text: String): Boolean {
        val pairs = mapOf('(' to ')', '[' to ']', '{' to '}')
        val stack = ArrayDeque<Char>()
        text.forEach { char ->
            when {
                char in pairs -> stack.addLast(pairs.getValue(char))
                char in pairs.values && (stack.isEmpty() || stack.removeLast() != char) -> return false
            }
        }
        return stack.isEmpty()
    }

    private fun formatPeriod(start: LocalDateTime?, end: LocalDateTime?): String =
        when {
            start == null && end == null -> "미상"
            else -> "${start ?: "?"} ~ ${end ?: "?"}"
        }
}
