CREATE TABLE event_search_outbox (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    event_id     BIGINT NOT NULL,
    operation    ENUM('UPSERT', 'DELETE') NOT NULL,
    status       ENUM('PENDING', 'DONE', 'FAILED') NOT NULL DEFAULT 'PENDING',
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    processed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (id),
    KEY idx_event_search_outbox_status_id (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
