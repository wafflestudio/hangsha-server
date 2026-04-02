-- 프로그램 유형: OpenLnL 카테고리 추가
INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, 'OpenLnL', 6
FROM category_groups cg
WHERE cg.name = '프로그램 유형'
  AND NOT EXISTS (
    SELECT 1
    FROM categories c
    WHERE c.group_id = cg.id
      AND c.name = 'OpenLnL'
);

-- 모집현황: 마감임박 -> 모집중 통합
UPDATE events e
    JOIN categories imminent ON imminent.id = e.status_id
    JOIN category_groups cg ON cg.id = imminent.group_id
    JOIN categories recruiting ON recruiting.group_id = imminent.group_id AND recruiting.name = '모집중'
    SET e.status_id = recruiting.id
WHERE cg.name = '모집현황'
  AND imminent.name = '마감임박';