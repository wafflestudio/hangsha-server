package com.team1.hangsha.search

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
class ManticoreTableInitializer(
    @Value("\${manticore.base-url}") private val baseUrl: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client by lazy { RestClient.create(baseUrl) }

    @PostConstruct
    fun init() {
        val sql = """
            CREATE TABLE IF NOT EXISTS events_search(
                title TEXT,
                content TEXT
            ) type='rt'
        """.trimIndent()

        try {
            val encoded = URLEncoder.encode(sql, StandardCharsets.UTF_8)
            client.post()
                .uri("/sql?mode=raw")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("query=$encoded")
                .retrieve()
                .toBodilessEntity()
            log.info("Manticore events_search table initialized")
        } catch (e: Exception) {
            log.warn("Manticore table init failed: ${e.message}")
        }
    }
}
