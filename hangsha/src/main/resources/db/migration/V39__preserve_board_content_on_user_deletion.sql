ALTER TABLE board_posts
DROP
FOREIGN KEY fk_board_posts_user,
    MODIFY COLUMN user_id BIGINT NULL,
    ADD CONSTRAINT fk_board_posts_author FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE
SET NULL;

ALTER TABLE board_comments
DROP
FOREIGN KEY fk_board_comments_user,
    MODIFY COLUMN user_id BIGINT NULL,
    ADD CONSTRAINT fk_board_comments_author FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE
SET NULL;

ALTER TABLE board_post_likes
DROP
FOREIGN KEY fk_board_post_likes_user,
    ADD CONSTRAINT fk_board_post_likes_voter FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE
CASCADE;

ALTER TABLE board_comment_likes
DROP
FOREIGN KEY fk_board_comment_likes_user,
    ADD CONSTRAINT fk_board_comment_likes_voter FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE
CASCADE;

ALTER TABLE board_anonymous_users
DROP
FOREIGN KEY fk_board_anonymous_user,
    ADD CONSTRAINT fk_board_anonymous_participant FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE
CASCADE;
