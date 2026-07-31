package com.team1.hangsha.batch.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.team1.hangsha.batch.ai.EliceEventParserClient
import com.team1.hangsha.batch.crawler.DetailSession
import com.team1.hangsha.batch.crawler.ExtraSnuCrawler
import com.team1.hangsha.batch.crawler.ProgramEvent
import com.team1.hangsha.batch.crawler.SnuNowCrawler
import com.team1.hangsha.common.upload.OciUploadService
import com.team1.hangsha.config.DatabaseConfig
import com.team1.hangsha.config.OciConfig
import com.team1.hangsha.config.TestValueLogger
import com.team1.hangsha.event.dto.core.CrawledDetailSession
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import com.team1.hangsha.event.model.EventPeriodPolicy
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.event.service.EventSyncService
import com.team1.hangsha.search.outbox.EventSearchOutboxWriter
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.system.exitProcess

@Component
@ConditionalOnProperty(name = ["job"], havingValue = "extra-snu-sync", matchIfMissing = true)
class ExtraSnuSyncRunner(
    private val eventSyncServiceProvider: ObjectProvider<EventSyncService>,
    private val eventRepositoryProvider: ObjectProvider<EventRepository>,
    private val ociUploadService: OciUploadService,
    private val eliceEventParserClient: EliceEventParserClient,
    private val objectMapper: ObjectMapper,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val job = args.getOptionValues("job")?.firstOrNull()
        if (job != null && job != "extra-snu-sync") {
            return
        }

        val opt = BatchArgs.from(args)

        val applyChkCodes = listOf("0001", "0002", "0003", "0004")

        var totalUpserted = 0
        var totalCrawled = 0
        var totalSkipped = 0
        val dumpBuffer = mutableListOf<CrawledProgramEvent>()

        ExtraSnuCrawler(
            delayMsBetweenPages = opt.delayMs,
            delayMsBetweenDetails = opt.detailDelayMs,
            applyChkCodes = applyChkCodes
        ).use { crawler ->
            val endPage = opt.startPage + opt.maxPages - 1

            for (page in opt.startPage..endPage) {
                val baseEvents = crawler.crawlPage(page)

                if (baseEvents.isEmpty()) {
                    println("Page $page: no events, stopping.")
                    break
                }

                val syncTargets = filterNewExtraSnuEvents(baseEvents, dumpOnly = opt.dumpOnly)
                if (syncTargets.isEmpty()) {
                    println("Page $page: all ${baseEvents.size} events already exist, skipping details and sync.")
                    continue
                }

                val events = crawler.enrichDetails(
                    events = syncTargets,
                    ociUploadService = ociUploadService,
                    shouldUseDetailSessions = { e -> !e.isPeriodEventFromList() }
                )

                // dumpOnly 여부와 상관없이 이미지 업로드는 항상 수행한다.
                val eventsWithUploadedImages = crawler.uploadEventImages(events, ociUploadService)

                val crawledEvents = eventsWithUploadedImages.map { it.toCrawledProgramEvent() }
                if (opt.outFile != null) {
                    dumpBuffer += crawledEvents
                }

                val detailFilter = filterEventsWithParsedDetail(crawledEvents, source = "Extra SNU", page = page.toString())
                val syncEvents = detailFilter.events

                totalSkipped += detailFilter.skipped
                totalCrawled += crawledEvents.size
                if (opt.dumpOnly) {
                    println("Page $page crawled: total=${crawledEvents.size}")
                    continue
                }

                val result = eventSyncService().sync(syncEvents)
                totalUpserted += result.upserted
                totalSkipped += result.skipped

                println("Page $page synced: upserted=${result.upserted}, total=${result.total}, skipped=${result.skipped}")
            }
        }

        if (opt.withSnuNow) {
            val crawler = SnuNowCrawler(
                delayMsBetweenPages = opt.delayMs,
                delayMsBetweenDetails = opt.detailDelayMs,
            )
            val crawlOptions = SnuNowCrawler.CrawlOptions(
                startPage = opt.snuNowStartPage,
                maxPages = opt.snuNowMaxPages,
            )
            val seenBbsidx = linkedSetOf<String>()
            val endPage = opt.snuNowStartPage + opt.snuNowMaxPages - 1

            for (page in opt.snuNowStartPage..endPage) {
                val parsedPage = crawler.crawlPage(page, crawlOptions)
                if (parsedPage == null) {
                    println("SNU Now page $page: fetch failed, stopping.")
                    break
                }
                if (parsedPage.hasNoResultsMessage) {
                    println("SNU Now page $page: no-results message, stopping.")
                    break
                }
                if (parsedPage.items.isEmpty()) {
                    println("SNU Now page $page: no events, stopping.")
                    break
                }

                val pageItems = parsedPage.items.filter { seenBbsidx.add(it.bbsidx) }
                if (pageItems.isEmpty()) {
                    println("SNU Now page $page: duplicate-only page, stopping.")
                    break
                }

                val newItems = filterNewByApplyLink(
                    items = pageItems,
                    dumpOnly = opt.dumpOnly,
                    applyLinkOf = crawler::canonicalApplyLink,
                )
                totalSkipped += newItems.skipped
                if (newItems.items.isEmpty()) {
                    println("SNU Now page $page: all ${pageItems.size} events already exist, skipping details and sync.")
                    if (opt.delayMs > 0) Thread.sleep(opt.delayMs)
                    continue
                }

                val snuNowEvents = crawler.enrichDetails(
                    items = newItems.items,
                    referer = crawler.buildListUrl(page, crawlOptions),
                )
                val detailFilter = filterEventsWithParsedDetail(snuNowEvents, source = "SNU Now", page = page.toString())
                val detailEvents = detailFilter.events
                totalSkipped += detailFilter.skipped

                val parsedSnuNowEvents = if (opt.aiParser) {
                    enrichSnuNowEvents(detailEvents, opt.aiParserBatchSize)
                } else {
                    detailEvents
                }

                if (opt.outFile != null) {
                    dumpBuffer += parsedSnuNowEvents
                }

                totalCrawled += snuNowEvents.size
                if (opt.dumpOnly) {
                    println("SNU Now page $page crawled: total=${snuNowEvents.size}")
                    if (opt.delayMs > 0) Thread.sleep(opt.delayMs)
                    continue
                }

                val result = eventSyncService().sync(parsedSnuNowEvents)
                totalUpserted += result.upserted
                totalSkipped += result.skipped

                println("SNU Now page $page synced: upserted=${result.upserted}, total=${result.total}, skipped=${result.skipped}")
                if (opt.delayMs > 0) Thread.sleep(opt.delayMs)
            }
        }

        if (opt.outFile != null) {
            writeDumpFile(opt.outFile, dumpBuffer)
            println("Saved crawled events to ${opt.outFile} (count=${dumpBuffer.size})")
        }

        if (opt.dumpOnly) {
            println("Crawled $totalCrawled rows (dump-only mode)")
        } else {
            val openedRecruiting = eventSyncService().openStartedWaitingEvents()
            val closedExpired = eventSyncService().closeExpiredRecruitingEvents() // 행사 마감 처리

            println(
                "Synced $totalUpserted rows from $totalCrawled crawled events " +
                        "(skipped=$totalSkipped, openedRecruiting=$openedRecruiting, closedExpired=$closedExpired)"
            )
        }
        exitProcess(0)
    }

    private fun writeDumpFile(outFile: String, rows: List<CrawledProgramEvent>) {
        val path = Path.of(outFile).toAbsolutePath().normalize()
        path.parent?.let { Files.createDirectories(it) }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows)
    }

    private fun eventSyncService(): EventSyncService =
        eventSyncServiceProvider.getIfAvailable()
            ?: throw IllegalStateException("EventSyncService is unavailable. Remove --dumpOnly only when DB settings are configured.")

    private fun filterNewExtraSnuEvents(events: List<ProgramEvent>, dumpOnly: Boolean): List<ProgramEvent> {
        return filterNewByApplyLink(
            items = events,
            dumpOnly = dumpOnly,
            applyLinkOf = { it.extraSnuApplyLink() },
        ).items
    }

    private fun <T> filterNewByApplyLink(
        items: List<T>,
        dumpOnly: Boolean,
        applyLinkOf: (T) -> String?,
    ): ExistingFilterResult<T> {
        if (dumpOnly) {
            return ExistingFilterResult(items = items, skipped = 0)
        }
        val repository = eventRepositoryProvider.getIfAvailable()
            ?: return ExistingFilterResult(items = items, skipped = 0)
        val filtered = items.filter { item ->
            val applyLink = applyLinkOf(item) ?: return@filter true
            !repository.existsByApplyLink(applyLink)
        }
        return ExistingFilterResult(
            items = filtered,
            skipped = items.size - filtered.size,
        )
    }

    private fun enrichSnuNowEvents(
        events: List<CrawledProgramEvent>,
        batchSize: Int,
    ): List<CrawledProgramEvent> {
        return eliceEventParserClient.enrich(events, batchSize)
    }

    private fun filterEventsWithParsedDetail(
        events: List<CrawledProgramEvent>,
        source: String,
        page: String? = null,
    ): DetailFilterResult {
        val filtered = events.filter { !it.mainContentHtml.isNullOrBlank() }
        val skipped = events.size - filtered.size
        if (skipped > 0) {
            val pageText = page?.let { " page=$it" }.orEmpty()
            println("$source$pageText skipped empty detail events: $skipped")
        }
        return DetailFilterResult(filtered, skipped)
    }
}

