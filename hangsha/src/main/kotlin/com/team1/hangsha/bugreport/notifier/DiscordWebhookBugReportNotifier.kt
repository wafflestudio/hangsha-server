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

    override fun notify(report: BugReport, userAgent: String?) {
        if (discordWebhookUri.isBlank()) {
            log.warn("bug-report notify skipped: discord_webhook_uri is empty")
            return
        }

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }
        val payload = DiscordWebhookPayload(
            content = buildMessage(report, userAgent),
            allowedMentions = DiscordAllowedMentions(parse = emptyList()),
        )

        restTemplate.postForEntity(
            discordWebhookUri,
            HttpEntity(payload, headers),
            String::class.java
        )
    }

    // 각 필드를 FIELD_LIMIT으로 잘라 메시지 길이를 항상 Discord 한도 안에 묶는다.
    // 덕분에 리포트 1건은 웹훅 호출 1번으로 끝난다. 잘린 전문은 bug_reports 테이블에 그대로 남아 있다.
    private fun buildMessage(report: BugReport, userAgent: String?): String {
        val message = buildString {

            appendLine("[작성자 id]")
            appendLine(report.userId?.toString() ?: "anonymous")
            appendLine()

            appendLine("[작성 시각]")
            appendLine(report.createdAt?.toString() ?: "unknown")
            appendLine()

            appendLine("[타이틀]")
            appendLine(truncate(report.title))
            appendLine()

            appendLine("[컨텐츠]")
            appendLine(truncate(report.content))
            appendLine()

            appendLine("[User-Agent Header]")
            append(truncate(userAgent?.takeIf { it.isNotBlank() } ?: "unknown"))
        }

        // 작성자 id·작성 시각까지 더해도 한도에 한참 못 미치지만, 웹훅이 400으로 떨어지는 일만은 없게 마지막 안전장치를 둔다.
        return message.take(DISCORD_CONTENT_LIMIT)
    }

    private fun truncate(value: String): String =
        if (value.length > FIELD_LIMIT) value.take(FIELD_LIMIT) + TRUNCATION_MARK else value

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
        private const val FIELD_LIMIT = 512
        private const val TRUNCATION_MARK = "…(생략)"
    }
}
