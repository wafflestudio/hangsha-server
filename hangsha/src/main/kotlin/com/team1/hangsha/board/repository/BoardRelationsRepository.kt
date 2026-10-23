package com.team1.hangsha.board.repository

import com.team1.hangsha.board.model.BoardPost
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class BoardRelationsRepository(private val jdbc: NamedParameterJdbcTemplate) {
    /** Caller holds the post lock. Mapping survives comment deletion and anonymity toggles. */
    fun ensureAnonymous(post: BoardPost, userId: Long) {
        val params = mapOf("postId" to post.id!!, "userId" to userId, "number" to post.nextAnonymousNumber)
        val exists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM board_anonymous_users WHERE post_id = :postId AND user_id = :userId",
            params, Long::class.java,
        )!! > 0
        if (exists) return
        jdbc.update(
            "INSERT INTO board_anonymous_users(post_id, user_id, anonymous_number) VALUES (:postId, :userId, :number)",
            params,
        )
        jdbc.update(
            "UPDATE board_posts SET next_anonymous_number = next_anonymous_number + 1 WHERE id = :postId",
            params
        )
    }

    fun replaceTags(postId: Long, names: List<String>) {
        jdbc.update("DELETE FROM board_post_tags WHERE post_id = :postId", mapOf("postId" to postId))
        names.forEach { name ->
            try {
                jdbc.update(
                    "INSERT INTO board_post_tags(post_id, tag_name) VALUES (:postId, :name)",
                    mapOf("postId" to postId, "name" to name)
                )
            } catch (_: DuplicateKeyException) {
                // The DB collation treats differently cased names as the same tag within a post.
            }
        }
    }

    fun postLike(postId: Long, userId: Long, add: Boolean) = like("board_post_likes", "post_id", postId, userId, add)
    fun commentLike(commentId: Long, userId: Long, add: Boolean) =
        like("board_comment_likes", "comment_id", commentId, userId, add)

    private fun like(table: String, column: String, id: Long, userId: Long, add: Boolean) {
        val params = mapOf("id" to id, "userId" to userId)
        if (!add) {
            jdbc.update("DELETE FROM $table WHERE $column = :id AND user_id = :userId", params)
            return
        }
        try {
            jdbc.update("INSERT INTO $table($column, user_id) VALUES (:id, :userId)", params)
        } catch (_: DuplicateKeyException) {
            // Only a duplicate PK is ignored; FK/truncation and other DB errors propagate.
        }
    }

    fun clearCommentLikes(id: Long) {
        jdbc.update("DELETE FROM board_comment_likes WHERE comment_id = :id", mapOf("id" to id))
    }

    fun tombstone(id: Long) {
        clearCommentLikes(id)
        jdbc.update("UPDATE board_comments SET content = '', is_deleted = true WHERE id = :id", mapOf("id" to id))
    }

    fun deletePostChildren(postId: Long) {
        val params = mapOf("postId" to postId)
        jdbc.update(
            "DELETE l FROM board_comment_likes l JOIN board_comments c ON c.id = l.comment_id WHERE c.post_id = :postId",
            params
        )
        jdbc.update("DELETE FROM board_comments WHERE post_id = :postId AND parent_id IS NOT NULL", params)
        jdbc.update("DELETE FROM board_comments WHERE post_id = :postId", params)
    }
}
