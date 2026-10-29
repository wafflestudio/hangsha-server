package com.team1.hangsha.review.repository

import com.team1.hangsha.review.dto.*
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class ReviewQueryRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private val reviewSelect = """
        SELECT r.*, u.username,
          (SELECT COUNT(*) FROM review_likes l WHERE l.review_id = r.id) AS like_count,
          (SELECT COUNT(*) FROM review_comments c WHERE c.review_id = r.id AND c.is_deleted = false) AS comment_count,
          EXISTS(SELECT 1 FROM review_likes l WHERE l.review_id = r.id AND l.user_id = :viewer) AS liked_by_me
        FROM reviews r LEFT JOIN users u ON u.id = r.user_id
    """.trimIndent()

    private val commentSelect = """
        SELECT c.*, u.username, a.anonymous_number, r.user_id AS review_author_id,
          (SELECT COUNT(*) FROM review_comment_likes l WHERE l.comment_id = c.id) AS like_count,
          EXISTS(SELECT 1 FROM review_comment_likes l WHERE l.comment_id = c.id AND l.user_id = :viewer) AS liked_by_me
        FROM review_comments c JOIN reviews r ON r.id = c.review_id
        LEFT JOIN users u ON u.id = c.user_id
        LEFT JOIN review_anon_aliases a ON a.review_id = c.review_id AND a.user_id = c.user_id
    """.trimIndent()

    fun reviews(eventId: Long, viewer: Long?, page: Int, size: Int): ReviewPageResponse {
        val safePage = maxOf(page, 1); val safeSize = size.coerceIn(1, 100); val offset = (safePage - 1L) * safeSize
        val params = mapOf("eventId" to eventId, "viewer" to viewer, "size" to safeSize, "offset" to offset)
        val total = jdbc.queryForObject("SELECT COUNT(*) FROM reviews WHERE event_id = :eventId AND is_deleted = false", params, Long::class.java) ?: 0
        val items = jdbc.query("$reviewSelect WHERE r.event_id = :eventId AND r.is_deleted = false ORDER BY r.created_at DESC, r.id DESC LIMIT :size OFFSET :offset", params) { rs, _ -> review(rs, viewer) }
        return ReviewPageResponse(safePage, safeSize, total, items)
    }

    fun review(id: Long, viewer: Long?): ReviewResponse? = jdbc.query(
        "$reviewSelect WHERE r.id = :id", mapOf("id" to id, "viewer" to viewer),
    ) { rs, _ -> review(rs, viewer) }.singleOrNull()

    fun myReviews(userId: Long, page: Int, size: Int): ReviewPageResponse {
        val safePage = maxOf(page, 1); val safeSize = size.coerceIn(1, 100); val offset = (safePage - 1L) * safeSize
        val params = mapOf("userId" to userId, "viewer" to userId, "size" to safeSize, "offset" to offset)
        val where = "r.user_id = :userId AND r.is_deleted = false AND e.admin_deleted = false"
        val total = jdbc.queryForObject("SELECT COUNT(*) FROM reviews r JOIN events e ON e.id = r.event_id WHERE $where", params, Long::class.java) ?: 0
        val items = jdbc.query("$reviewSelect JOIN events e ON e.id = r.event_id WHERE $where ORDER BY r.created_at DESC, r.id DESC LIMIT :size OFFSET :offset", params) { rs, _ -> review(rs, userId) }
        return ReviewPageResponse(safePage, safeSize, total, items)
    }

    fun comments(reviewId: Long, viewer: Long?): List<ReviewCommentResponse> {
        val roots = jdbc.query(
            "$commentSelect WHERE c.review_id = :reviewId AND c.parent_id IS NULL ORDER BY c.created_at ASC, c.id ASC",
            mapOf("reviewId" to reviewId, "viewer" to viewer),
        ) { rs, _ -> comment(rs, viewer) }
        if (roots.isEmpty()) return emptyList()
        val replies = jdbc.query(
            "$commentSelect WHERE c.parent_id IN (:ids) ORDER BY c.created_at ASC, c.id ASC",
            mapOf("ids" to roots.map { it.id }, "viewer" to viewer),
        ) { rs, _ -> comment(rs, viewer) }.groupBy { it.parentId }
        return roots.map { it.copy(replies = replies[it.id].orEmpty()) }
    }

    fun comment(id: Long, viewer: Long?): ReviewCommentResponse? = jdbc.query(
        "$commentSelect WHERE c.id = :id", mapOf("id" to id, "viewer" to viewer),
    ) { rs, _ -> comment(rs, viewer) }.singleOrNull()

    private fun review(rs: ResultSet, viewer: Long?): ReviewResponse {
        val deleted = rs.getBoolean("is_deleted"); val anonymous = rs.getBoolean("is_anonymous")
        return ReviewResponse(
            rs.getLong("id"), rs.getLong("event_id"), rs.getInt("rating"),
            if (deleted) "삭제된 후기입니다." else rs.getString("content"),
            if (deleted || anonymous) null else ReviewAuthorResponse(rs.getString("username") ?: "알 수 없음"),
            !deleted && anonymous, !deleted && viewer != null && viewer == rs.getLong("user_id"), deleted, !deleted && rs.getBoolean("is_edited"),
            if (deleted) 0 else rs.getLong("like_count"), !deleted && rs.getBoolean("liked_by_me"), if (deleted) 0 else rs.getLong("comment_count"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
        )
    }

    private fun comment(rs: ResultSet, viewer: Long?): ReviewCommentResponse {
        val deleted = rs.getBoolean("is_deleted"); val anonymous = rs.getBoolean("is_anonymous")
        val authorIsReviewAuthor = rs.getLong("user_id") == rs.getLong("review_author_id")
        val name = if (authorIsReviewAuthor) "작성자" else "익명${rs.getLong("anonymous_number")}" 
        return ReviewCommentResponse(
            rs.getLong("id"), rs.getLong("review_id"), rs.getObject("parent_id")?.let { (it as Number).toLong() },
            if (deleted) "삭제된 댓글입니다." else rs.getString("content"),
            if (deleted) null else if (anonymous) ReviewAuthorResponse(name) else ReviewAuthorResponse(rs.getString("username") ?: "알 수 없음"),
            !deleted && anonymous, !deleted && authorIsReviewAuthor, !deleted && viewer != null && viewer == rs.getLong("user_id"), deleted,
            !deleted && rs.getBoolean("is_edited"), if (deleted) 0 else rs.getLong("like_count"), !deleted && rs.getBoolean("liked_by_me"),
            rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
        )
    }
}
