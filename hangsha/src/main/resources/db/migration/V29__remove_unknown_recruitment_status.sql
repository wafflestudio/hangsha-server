INSERT INTO event_search_outbox (event_id, operation, status)
SELECT e.id, 'DELETE', 'PENDING'
FROM events e
    JOIN categories unknown_status ON unknown_status.id = e.status_id
    JOIN category_groups status_group ON status_group.id = unknown_status.group_id
WHERE status_group.name = '모집현황'
  AND unknown_status.name = '상태 미제공'
  AND NOT EXISTS (
      SELECT 1
      FROM event_search_outbox eso
      WHERE eso.event_id = e.id
        AND eso.operation = 'DELETE'
        AND eso.status = 'PENDING'
  );

DELETE e
FROM events e
    JOIN categories unknown_status ON unknown_status.id = e.status_id
    JOIN category_groups status_group ON status_group.id = unknown_status.group_id
WHERE status_group.name = '모집현황'
  AND unknown_status.name = '상태 미제공';

DELETE uic
FROM user_interest_categories uic
    JOIN categories unknown_status ON unknown_status.id = uic.category_id
    JOIN category_groups status_group ON status_group.id = unknown_status.group_id
WHERE status_group.name = '모집현황'
  AND unknown_status.name = '상태 미제공';

DELETE unknown_status
FROM categories unknown_status
    JOIN category_groups status_group ON status_group.id = unknown_status.group_id
WHERE status_group.name = '모집현황'
  AND unknown_status.name = '상태 미제공';
