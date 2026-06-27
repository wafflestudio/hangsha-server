package com.team1.hangsha.search

import org.springframework.stereotype.Service

/**
 * manticoreClient, kiwiTokenizerClient를 이용하여 searchService를 구현한다.
 * EventService의 searchTitle, searchContent, search에서 이를 사용한다.
 */
@Service
class ManticoreSearchService(
    private val manticoreClient: ManticoreClient,
    private val kiwiTokenizerClient: KiwiTokenizerClient,
) {
    private val indexName = "events_search"

    data class SearchResult(
        val eventIds: List<Long>,
        val total: Int,
        val rawWords: List<String>,    // 공백 구분 원본 단어 (highlight primary)
        val kiwiTokens: List<String>,  // KiWi 명사 토큰 (highlight fallback)
    )

    fun searchByTitle(query: String): SearchResult {
        val tokenized = kiwiTokenizerClient.tokenize(query)
        return doSearch(fields = listOf("title"), tokenizedQuery = tokenized, rawQuery = query)
    }

    fun searchByContent(query: String): SearchResult {
        val tokenized = kiwiTokenizerClient.tokenize(query)
        return doSearch(fields = listOf("content"), tokenizedQuery = tokenized, rawQuery = query)
    }

    fun searchUnified(query: String): SearchResult {
        val tokenized = kiwiTokenizerClient.tokenize(query)
        return doSearch(fields = listOf("title", "content"), tokenizedQuery = tokenized, rawQuery = query)
    }

    @Suppress("UNCHECKED_CAST")
    private fun doSearch(
        fields: List<String>,
        tokenizedQuery: String,
        rawQuery: String,
    ): SearchResult {
        val rawWords = rawQuery.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        val kiwiTokens = tokenizedQuery.split(" ").filter { it.isNotBlank() }

        val infixQuery = rawWords.joinToString(" ") { "*$it*" }

        val shouldClauses = buildList {
            for (field in fields) {
                if (tokenizedQuery.isNotBlank()) {
                    add(mapOf("match" to mapOf(
                        "${field}_tokens" to mapOf("query" to tokenizedQuery, "operator" to "and")
                    )))
                }
                add(mapOf("match" to mapOf(
                    "${field}_raw" to mapOf("query" to infixQuery, "operator" to "and")
                )))
            }
        }

        val requestBody = mapOf(
            "index" to indexName,
            "query" to mapOf("bool" to mapOf("should" to shouldClauses)),
            "limit" to 1000,
        )

        val response = manticoreClient.search(requestBody)
        val hits = response["hits"] as? Map<String, Any>
            ?: return SearchResult(emptyList(), 0, rawWords, kiwiTokens)
        val total = (hits["total"] as? Number)?.toInt() ?: 0
        val hitList = hits["hits"] as? List<Map<String, Any>> ?: emptyList()
        val ids = hitList.mapNotNull { (it["_id"] as? Number)?.toLong() }
        return SearchResult(eventIds = ids, total = total, rawWords = rawWords, kiwiTokens = kiwiTokens)
    }
}
