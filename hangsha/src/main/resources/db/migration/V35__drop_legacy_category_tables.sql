-- V32 moved all live category references to the domain-specific tables.
-- The legacy tables have remained for one release as a rollback window;
-- remove them only after the new schema is running on main.
DROP TABLE legacy_user_interest_categories;
DROP TABLE categories;
DROP TABLE category_groups;
