ALTER TABLE events
    ADD COLUMN is_period_event BOOLEAN NOT NULL DEFAULT FALSE AFTER event_end;

UPDATE events
SET is_period_event =
        CASE
            WHEN event_start IS NULL OR event_end IS NULL THEN TRUE
            WHEN title LIKE '%공모전%' THEN TRUE
            WHEN title LIKE '%인턴십%' THEN TRUE
            WHEN title LIKE '%학생기자단%' THEN TRUE
            WHEN event_end > DATE_ADD(event_start, INTERVAL 7 DAY) THEN TRUE
            ELSE FALSE
            END;