package com.team1.hangsha.batch.crawler

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import com.microsoft.playwright.options.WaitUntilState
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit
import com.team1.hangsha.common.upload.OciUploadService
import org.jsoup.parser.Parser

class ExtraSnuCrawler(
    private val baseUrl: String = "https://extra.snu.ac.kr",
    private val listPath: String = "/ptfol/pgm/index.do",
    private val viewPath: String = "/ptfol/pgm/view.do",
    private val delayMsBetweenPages: Long = 200,
    private val delayMsBetweenDetails: Long = 100,
    private val debug: Boolean = true,
    private val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36",
    private val applyChkCodes: List<String> = listOf("0001", "0002", "0004"), // sync 기본
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) : AutoCloseable {

    // ✅ 상세(NetFunnel)는 JS 실행이 필요 → Playwright로 처리
    private val playwright: Playwright = Playwright.create()
    private val browser: Browser = playwright.chromium().launch(
        BrowserType.LaunchOptions().setHeadless(true)
    )
    private val context: Browser.NewContextOptions = Browser.NewContextOptions().setUserAgent(userAgent)

    // ✅ 재사용 (매 요청마다 newContext/newPage 만들지 않음)
    private val pwContext = browser.newContext(context)
    init {
        // 무거운 리소스 차단 (JS는 살림)
        pwContext.route("**/*") { route ->
            val rt = route.request().resourceType()
            if (rt == "image" || rt == "media" || rt == "font") route.abort()
            else route.resume()
        }
    }

    override fun close() {
        runCatching { pwContext.close() }
        runCatching { browser.close() }
        runCatching { playwright.close() }
    }

    fun parseListHtml(html: String): List<ProgramEvent> {
        val doc = Jsoup.parse(html, baseUrl)
        val cards = doc.select("div.lica_gp")
        if (cards.isEmpty()) return emptyList()

        return cards.mapNotNull { cardToEvent(it) }
            .filter { !it.title.isNullOrBlank() }
    }

    /**
     * ✅ 상세 크롤링 (조건부 실행 지원)
     * - init 모드에서 "모집마감" 스킵 같은 정책을 main에서 람다로 주입 가능
     */
    fun enrichDetails(
        events: List<ProgramEvent>,
        ociUploadService: OciUploadService,
        shouldFetch: (ProgramEvent) -> Boolean = { true }
    ): List<ProgramEvent> {
        return events.map { e ->
            val dataSeq = e.dataSeq
            if (dataSeq.isNullOrBlank()) return@map e
            if (!shouldFetch(e)) return@map e

            val html1 = fetchDetailPageByPlaywright(dataSeq) ?: return@map e
            var sessions = parseDetailSessions(html1)
            var mainHtml = parseMainContentHtml(html1, ociUploadService)

            if (sessions.isEmpty()) { // fallback once
                val html2 = fetchDetailPageByPlaywright(dataSeq)
                if (html2 != null) {
                    sessions = parseDetailSessions(html2)
                    mainHtml = parseMainContentHtml(html2, ociUploadService)
                }
            }

            if (delayMsBetweenDetails > 0) Thread.sleep(delayMsBetweenDetails)
            e.copy(detailSessions = sessions, mainContentHtml = mainHtml)
        }
    }

    private fun fetchListPage(pageNo: Int): String? {
        val builder = (baseUrl + listPath).toHttpUrl().newBuilder()
            .addQueryParameter("currentPageNo", pageNo.toString())

        // ✅ applyChkCdSh만 붙임 (init/sync 모드에 따라 applyChkCodes가 다름)
        for (code in applyChkCodes) {
            builder.addQueryParameter("applyChkCdSh", code)
        }

        val url = builder.build()

        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()

        if (debug) println("[LIST] GET $url")

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (debug) println("[LIST] FAIL code=${resp.code} url=$url")
                return null
            }
            return resp.body?.string()
        }
    }

    /**
     * ✅ NetFunnel/Wait.jsp 때문에 OkHttp만으로는 302 루프가 날 수 있음
     * Playwright로 실제 브라우저처럼 진입해서 최종 DOM(html)을 가져온다.
     *
     * - wait.jsp / netfunnel 감지 시 재시도 + 백오프(지터)
     */
    private fun fetchDetailPageByPlaywright(dataSeq: String): String? {
        val viewUrl = "$baseUrl$viewPath?dataSeq=$dataSeq"
        if (debug) println("\n[PW] goto(view) => $viewUrl")

        fun isWait(u: String) = u.contains("/wait.jsp")
        fun isDetail(u: String) =
            u.contains("/ptfol/imng/icmpNsbjtPgm/findIcmpNsbjtPgmInfo.do") ||
            u.contains("/ptfol/cous/staGrp/rcri/view.do")

        val page = pwContext.newPage()

        return try {
            page.navigate(
                viewUrl,
                Page.NavigateOptions()
                    .setWaitUntil(WaitUntilState.COMMIT)
                    .setTimeout(20_000.0)
            )

            val hardDeadlineMs = System.currentTimeMillis() + 120_000L
            var lastUrl = page.url()

            while (System.currentTimeMillis() < hardDeadlineMs) {
                val curUrl = page.url()
                if (curUrl != lastUrl && debug) println("[PW] url => $curUrl")
                lastUrl = curUrl

                if (curUrl == "https://nsso.snu.ac.kr/sso/usr/snu/mfa/login/view") {
                    return null
                }

                if (isWait(curUrl)) {
                    if (debug) println("[PW] wait.jsp... (sleep)")
                    page.waitForTimeout(800.0 + Math.random() * 1200.0)
                    continue
                }

                if (isDetail(curUrl)) {
                    val detailDeadlineMs = System.currentTimeMillis() + 10_000L

                    while (System.currentTimeMillis() < detailDeadlineMs) {
                        val titles = runCatching {
                            @Suppress("UNCHECKED_CAST")
                            page.evalOnSelectorAll(
                                "div.cont_box p.cont_tit",
                                "els => els.map(el => (el.textContent || '').replace(/\\s+/g, ' ').trim())"
                            ) as List<String>
                        }.getOrDefault(emptyList())

                        if (debug) println("[PW] titles=$titles")

                        // 아직 본문 골격이 안 뜬 상태
                        if (titles.isEmpty()) {
                            page.waitForTimeout(500.0)
                            continue
                        }

                        // 강좌 정보 자체가 없는 페이지면 더 기다리지 말고 그냥 반환
                        if (!titles.contains("강좌 정보")) {
                            val html = try {
                                page.content()
                            } catch (e: Exception) {
                                page.waitForTimeout(500.0)
                                page.content()
                            }
                            if (debug) println("[PW] OK detail(no lecture info) url=$curUrl htmlLen=${html.length}")
                            return html
                        }

                        // 강좌 정보가 있으면 교육(활동)기간 값이 실제로 채워질 때까지 조금 더 기다림
                        val hasPeriod = runCatching {
                            page.evaluate(
                                """
                            () => {
                              const ths = Array.from(document.querySelectorAll("th"));
                              const th = ths.find(x => (x.textContent || "").includes("교육(활동)기간"));
                              if (!th) return false;
                              const td = th.nextElementSibling;
                              if (!td) return false;
                              const txt = (td.textContent || "").replace(/\s+/g, " ").trim();
                              return /\d{4}\.\d{2}\.\d{2}\./.test(txt) && /\d{2}:\d{2}/.test(txt);
                            }
                            """.trimIndent()
                            ) as Boolean
                        }.getOrDefault(false)

                        if (hasPeriod) {
                            val html = try {
                                page.content()
                            } catch (e: Exception) {
                                page.waitForTimeout(500.0 + Math.random() * 600.0)
                                page.content()
                            }
                            if (debug) println("[PW] OK detail(with lecture info) url=$curUrl htmlLen=${html.length}")
                            return html
                        }

                        page.waitForTimeout(500.0)
                    }

                    // detail 페이지까지는 왔는데 10초 동안 period가 안 떴음
                    // 그래도 HTML은 넘기고, 실제 판정은 Kotlin parse 쪽에서 처리
                    val html = try {
                        page.content()
                    } catch (e: Exception) {
                        page.waitForTimeout(500.0)
                        page.content()
                    }
                    if (debug) println("[PW] OK detail(timeout fallback) url=$curUrl htmlLen=${html.length}")
                    return html
                }

                page.waitForTimeout(400.0 + Math.random() * 600.0)
            }

            if (debug) println("[PW] FAIL timeout waiting for detail dataSeq=$dataSeq lastUrl=${page.url()}")
            null
        } catch (e: Exception) {
            if (debug) println("[PW] FAIL dataSeq=$dataSeq : ${e::class.simpleName} ${e.message}")
            null
        } finally {
            runCatching { page.close() }
        }
    }

    // ---------------------------
    // ✅ 리스트 카드 -> ProgramEvent (신형 모델 + status + tags)
    // ---------------------------
    private fun cardToEvent(card: Element): ProgramEvent? {
        val li = card.closest("li")

        // dataSeq
        val onclick = card.selectFirst("a[onclick*=global.write]")?.attr("onclick")?.normalize()
        val dataSeq = onclick?.let { Regex("""global\.write\('([^']+)'""").find(it)?.groupValues?.get(1) }

        // status ("모집중" / "마감임박" / "모집마감" ...)
        val status = card.selectFirst(".label_box a.btn01 span")?.text()?.normalize()
            ?: card.selectFirst(".label_box a.btn01")?.text()?.normalize()

        val majors = card.select("ul.major_type > li")
            .map { it.text().normalize() }
            .filter { it.isNotEmpty() }

        val title = card.selectFirst("a.tit")?.text()?.normalize()

        val operationMode = card.selectFirst("dl.class_cd dd")?.text()?.normalize()

        // 기간 raw
        val applyRaw = card.selectFirst("dl.apl_date dd")?.text()?.normalize()
        val activityRaw = card.selectFirst("dl.edu_date dd")?.text()?.normalize()

        val (applyStart, applyEnd) = parseDotRangeToYmd(applyRaw)
        val (activityStart, activityEnd) = parseDotRangeToYmd(activityRaw)

        val counts = li?.select(".cnt_gp > li")
            ?.associate { row ->
                val label = row.selectFirst("span.lg")?.text()?.normalize().orEmpty()
                val value = row.selectFirst("strong")?.text()?.normalize().orEmpty()
                label to value
            }.orEmpty()

        val applyCount = counts["신청"]?.toIntOrNullSafe() ?: 0
        val capacity = counts["정원"]?.toIntOrNullSafe() ?: 0

        val imageUrl = card.selectFirst(".img_wrap img")
            ?.absUrl("src")
            ?.takeIf { it.isNotBlank() }

        // tags: "#..." 원문 그대로 저장
        val tags = card.select(".keyword_list .keyword")
            .map { it.text().normalize() }
            .filter { it.isNotBlank() }

        return ProgramEvent(
            dataSeq = dataSeq,
            status = status,
            majorTypes = majors,
            title = title,
            operationMode = operationMode,
            applyStart = applyStart,
            applyEnd = applyEnd,
            activityStart = activityStart,
            activityEnd = activityEnd,
            applyCount = applyCount,
            capacity = capacity,
            imageUrl = imageUrl,
            tags = tags,
            detailSessions = emptyList()
        )
    }

    // ---------------------------
    // ✅ 상세 페이지 -> DetailSession (start/end/time 평탄화, n회차, 우측 날짜 생략 처리)
    // ---------------------------
    private fun parseDetailSessions(html: String): List<DetailSession> {
        val doc = Jsoup.parse(html, baseUrl)

        val table = doc.select("table.table.t_view.add_tr")
            .firstOrNull { it.text().normalize().contains("교육(활동)기간") }
            ?: return emptyList()

        val trs = table.select("tr")
        if (trs.isEmpty()) return emptyList()

        val sessions = linkedMapOf<Int, MutableSession>()
        var currentRound = 1

        fun ensure(round: Int): MutableSession {
            sessions.putIfAbsent(round, MutableSession(round))
            return sessions[round]!!
        }

        for (tr in trs) {
            // 회차 감지: th.bg2 내부의 [n]
            tr.selectFirst("th.bg2")?.text()?.normalize()?.let { t ->
                Regex("""\[(\d+)]""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { n ->
                    currentRound = n
                }
            }

            val loc = tdAfterThContains(tr, "교육(활동)장소")
            if (!loc.isNullOrBlank()) ensure(currentRound).location = loc

            val periodRaw = tdAfterThContains(tr, "교육(활동)기간")
            if (!periodRaw.isNullOrBlank()) {
                val f = parseDetailPeriod(periodRaw)
                val s = ensure(currentRound)
                s.startDate = f.startDate
                s.endDate = f.endDate
                s.startTime = f.startTime
                s.endTime = f.endTime
            }
        }

        return sessions.values
            .map { it.toImmutable() }
            .sortedBy { it.round ?: Int.MAX_VALUE }
            .filter { it.location != null || it.startDate != null || it.startTime != null }
    }

    /**
     * ✅ "프로그램 주요내용" 섹션의 td_box HTML을 "그대로" 저장하되,
     *
     * 반환값은 td_box의 innerHTML.
     * 없으면 null.
     */
    private fun parseMainContentHtml(html: String, ociUploadService: OciUploadService): String? {
        val doc = Jsoup.parse(html, baseUrl)

        val box = doc.select("div.cont_box")
            .firstOrNull {
                it.selectFirst("p.cont_tit")?.text()?.normalize() == "프로그램 주요내용"
            } ?: return null

        val tdBox = box.selectFirst("div.td_box") ?: return null

        val cookieHeader = buildCookieHeader()

        tdBox.select("img").forEach { img ->
            val rawSrc = img.absUrl("src").ifBlank {
                val decoded = Parser.unescapeEntities(img.attr("src"), false)
                when {
                    decoded.startsWith("http://") || decoded.startsWith("https://") -> decoded
                    decoded.startsWith("/") -> "$baseUrl$decoded"
                    else -> "$baseUrl/$decoded"
                }
            }

            if (rawSrc.isBlank()) {
                img.remove()
                return@forEach
            }

            val downloaded = runCatching {
                downloadImage(rawSrc, cookieHeader)
            }.getOrNull()

            if (downloaded == null) {
                img.remove()
                return@forEach
            }

            val uploadedUrl = runCatching {
                ociUploadService.uploadBytesIfAbsent(
                    prefix = "events/detail",
                    originalFilename = null,
                    bytes = downloaded.bytes,
                    contentType = downloaded.contentType,
                )
            }.getOrNull()

            if (uploadedUrl == null) {
                img.remove()
                return@forEach
            }

            img.attr("src", uploadedUrl)
            img.removeAttr("onclick")
            img.removeAttr("usemap")
        }

        tdBox.select("a").forEach { it.unwrap() }
        tdBox.select("script, style").remove()

        tdBox.select("p").forEach { p ->
            if (p.text().normalize().isBlank() && p.select("br").isEmpty() && p.childrenSize() == 0) {
                p.remove()
            }
        }

        val out = tdBox.html().trim()
        return out.ifBlank { null }
    }

    // ---------------------------
    // helpers
    // ---------------------------

    private fun tdAfterThContains(tr: Element, label: String): String? {
        val th = tr.select("th")
            .firstOrNull { it.text().normalize().contains(label) }
            ?: return null

        val td = th.nextElementSibling() ?: return null
        return td.text().normalize().takeIf { it.isNotBlank() }
    }

    private fun String.toIntOrNullSafe(): Int? {
        val cleaned = this.replace(",", "").trim()
        return cleaned.toIntOrNull()
    }

    private fun String.normalize(): String =
        this.replace("\u00a0", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    // "2026.01.07.~2026.01.13." -> ("2026-01-07","2026-01-13")
    // "" or null -> (null,null)
    private fun parseDotRangeToYmd(raw: String?): Pair<String?, String?> {
        val s = raw?.normalize()?.takeIf { it.isNotBlank() } ?: return null to null
        val parts = s.split("~")
        val left = parts.getOrNull(0)?.trim().orEmpty()
        val right = parts.getOrNull(1)?.trim().orEmpty()
        return parseDotDateToYmd(left) to parseDotDateToYmd(right)
    }

    private fun parseDotDateToYmd(x: String): String? {
        val t = x.trim().removeSuffix(".")
        if (t.isBlank()) return null
        val m = Regex("""(\d{4})\.(\d{2})\.(\d{2})""").find(t) ?: return null
        val (y, mo, d) = m.destructured
        return "$y-$mo-$d"
    }

    private data class PeriodFields(
        val startDate: String?,
        val endDate: String?,
        val startTime: String?,
        val endTime: String?
    )

    /**
     * "2026.01.15. 09:30 ~ 2026.01.15. 18:00"
     * "2026.01.20. 14:00 ~ 2026.01.22. 17:00"
     * "2026.01.20. 14:00 ~ 17:00" (우측 날짜 생략)
     */
    private fun parseDetailPeriod(raw: String): PeriodFields {
        val s = raw.normalize()
        val parts = s.split("~").map { it.trim() }
        val left = parts.getOrNull(0).orEmpty()
        val right = parts.getOrNull(1).orEmpty()

        val (lsDate, lsTime) = parseDateTimeLoose(left)
        val (rsDate, rsTime) = parseDateTimeLoose(right)

        val startDate = lsDate
        val startTime = lsTime

        // 우측 날짜 없고 시간만 있으면 same-day
        val endDate = rsDate ?: (if (rsTime != null) lsDate else null)
        val endTime = rsTime

        return PeriodFields(startDate, endDate, startTime, endTime)
    }

    private fun parseDateTimeLoose(x: String): Pair<String?, String?> {
        val t = x.trim()
        if (t.isBlank()) return null to null

        val dm = Regex("""(\d{4})\.(\d{2})\.(\d{2})""").find(t)
        val date = dm?.let { "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }

        val tm = Regex("""(\d{2}:\d{2})""").find(t)
        val time = tm?.groupValues?.get(1)

        return date to time
    }

    private data class MutableSession(
        val round: Int,
        var location: String? = null,
        var startDate: String? = null,
        var endDate: String? = null,
        var startTime: String? = null,
        var endTime: String? = null
    ) {
        fun toImmutable(): DetailSession =
            DetailSession(
                round = round,
                location = location,
                startDate = startDate,
                endDate = endDate,
                startTime = startTime,
                endTime = endTime
            )
    }

    fun crawlPage(pageNo: Int): List<ProgramEvent> {
        val html = fetchListPage(pageNo) ?: return emptyList()
        return parseListHtml(html)
    }

    fun uploadEventImages(
        events: List<ProgramEvent>,
        ociUploadService: OciUploadService,
    ): List<ProgramEvent> {
        val cookieHeader = buildCookieHeader()

        if (cookieHeader.isBlank()) {
            if (debug) println("[IMG] no playwright cookies; skip image upload")
            return events
        }

        return events.map { event ->
            val rawUrl = event.imageUrl?.trim()
            if (rawUrl.isNullOrBlank()) return@map event

            val downloaded = runCatching {
                downloadImage(rawUrl, cookieHeader)
            }.onFailure {
                if (debug) println("[IMG] download fail dataSeq=${event.dataSeq} msg=${it.message}")
            }.getOrNull()

            if (downloaded == null) {
                return@map event.copy(imageUrl = null)
            }

            val uploadedUrl = runCatching {
                ociUploadService.uploadBytesIfAbsent(
                    prefix = "events",
                    originalFilename = "event-${event.dataSeq ?: "unknown"}",
                    bytes = downloaded.bytes,
                    contentType = downloaded.contentType,
                )
            }.onFailure {
                if (debug) println("[IMG] upload fail dataSeq=${event.dataSeq} msg=${it.message}")
            }.getOrNull()

            if (uploadedUrl == null) {
                event.copy(imageUrl = null)
            } else {
                if (debug) println("[IMG] uploaded dataSeq=${event.dataSeq} -> $uploadedUrl")
                event.copy(imageUrl = uploadedUrl)
            }
        }
    }

    private fun buildCookieHeader(): String {
        return pwContext.cookies()
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    private fun downloadImage(rawUrl: String, cookieHeader: String): DownloadedImage? {
        val absoluteUrl = when {
            rawUrl.startsWith("http://") || rawUrl.startsWith("https://") -> rawUrl
            rawUrl.startsWith("/") -> "$baseUrl$rawUrl"
            else -> "$baseUrl/$rawUrl"
        }

        val req = Request.Builder()
            .url(absoluteUrl)
            .get()
            .header("Referer", "$baseUrl$listPath")
            .header("User-Agent", userAgent)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .header("Cookie", cookieHeader)
            .build()

        if (debug) println("[IMG] GET $absoluteUrl")

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                if (debug) println("[IMG] FAIL code=${resp.code} url=$absoluteUrl")
                return null
            }

            val body = resp.body ?: return null
            val bytes = body.bytes()
            if (bytes.isEmpty()) return null

            val contentType = body.contentType()?.toString() ?: "application/octet-stream"
            val extension = when (contentType.lowercase()) {
                "image/jpeg", "image/jpg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                "image/bmp" -> "bmp"
                else -> "bin"
            }

            return DownloadedImage(
                bytes = bytes,
                contentType = contentType,
                extension = extension,
            )
        }
    }

    private data class DownloadedImage(
        val bytes: ByteArray,
        val contentType: String,
        val extension: String,
    )
}
