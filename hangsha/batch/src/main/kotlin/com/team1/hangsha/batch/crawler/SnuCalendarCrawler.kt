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
                result += event

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

    fun parseDetailHtml(html: String, listItem: ListItem, sourceUrl: String): CrawledProgramEvent {
        val doc = Jsoup.parse(html, sourceUrl)
        val content = doc.selectFirst(".board-view .content") ?: doc.selectFirst(".view .content")
        val contentHtml = content?.html()?.trim()?.takeIf { it.isNotBlank() }
        val contentText = content?.text()?.normalize().orEmpty()

        val title = doc.selectFirst(".board-view .header .title")?.text()?.normalize()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.normalize()
            ?: listItem.title
        val listPeriod = parseDotRangeToYmd(
            doc.selectFirst(".board-view .header .date")?.text()?.normalize() ?: listItem.dateText
        )
        val detailDateTime = parseBestEventDateTime(contentText)
        val applyEnd = parseApplyEnd(contentText)
        val location = parseLocation(contentText)
        val externalApplyLink = extractExternalApplyLink(doc)
        val imageUrl = extractImageUrl(doc) ?: listItem.imageUrl
        val attachments = extractAttachmentLinks(doc)
        val tags = buildList {
            if (attachments.isNotEmpty()) add("attachments:${attachments.size}")
        }

        val activityStart = detailDateTime?.date ?: listPeriod.first
        val activityEnd = detailDateTime?.date ?: listPeriod.second
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
            ?.let { appendLinkSummary(it, externalApplyLink, attachments) }

        return CrawledProgramEvent(
            dataSeq = "snu-calendar:${listItem.bbsidx}",
            sourceUrl = sourceUrl,
            applyLink = externalApplyLink ?: sourceUrl,
            majorTypes = listOf("서울대학교"),
            title = title,
            status = null,
            operationMode = null,
            applyStart = null,
            applyEnd = applyEnd,
            activityStart = activityStart,
            activityEnd = activityEnd,
            applyCount = null,
            capacity = null,
            imageUrl = imageUrl,
            tags = tags,
            mainContentHtml = enrichedHtml,
            isPeriodEvent = false,
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
        val (start, end) = parseDotRangeToYmd(dateText)
        return CrawledProgramEvent(
            dataSeq = "snu-calendar:$bbsidx",
            sourceUrl = sourceUrl,
            applyLink = sourceUrl,
            majorTypes = listOf("서울대학교"),
            title = title,
            activityStart = start,
            activityEnd = end,
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

    private fun extractExternalApplyLink(doc: Document): String? {
        val anchors = doc.select(".board-view .content a[href]")
        val formLink = anchors.firstOrNull { a ->
            val href = a.absUrl("href")
            href.contains("forms.gle") || href.contains("docs.google.com/forms")
        }?.absUrl("href")?.takeIf { it.isNotBlank() }
        if (formLink != null) return formLink

        return anchors.firstOrNull { a ->
            val nearby = buildString {
                append(a.text())
                append(" ")
                append(a.parent()?.ownText().orEmpty())
                append(" ")
                append(a.previousSibling()?.toString().orEmpty().takeLast(40))
            }.normalize()
            nearby.contains("신청")
        }?.absUrl("href")?.takeIf { it.isNotBlank() }
    }

    private fun extractAttachmentLinks(doc: Document): List<String> =
        doc.select(".download a[href]")
            .mapNotNull { it.absUrl("href").takeIf { href -> href.isNotBlank() } }
            .distinct()

    private fun appendLinkSummary(html: String, applyLink: String?, attachments: List<String>): String {
        if (applyLink == null && attachments.isEmpty()) return html
        val extra = buildString {
            append("""<div class="snu-calendar-links">""")
            if (applyLink != null) {
                append("""<p><strong>신청 링크:</strong> <a href="$applyLink">$applyLink</a></p>""")
            }
            attachments.forEach { link ->
                append("""<p><strong>첨부 링크:</strong> <a href="$link">$link</a></p>""")
            }
            append("</div>")
        }
        return "$html\n$extra"
    }

    private fun parseLocation(text: String): String? {
        val m = Regex("""(?:장소|위치)\s*[:：]\s*([^·\n\r]+?)(?=\s+(?:대상|제공|신청|일정|문의|상세|$))""")
            .find(text)
        return m?.groupValues?.get(1)?.normalize()?.takeIf { it.isNotBlank() }
    }

    private fun parseApplyEnd(text: String): String? {
        val marker = Regex("""신청\s*마감\s*[:：]?\s*""").find(text) ?: return null
        val tail = text.substring(marker.range.last + 1).take(80)
        return parseKoreanDate(tail)?.date
    }

    private fun parseBestEventDateTime(text: String): ParsedDateTime? {
        val marker = Regex("""(?:일정|일시|행사\s*일시)\s*[:：]?\s*""").find(text)
        if (marker != null) {
            val tail = text.substring(marker.range.last + 1).take(120)
            parseKoreanDate(tail)?.let { return it }
        }
        return Regex("""20\d{2}년\s*\d{1,2}월\s*\d{1,2}일.{0,40}""")
            .findAll(text)
            .mapNotNull { parseKoreanDate(it.value) }
            .firstOrNull { it.startTime != null }
    }

    private fun parseKoreanDate(raw: String): ParsedDateTime? {
        val dateMatch = Regex("""(20\d{2})년\s*(\d{1,2})월\s*(\d{1,2})일""").find(raw) ?: return null
        val y = dateMatch.groupValues[1].toInt()
        val m = dateMatch.groupValues[2].toInt()
        val d = dateMatch.groupValues[3].toInt()
        val date = runCatching { LocalDate.of(y, m, d).toString() }.getOrNull() ?: return null
        val tail = raw.substring(dateMatch.range.last + 1).take(80)
        val timeRange = parseTimeRange(tail)
        return ParsedDateTime(
            date = date,
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

    private fun parseDotRangeToYmd(raw: String?): Pair<String?, String?> {
        val dates = Regex("""(20\d{2})\.(\d{1,2})\.(\d{1,2})\.?""")
            .findAll(raw.orEmpty())
            .mapNotNull {
                val y = it.groupValues[1].toInt()
                val m = it.groupValues[2].toInt()
                val d = it.groupValues[3].toInt()
                runCatching { LocalDate.of(y, m, d).toString() }.getOrNull()
            }
            .toList()
        return dates.firstOrNull() to (dates.getOrNull(1) ?: dates.firstOrNull())
    }

    private fun String.normalize(): String =
        replace("\u00a0", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

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
        val date: String,
        val startTime: String?,
        val endTime: String?,
    )
}
