package com.team1.hangsha.batch.job

import com.team1.hangsha.event.dto.core.CrawledDetailSession
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import com.team1.hangsha.event.service.EventSyncService
import com.team1.hangsha.batch.crawler.DetailSession
import com.team1.hangsha.batch.crawler.ExtraSnuCrawler
import com.team1.hangsha.batch.crawler.ProgramEvent
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.io.File
import kotlin.system.exitProcess

@Component
class ExtraSnuSyncRunner(
    private val eventSyncService: EventSyncService,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        val opt = BatchArgs.from(args)

        val applyChkCodes = listOf("0001", "0002", "0003", "0004")

        ExtraSnuCrawler(
            delayMsBetweenPages = opt.delayMs,
            delayMsBetweenDetails = opt.detailDelayMs,
            applyChkCodes = applyChkCodes
        ).use { crawler ->
            val baseEvents = when (opt.fromFile) {
                null -> crawler.crawlAll(startPage = opt.startPage, maxPages = opt.maxPages)
                else -> {
                    val html = File(opt.fromFile).readText(Charsets.UTF_8)
                    crawler.parseListHtml(html)
                }
            }

            val events = if (!opt.withDetails) {
                baseEvents
            } else {
                crawler.enrichDetails(baseEvents) { e -> e.status != "모집마감" } // @TODO: raw string, 0001, 0002 ... --> ENUM에 넣기?
            }

            val result = eventSyncService.sync(events.map { it.toCrawledProgramEvent() })
            println("Synced ${result.upserted} rows from ${result.total} crawled events (skipped=${result.skipped})")
        }

        exitProcess(0)
    }
}

private data class BatchArgs(
    val startPage: Int = 1,
    val maxPages: Int = 3,
    val delayMs: Long = 200,
    val withDetails: Boolean = true,
    val detailDelayMs: Long = 100,
    val fromFile: String? = null
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
                maxPages = single("maxPages")?.toInt() ?: 500,
                delayMs = single("delayMs")?.toLong() ?: 200L,
                withDetails = withDetails,
                detailDelayMs = single("detailDelayMs")?.toLong() ?: 100L,
                fromFile = single("fromFile")
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