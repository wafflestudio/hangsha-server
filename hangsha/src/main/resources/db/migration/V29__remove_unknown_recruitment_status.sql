UPDATE events e
    JOIN categories unknown_status ON unknown_status.id = e.status_id
    JOIN category_groups status_group ON status_group.id = unknown_status.group_id
    JOIN categories next_status ON next_status.group_id = status_group.id
        AND next_status.name = CASE
            WHEN e.apply_end >= NOW()
                OR COALESCE(e.event_end, e.event_start) >= NOW()
                OR COALESCE(e.title, '') REGEXP '신청|지원'
                OR COALESCE(e.main_content_html, '') REGEXP '신청|지원'
                OR COALESCE(e.tags, '') REGEXP '신청|지원'
                THEN '모집중'
            ELSE '모집마감'
        END
SET e.status_id = next_status.id
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
