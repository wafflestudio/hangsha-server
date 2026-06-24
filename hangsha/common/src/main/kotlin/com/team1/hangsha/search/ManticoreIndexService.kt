package com.team1.hangsha.search

import com.team1.hangsha.event.model.Event
import com.team1.hangsha.event.repository.EventRepository
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Service
class ManticoreIndexService(
    private val manticoreClient: ManticoreClient,
    private val kiwiTokenizerClient: KiwiTokenizerClient,
    private val eventRepository: EventRepository,
    @Value("\${manticore.base-url}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val indexName = "events_search"
    private val restClient by lazy { RestClient.create(baseUrl) }

    fun indexEvent(event: Event) {
        val tokenizedTitle = kiwiTokenizerClient.tokenize(event.title)
        val rawContent = event.mainContentHtml?.let { Jsoup.parse(it).text() } ?: ""
        val tokenizedContent = if (rawContent.isBlank()) "" else kiwiTokenizerClient.tokenize(rawContent)

        manticoreClient.upsert(
            index = indexName,
            id = requireNotNull(event.id) { "Event id must not be null" },
            doc = mapOf(
                "title" to tokenizedTitle,
                "content" to tokenizedContent,
            )
        )
    }

    fun deleteEvent(eventId: Long) {
        manticoreClient.delete(index = indexName, id = eventId)
    }

    fun reindexAll(): Map<String, Any> {
        val sql = "TRUNCATE TABLE $indexName"
        val encoded = URLEncoder.encode(sql, StandardCharsets.UTF_8)
        restClient.post()
            .uri("/sql?mode=raw")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("query=$encoded")
            .retrieve()
            .toBodilessEntity()
        log.info("Truncated $indexName for full reindex")

        var indexed = 0
        var failed = 0
        eventRepository.findAll()
            .filter { !it.adminDeleted }
            .forEach { event ->
                runCatching { indexEvent(event) }
                    .onSuccess { indexed++ }
                    .onFailure { e ->
                        failed++
                        log.error("Failed to index eventId={}: {}", event.id, e.message)
                    }
            }

        log.info("Reindex complete: indexed={}, failed={}", indexed, failed)
        return mapOf("ok" to true, "indexed" to indexed, "failed" to failed)
    }
}
