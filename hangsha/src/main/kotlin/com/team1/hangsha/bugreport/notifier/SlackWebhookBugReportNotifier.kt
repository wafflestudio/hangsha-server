package com.team1.hangsha.bugreport.notifier

import com.team1.hangsha.bugreport.model.BugReport
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestTemplate

class SlackWebhookBugReportNotifier(
    // OCI Vault 동적 주입 키
    @Value("\${slack_webhook_uri:}") private val slackWebhookUri: String,
) : BugReportNotifier {
    private val log = LoggerFactory.getLogger(SlackWebhookBugReportNotifier::class.java)
    private val restTemplate = RestTemplate()

    override fun notify(report: BugReport, userAgent: String?) {
        if (slackWebhookUri.isBlank()) {
            log.warn("bug-report notify skipped: slack_webhook_uri is empty")
            return
        }

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }
        val payload = SlackWebhookPayload(
            text = buildMessage(report, userAgent)
        )

        restTemplate.postForEntity(
            slackWebhookUri,
            HttpEntity(payload, headers),
            String::class.java
        )
    }

    private fun buildMessage(report: BugReport, userAgent: String?): String {
        return buildString {
            appendLine("[작성자 id]")
            appendLine(report.userId?.toString() ?: "anonymous")

            appendLine("[작성 시각]")
            appendLine(report.createdAt?.toString() ?: "unknown")

            appendLine("[타이틀]")
            appendLine(truncate(report.title))

            appendLine("[컨텐츠]")
            appendLine(truncate(report.content))

            appendLine("[User-Agent Header]")
            append(truncate(userAgent?.takeIf { it.isNotBlank() } ?: "unknown"))
        }
    }

    private fun truncate(value: String): String =
        if (value.length > FIELD_LIMIT) value.take(FIELD_LIMIT) + TRUNCATION_MARK else value

    data class SlackWebhookPayload(
        val text: String,
    )

    companion object {
        private const val FIELD_LIMIT = 512
        private const val TRUNCATION_MARK = "…(생략)"
    }
}
