package com.team1.hangsha.batch.review

import kotlin.test.Test
import kotlin.test.assertContains
import java.time.LocalDateTime

class CrawlReviewFormatterTest {
    @Test
    fun `groups multiple sessions by source link and lists deterministic review flags`() {
        val start = LocalDateTime.of(2026, 8, 20, 10, 0)
        val link = "https://www.snu.ac.kr/snunow/events?md=v&bbsidx=173582"
        val report = CrawlReviewFormatter.format(
            events = listOf(
                ReviewEvent(
                    id = 10,
                    title = "여름 공모전 모집",
                    applyStart = start,
                    applyEnd = start.plusDays(1),
                    eventStart = start,
                    eventEnd = start.plusDays(20),
                    isPeriodEvent = false,
                    organization = "학생지원팀",
                    location = null,
                    applyLink = link,
                    mainContentHtml = "<p>공지</p>",
                ),
                ReviewEvent(
                    id = 11,
                    title = "여름 공모전 모집",
                    applyStart = start,
                    applyEnd = start.plusDays(1),
                    eventStart = start.plusDays(2),
                    eventEnd = start.plusDays(2).plusHours(2),
                    isPeriodEvent = false,
                    organization = "학생지원팀",
                    location = null,
                    applyLink = link,
                    mainContentHtml = "<p>공지</p>",
                ),
            ),
            skipped = listOf(SkippedCrawl("SNU Now", "상세 실패", link, "상세 본문을 읽지 못함")),
        )

        assertContains(report, "신규 원문 1건 / 저장 행사 2건")
        assertContains(report, "#10, #11 여름 공모전 모집")
        assertContains(report, "일반 행사인데 기간이 7일을 초과함")
        assertContains(report, "공모전·인턴십·학생기자단 제목인데 period event가 아님")
        assertContains(report, "[크롤링 중 저장 누락]")
    }

    @Test
    fun `flags invalid periods partial dates malformed links and blank content`() {
        val start = LocalDateTime.of(2026, 8, 20, 10, 0)
        val report = CrawlReviewFormatter.format(
            events = listOf(
                ReviewEvent(
                    id = 1,
                    title = "[깨진 제목",
                    applyStart = start.plusDays(1),
                    applyEnd = start,
                    eventStart = start,
                    eventEnd = null,
                    isPeriodEvent = true,
                    organization = null,
                    location = null,
                    applyLink = "https://example.com/event",
                    mainContentHtml = "<img src='poster.png'>",
                ),
            ),
            skipped = emptyList(),
        )

        assertContains(report, "신청 시작일이 종료일보다 늦음")
        assertContains(report, "행사 기간의 시작/종료 중 하나만 있음")
        assertContains(report, "제목의 HTML/괄호 표현이 깨졌을 수 있음")
        assertContains(report, "지원하지 않는 원문 링크 형식")
        assertContains(report, "본문 텍스트가 없음")
    }
}
