INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, '상태 미제공', 999
FROM category_groups cg
WHERE cg.name = '모집현황'
  AND NOT EXISTS (
      SELECT 1
      FROM categories c
      WHERE c.group_id = cg.id
        AND c.name = '상태 미제공'
  );
