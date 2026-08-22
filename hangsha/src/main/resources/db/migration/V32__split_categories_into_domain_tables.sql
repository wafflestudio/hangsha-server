-- Categories have three unrelated domains.  Keep the old tables for one
-- release so a rollback remains possible, but move every live reference to
-- the domain table that owns it.
CREATE TABLE event_statuses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_statuses_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE event_types (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_types_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE organizations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_organizations_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO event_statuses (name, sort_order)
SELECT c.name, c.sort_order
FROM categories c
JOIN category_groups cg ON cg.id = c.group_id
WHERE cg.name = '모집현황';

INSERT INTO event_types (name, sort_order)
SELECT c.name, c.sort_order
FROM categories c
JOIN category_groups cg ON cg.id = c.group_id
WHERE cg.name = '프로그램 유형';

INSERT INTO organizations (name, sort_order, created_at)
SELECT c.name, c.sort_order, c.created_at
FROM categories c
JOIN category_groups cg ON cg.id = c.group_id
WHERE cg.name = '주체기관';

-- Each domain gets its own interest relation, so every column has a real FK.
CREATE TABLE user_interest_event_statuses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    event_status_id BIGINT NOT NULL,
    priority INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_uies_user_status (user_id, event_status_id),
    KEY idx_uies_user_priority (user_id, priority),
    CONSTRAINT fk_uies_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_uies_status FOREIGN KEY (event_status_id) REFERENCES event_statuses(id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_interest_event_types (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    event_type_id BIGINT NOT NULL,
    priority INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_uiet_user_type (user_id, event_type_id),
    KEY idx_uiet_user_priority (user_id, priority),
    CONSTRAINT fk_uiet_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_uiet_type FOREIGN KEY (event_type_id) REFERENCES event_types(id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_interest_organizations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    organization_id BIGINT NOT NULL,
    priority INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_uio_user_organization (user_id, organization_id),
    KEY idx_uio_user_priority (user_id, priority),
    CONSTRAINT fk_uio_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_uio_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO user_interest_event_statuses (user_id, event_status_id, priority, created_at)
SELECT uic.user_id, es.id, uic.priority, uic.created_at
FROM user_interest_categories uic
JOIN categories c ON c.id = uic.category_id
JOIN category_groups cg ON cg.id = c.group_id AND cg.name = '모집현황'
JOIN event_statuses es ON es.name = c.name;

INSERT INTO user_interest_event_types (user_id, event_type_id, priority, created_at)
SELECT uic.user_id, et.id, uic.priority, uic.created_at
FROM user_interest_categories uic
JOIN categories c ON c.id = uic.category_id
JOIN category_groups cg ON cg.id = c.group_id AND cg.name = '프로그램 유형'
JOIN event_types et ON et.name = c.name;

INSERT INTO user_interest_organizations (user_id, organization_id, priority, created_at)
SELECT uic.user_id, o.id, uic.priority, uic.created_at
FROM user_interest_categories uic
JOIN categories c ON c.id = uic.category_id
JOIN category_groups cg ON cg.id = c.group_id AND cg.name = '주체기관'
JOIN organizations o ON o.name = c.name;

ALTER TABLE events
    DROP FOREIGN KEY fk_events_status,
    DROP FOREIGN KEY fk_events_event_type,
    DROP FOREIGN KEY fk_events_org;

UPDATE events e
JOIN categories c ON c.id = e.status_id
JOIN category_groups cg ON cg.id = c.group_id AND cg.name = '모집현황'
JOIN event_statuses es ON es.name = c.name
SET e.status_id = es.id;

UPDATE events e
JOIN categories c ON c.id = e.event_type_id
JOIN category_groups cg ON cg.id = c.group_id AND cg.name = '프로그램 유형'
JOIN event_types et ON et.name = c.name
SET e.event_type_id = et.id;

UPDATE events e
JOIN categories c ON c.id = e.org_id
JOIN category_groups cg ON cg.id = c.group_id AND cg.name = '주체기관'
JOIN organizations o ON o.name = c.name
SET e.org_id = o.id;

ALTER TABLE events
    ADD CONSTRAINT fk_events_status FOREIGN KEY (status_id) REFERENCES event_statuses(id),
    ADD CONSTRAINT fk_events_event_type FOREIGN KEY (event_type_id) REFERENCES event_types(id),
    ADD CONSTRAINT fk_events_org FOREIGN KEY (org_id) REFERENCES organizations(id);
