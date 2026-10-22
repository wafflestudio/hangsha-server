package com.team1.hangsha.batch.ai

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeminiEventParserClientTest {
    private val mapper = jacksonObjectMapper()
    private val event = CrawledProgramEvent(
        dataSeq = "test-event",
        title = "행사 안내",
        mainContentHtml = "<p>10월 20일 오후 1시~3시, 301동</p>",
    )

    @Test
    fun `sends structured Gemini request and preserves event merging after rate limit retry`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        server.createContext("/v1beta/openai/chat/completions") { exchange ->
            val payload = mapper.readTree(exchange.requestBody)
            assertEquals("POST", exchange.requestMethod)
            assertEquals("Bearer test-key", exchange.requestHeaders.getFirst("Authorization"))
            assertEquals("gemini-3.5-flash-lite", payload["model"].asText())
            assertEquals("json_schema", payload["response_format"]["type"].asText())
            assertEquals(4096, payload["max_completion_tokens"].asInt())
            val input = mapper.readTree(payload["messages"][1]["content"].asText())
            assertEquals("10월 20일 오후 1시~3시, 301동", input["events"][0]["mainContentText"].asText())
            requests++
            val result = mapOf("items" to listOf(mapOf(
                "id" to input["events"][0]["id"].asText(),
                "organization" to "학생지원팀",
                "category" to "교육(특강/세미나)",
                "location" to "301동",
                "eventStart" to "2026-10-20T13:00:00",
                "eventEnd" to "2026-10-20T15:00:00",
            )))
            val body = mapper.writeValueAsBytes(mapOf("choices" to listOf(mapOf(
                "message" to mapOf("content" to mapper.writeValueAsString(result)),
            ))))
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(if (requests == 1) 429 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            val client = client("http://127.0.0.1:${server.address.port}/v1beta/openai/")
            val parsed = client.enrich(listOf(event)).single()
            assertEquals(2, requests)
            assertEquals("301동", parsed.location)
            assertEquals(listOf("학생지원팀", "교육(특강/세미나)"), parsed.majorTypes)
            assertEquals("13:00", parsed.detailSessions.single().startTime)
            assertEquals("15:00", parsed.detailSessions.single().endTime)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `disabled parser and missing key return original events without network calls`() {
        val events = listOf(event)
        assertEquals(events, client("http://127.0.0.1:1", enabled = false).enrich(events))
        assertEquals(events, client("http://127.0.0.1:1", apiKey = "").enrich(events))
    }

    @Test
    fun `only production profile enables parser by default`() {
        val sources = YamlPropertySourceLoader().load("batch", ClassPathResource("application.yml"))
        val base = sources.single { it.getProperty("spring.config.activate.on-profile") == null }
        val prod = sources.single { it.getProperty("spring.config.activate.on-profile") == "prod" }
        assertFalse(base.getProperty("gemini.event-parser.enabled") as Boolean)
        assertTrue(prod.getProperty("gemini.event-parser.enabled") as Boolean)
    }

    private fun client(url: String, enabled: Boolean = true, apiKey: String = "test-key") =
        GeminiEventParserClient(mapper, enabled, url, "gemini-3.5-flash-lite", apiKey, 4096, "low", 0)
}
