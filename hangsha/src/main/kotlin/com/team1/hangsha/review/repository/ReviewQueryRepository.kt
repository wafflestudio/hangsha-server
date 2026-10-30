package com.team1.hangsha.review.repository

import com.team1.hangsha.review.dto.ReviewCommentResponse
import com.team1.hangsha.review.dto.ReviewResponse
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

private const val UNKNOWN_AUTHOR = "알 수 없음"

/**
 * 후기·댓글 조회 SQL은 여기서만 만든다.
 * 지워진 후기와 관리자가 지운 행사(events.admin_deleted) 조건을 빠뜨리지 않기 위해서다.
 */
@Repository
class ReviewQueryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val reviewSelect = """
        SELECT r.id, r.event_id, e.title AS event_title, r.user_id, r.rating, r.content,
               r.is_anonymous, r.is_edited, r.created_at,
               u.username, a.seq AS anon_seq,
               (SELECT COUNT(*) FROM review_likes l WHERE l.review_id = r.id) AS like_count,
               (SELECT COUNT(*) FROM review_comments c
                 WHERE c.review_id = r.id AND c.is_deleted = FALSE) AS comment_count,
               EXISTS(SELECT 1 FROM review_likes l
                       WHERE l.review_id = r.id AND l.user_id = :viewerId) AS is_liked
        FROM reviews r
        JOIN events e ON e.id = r.event_id
        JOIN users u ON u.id = r.user_id
        LEFT JOIN review_anon_aliases a ON a.event_id = r.event_id AND a.user_id = r.user_id
        WHERE r.is_deleted = FALSE
          AND e.admin_deleted = FALSE
    """.trimIndent()

    private val commentSelect = """
        SELECT c.id, c.review_id, c.parent_id, c.user_id, c.content,
               c.is_anonymous, c.is_edited, c.is_deleted, c.created_at,
               r.user_id AS review_user_id, u.username, a.seq AS anon_seq,
               (SELECT COUNT(*) FROM review_comment_likes l WHERE l.comment_id = c.id) AS like_count,
               EXISTS(SELECT 1 FROM review_comment_likes l
                       WHERE l.comment_id = c.id AND l.user_id = :viewerId) AS is_liked
        FROM review_comments c
        JOIN reviews r ON r.id = c.review_id
        JOIN events e ON e.id = r.event_id
        JOIN users u ON u.id = c.user_id
        LEFT JOIN review_anon_aliases a ON a.event_id = r.event_id AND a.user_id = c.user_id
        WHERE r.is_deleted = FALSE
          AND e.admin_deleted = FALSE
    """.trimIndent()

    /** 행사 후기 전체, 최신순. */
    fun findEventReviews(eventId: Long, viewerId: Long?): List<ReviewResponse> =
        jdbc.query(
            "$reviewSelect AND r.event_id = :eventId ORDER BY r.created_at DESC, r.id DESC",
            mapOf("eventId" to eventId, "viewerId" to viewerId),
        ) { rs, _ -> mapReview(rs, viewerId) }

    /** 내 후기 전체, 최신순. */
    fun findUserReviews(userId: Long): List<ReviewResponse> =
        jdbc.query(
            "$reviewSelect AND r.user_id = :userId ORDER BY r.created_at DESC, r.id DESC",
            mapOf("userId" to userId, "viewerId" to userId),
        ) { rs, _ -> mapReview(rs, userId) }

    fun findReview(reviewId: Long, viewerId: Long?): ReviewResponse? =
        jdbc.query(
            "$reviewSelect AND r.id = :reviewId",
            mapOf("reviewId" to reviewId, "viewerId" to viewerId),
        ) { rs, _ -> mapReview(rs, viewerId) }.firstOrNull()

    /**
     * 댓글과 답글을 평평한 목록으로, 작성순.
     * 지운 댓글은 살아 있는 답글이 있을 때만 자리로 남고, 지운 답글은 빠진다.
     */
    fun findComments(reviewId: Long, viewerId: Long?): List<ReviewCommentResponse> =
        jdbc.query(
            """
            $commentSelect
              AND c.review_id = :reviewId
              AND (
                c.is_deleted = FALSE
                OR (
                  c.parent_id IS NULL
                  AND EXISTS(SELECT 1 FROM review_comments x
                              WHERE x.parent_id = c.id AND x.is_deleted = FALSE)
                )
              )
            ORDER BY c.created_at ASC, c.id ASC
            """.trimIndent(),
            mapOf("reviewId" to reviewId, "viewerId" to viewerId),
        ) { rs, _ -> mapComment(rs, viewerId) }

    fun findComment(commentId: Long, viewerId: Long?): ReviewCommentResponse? =
        jdbc.query(
            "$commentSelect AND c.id = :commentId",
            mapOf("commentId" to commentId, "viewerId" to viewerId),
        ) { rs, _ -> mapComment(rs, viewerId) }.firstOrNull()

    private fun mapReview(rs: ResultSet, viewerId: Long?): ReviewResponse {
        val isAnonymous = rs.getBoolean("is_anonymous")
        return ReviewResponse(
            id = rs.getLong("id"),
            eventId = rs.getLong("event_id"),
            eventTitle = rs.getString("event_title"),
            rating = rs.getInt("rating"),
            content = rs.getString("content"),
            authorName = authorName(rs, isAnonymous),
            isAnonymous = isAnonymous,
            isMine = viewerId != null && viewerId == rs.getLong("user_id"),
            isEdited = rs.getBoolean("is_edited"),
            likeCount = rs.getLong("like_count"),
            isLiked = rs.getBoolean("is_liked"),
            commentCount = rs.getLong("comment_count"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    private fun mapComment(rs: ResultSet, viewerId: Long?): ReviewCommentResponse {
        val userId = rs.getLong("user_id")
        val isAnonymous = rs.getBoolean("is_anonymous")
        val isReviewAuthor = userId == rs.getLong("review_user_id")
        val createdAt = rs.getTimestamp("created_at").toInstant()
        val parentId = rs.getLong("parent_id").takeUnless { rs.wasNull() }

        // 지운 댓글 자리는 내용·작성자·좋아요를 모두 감춘다.
        if (rs.getBoolean("is_deleted")) {
            return ReviewCommentResponse(
                id = rs.getLong("id"),
                reviewId = rs.getLong("review_id"),
                parentId = parentId,
                content = null,
                authorName = null,
                isAnonymous = false,
                isMine = false,
                isReviewAuthor = false,
                isEdited = false,
                isDeleted = true,
                likeCount = 0,
                isLiked = false,
                createdAt = createdAt,
            )
        }

        return ReviewCommentResponse(
            id = rs.getLong("id"),
            reviewId = rs.getLong("review_id"),
            parentId = parentId,
            content = rs.getString("content"),
            authorName = authorName(rs, isAnonymous),
            isAnonymous = isAnonymous,
            isMine = viewerId != null && viewerId == userId,
            isReviewAuthor = isReviewAuthor,
            isEdited = rs.getBoolean("is_edited"),
            isDeleted = false,
            likeCount = rs.getLong("like_count"),
            isLiked = rs.getBoolean("is_liked"),
            createdAt = createdAt,
        )
    }

    private fun authorName(rs: ResultSet, isAnonymous: Boolean): String {
        if (!isAnonymous) return rs.getString("username") ?: UNKNOWN_AUTHOR
        val seq = rs.getInt("anon_seq")
        return if (rs.wasNull()) "익명" else "익명$seq"
    }
}
