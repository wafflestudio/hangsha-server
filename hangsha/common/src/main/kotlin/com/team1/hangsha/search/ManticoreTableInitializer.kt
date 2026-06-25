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
        try {
            sqlRaw("DROP TABLE IF EXISTS events_search")
            sqlRaw("""
                CREATE TABLE events_search(
                    title_tokens   TEXT,
                    content_tokens TEXT,
                    title_raw      TEXT,
                    content_raw    TEXT
                ) min_infix_len='2' charset_table='non_cjk, U+AC00..U+D7AF, U+1100..U+11FF, U+3130..U+318F'
            """.trimIndent())
            log.info("Manticore events_search table (re)created with 4-field schema")
        } catch (e: Exception) {
            log.warn("Manticore table init failed: ${e.message}")
        }
    }

    private fun sqlRaw(sql: String) {
        val encoded = URLEncoder.encode(sql, StandardCharsets.UTF_8)
        client.post()
            .uri("/sql?mode=raw")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body("query=$encoded")
            .retrieve()
            .toBodilessEntity()
    }
}
