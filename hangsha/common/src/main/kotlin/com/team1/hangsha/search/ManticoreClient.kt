package com.team1.hangsha.search

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class ManticoreClient(
    @Value("\${manticore.base-url}") private val baseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val client by lazy { RestClient.create(baseUrl) }

    @Suppress("UNCHECKED_CAST")
    fun upsert(index: String, id: Long, doc: Map<String, Any>): Map<String, Any> {
        // index : table이름, id : 게시글 identifier, doc : 실제 게시글 데이터(json 형태.)
        val body = mapOf("index" to index, "id" to id, "doc" to doc)
        return client.post()
            .uri("/json/replace")
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
            .retrieve()
            .body(Map::class.java)!! as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    fun delete(index: String, id: Long): Map<String, Any> {
        val body = mapOf("index" to index, "id" to id)
        return client.post()
            .uri("/json/delete")
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
            .retrieve()
            .body(Map::class.java)!! as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    fun search(requestBody: Map<String, Any>): Map<String, Any> {
        return client.post()
            .uri("/json/search")
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(requestBody))
            .retrieve()
            .body(Map::class.java)!! as Map<String, Any>
    }
}