private data class DetailFilterResult(
    val events: List<CrawledProgramEvent>,
    val skipped: Int,
)

private data class ExistingFilterResult<T>(
    val items: List<T>,
    val skipped: Int,
)

private data class BatchArgs(
    val startPage: Int = 1,
    val maxPages: Int = 4,
    val delayMs: Long = 200,
    val detailDelayMs: Long = 100,
    val outFile: String? = null,
    val dumpOnly: Boolean = false,
    val withSnuNow: Boolean = true,
    val snuNowStartPage: Int = 1,
    val snuNowMaxPages: Int = 4,
    val aiParser: Boolean = true,
    val aiParserBatchSize: Int = 1,
) {
    companion object {
        fun from(args: ApplicationArguments): BatchArgs {
            fun single(name: String): String? = args.getOptionValues(name)?.firstOrNull()

            return BatchArgs(
                startPage = single("startPage")?.toInt() ?: 1,
                maxPages = single("maxPages")?.toInt() ?: 4,
                delayMs = single("delayMs")?.toLong() ?: 200L,
                detailDelayMs = single("detailDelayMs")?.toLong() ?: 100L,
                outFile = single("outFile"),
                dumpOnly = args.containsOption("dumpOnly"),
                withSnuNow = !args.containsOption("noSnuNow") && !args.containsOption("noSnuCalendar"),
                snuNowStartPage = single("snuNowStartPage")?.toInt()
                    ?: single("snuCalendarStartPage")?.toInt()
                    ?: 1,
                snuNowMaxPages = single("snuNowMaxPages")?.toInt()
                    ?: single("snuCalendarMaxPages")?.toInt()
                    ?: 4,
                aiParser = !args.containsOption("noAiParser"),
                aiParserBatchSize = single("aiParserBatchSize")?.toInt()?.coerceAtLeast(1) ?: 1,
            )
        }
    }
}

