package com.team1.hangsha.search

import org.springframework.stereotype.Service

@Service
class ManticoreSearchService(
    private val manticoreClient: ManticoreClient,
    private val kiwiTokenizerClient: KiwiTokenizerClient,
) {
    private val indexName = "events_search"

    data class SearchResult(val eventIds: List<Long>, val total: Int)

    fun searchByTitle(query: String): SearchResult {
        val tokenized = kiwiTokenizerClient.tokenize(query)
        return doSearch(field = "title", tokenizedQuery = tokenized)
    }

    fun searchByContent(query: String): SearchResult {
        val tokenized = kiwiTokenizerClient.tokenize(query)
        return doSearch(field = "content", tokenizedQuery = tokenized)
    }

    @Suppress("UNCHECKED_CAST")
    private fun doSearch(field: String, tokenizedQuery: String): SearchResult {
        val requestBody = mapOf(
            "index" to indexName,
            "query" to mapOf("match" to mapOf(field to tokenizedQuery)),
            "limit" to 1000,
        )
        val response = manticoreClient.search(requestBody)
        val hits = response["hits"] as? Map<String, Any> ?: return SearchResult(emptyList(), 0)
        val total = (hits["total"] as? Number)?.toInt() ?: 0
        val hitList = hits["hits"] as? List<Map<String, Any>> ?: emptyList()
        val ids = hitList.mapNotNull { (it["_id"] as? Number)?.toLong() }
        return SearchResult(eventIds = ids, total = total)
    }
}
