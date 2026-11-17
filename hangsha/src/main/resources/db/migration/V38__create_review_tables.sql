CREATE TABLE IF NOT EXISTS reviews (
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    event_id     BIGINT NOT NULL,
    rating       TINYINT NOT NULL,
    content      VARCHAR(500) NOT NULL,
    is_anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    -- 소프트 삭제. 조회 쿼리마다 is_deleted = FALSE 를 빠뜨리지 말 것.
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    -- 수정 여부. updated_at 으로는 판단 불가 — 소프트 삭제도 UPDATE 라서 같이 갱신된다.
    is_edited    BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                 ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_reviews_user_id  FOREIGN KEY (user_id)  REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_reviews_event_id FOREIGN KEY (event_id) REFERENCES events(id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5),

    KEY idx_reviews_event_created (event_id, created_at),
    KEY idx_reviews_user_created  (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS review_comments (
    id           BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    review_id    BIGINT NOT NULL,
    -- NULL이면 최상위 댓글, 값이 있으면 그 댓글의 답글.
    -- 깊이 1단 제한은 서비스에서 건다 (parent가 이미 parent_id를 가지면 거부).
    parent_id    BIGINT NULL,
    user_id      BIGINT NOT NULL,
    content      VARCHAR(500) NOT NULL,
    is_anonymous BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    is_edited    BOOLEAN NOT NULL DEFAULT FALSE,

    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
                 ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_rc_review_id FOREIGN KEY (review_id) REFERENCES reviews(id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_rc_user_id   FOREIGN KEY (user_id)   REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_rc_parent_id FOREIGN KEY (parent_id) REFERENCES review_comments(id)
        ON DELETE CASCADE ON UPDATE CASCADE,

    KEY idx_rc_review_created (review_id, parent_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- 그 행사에 처음 익명으로 쓸 때 번호를 발급해 저장한다. 이후 그 행사의 모든
-- 익명 글이 이 번호를 재사용하므로, 앞선 글이 지워져도 번호가 밀리지 않는다.
CREATE TABLE IF NOT EXISTS review_anon_aliases (
    event_id BIGINT NOT NULL,
    user_id  BIGINT NOT NULL,
    seq      INT    NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (event_id, user_id),
    -- 동시 발급 시 한쪽을 실패시켜 번호 중복을 막는다. 예외 잡고 재시도.
    UNIQUE KEY uk_raa_event_seq (event_id, seq),

    CONSTRAINT fk_raa_event_id FOREIGN KEY (event_id) REFERENCES events(id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_raa_user_id  FOREIGN KEY (user_id)  REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS review_likes (
    user_id   BIGINT NOT NULL,
    review_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, review_id),

    CONSTRAINT fk_rl_user_id   FOREIGN KEY (user_id)   REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_rl_review_id FOREIGN KEY (review_id) REFERENCES reviews(id)
        ON DELETE CASCADE ON UPDATE CASCADE,

    KEY idx_rl_review (review_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS review_comment_likes (
    user_id    BIGINT NOT NULL,
    comment_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, comment_id),

    CONSTRAINT fk_rcl_user_id    FOREIGN KEY (user_id)    REFERENCES users(id)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT fk_rcl_comment_id FOREIGN KEY (comment_id) REFERENCES review_comments(id)
        ON DELETE CASCADE ON UPDATE CASCADE,

    KEY idx_rcl_comment (comment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