private fun String?.toLocalDateOrNull(): LocalDate? =
    this?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun ProgramEvent.isPeriodEventFromList(): Boolean {
    val title = title?.trim().orEmpty()
    val eventStart = activityStart.toLocalDateOrNull()?.atStartOfDay()
    val eventEnd = activityEnd.toLocalDateOrNull()?.atTime(23, 59, 59)

    return EventPeriodPolicy.isPeriodEvent(
        title = title,
        eventStart = eventStart,
        eventEnd = eventEnd,
    )
}

private fun ProgramEvent.extraSnuApplyLink(): String? =
    dataSeq
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { "https://extra.snu.ac.kr/ptfol/pgm/view.do?dataSeq=$it" }

@Configuration
@ConditionalOnProperty(name = ["job"], havingValue = "extra-snu-sync", matchIfMissing = true)
@Import(
    TestValueLogger::class,
    OciConfig::class,
    OciUploadService::class,
)
class ExtraSnuSyncConfiguration

@Configuration
@ConditionalOnExpression("'\${job:extra-snu-sync}' == 'extra-snu-sync' && '\${dumpOnly:false}' == 'false'")
@Import(
    DatabaseConfig::class,
    EventSyncService::class,
    EventSearchOutboxWriter::class,
)
class ExtraSnuSyncDatabaseConfiguration

private fun ProgramEvent.toCrawledProgramEvent(): CrawledProgramEvent {
    val isPeriodEvent = isPeriodEventFromList()

    return CrawledProgramEvent(
        dataSeq = dataSeq,
        applyLink = dataSeq?.let { "https://extra.snu.ac.kr/ptfol/pgm/view.do?dataSeq=$it" },
        majorTypes = majorTypes,
        title = title,
        status = status,
        operationMode = operationMode,
        applyStart = applyStart,
        applyEnd = applyEnd,
        activityStart = activityStart,
        activityEnd = activityEnd,
        applyCount = applyCount,
        capacity = capacity,
        imageUrl = imageUrl,
        tags = tags,
        mainContentHtml = mainContentHtml,
        isPeriodEvent = isPeriodEvent,
        detailSessions = if (isPeriodEvent) {
            emptyList()
        } else {
            detailSessions.map { it.toCrawledDetailSession() }
        }
    )
}

private fun DetailSession.toCrawledDetailSession(): CrawledDetailSession =
    CrawledDetailSession(
        round = round,
        location = location,
        startDate = startDate,
        endDate = endDate,
        startTime = startTime,
        endTime = endTime
    )

private fun CrawledProgramEvent.parserKey(): String =
    dataSeq?.takeIf { it.isNotBlank() } ?: applyLink?.takeIf { it.isNotBlank() } ?: title.orEmpty()
