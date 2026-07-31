package com.team1.hangsha.search

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "search.reindex",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SearchReindexScheduler(
    private val manticoreIndexService: ManticoreIndexService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        cron = "\${search.reindex.cron:0 0 */3 * * *}",
        zone = "\${search.reindex.zone:Asia/Seoul}",
    )
    fun reindex() {
        runCatching {
            log.info("Scheduled search reindex started")
            manticoreIndexService.reindexAll()
        }.onSuccess { result ->
            log.info("Scheduled search reindex completed: {}", result)
        }.onFailure { e ->
            log.error("Scheduled search reindex failed: {}", e.message, e)
        }
    }
}
