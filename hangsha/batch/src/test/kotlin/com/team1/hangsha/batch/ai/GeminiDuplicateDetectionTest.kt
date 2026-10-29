package com.team1.hangsha.batch.ai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import com.team1.hangsha.event.dto.core.CrawledDetailSession
import com.team1.hangsha.event.dto.core.CrawledProgramEvent
import com.team1.hangsha.event.model.Event
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeminiDuplicateDetectionTest {
    private val mapper = jacksonObjectMapper()
    private val event = CrawledProgramEvent(
        dataSeq = "program", title = "연구 세미나", mainContentHtml = "<p>두 회차가 있는 세미나</p>",
        detailSessions = listOf(CrawledDetailSession(
            round = 2, startDate = "2026-10-21", endDate = "2026-10-21", startTime = "13:00", endTime = "14:00",
        )),
    )
    private val candidate = Event(
        id = 1, title = "연구 세미나", eventStart = LocalDateTime.parse("2026-10-21T13:00:00"),
    )

    @Test
    fun `accepts only unique high confidence decisions for candidates of the same session`() {
        withServer(respond = { _, _ ->
            200 to response(listOf(
                decision("a"), decision("b", confidence = "medium"), decision("c", candidateId = 999),
                decision("d"), decision("d"), decision("not-an-input"), decision("e", candidateId = null),
            ))
        }) { client, _ ->
            assertEquals(mapOf("a" to 1L), client.findHighConfidenceDuplicates(listOf("a", "b", "c", "d", "e").map(::group)))
        }
    }

    @Test
    fun `parsing and duplicate checking share request spacing and send the selected round`() {
        withServer(intervalMs = 250, respond = { payload, _ ->
            val input = mapper.readTree(payload["messages"][1]["content"].asText())
            if (input.has("events")) 200 to response(listOf(mapOf("id" to "program")))
            else 200 to response(listOf(decision(input["items"][0]["id"].asText())))
        }) { client, requests ->
            client.enrich(listOf(event))
            assertEquals(mapOf("round-2" to 1L), client.findHighConfidenceDuplicates(listOf(group("round-2"))))

            assertEquals(2, requests.size)
            val request = requests[1]
            assertEquals("/v1beta/openai/chat/completions", request.path)
            assertEquals("Bearer test-key", request.authorization)
            assertEquals("gemini-3.5-flash-lite", request.body["model"].asText())
            assertEquals("duplicate_events", request.body["response_format"]["json_schema"]["name"].asText())
            val item = mapper.readTree(request.body["messages"][1]["content"].asText())["items"][0]
            assertEquals(2, item["currentEvent"]["round"].asInt())
            assertEquals("2026-10-21T13:00", item["currentEvent"]["eventStart"].asText())
            assertTrue(TimeUnit.NANOSECONDS.toMillis(request.timeNanos - requests[0].timeNanos) >= 150)
        }
    }

    @Test
    fun `retries rate limit and unavailable responses before returning a duplicate`() {
        withServer(respond = { _, attempt ->
            when (attempt) {
                1 -> 429 to "rate limited"
                2 -> 503 to "unavailable"
                else -> 200 to response(listOf(decision("session")))
            }
        }) { client, requests ->
            assertEquals(mapOf("session" to 1L), client.findHighConfidenceDuplicates(listOf(group("session"))))
            assertEquals(3, requests.size)
        }
    }

    @Test
    fun `exhausted retries preserve the event instead of returning a duplicate`() {
        withServer(respond = { _, _ -> 503 to "unavailable" }) { client, requests ->
            assertTrue(client.findHighConfidenceDuplicates(listOf(group("session"))).isEmpty())
            assertEquals(3, requests.size)
        }
    }

    @Test
    fun `a malformed chunk does not discard valid decisions for later sessions`() {
        withServer(respond = { payload, attempt ->
            if (attempt == 1) 200 to "invalid JSON"
            else {
                val items = mapper.readTree(payload["messages"][1]["content"].asText())["items"]
                200 to response(items.map { decision(it["id"].asText()) })
            }
        }) { client, requests ->
            val groups = (0..8).map { group("session-$it") }
            assertEquals(mapOf("session-8" to 1L), client.findHighConfidenceDuplicates(groups))
            assertEquals(2, requests.size)
        }
    }

    @Test
    fun `disabled or unconfigured clients skip duplicate API calls`() {
        val groups = listOf(group("session"))
        assertTrue(client("http://127.0.0.1:1", enabled = false).findHighConfidenceDuplicates(groups).isEmpty())
        assertTrue(client("http://127.0.0.1:1", key = "").findHighConfidenceDuplicates(groups).isEmpty())
    }

    private fun group(id: String) = DuplicateCandidateGroup(id, event, listOf(candidate))

    private fun decision(id: String, candidateId: Long? = 1, confidence: String = "high") = mapOf(
        "id" to id, "duplicateOfEventId" to candidateId, "confidence" to confidence, "reason" to "test decision",
    )

    private fun response(items: List<Any?>): String {
        val content = mapper.writeValueAsString(mapOf("items" to items))
        return mapper.writeValueAsString(mapOf("choices" to listOf(mapOf("message" to mapOf("content" to content)))))
    }

    private fun withServer(
        intervalMs: Long = 0,
        respond: (JsonNode, Int) -> Pair<Int, String>,
        block: (GeminiEventParserClient, List<RecordedRequest>) -> Unit,
    ) {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val time = System.nanoTime()
            val payload = exchange.requestBody.use { mapper.readTree(it) }
            requests += RecordedRequest(time, payload, exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"))
            val (status, text) = respond(payload, requests.size)
            val bytes = text.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block(client("http://127.0.0.1:${server.address.port}/v1beta/openai", intervalMs = intervalMs), requests)
        } finally {
            server.stop(0)
        }
    }

    private fun client(url: String, enabled: Boolean = true, key: String = "test-key", intervalMs: Long = 0) =
        GeminiEventParserClient(mapper, enabled, url, "gemini-3.5-flash-lite", key, 4096, "low", intervalMs)

    private data class RecordedRequest(val timeNanos: Long, val body: JsonNode, val path: String, val authorization: String?)
}
