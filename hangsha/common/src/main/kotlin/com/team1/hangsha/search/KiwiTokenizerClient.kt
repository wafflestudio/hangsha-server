package com.team1.hangsha.search

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class KiwiTokenizerClient(
    @Value("\${kiwi.base-url}") private val baseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val client by lazy { RestClient.create(baseUrl) }
    private val log = LoggerFactory.getLogger(javaClass)

    @Suppress("UNCHECKED_CAST")
    fun tokenize(text: String): String {
        if (text.isBlank()) return ""
        return try {
            val body = mapOf("text" to text)
            val response = client.post()
                .uri("/tokenize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(objectMapper.writeValueAsString(body))
                .retrieve()
                .body(Map::class.java)!!
            (response as Map<String, Any>)["tokens"] as? String ?: text
        } catch (e: Exception) {
            log.warn("kiwi service unavailable, falling back to raw text: ${e.message}")
            text
        }
    }
}
