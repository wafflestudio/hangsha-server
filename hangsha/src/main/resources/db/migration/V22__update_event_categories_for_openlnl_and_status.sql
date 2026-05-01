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
