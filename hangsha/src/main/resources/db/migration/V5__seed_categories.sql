-- ===============================
-- category_groups seed
-- ===============================
INSERT INTO category_groups (name, sort_order) VALUES
('모집현황', 1),
('주체기관', 2),
('프로그램 유형', 3);

-- ===============================
-- 모집현황 categories seed
-- ===============================
INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, '모집대기', 1
FROM category_groups cg WHERE cg.name = '모집현황';

INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, '모집중', 2
FROM category_groups cg WHERE cg.name = '모집현황';

INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, '마감', 3
FROM category_groups cg WHERE cg.name = '모집현황';

-- ===============================
-- 프로그램 유형 categories seed
-- ===============================
INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, '교육(특강/세미나)', 1
FROM category_groups cg WHERE cg.name = '프로그램 유형';

INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, '공모전/경진대회', 2
FROM category_groups cg WHERE cg.name = '프로그램 유형';

INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, '현장학습/인턴', 3
FROM category_groups cg WHERE cg.name = '프로그램 유형';

INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, '사회공헌(봉사)', 4
FROM category_groups cg WHERE cg.name = '프로그램 유형';

INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, '학습/진로상담', 5
FROM category_groups cg WHERE cg.name = '프로그램 유형';

INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, '레크리에이션', 6
FROM category_groups cg WHERE cg.name = '프로그램 유형';

INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, 'OpenLnL', 6
FROM category_groups cg WHERE cg.name = '프로그램 유형';


INSERT INTO categories (group_id, name, sort_order)
SELECT cg.id, '기타', 999
FROM category_groups cg WHERE cg.name = '프로그램 유형';
