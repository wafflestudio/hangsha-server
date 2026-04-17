package com.team1.hangsha.batch.job

import com.fasterxml.jackson.databind.ObjectMapper
import com.team1.hangsha.batch.crawler.DetailSession
import com.team1.hangsha.batch.crawler.ExtraSnuCrawler
import com.team1.hangsha.batch.crawler.ProgramEvent
import com.team1.hangsha.common.upload.OciUploadService
import com.team1.hangsha.event.dto.core.CrawledDetailSession
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import com.team1.hangsha.event.service.EventSyncService
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

@Component
class ExtraSnuSyncRunner(
    private val eventSyncService: EventSyncService,
    private val ociUploadService: OciUploadService,
    private val objectMapper: ObjectMapper,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
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

                val events = if (!opt.withDetails) {
                    baseEvents
                } else {
                    crawler.enrichDetails(baseEvents, ociUploadService) // { e -> e.status != "모집마감" } // @TODO: 위의 0001, 0002, ... 와 같이 매직 넘버라, ENUM화?
                }

                val eventsWithUploadedImages = if (!opt.withDetails) {
                    events
                } else {
                    crawler.uploadEventImages(events, ociUploadService)
                }

                val crawledEvents = eventsWithUploadedImages.map { it.toCrawledProgramEvent() }
                if (opt.outFile != null) {
                    dumpBuffer += crawledEvents
                }

                totalCrawled += crawledEvents.size
                if (opt.dumpOnly) {
                    println("Page $page crawled: total=${crawledEvents.size}")
                    continue
                }

                val result = eventSyncService.sync(crawledEvents)
                totalUpserted += result.upserted
                totalSkipped += result.skipped

                println("Page $page synced: upserted=${result.upserted}, total=${result.total}, skipped=${result.skipped}")
            }
        }

        if (opt.outFile != null) {
            writeDumpFile(opt.outFile, dumpBuffer)
            println("Saved crawled events to ${opt.outFile} (count=${dumpBuffer.size})")
        }

        if (opt.dumpOnly) {
            println("Crawled $totalCrawled rows (dump-only mode)")
        } else {
            println("Synced $totalUpserted rows from $totalCrawled crawled events (skipped=$totalSkipped)")
        }
        exitProcess(0)
    }

    private fun writeDumpFile(outFile: String, rows: List<CrawledProgramEvent>) {
        val path = Path.of(outFile).toAbsolutePath().normalize()
        path.parent?.let { Files.createDirectories(it) }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), rows)
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
            )
        }
    }
}

private fun ProgramEvent.toCrawledProgramEvent(): CrawledProgramEvent =
    CrawledProgramEvent(
        dataSeq = dataSeq,
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
        detailSessions = detailSessions.map { it.toCrawledDetailSession() }
    )

private fun DetailSession.toCrawledDetailSession(): CrawledDetailSession =
    CrawledDetailSession(
        round = round,
        location = location,
        startDate = startDate,
        endDate = endDate,
        startTime = startTime,
        endTime = endTime
    )
