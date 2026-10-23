package com.team1.hangsha.board.repository

import com.team1.hangsha.board.dto.core.*
import com.team1.hangsha.board.service.BoardPolicy
import com.team1.hangsha.event.dto.response.DiscussedEventItem
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class BoardQueryRepository(private val jdbc: NamedParameterJdbcTemplate) {
    private val postSelect = """
        SELECT p.*, u.username, e.id AS visible_event_id, e.title AS event_title,
          (SELECT COUNT(*) FROM board_post_likes l WHERE l.post_id = p.id) AS like_count,
          (SELECT COUNT(*) FROM board_comments c WHERE c.post_id = p.id AND c.is_deleted = false) AS comment_count,
          EXISTS(SELECT 1 FROM board_post_likes l WHERE l.post_id = p.id AND l.user_id = :viewer) AS liked_by_me
        FROM board_posts p
        LEFT JOIN users u ON u.id = p.user_id
        LEFT JOIN events e ON e.id = p.event_id AND e.admin_deleted = false
    """.trimIndent()

    private fun filters(query: String?, eventId: Long?, popularOnly: Boolean): String = buildString {
        append(" WHERE 1 = 1")
        if (eventId != null) append(" AND p.event_id = :eventId")
        if (popularOnly) append(" AND (SELECT COUNT(*) FROM board_post_likes l WHERE l.post_id = p.id) >= ${BoardPolicy.POPULAR_LIKES}")
        if (query != null) append(
            " " + """
            AND (p.title LIKE :query ESCAPE '!' OR p.content LIKE :query ESCAPE '!'
              OR e.title LIKE :query ESCAPE '!'
              OR EXISTS(SELECT 1 FROM board_post_tags pt
                        WHERE pt.post_id = p.id AND pt.tag_name LIKE :query ESCAPE '!'))
        """.trimIndent()
        )
    }

    fun list(
        query: String?,
        eventId: Long?,
        popularOnly: Boolean,
        offset: Long,
        size: Int,
        viewer: Long?
    ): Pair<Long, List<PostSummaryResponse>> {
        val params = mapOf(
            "viewer" to viewer,
            "query" to query?.let { "%${escapeLike(it)}%" },
            "eventId" to eventId,
            "offset" to offset,
            "size" to size
        )
        val where = filters(query, eventId, popularOnly)
        val total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM board_posts p LEFT JOIN events e ON e.id = p.event_id AND e.admin_deleted = false $where",
            params, Long::class.java,
        )!!
        val rows = jdbc.query(
            "$postSelect $where ORDER BY p.created_at DESC, p.id DESC LIMIT :size OFFSET :offset",
            params
        ) { rs, _ -> post(rs, viewer) }
        val tags = tags(rows.map { it.id })
        return total to rows.map { it.summary(tags[it.id].orEmpty()) }
    }

    fun detail(id: Long, viewer: Long?): PostDetailResponse? {
        val row = jdbc.query("$postSelect WHERE p.id = :id", mapOf("id" to id, "viewer" to viewer)) { rs, _ ->
            post(
                rs,
                viewer
            )
        }.singleOrNull()
            ?: return null
        return row.detail(tags(listOf(id))[id].orEmpty())
    }

    private fun tags(ids: List<Long>): Map<Long, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query(
            "SELECT post_id, tag_name FROM board_post_tags WHERE post_id IN (:ids) ORDER BY post_id, tag_name",
            mapOf("ids" to ids),
        ) { rs, _ -> rs.getLong("post_id") to rs.getString("tag_name") }.groupBy({ it.first }, { it.second })
    }

    private val commentSelect = """
        SELECT c.*, u.username, a.anonymous_number, p.user_id AS post_author_id,
          (SELECT COUNT(*) FROM board_comment_likes l WHERE l.comment_id = c.id) AS like_count,
          EXISTS(SELECT 1 FROM board_comment_likes l WHERE l.comment_id = c.id AND l.user_id = :viewer) AS liked_by_me
        FROM board_comments c JOIN board_posts p ON p.id = c.post_id LEFT JOIN users u ON u.id = c.user_id
        LEFT JOIN board_anonymous_users a ON a.post_id = c.post_id AND a.user_id = c.user_id
    """.trimIndent()

    fun comment(id: Long, viewer: Long?): CommentResponse? = jdbc.query(
        "$commentSelect WHERE c.id = :id", mapOf("id" to id, "viewer" to viewer),
    ) { rs, _ -> comment(rs, viewer) }.singleOrNull()

    fun comments(postId: Long, viewer: Long?, sort: String, offset: Long, size: Int): List<CommentResponse> {
        val order =
            if (sort == "recommended") "like_count DESC, c.created_at ASC, c.id ASC" else "c.created_at DESC, c.id DESC"
        val params = mapOf("postId" to postId, "viewer" to viewer, "size" to size, "offset" to offset)
        val roots = jdbc.query(
            "$commentSelect WHERE c.post_id = :postId AND c.parent_id IS NULL ORDER BY $order LIMIT :size OFFSET :offset",
            params
        ) { rs, _ -> comment(rs, viewer) }
        if (roots.isEmpty()) return emptyList()
        val replies = jdbc.query(
            "$commentSelect WHERE c.parent_id IN (:ids) AND c.is_deleted = false ORDER BY c.created_at ASC, c.id ASC",
            mapOf("ids" to roots.map { it.id }, "viewer" to viewer),
        ) { rs, _ -> comment(rs, viewer) }.groupBy { it.parentId }
        return roots.map { it.copy(replies = replies[it.id].orEmpty()) }
    }

    fun commentTotals(postId: Long): Pair<Long, Long> {
        val params = mapOf("postId" to postId)
        val roots = jdbc.queryForObject(
            "SELECT COUNT(*) FROM board_comments WHERE post_id = :postId AND parent_id IS NULL",
            params,
            Long::class.java
        )!!
        val comments = jdbc.queryForObject(
            "SELECT COUNT(*) FROM board_comments WHERE post_id = :postId AND is_deleted = false",
            params,
            Long::class.java
        )!!
        return roots to comments
    }

    fun discussed(size: Int): List<DiscussedEventItem> = jdbc.query(
        """
        SELECT e.id, e.title, COUNT(*) AS post_count FROM board_posts p JOIN events e ON e.id = p.event_id
        WHERE e.admin_deleted = false GROUP BY e.id, e.title ORDER BY post_count DESC, e.id DESC LIMIT :size
    """.trimIndent(), mapOf("size" to size)
    ) { rs, _ -> DiscussedEventItem(rs.getLong("id"), rs.getString("title"), rs.getLong("post_count")) }

    private fun author(rs: ResultSet, anonymous: Boolean, anonymousName: String? = null): AuthorResponse =
        AuthorResponse(
            if (rs.getObject("user_id") == null) BoardPolicy.WITHDRAWN_AUTHOR_NAME
            else if (anonymous) anonymousName
                ?: (BoardPolicy.ANONYMOUS_AUTHOR_NAME + checkNotNull(rs.getObject("anonymous_number")) { "Missing anonymous mapping" })
            else rs.getString("username") ?: BoardPolicy.DEFAULT_AUTHOR_NAME,
        )

    private fun post(rs: ResultSet, viewer: Long?): PostRow {
        val anonymous = rs.getBoolean("is_anonymous")
        return PostRow(
            rs.getLong("id"),
            rs.getString("title"),
            rs.getString("content"),
            rs.getObject("visible_event_id")
                ?.let { BoardEventResponse((it as Number).toLong(), rs.getString("event_title")) },
            author(rs, anonymous, BoardPolicy.ANONYMOUS_AUTHOR_NAME),
            anonymous,
            rs.getLong("like_count"),
            rs.getLong("comment_count"),
            rs.getBoolean("liked_by_me"),
            viewer != null && rs.getObject("user_id") != null && viewer == rs.getLong("user_id"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
        )
    }

    private fun comment(rs: ResultSet, viewer: Long?): CommentResponse {
        val deleted = rs.getBoolean("is_deleted")
        val anonymous = rs.getBoolean("is_anonymous")
        val isPostAuthor = rs.getObject("user_id") != null && rs.getObject("post_author_id") != null &&
                rs.getLong("user_id") == rs.getLong("post_author_id")
        return CommentResponse(
            rs.getLong("id"),
            rs.getLong("post_id"),
            rs.getObject("parent_id")?.let { (it as Number).toLong() },
            if (deleted) BoardPolicy.DELETED_COMMENT_CONTENT else rs.getString("content"),
            if (deleted) null else author(rs, anonymous, if (isPostAuthor) BoardPolicy.ANONYMOUS_POST_AUTHOR_NAME else null),
            !deleted && anonymous,
            !deleted && isPostAuthor,
            !deleted && viewer != null && rs.getObject("user_id") != null && viewer == rs.getLong("user_id"),
            deleted,
            if (deleted) 0 else rs.getLong("like_count"),
            !deleted && rs.getBoolean("liked_by_me"),
            rs.getTimestamp("created_at").toInstant(),
        )
    }

    companion object {
        fun escapeLike(value: String): String = value.replace("!", "!!").replace("%", "!%").replace("_", "!_")
    }
}

private data class PostRow(
    val id: Long, val title: String, val content: String, val event: BoardEventResponse?, val author: AuthorResponse,
    val anonymous: Boolean, val likes: Long, val comments: Long, val liked: Boolean, val mine: Boolean,
    val created: Instant, val updated: Instant,
) {
    fun summary(tags: List<String>) =
        PostSummaryResponse(id, title, event, tags, author, anonymous, likes, comments, liked, mine, created, updated)

    fun detail(tags: List<String>) = PostDetailResponse(
        id,
        title,
        content,
        event,
        tags,
        author,
        anonymous,
        likes,
        comments,
        liked,
        mine,
        created,
        updated
    )
}
