DELETE uic
FROM user_interest_categories uic
    JOIN categories c ON c.id = uic.category_id
    JOIN category_groups cg ON cg.id = c.group_id
WHERE cg.name = '주체기관'
  AND c.name IN ('SNU 캘린더', 'SNU캘린더');

UPDATE events e
    JOIN categories snu_org ON snu_org.id = e.org_id
    JOIN category_groups org_group ON org_group.id = snu_org.group_id
SET e.org_id = NULL,
    e.organization = CASE
        WHEN e.organization IN ('SNU 캘린더', 'SNU캘린더') THEN NULL
        ELSE e.organization
    END
WHERE org_group.name = '주체기관'
  AND snu_org.name IN ('SNU 캘린더', 'SNU캘린더');

DELETE c
FROM categories c
    JOIN category_groups cg ON cg.id = c.group_id
WHERE cg.name = '주체기관'
  AND c.name IN ('SNU 캘린더', 'SNU캘린더');
