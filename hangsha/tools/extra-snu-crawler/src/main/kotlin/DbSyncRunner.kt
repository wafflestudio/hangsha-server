package org.example

import com.team1.hangsha.HangshaApplication
import com.team1.hangsha.event.dto.core.CrawledDetailSession
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import com.team1.hangsha.event.service.EventSyncService
import org.example.crawler.DetailSession
import org.example.crawler.ProgramEvent
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder

fun syncEventsToDb(events: List<ProgramEvent>): EventSyncService.SyncResult {
    val ctx = SpringApplicationBuilder(HangshaApplication::class.java)
        .web(WebApplicationType.NONE)
        .run()

    return ctx.use {
        val syncService = it.getBean(EventSyncService::class.java)
        syncService.sync(events.map { it.toCrawledProgramEvent() })
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