package com.team1.hangsha.review.repository

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** 복합 PK 테이블이라 JdbcTemplate으로 직접 다룬다. BookmarkRepository와 같은 방식. */
@Repository
class ReviewLikeRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun insertReviewLike(userId: Long, reviewId: Long): Int =
        jdbc.update(
            "INSERT IGNORE INTO review_likes (user_id, review_id) VALUES (:userId, :reviewId)",
            mapOf("userId" to userId, "reviewId" to reviewId),
        )

    fun deleteReviewLike(userId: Long, reviewId: Long): Int =
        jdbc.update(
            "DELETE FROM review_likes WHERE user_id = :userId AND review_id = :reviewId",
            mapOf("userId" to userId, "reviewId" to reviewId),
        )

    fun insertCommentLike(userId: Long, commentId: Long): Int =
        jdbc.update(
            "INSERT IGNORE INTO review_comment_likes (user_id, comment_id) VALUES (:userId, :commentId)",
            mapOf("userId" to userId, "commentId" to commentId),
        )

    fun deleteCommentLike(userId: Long, commentId: Long): Int =
        jdbc.update(
            "DELETE FROM review_comment_likes WHERE user_id = :userId AND comment_id = :commentId",
            mapOf("userId" to userId, "commentId" to commentId),
        )
}
