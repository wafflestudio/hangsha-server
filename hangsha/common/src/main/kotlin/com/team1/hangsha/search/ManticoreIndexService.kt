package com.team1.hangsha.search

import com.team1.hangsha.event.model.Event
import org.jsoup.Jsoup
import org.springframework.stereotype.Service

@Service
class ManticoreIndexService(
    private val manticoreClient: ManticoreClient,
    private val kiwiTokenizerClient: KiwiTokenizerClient,
) {
    private val indexName = "events_search"

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
}
