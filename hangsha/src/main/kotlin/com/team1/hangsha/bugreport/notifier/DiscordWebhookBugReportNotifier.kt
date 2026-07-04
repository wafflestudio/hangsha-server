package com.team1.hangsha.bugreport.notifier

import com.fasterxml.jackson.annotation.JsonProperty
import com.team1.hangsha.bugreport.model.BugReport
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

@Component
class DiscordWebhookBugReportNotifier(
    // OCI Vault 동적 주입 키
    @Value("\${discord_webhook_uri:}") private val discordWebhookUri: String,
) : BugReportNotifier {
    private val log = LoggerFactory.getLogger(DiscordWebhookBugReportNotifier::class.java)
    private val restTemplate = RestTemplate()

    override fun notify(report: BugReport) {
        if (discordWebhookUri.isBlank()) {
            log.warn("bug-report notify skipped: discord_webhook_uri is empty")
            return
        }

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }

        buildMessages(report).forEach { message ->
            val payload = DiscordWebhookPayload(
                content = message,
                allowedMentions = DiscordAllowedMentions(parse = emptyList()),
            )

            restTemplate.postForEntity(
                discordWebhookUri,
                HttpEntity(payload, headers),
                String::class.java
            )
        }
    }

    private fun buildMessages(report: BugReport): List<String> {
        val header = buildString {

            appendLine("[작성자 id]")
            appendLine(report.userId?.toString() ?: "anonymous")
            appendLine()

            appendLine("[작성 시각]")
            appendLine(report.createdAt?.toString() ?: "unknown")
            appendLine()

            appendLine("[타이틀]")
            appendLine(report.title)
            appendLine()

            appendLine("[컨텐츠]")
        }
        val chunkSize = maxOf(1, DISCORD_CONTENT_LIMIT - header.length - PART_LABEL_LIMIT)
        val chunks = report.content.chunked(chunkSize)

        return chunks.mapIndexed { index, chunk ->
            buildString {
                append(header)
                if (chunks.size > 1) {
                    appendLine("(part ${index + 1}/${chunks.size})")
                    appendLine()
                }
                append(chunk)
            }
        }
    }

    data class DiscordWebhookPayload(
        val content: String,
        @get:JsonProperty("allowed_mentions")
        val allowedMentions: DiscordAllowedMentions,
    )

    data class DiscordAllowedMentions(
        val parse: List<String>,
    )

    companion object {
        private const val DISCORD_CONTENT_LIMIT = 2000
        private const val PART_LABEL_LIMIT = 32
    }
}
