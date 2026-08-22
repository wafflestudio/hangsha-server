-- V32 copied the three domains without an ORDER BY.  MySQL therefore assigned
-- auto-increment IDs in an implementation-dependent order.  Rebuild each
-- domain in display order and remap all references before swapping the tables.

CREATE TABLE event_statuses_reordered (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_statuses_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE event_types_reordered (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_types_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE organizations_reordered (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_organizations_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO event_statuses_reordered (name, sort_order)
SELECT name, sort_order
FROM event_statuses
ORDER BY sort_order, id;

INSERT INTO event_types_reordered (name, sort_order)
SELECT name, sort_order
FROM event_types
ORDER BY sort_order, id;

INSERT INTO organizations_reordered (name, sort_order, created_at)
SELECT name, sort_order, created_at
FROM organizations
ORDER BY sort_order, id;

CREATE TABLE event_status_id_map AS
SELECT old.id AS old_id, reordered.id AS new_id
FROM event_statuses old
JOIN event_statuses_reordered reordered ON reordered.name = old.name;

CREATE TABLE event_type_id_map AS
SELECT old.id AS old_id, reordered.id AS new_id
FROM event_types old
JOIN event_types_reordered reordered ON reordered.name = old.name;

CREATE TABLE organization_id_map AS
SELECT old.id AS old_id, reordered.id AS new_id
FROM organizations old
JOIN organizations_reordered reordered ON reordered.name = old.name;

ALTER TABLE events
    DROP FOREIGN KEY fk_events_status,
    DROP FOREIGN KEY fk_events_event_type,
    DROP FOREIGN KEY fk_events_org;

ALTER TABLE user_interest_categories
    DROP FOREIGN KEY fk_uic_event_status,
    DROP FOREIGN KEY fk_uic_event_type,
    DROP FOREIGN KEY fk_uic_organization;

UPDATE events e
JOIN event_status_id_map m ON m.old_id = e.status_id
SET e.status_id = m.new_id;

UPDATE events e
JOIN event_type_id_map m ON m.old_id = e.event_type_id
SET e.event_type_id = m.new_id;

UPDATE events e
JOIN organization_id_map m ON m.old_id = e.org_id
SET e.org_id = m.new_id;

-- The per-user unique keys can collide while an ID permutation is applied.
-- Move referenced IDs outside the target range before assigning their new IDs.
UPDATE user_interest_categories
SET event_status_id = -event_status_id
WHERE event_status_id IS NOT NULL;

UPDATE user_interest_categories
SET event_type_id = -event_type_id
WHERE event_type_id IS NOT NULL;

UPDATE user_interest_categories
SET organization_id = -organization_id
WHERE organization_id IS NOT NULL;

UPDATE user_interest_categories uic
JOIN event_status_id_map m ON m.old_id = -uic.event_status_id
SET uic.event_status_id = m.new_id;

UPDATE user_interest_categories uic
JOIN event_type_id_map m ON m.old_id = -uic.event_type_id
SET uic.event_type_id = m.new_id;

UPDATE user_interest_categories uic
JOIN organization_id_map m ON m.old_id = -uic.organization_id
SET uic.organization_id = m.new_id;

DROP TABLE event_statuses, event_types, organizations;

RENAME TABLE
    event_statuses_reordered TO event_statuses,
    event_types_reordered TO event_types,
    organizations_reordered TO organizations;

ALTER TABLE events
    ADD CONSTRAINT fk_events_status FOREIGN KEY (status_id) REFERENCES event_statuses(id),
    ADD CONSTRAINT fk_events_event_type FOREIGN KEY (event_type_id) REFERENCES event_types(id),
    ADD CONSTRAINT fk_events_org FOREIGN KEY (org_id) REFERENCES organizations(id);

ALTER TABLE user_interest_categories
    ADD CONSTRAINT fk_uic_event_status FOREIGN KEY (event_status_id) REFERENCES event_statuses(id) ON DELETE RESTRICT ON UPDATE CASCADE,
    ADD CONSTRAINT fk_uic_event_type FOREIGN KEY (event_type_id) REFERENCES event_types(id) ON DELETE RESTRICT ON UPDATE CASCADE,
    ADD CONSTRAINT fk_uic_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT ON UPDATE CASCADE;

DROP TABLE event_status_id_map, event_type_id_map, organization_id_map;
