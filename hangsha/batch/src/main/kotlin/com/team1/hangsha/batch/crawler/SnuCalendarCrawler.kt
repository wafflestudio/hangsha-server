package com.team1.hangsha.batch.crawler

import com.team1.hangsha.event.dto.core.CrawledDetailSession
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class SnuCalendarCrawler(
    private val baseUrl: String = "https://www.snu.ac.kr",
    private val delayMsBetweenPages: Long = 200,
    private val delayMsBetweenDetails: Long = 100,
    private val debug: Boolean = true,
    private val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    fun crawl(opt: CrawlOptions): List<CrawledProgramEvent> {
        require(opt.maxPages > 0) { "maxPages must be positive" }
        require(opt.startPage > 0) { "startPage must be positive" }

        val result = mutableListOf<CrawledProgramEvent>()
        val seenBbsidx = linkedSetOf<String>()
        val lastPage = opt.startPage + opt.maxPages - 1

        for (page in opt.startPage..lastPage) {
            val listUrl = buildListUrl(
                page = page,
                df = opt.df,
                dt = opt.dt,
                qt = opt.qt,
                q = opt.q,
            )
            val html = fetch(listUrl, referer = "$baseUrl/snunow/events") ?: break
            val parsed = parseListHtml(html)

            if (parsed.hasNoResultsMessage) {
                if (debug) println("[SNU-CALENDAR] page=$page no-results message, stopping.")
                break
            }
            if (parsed.items.isEmpty()) {
                if (debug) println("[SNU-CALENDAR] page=$page no events, stopping.")
                break
            }

            val newItems = parsed.items.filter { seenBbsidx.add(it.bbsidx) }
            if (newItems.isEmpty()) {
                if (debug) println("[SNU-CALENDAR] page=$page duplicate-only page, stopping.")
                break
            }

            if (debug) println("[SNU-CALENDAR] page=$page listItems=${parsed.items.size} newItems=${newItems.size}")

            newItems.forEach { item ->
                val detailUrl = canonicalDetailUrl(item.bbsidx)
                val detailHtml = fetch(detailUrl, referer = listUrl)
                val event = if (detailHtml == null) {
                    item.toFallbackEvent(detailUrl)
                } else {
                    parseDetailHtml(detailHtml, item, detailUrl)
                }
                if (event != null) {
                    result += event
                }

                if (delayMsBetweenDetails > 0) Thread.sleep(delayMsBetweenDetails)
            }

            if (delayMsBetweenPages > 0) Thread.sleep(delayMsBetweenPages)
        }

        return result
    }

    fun buildListUrl(page: Int, df: String? = null, dt: String? = null, qt: String? = null, q: String? = null): String {
        val builder = "$baseUrl/snunow/events".toHttpUrl().newBuilder()
        if (!df.isNullOrBlank() || !dt.isNullOrBlank()) {
            builder.addQueryParameter("df", df.orEmpty())
            builder.addQueryParameter("dt", dt.orEmpty())
            builder.addQueryParameter("page", page.toString())
            builder.addQueryParameter("qt", qt ?: "b")
            builder.addQueryParameter("q", q.orEmpty())
        } else {
            builder.addQueryParameter("page", page.toString())
        }
        return builder.build().toString()
    }

    fun parseListHtml(html: String): ParsedListPage {
        val doc = Jsoup.parse(html, baseUrl)
        val hasNoResults = doc.text().normalize().contains("검색된 자료가 없습니다.")
        if (hasNoResults) return ParsedListPage(hasNoResultsMessage = true, items = emptyList())

        val items = doc.select(".board-imgline a.item[href*=bbsidx]")
            .mapNotNull { parseListItem(it) }
            .distinctBy { it.bbsidx }

        return ParsedListPage(hasNoResultsMessage = false, items = items)
    }

    fun parseDetailHtml(html: String, listItem: ListItem, sourceUrl: String): CrawledProgramEvent? {
        val doc = Jsoup.parse(html, sourceUrl)
        val content = doc.selectFirst(".board-view .content") ?: doc.selectFirst(".view .content")
        val contentHtml = content?.html()?.trim()?.takeIf { it.isNotBlank() }
        val contentText = content?.text()?.normalize().orEmpty()
        if (contentText.contains("비교과") || contentHtml.orEmpty().contains("extra.snu.ac.kr", ignoreCase = true)) {
            if (debug) println("[SNU-CALENDAR] skip duplicate extra-snu candidate url=$sourceUrl")
            return null
        }
        val contentLines = extractContentLines(content)

        val title = doc.selectFirst(".board-view .header .title")?.text()?.normalize()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.normalize()
            ?: listItem.title
        val listPeriod = parseListDateRange(
            doc.selectFirst(".board-view .header .date")?.text()?.normalize() ?: listItem.dateText
        )
        val isRecruitingPost = Regex("""모집|신청""").containsMatchIn(contentText)
        val listRangeIsApplyPeriod = listPeriod.isRange && isRecruitingPost
        val detailDateTime = if (listRangeIsApplyPeriod) {
            null
        } else {
            parseBestEventDateTime(contentLines, listPeriod.start)
        }
        val location = parseLocation(contentLines)
        val imageUrl = extractImageUrl(doc) ?: listItem.imageUrl
        val attachments = extractAttachmentLinks(doc)
        val tags = emptyList<String>()

        val applyStart = if (listRangeIsApplyPeriod) listPeriod.start else null
        val applyEnd = if (listRangeIsApplyPeriod) listPeriod.end else null
        val activityStart = if (listRangeIsApplyPeriod) null else detailDateTime?.startDate ?: listPeriod.start
        val activityEnd = if (listRangeIsApplyPeriod) null else detailDateTime?.endDate ?: listPeriod.end
        val session = if (activityStart != null || detailDateTime?.startTime != null || location != null) {
            CrawledDetailSession(
                round = 1,
                location = location,
                startDate = activityStart,
                endDate = activityEnd ?: activityStart,
                startTime = detailDateTime?.startTime,
                endTime = detailDateTime?.endTime,
            )
        } else {
            null
        }

        val enrichedHtml = contentHtml
            ?.let { appendLinkSummary(it, attachments) }

        return CrawledProgramEvent(
            dataSeq = listItem.bbsidx,
            applyLink = sourceUrl,
            majorTypes = listOf("SNU 캘린더"),
            title = title,
            status = "상태 미제공",
            operationMode = null,
            applyStart = applyStart,
            applyEnd = applyEnd,
            activityStart = activityStart,
            activityEnd = activityEnd,
            applyCount = null,
            capacity = null,
            imageUrl = imageUrl,
            tags = tags,
            mainContentHtml = enrichedHtml,
            isPeriodEvent = applyStart != null,
            detailSessions = listOfNotNull(session),
        )
    }

    private fun fetch(url: String, referer: String): String? {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Referer", referer)
            .build()

        if (debug) println("[SNU-CALENDAR] GET $url")

        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (debug) println("[SNU-CALENDAR] FAIL code=${resp.code} url=$url")
                return@use null
            }
            resp.body?.string()
        }
    }

    private fun parseListItem(a: Element): ListItem? {
        val href = a.absUrl("href").ifBlank { a.attr("href") }
        val bbsidx = Regex("""[?&]bbsidx=(\d+)""").find(href)?.groupValues?.get(1) ?: return null
        val title = a.selectFirst(".texts .title")?.text()?.normalize()
            ?: a.selectFirst(".title")?.text()?.normalize()
            ?: return null
        val dateText = a.selectFirst(".point")?.text()?.normalize()
        val imageUrl = extractBackgroundImage(a.selectFirst(".thumb")?.attr("style").orEmpty())

        return ListItem(
            bbsidx = bbsidx,
            title = title,
            dateText = dateText,
            imageUrl = imageUrl,
        )
    }

    private fun ListItem.toFallbackEvent(sourceUrl: String): CrawledProgramEvent {
        val period = parseListDateRange(dateText)
        return CrawledProgramEvent(
            dataSeq = bbsidx,
            applyLink = sourceUrl,
            majorTypes = listOf("SNU 캘린더"),
            title = title,
            status = "상태 미제공",
            activityStart = period.start,
            activityEnd = period.end,
            imageUrl = imageUrl,
            isPeriodEvent = false,
        )
    }

    private fun canonicalDetailUrl(bbsidx: String): String =
        "$baseUrl/snunow/events?md=v&bbsidx=$bbsidx"

    private fun extractImageUrl(doc: Document): String? =
        doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

    private fun extractBackgroundImage(style: String): String? {
        val raw = Regex("""url\((['"]?)(.*?)\1\)""").find(style)?.groupValues?.get(2)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> "$baseUrl/$raw"
        }
    }

    private fun extractAttachmentLinks(doc: Document): List<String> =
        doc.select(".download a[href]")
            .mapNotNull { it.absUrl("href").takeIf { href -> href.isNotBlank() } }
            .distinct()

    private fun appendLinkSummary(html: String, attachments: List<String>): String {
        if (attachments.isEmpty()) return html
        val extra = buildString {
            append("""<div class="snu-calendar-links">""")
            attachments.forEach { link ->
                append("""<p><strong>첨부 링크:</strong> <a href="$link">$link</a></p>""")
            }
            append("</div>")
        }
        return "$html\n$extra"
    }

    private fun extractContentLines(content: Element?): List<String> {
        val html = content?.html() ?: return emptyList()
        return html
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</(p|div|li|tr|h[1-6])>"""), "\n")
            .lines()
            .map { Jsoup.parse(it).text().normalize() }
            .filter { it.isNotBlank() }
    }

    private fun parseLocation(lines: List<String>): String? {
        lines.forEach { line ->
            if (!line.contains("장소")) return@forEach
            parseLocationFromLine(line)?.let { return it }
        }
        return null
    }

    private fun parseLocationFromLine(line: String): String? {
        val m = Regex("""장소\s*[:：]?\s*(.+)$""").find(line) ?: return null
        return m.groupValues[1].normalize().takeIf { it.isNotBlank() }
    }

    private fun parseBestEventDateTime(lines: List<String>, fallbackDate: String?): ParsedDateTime? {
        val defaultDate = fallbackDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        lines.forEachIndexed { index, line ->
            if (!Regex("""시간|기간|일시|일정""").containsMatchIn(line)) return@forEachIndexed
            val candidate = listOfNotNull(line, lines.getOrNull(index + 1))
                .joinToString(" ")
                .normalize()
            parseDateTimeRange(candidate, defaultDate)?.let { return it }
        }
        return null
    }

    private fun parseDateTimeRange(raw: String, fallbackDate: LocalDate?): ParsedDateTime? {
        val dates = extractDates(raw, fallbackDate)
        if (dates.isEmpty()) return null
        val startDate = dates.first()
        val endDate = normalizeEndDate(startDate, dates.getOrNull(1), hasExplicitEndYear(raw)) ?: startDate
        val timeRange = parseTimeRange(raw)
        return ParsedDateTime(
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            startTime = timeRange?.first,
            endTime = timeRange?.second,
        )
    }

    private fun parseTimeRange(raw: String): Pair<String?, String?>? {
        val s = raw.normalize()
        val colonRange = Regex("""(\d{1,2}):(\d{2})\s*(?:-|–|~)\s*(\d{1,2}):(\d{2})""").find(s)
        if (colonRange != null) {
            val start = formatTime(colonRange.groupValues[1].toInt(), colonRange.groupValues[2].toInt())
            val end = formatTime(colonRange.groupValues[3].toInt(), colonRange.groupValues[4].toInt())
            return start to end
        }

        val singleColon = Regex("""(\d{1,2}):(\d{2})""").find(s)
        if (singleColon != null) {
            return formatTime(singleColon.groupValues[1].toInt(), singleColon.groupValues[2].toInt()) to null
        }

        val koreanTime = Regex("""(오전|오후)?\s*(\d{1,2})시(?:\s*(\d{1,2})분)?""").find(s)
            ?: return null
        val ampm = koreanTime.groupValues[1].takeIf { it.isNotBlank() }
        val hour = koreanTime.groupValues[2].toInt().let {
            when {
                ampm == "오후" && it < 12 -> it + 12
                ampm == "오전" && it == 12 -> 0
                else -> it
            }
        }
        val minute = koreanTime.groupValues[3].takeIf { it.isNotBlank() }?.toInt() ?: 0
        return formatTime(hour, minute) to null
    }

    private fun formatTime(hour: Int, minute: Int): String? {
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(hour, minute)
    }

    private fun parseListDateRange(raw: String?): ParsedListPeriod {
        val dates = extractDates(raw.orEmpty(), fallbackDate = null)
        val start = dates.firstOrNull()
        val end = normalizeEndDate(start, dates.getOrNull(1), hasExplicitEndYear(raw.orEmpty())) ?: start
        return ParsedListPeriod(
            start = start?.toString(),
            end = end?.toString(),
            isRange = raw.orEmpty().contains("~") && start != null && end != null && start != end,
        )
    }

    private fun extractDates(raw: String, fallbackDate: LocalDate?): List<LocalDate> {
        val defaultYear = fallbackDate?.year ?: LocalDate.now().year
        var previousMonth = fallbackDate?.monthValue
        val currentMonth = LocalDate.now().monthValue

        return dateTokenRegex.findAll(raw)
            .mapNotNull { match ->
                val yearGroup = match.groups["year"]?.value ?: match.groups["dotYear"]?.value
                val monthGroup = match.groups["month"]?.value ?: match.groups["dotMonth"]?.value
                val dayGroup = match.groups["day"]?.value ?: match.groups["dotDay"]?.value ?: return@mapNotNull null

                val month = monthGroup?.toIntOrNull() ?: previousMonth ?: fallbackDate?.monthValue ?: return@mapNotNull null
                val explicitYear = yearGroup?.toIntOrNull()
                val year = explicitYear ?: inferYear(defaultYear, currentMonth, month)
                val day = dayGroup.toIntOrNull() ?: return@mapNotNull null
                previousMonth = month

                runCatching { LocalDate.of(year, month, day) }.getOrNull()
            }
            .toList()
    }

    private fun inferYear(defaultYear: Int, currentMonth: Int, parsedMonth: Int): Int =
        if (currentMonth == 12 && parsedMonth == 1) defaultYear + 1 else defaultYear

    private fun normalizeEndDate(start: LocalDate?, end: LocalDate?, hasExplicitEndYear: Boolean): LocalDate? {
        if (start == null || end == null) return end
        if (end >= start || hasExplicitEndYear) return end
        return runCatching { end.plusYears(1) }.getOrNull() ?: end
    }

    private fun hasExplicitEndYear(raw: String): Boolean {
        val years = Regex("""20\d{2}""").findAll(raw).toList()
        return years.size >= 2
    }

    private fun String.normalize(): String =
        replace("\u00a0", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private val dateTokenRegex = Regex(
        pattern = """(?:(?<year>20\d{2})\s*년\s*)?(?:(?<month>\d{1,2})\s*월\s*)?(?<day>\d{1,2})\s*일|(?:(?<dotYear>20\d{2})\s*[.]\s*)?(?<dotMonth>\d{1,2})\s*[./]\s*(?<dotDay>\d{1,2})\s*[.]?"""
    )

    data class CrawlOptions(
        val startPage: Int,
        val maxPages: Int,
        val df: String? = null,
        val dt: String? = null,
        val qt: String? = null,
        val q: String? = null,
    )

    data class ParsedListPage(
        val hasNoResultsMessage: Boolean,
        val items: List<ListItem>,
    )

    data class ListItem(
        val bbsidx: String,
        val title: String,
        val dateText: String?,
        val imageUrl: String?,
    )

    private data class ParsedDateTime(
        val startDate: String,
        val endDate: String?,
        val startTime: String?,
        val endTime: String?,
    )

    private data class ParsedListPeriod(
        val start: String?,
        val end: String?,
        val isRange: Boolean,
    )
}
