ALTER TABLE events
    ADD COLUMN admin_overridden_fields JSON NULL AFTER is_period_event,
    ADD COLUMN admin_deleted BOOLEAN NOT NULL DEFAULT FALSE AFTER admin_overridden_fields;

CREATE INDEX idx_events_admin_deleted ON events(admin_deleted);
CREATE INDEX idx_events_apply_link_admin_deleted ON events(apply_link(255), admin_deleted);