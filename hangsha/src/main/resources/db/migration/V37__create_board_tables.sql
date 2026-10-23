CREATE TABLE board_posts
(
    id                    BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id               BIGINT       NOT NULL,
    event_id              BIGINT NULL,
    title                 VARCHAR(100) NOT NULL,
    content               TEXT         NOT NULL,
    is_anonymous          BOOLEAN      NOT NULL DEFAULT FALSE,
    next_anonymous_number BIGINT       NOT NULL DEFAULT 1,
    created_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY                   idx_board_posts_created (created_at, id),
    KEY                   idx_board_posts_event_created (event_id, created_at, id),
    CONSTRAINT fk_board_posts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_board_posts_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE board_comments
(
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    post_id      BIGINT       NOT NULL,
    user_id      BIGINT       NOT NULL,
    parent_id    BIGINT NULL,
    content      TEXT         NOT NULL,
    is_anonymous BOOLEAN      NOT NULL DEFAULT FALSE,
    is_deleted   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY          idx_board_comments_post_parent_created (post_id, parent_id, created_at, id),
    CONSTRAINT fk_board_comments_post FOREIGN KEY (post_id) REFERENCES board_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_board_comments_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_board_comments_parent FOREIGN KEY (parent_id) REFERENCES board_comments (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE board_post_likes
(
    post_id    BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (post_id, user_id),
    KEY        idx_board_post_likes_user (user_id),
    CONSTRAINT fk_board_post_likes_post FOREIGN KEY (post_id) REFERENCES board_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_board_post_likes_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE board_comment_likes
(
    comment_id BIGINT       NOT NULL,
    user_id    BIGINT       NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (comment_id, user_id),
    KEY        idx_board_comment_likes_user (user_id),
    CONSTRAINT fk_board_comment_likes_comment FOREIGN KEY (comment_id) REFERENCES board_comments (id) ON DELETE CASCADE,
    CONSTRAINT fk_board_comment_likes_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE board_post_tags
(
    post_id  BIGINT      NOT NULL,
    tag_name VARCHAR(20) NOT NULL,
    PRIMARY KEY (post_id, tag_name),
    CONSTRAINT fk_board_post_tags_post FOREIGN KEY (post_id) REFERENCES board_posts (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE board_anonymous_users
(
    post_id          BIGINT NOT NULL,
    user_id          BIGINT NOT NULL,
    anonymous_number BIGINT NOT NULL,
    PRIMARY KEY (post_id, user_id),
    UNIQUE KEY uk_board_anonymous_number (post_id, anonymous_number),
    CONSTRAINT fk_board_anonymous_post FOREIGN KEY (post_id) REFERENCES board_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_board_anonymous_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
