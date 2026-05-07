package com.team1.hangsha.bugreport.notifier

import com.team1.hangsha.bugreport.model.BugReport
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

@Component
class SlackWebhookBugReportNotifier(
    // OCI Vault 동적 주입 키
    @Value("\${slack_webhook_uri:}") private val slackWebhookUri: String,
) : BugReportNotifier {
    private val log = LoggerFactory.getLogger(SlackWebhookBugReportNotifier::class.java)
    private val restTemplate = RestTemplate()

    override fun notify(report: BugReport) {
        if (slackWebhookUri.isBlank()) {
            log.warn("bug-report notify skipped: slack_webhook_uri is empty")
            return
        }

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }
        val payload = SlackWebhookPayload(
            text = buildMessage(report)
        )

        restTemplate.postForEntity(
            slackWebhookUri,
            HttpEntity(payload, headers),
            String::class.java
        )
    }

    private fun buildMessage(report: BugReport): String {
        return buildString {
            appendLine("[작성자 id]")
            appendLine(report.userId?.toString() ?: "anonymous")

            appendLine("[작성 시각]")
            appendLine(report.createdAt?.toString() ?: "unknown")

            appendLine("[타이틀]")
            appendLine(report.title)

            appendLine("[컨텐츠]")
            append(report.content)
        }
    }

    data class SlackWebhookPayload(
        val text: String,
    )
}
