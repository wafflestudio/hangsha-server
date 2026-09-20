package com.team1.hangsha.discord

import com.team1.hangsha.event.dto.response.DetailEventResponse
import com.team1.hangsha.event.dto.response.SearchEventResponse
import org.jsoup.Jsoup
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Discord's 2,000-character limit must not hide the IDs used by edit/delete commands. */
internal object DiscordEventMessages {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun detail(event: DetailEventResponse): String = buildString {
        appendLine("행사 #${event.id} · ${text(event.title, 200)}")
        appendLine("주최: ${text(event.organization, 100)}")
        appendLine("장소: ${text(event.location, 100)}")
        appendLine("진행 방식: ${text(event.operationMode, 50)}")
        appendLine("행사 기간: ${period(event.eventStart, event.eventEnd)}")
        appendLine("신청 기간: ${period(event.applyStart, event.applyEnd)}")
        appendLine("상태 ID: ${event.statusId ?: "미지정"} / 유형 ID: ${event.eventTypeId ?: "미지정"} / 기관 ID: ${event.orgId ?: "미지정"}")
        appendLine("신청 인원: ${event.applyCount} / 정원: ${event.capacity ?: "미지정"}")
        appendLine("태그: ${text(event.tags, 100)}")
        appendLine("신청 링크: ${text(event.applyLink, 300)}")
        appendLine()
        append(text(event.detail, 650))
    }

    fun search(result: SearchEventResponse): String {
        if (result.total == 0) return "검색 결과가 없습니다. 다른 검색어나 필터를 사용해 보세요."
        val pages = (result.total + result.size - 1) / result.size
        if (result.items.isEmpty()) return "해당 페이지에 결과가 없습니다. 총 ${result.total}건, 마지막 페이지는 ${pages}입니다."
        return buildString {
            appendLine("검색 결과 ${result.total}건 · ${result.page}/${pages}페이지")
            result.items.forEach { item ->
                val event = item.event
                appendLine()
                appendLine("#${event.id} · ${text(event.title, 120)}")
                appendLine("주최: ${text(event.organization, 40)}")
                appendLine("행사: ${period(event.eventStart, event.eventEnd)}")
                appendLine("신청 마감: ${date(event.applyEnd)}")
                item.highlight.contentSnippet?.let { appendLine(text(it, 60)) }
            }
            appendLine()
            appendLine("상세 조회: /event-detail event_id:<행사 ID>")
            if (result.page < pages) append("다음 결과: 같은 검색어와 필터로 page:${result.page + 1}")
        }
    }

    private fun date(value: LocalDateTime?): String = value?.format(dateFormat) ?: "미정"
    private fun period(start: LocalDateTime?, end: LocalDateTime?): String = "${date(start)} ~ ${date(end)}"

    private fun text(value: String?, limit: Int): String {
        val plain = value?.let { Jsoup.parse(it).text() }?.trim().orEmpty()
        if (plain.isEmpty()) return "미등록"
        return if (plain.length <= limit) plain else plain.take(limit - 1) + "…"
    }
}
