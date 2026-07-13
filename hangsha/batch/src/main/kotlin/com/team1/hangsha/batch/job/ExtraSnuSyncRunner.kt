package com.team1.hangsha.batch.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.team1.hangsha.batch.ai.EliceEventParserClient
import com.team1.hangsha.batch.crawler.DetailSession
import com.team1.hangsha.batch.crawler.ExtraSnuCrawler
import com.team1.hangsha.batch.crawler.ProgramEvent
import com.team1.hangsha.batch.crawler.SnuCalendarCrawler
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

                val syncTargets = filterNewExtraSnuEvents(baseEvents)
                if (syncTargets.isEmpty()) {
                    println("Page $page: all ${baseEvents.size} events already exist, skipping details and sync.")
                    continue
                }

                val events = if (!opt.withDetails) {
                    syncTargets
                } else {
                    crawler.enrichDetails(
                        events = syncTargets,
                        ociUploadService = ociUploadService,
                        shouldUseDetailSessions = { e -> !e.isPeriodEventFromList() }
                    )
                }

                // dumpOnly 여부와 상관없이 이미지 업로드는 항상 수행한다.
                val eventsWithUploadedImages = crawler.uploadEventImages(events, ociUploadService)

                val crawledEvents = eventsWithUploadedImages.map { it.toCrawledProgramEvent() }
                if (opt.outFile != null) {
                    dumpBuffer += crawledEvents
                }

                totalCrawled += crawledEvents.size
                if (opt.dumpOnly) {
                    println("Page $page crawled: total=${crawledEvents.size}")
                    continue
                }

                val result = eventSyncService().sync(crawledEvents)
                totalUpserted += result.upserted
                totalSkipped += result.skipped

                println("Page $page synced: upserted=${result.upserted}, total=${result.total}, skipped=${result.skipped}")
            }
        }

        if (opt.withSnuCalendar) {
            val snuCalendarEvents = SnuCalendarCrawler(
                delayMsBetweenPages = opt.delayMs,
                delayMsBetweenDetails = opt.detailDelayMs,
            ).crawl(
                SnuCalendarCrawler.CrawlOptions(
                    startPage = opt.snuCalendarStartPage,
                    maxPages = opt.snuCalendarMaxPages,
                )
            )

            val parsedSnuCalendarEvents = if (opt.aiParser) {
                enrichNewSnunowEvents(snuCalendarEvents, opt.aiParserBatchSize)
            } else {
                snuCalendarEvents
            }

            if (opt.outFile != null) {
                dumpBuffer += parsedSnuCalendarEvents
            }

            totalCrawled += parsedSnuCalendarEvents.size
            if (opt.dumpOnly) {
                println("SNU calendar crawled: total=${parsedSnuCalendarEvents.size}")
            } else {
                val result = eventSyncService().sync(parsedSnuCalendarEvents)
                totalUpserted += result.upserted
                totalSkipped += result.skipped

                println(
                    "SNU calendar synced: upserted=${result.upserted}, " +
                            "total=${result.total}, skipped=${result.skipped}"
                )
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

    private fun filterNewExtraSnuEvents(events: List<ProgramEvent>): List<ProgramEvent> {
        val repository = eventRepositoryProvider.getIfAvailable() ?: return events
        return events.filter { event ->
            val applyLink = event.extraSnuApplyLink() ?: return@filter true
            !repository.existsByApplyLink(applyLink)
        }
    }

    private fun enrichNewSnunowEvents(
        events: List<CrawledProgramEvent>,
        batchSize: Int,
    ): List<CrawledProgramEvent> {
        val repository = eventRepositoryProvider.getIfAvailable()
        val parserTargets = events.filter { event ->
            val applyLink = event.applyLink?.trim().orEmpty()
            if (!applyLink.isSnunowEventLink()) {
                false
            } else if (repository == null) {
                true
            } else {
                !repository.existsByApplyLink(applyLink)
            }
        }

        if (parserTargets.isEmpty()) return events

        val parsedByKey = eliceEventParserClient.enrich(parserTargets, batchSize)
            .associateBy { it.parserKey() }

        return events.map { parsedByKey[it.parserKey()] ?: it }
    }
}

private data class BatchArgs(
    val startPage: Int = 1,
    val maxPages: Int = 4,
    val delayMs: Long = 200,
    val withDetails: Boolean = true,
    val detailDelayMs: Long = 100,
    val outFile: String? = null,
    val dumpOnly: Boolean = false,
    val withSnuCalendar: Boolean = true,
    val snuCalendarStartPage: Int = 1,
    val snuCalendarMaxPages: Int = 4,
    val aiParser: Boolean = true,
    val aiParserBatchSize: Int = 1,
) {
    companion object {
        fun from(args: ApplicationArguments): BatchArgs {
            fun single(name: String): String? = args.getOptionValues(name)?.firstOrNull()

            val withDetails = when {
                args.containsOption("noDetails") -> false
                args.containsOption("withDetails") -> true
                else -> true
            }

            return BatchArgs(
                startPage = single("startPage")?.toInt() ?: 1,
                maxPages = single("maxPages")?.toInt() ?: 4,
                delayMs = single("delayMs")?.toLong() ?: 200L,
                withDetails = withDetails,
                detailDelayMs = single("detailDelayMs")?.toLong() ?: 100L,
                outFile = single("outFile"),
                dumpOnly = args.containsOption("dumpOnly"),
                withSnuCalendar = !args.containsOption("noSnuCalendar"),
                snuCalendarStartPage = single("snuCalendarStartPage")?.toInt() ?: 1,
                snuCalendarMaxPages = single("snuCalendarMaxPages")?.toInt() ?: 4,
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

private fun String.isSnunowEventLink(): Boolean =
    startsWith("https://www.snu.ac.kr/snunow/events")

private fun CrawledProgramEvent.parserKey(): String =
    dataSeq?.takeIf { it.isNotBlank() } ?: applyLink?.takeIf { it.isNotBlank() } ?: title.orEmpty()
