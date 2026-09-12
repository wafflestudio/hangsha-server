package com.team1.hangsha.discord

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Claims a Discord interaction ID before a side effect is performed. */
@Component
class DiscordInteractionDeduplicator(private val jdbcTemplate: JdbcTemplate) {
    fun claim(interactionId: String): Boolean {
        if (interactionId.isBlank()) return false
        jdbcTemplate.update(
            "DELETE FROM discord_processed_interactions WHERE processed_at < DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY)",
        )
        return jdbcTemplate.update(
            "INSERT IGNORE INTO discord_processed_interactions (interaction_id) VALUES (?)",
            interactionId,
        ) == 1
    }
}
