ALTER TABLE users
    ADD COLUMN is_admin TINYINT(1) NOT NULL DEFAULT 0 AFTER profile_image_url;

UPDATE users
SET is_admin = 1
WHERE email = 'admin@hangsha.local';
