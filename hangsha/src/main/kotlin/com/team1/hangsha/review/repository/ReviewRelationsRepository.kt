package com.team1.hangsha.review.repository

import com.team1.hangsha.review.model.Review
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class ReviewRelationsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun ensureAnonymous(review: Review, userId: Long) {
        val params = mapOf("reviewId" to review.id!!, "userId" to userId, "number" to review.nextAnonymousNumber)
        val exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM review_anon_aliases WHERE review_id = :reviewId AND user_id = :userId", params, Long::class.java,
        )!! > 0
        if (exists) return
        jdbc.update("INSERT INTO review_anon_aliases(review_id, user_id, anonymous_number) VALUES (:reviewId, :userId, :number)", params)
        jdbc.update("UPDATE reviews SET next_anonymous_number = next_anonymous_number + 1 WHERE id = :reviewId", params)
    }

    fun reviewLike(reviewId: Long, userId: Long, add: Boolean) = like("review_likes", "review_id", reviewId, userId, add)
    fun commentLike(commentId: Long, userId: Long, add: Boolean) = like("review_comment_likes", "comment_id", commentId, userId, add)

    fun tombstoneReview(id: Long) {
        val params = mapOf("id" to id)
        jdbc.update("DELETE FROM review_likes WHERE review_id = :id", params)
        jdbc.update("UPDATE reviews SET content = '', is_deleted = true WHERE id = :id", params)
    }

    fun tombstoneComment(id: Long) {
        val params = mapOf("id" to id)
        jdbc.update("DELETE FROM review_comment_likes WHERE comment_id = :id", params)
        jdbc.update("UPDATE review_comments SET content = '', is_deleted = true WHERE id = :id", params)
    }

    private fun like(table: String, column: String, id: Long, userId: Long, add: Boolean) {
        val params = mapOf("id" to id, "userId" to userId)
        if (!add) {
            jdbc.update("DELETE FROM $table WHERE $column = :id AND user_id = :userId", params)
            return
        }
        try { jdbc.update("INSERT INTO $table($column, user_id) VALUES (:id, :userId)", params) }
        catch (_: DuplicateKeyException) { }
    }
}
