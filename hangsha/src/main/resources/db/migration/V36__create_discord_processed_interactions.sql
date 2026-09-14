CREATE TABLE discord_processed_interactions (
    interaction_id VARCHAR(32) NOT NULL,
    processed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (interaction_id),
    KEY idx_discord_processed_interactions_processed_at (processed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
