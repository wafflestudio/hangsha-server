package com.team1.hangsha

import com.fasterxml.jackson.databind.JsonNode
import com.team1.hangsha.board.dto.CreateCommentRequest
import com.team1.hangsha.board.dto.CreatePostRequest
import com.team1.hangsha.board.dto.core.PostDetailResponse
import com.team1.hangsha.board.repository.BoardQueryRepository
import com.team1.hangsha.board.service.BoardCommentService
import com.team1.hangsha.board.service.BoardPostService
import com.team1.hangsha.board.service.BoardPolicy
import com.team1.hangsha.bookmark.service.BookmarkService
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.event.repository.EventQueryRepository
import com.team1.hangsha.helper.IntegrationTestBase
import com.team1.hangsha.memo.dto.CreateMemoRequest
import com.team1.hangsha.memo.service.MemoService
import com.team1.hangsha.user.JwtTokenProvider
import com.team1.hangsha.user.model.User
import com.team1.hangsha.user.repository.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class BoardIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var posts: BoardPostService

    @Autowired
    lateinit var comments: BoardCommentService

    @Autowired
    lateinit var memos: MemoService

    @Autowired
    lateinit var bookmarks: BookmarkService

    @Autowired
    lateinit var users: UserRepository

    @Autowired
    lateinit var query: BoardQueryRepository

    @Autowired
    lateinit var eventQuery: EventQueryRepository

    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    lateinit var jwt: JwtTokenProvider
    var author = 0L
    var other = 0L

    @BeforeEach
    fun setup() {
        author = dataGenerator.generateLocalUser(username = "작성자").first.id!!
        other = dataGenerator.generateLocalUser(username = "다른 사람").first.id!!
    }

    private fun body(value: String): JsonNode = objectMapper.readTree(value)
    private fun post(anonymous: Boolean = false, event: Long? = null, tags: String = "[]"): PostDetailResponse =
        posts.create(
            author,
            CreatePostRequest("제목", "글", event, objectMapper.readTree(tags).map { it.asText() }, anonymous)
        )

    private fun comment(postId: Long, userId: Long = other, anonymous: Boolean = false, parent: Long? = null) =
        comments.create(
            userId, postId,
            CreateCommentRequest("댓글", parent, anonymous)
        )

    private fun error(code: ErrorCode, action: () -> Unit) {
        assertEquals(code, assertFailsWith<DomainException>(block = action).errorCode)
    }

    private fun count(table: String): Long =
        jdbc.queryForObject("SELECT COUNT(*) FROM $table", emptyMap<String, Any>(), Long::class.java)!!

    private fun event(
        title: String = "행사",
        hidden: Boolean = false,
        start: LocalDateTime? = null,
        end: LocalDateTime? = null
    ): Long {
        jdbc.update(
            "INSERT INTO events(title, admin_deleted, apply_start, apply_end) VALUES (:title, :hidden, :start, :end)",
            mapOf(
                "title" to title,
                "hidden" to hidden,
                "start" to start?.let(Timestamp::valueOf),
                "end" to end?.let(Timestamp::valueOf)
            )
        )
        return jdbc.queryForObject("SELECT MAX(id) FROM events", emptyMap<String, Any>(), Long::class.java)!!
    }

    @Test
    fun `POST board posts - 자유글과 종료 행사는 허용하고 숨김 또는 없는 행사는 거부한다`() {
        assertNull(post().event)
        val ended = event(end = LocalDateTime.of(2020, 1, 1, 0, 0))
        assertEquals(ended, post(event = ended).event!!.id)
        error(ErrorCode.EVENT_NOT_FOUND) { post(event = event(hidden = true)) }
        error(ErrorCode.EVENT_NOT_FOUND) { post(event = Long.MAX_VALUE) }
    }

    @Test
    fun `PATCH board posts - 필드 생략과 null을 구분하고 행사 변경과 타인 수정을 거부한다`() {
        val post = post(tags = """[" 태그 ", "태그"]""")
        assertEquals(listOf("태그"), post.tagNames)
        assertEquals("수정", posts.update(author, post.id, body("""{"title":" 수정 "}""")).title)
        assertEquals("글", posts.detail(post.id, author).content)
        assertEquals(emptyList(), posts.update(author, post.id, body("""{"tagNames":null}""")).tagNames)
        listOf(
            "{}",
            """{"eventId":null}""",
            """{"title":null}""",
            """{"isAnonymous":"true"}""",
            """{"unexpected":1}"""
        ).forEach {
            error(ErrorCode.INVALID_REQUEST) { posts.update(author, post.id, body(it)) }
        }
        error(ErrorCode.POST_NOT_FOUND) { posts.update(other, post.id, body("""{"title":"수정"}""")) }
        error(ErrorCode.POST_NOT_FOUND) { posts.delete(other, post.id) }
    }

    @Test
    fun `PATCH board posts - 태그 검증 실패 시 글 수정과 기존 태그가 모두 롤백된다`() {
        val original = post(tags = """["기존태그"]""")
        error(ErrorCode.INVALID_REQUEST) {
            posts.update(author, original.id, body("""{"title":"변경된 제목","isAnonymous":true,"tagNames":[" "]}"""))
        }
        assertEquals(original, posts.detail(original.id, author))
    }

    @Test
    fun `익명 댓글 - 글쓴이는 별도 표시하고 다른 참여자의 번호는 삭제 후에도 유지한다`() {
        val p = post(true)
        assertEquals(BoardPolicy.ANONYMOUS_AUTHOR_NAME, p.author.displayName)
        assertEquals(0L, count("board_anonymous_users"))
        jdbc.update("UPDATE users SET username = '실명작성자' WHERE id = :id", mapOf("id" to author))
        val anonymousPostComment = comment(p.id, author, true)
        assertEquals(BoardPolicy.ANONYMOUS_POST_AUTHOR_NAME, anonymousPostComment.author!!.displayName)
        assertTrue(anonymousPostComment.isPostAuthor)
        assertTrue(anonymousPostComment.isAnonymous)
        assertEquals(0L, count("board_anonymous_users"))
        val realPostComment = comment(p.id, author, false)
        assertEquals("실명작성자", realPostComment.author!!.displayName)
        assertTrue(realPostComment.isPostAuthor)
        val c = comment(p.id, anonymous = true)
        assertEquals(BoardPolicy.ANONYMOUS_AUTHOR_NAME + 1, c.author!!.displayName)
        comments.delete(other, c.id)
        val nextUser = users.save(User(username = "새 사용자")).id!!
        assertEquals(BoardPolicy.ANONYMOUS_AUTHOR_NAME + 2, comment(p.id, nextUser, true).author!!.displayName)
        assertEquals(BoardPolicy.ANONYMOUS_AUTHOR_NAME + 1, comment(p.id, anonymous = true).author!!.displayName)
        posts.update(author, p.id, body("""{"isAnonymous":false}"""))
        assertEquals("실명작성자", posts.detail(p.id, null).author.displayName)
        assertEquals(BoardPolicy.ANONYMOUS_AUTHOR_NAME, posts.update(author, p.id, body("""{"isAnonymous":true}""")).author.displayName)
        assertEquals(BoardPolicy.ANONYMOUS_AUTHOR_NAME + 1, comment(post().id, anonymous = true).author!!.displayName)
        posts.update(author, p.id, body("""{"isAnonymous":false}"""))
        assertEquals(BoardPolicy.ANONYMOUS_POST_AUTHOR_NAME, query.comment(anonymousPostComment.id, null)!!.author!!.displayName)
        comments.delete(author, anonymousPostComment.id)
        assertEquals(BoardPolicy.ANONYMOUS_POST_AUTHOR_NAME, comment(p.id, author, true).author!!.displayName)
        assertEquals(3L, count("board_anonymous_users"))
        val listed = comments.list(p.id, null, "recommended", 1, 20)
        assertTrue(listed.items.filter { it.isPostAuthor && it.isAnonymous }
            .all { it.author!!.displayName == BoardPolicy.ANONYMOUS_POST_AUTHOR_NAME })
    }

    @Test
    fun `POST comments - 다른 글 또는 삭제된 부모와 2단계 답글을 거부한다`() {
        val p = post()
        val root = comment(p.id)
        val reply = comment(p.id, parent = root.id)
        error(ErrorCode.INVALID_REQUEST) { comment(p.id, parent = reply.id) }
        error(ErrorCode.COMMENT_NOT_FOUND) { comment(post().id, parent = root.id) }
        comments.delete(other, root.id)
        error(ErrorCode.COMMENT_NOT_FOUND) { comment(p.id, parent = root.id) }
        error(ErrorCode.COMMENT_NOT_FOUND) { comments.like(author, root.id, true) }
        error(ErrorCode.COMMENT_NOT_FOUND) { comments.delete(other, root.id) }
        error(ErrorCode.COMMENT_NOT_FOUND) { comments.delete(author, reply.id) }
    }

    @Test
    fun `DELETE comments - 답글 있는 부모를 삭제 표시하고 마지막 답글 삭제 시 함께 제거한다`() {
        val p = post()
        val root = comment(p.id, author, true)
        val reply = comment(p.id, parent = root.id)
        comments.like(other, root.id, true)
        comments.delete(author, root.id)
        val list = comments.list(p.id, author, "recommended", 1, 20)
        assertEquals(1L, list.totalRoots)
        assertEquals(1L, list.totalComments)
        val deleted = list.items.single()
        assertEquals(BoardPolicy.DELETED_COMMENT_CONTENT, deleted.content)
        assertNull(deleted.author)
        assertFalse(deleted.isAnonymous)
        assertFalse(deleted.isPostAuthor)
        assertFalse(deleted.isMine)
        assertFalse(deleted.likedByMe)
        assertEquals(0L, deleted.likeCount)
        assertEquals(reply.id, deleted.replies.single().id)
        assertEquals(1L, posts.detail(p.id, null).commentCount)
        comments.delete(other, reply.id)
        assertEquals(0L, comments.list(p.id, null, "latest", 1, 20).totalRoots)
        assertEquals(0L, count("board_anonymous_users"))
    }

    @Test
    fun `DELETE board posts - 댓글과 좋아요 태그 익명 매핑을 함께 제거한다`() {
        val p = post(true, tags = """["태그"]""")
        val root = comment(p.id, anonymous = true)
        val reply = comment(p.id, parent = root.id)
        posts.like(other, p.id, true)
        comments.like(author, reply.id, true)
        posts.delete(author, p.id)
        listOf(
            "board_posts",
            "board_comments",
            "board_post_likes",
            "board_comment_likes",
            "board_post_tags",
            "board_anonymous_users"
        ).forEach { assertEquals(0L, count(it), it) }
        error(ErrorCode.POST_NOT_FOUND) { posts.detail(p.id, null) }
        error(ErrorCode.POST_NOT_FOUND) { posts.delete(author, p.id) }
        error(ErrorCode.COMMENT_NOT_FOUND) { comments.like(author, reply.id, true) }
    }

    @Test
    fun `게시판 태그 - 글별로 저장하고 교체와 삭제가 다른 글에 영향을 주지 않는다`() {
        val first = post(tags = """[" Spring ", "spring", "질문"]""")
        val second = post(tags = """["Spring"]""")
        assertEquals(setOf("Spring", "질문"), first.tagNames.toSet())
        assertEquals(3L, count("board_post_tags"))
        assertEquals(2L, posts.list(null, "spring", null, false, 1, 20).total)
        posts.update(author, first.id, body("""{"tagNames":["Kotlin"]}"""))
        assertEquals(listOf("Kotlin"), posts.detail(first.id, null).tagNames)
        assertEquals(listOf("Spring"), posts.detail(second.id, null).tagNames)
        assertEquals(1L, posts.list(null, "spring", null, false, 1, 20).total)
        posts.delete(author, first.id)
        assertEquals(1L, count("board_post_tags"))
        assertEquals(listOf("Spring"), posts.detail(second.id, null).tagNames)
        posts.update(author, second.id, body("""{"tagNames":null}"""))
        assertEquals(0L, count("board_post_tags"))
    }

    @Test
    fun `인기글 - 좋아요 10개 경계를 적용하고 좋아요 수와 무관하게 최신순으로 조회한다`() {
        val older = post()
        val newer = post()
        val voters = (1..10).map { users.save(User(username = "투표자$it")).id!! }
        voters.take(9).forEach { posts.like(it, older.id, true) }
        assertEquals(0L, posts.list(null, null, null, true, 1, 20).total)
        posts.like(voters.last(), older.id, true)
        posts.like(voters.last(), older.id, true)
        assertEquals(10L, posts.detail(older.id, voters.last()).likeCount)
        assertTrue(posts.detail(older.id, voters.last()).likedByMe)
        voters.forEach { posts.like(it, newer.id, true) }
        posts.like(author, older.id, true)
        assertEquals(11L, posts.detail(older.id, null).likeCount)
        assertEquals(10L, posts.detail(newer.id, null).likeCount)
        assertEquals(listOf(newer.id, older.id), posts.list(null, null, null, true, 1, 20).items.map { it.id })
        posts.like(author, older.id, false)
        assertEquals(2L, posts.list(null, null, null, true, 1, 20).total)
        posts.like(voters.last(), older.id, false)
        posts.like(voters.last(), older.id, false)
        assertEquals(listOf(newer.id), posts.list(null, null, null, true, 1, 20).items.map { it.id })
        val c = comment(newer.id)
        comments.like(author, c.id, true)
        comments.like(author, c.id, true)
        assertEquals(1L, query.comment(c.id, author)!!.likeCount)
        comments.like(author, c.id, false)
        comments.like(author, c.id, false)
        assertEquals(0L, query.comment(c.id, null)!!.likeCount)
    }

    @Test
    fun `글 좋아요 - 조회자별 상태와 소유권을 구분하고 타인의 취소는 영향을 주지 않는다`() {
        val p = post()
        val authorToken = jwt.createAccessToken(author)
        mockMvc.perform(post("/api/v1/board/posts/${p.id}/like").header("Authorization", "Bearer $authorToken"))
            .andExpect(status().isNoContent).andExpect(content().string(""))
        mockMvc.perform(
            delete("/api/v1/board/posts/${p.id}/like").header(
                "Authorization",
                "Bearer ${jwt.createAccessToken(other)}"
            )
        )
            .andExpect(status().isNoContent)

        listOf(author, other, null).forEach { viewer ->
            listOf("/api/v1/board/posts/${p.id}" to "$", "/api/v1/board/posts" to "$.items[0]").forEach { (url, path) ->
                val request = get(url)
                if (viewer != null) request.header("Authorization", "Bearer ${jwt.createAccessToken(viewer)}")
                mockMvc.perform(request).andExpect(status().isOk)
                    .andExpect(jsonPath("$path.likeCount").value(1))
                    .andExpect(jsonPath("$path.likedByMe").value(viewer == author))
                    .andExpect(jsonPath("$path.isMine").value(viewer == author))
            }
        }
        mockMvc.perform(delete("/api/v1/board/posts/${p.id}/like").header("Authorization", "Bearer $authorToken"))
            .andExpect(status().isNoContent)
        assertEquals(0L, posts.detail(p.id, author).likeCount)
        assertFalse(posts.detail(p.id, author).likedByMe)
    }

    @Test
    fun `댓글 좋아요 - 조회자별 상태와 댓글 소유권을 구분하고 타인의 취소는 영향을 주지 않는다`() {
        val p = post()
        val c = comment(p.id, userId = other)
        val authorToken = jwt.createAccessToken(author)
        mockMvc.perform(post("/api/v1/board/comments/${c.id}/like").header("Authorization", "Bearer $authorToken"))
            .andExpect(status().isNoContent).andExpect(content().string(""))
        mockMvc.perform(
            delete("/api/v1/board/comments/${c.id}/like").header(
                "Authorization",
                "Bearer ${jwt.createAccessToken(other)}"
            )
        )
            .andExpect(status().isNoContent)

        listOf(author, other, null).forEach { viewer ->
            val request = get("/api/v1/board/posts/${p.id}/comments")
            if (viewer != null) request.header("Authorization", "Bearer ${jwt.createAccessToken(viewer)}")
            mockMvc.perform(request).andExpect(status().isOk)
                .andExpect(jsonPath("$.items[0].likeCount").value(1))
                .andExpect(jsonPath("$.items[0].likedByMe").value(viewer == author))
                .andExpect(jsonPath("$.items[0].isMine").value(viewer == other))
        }
        mockMvc.perform(delete("/api/v1/board/comments/${c.id}/like").header("Authorization", "Bearer $authorToken"))
            .andExpect(status().isNoContent)
        assertEquals(0L, query.comment(c.id, author)!!.likeCount)
        assertFalse(query.comment(c.id, author)!!.likedByMe)
    }

    @Test
    fun `GET board posts - 검색 필드와 행사 인기 조건을 적용하고 중복과 와일드카드 해석을 막는다`() {
        val e = event("행사이름")
        val p = post(event = e, tags = """["태그검색1", "태그검색2", "100%_!"]""")
        posts.update(author, p.id, body("""{"title":"제목검색", "content":"본문검색"}"""))
        post()
        listOf("제목검색", "본문검색", "행사이름", "태그검색", "%_!").forEach { term ->
            val result = posts.list(null, term, null, false, 1, 20)
            assertEquals(1L, result.total, term)
            assertEquals(p.id, result.items.single().id)
        }
        assertEquals(0L, posts.list(null, "태그검색", e + 1, false, 1, 20).total)
        assertEquals(0L, posts.list(null, "태그검색", e, true, 1, 20).total)
        jdbc.update("UPDATE events SET admin_deleted = true WHERE id = :id", mapOf("id" to e))
        assertNull(posts.detail(p.id, null).event)
        assertEquals(0L, posts.list(null, "행사이름", null, false, 1, 20).total)
        assertTrue(query.discussed(5).isEmpty())
    }

    @Test
    fun `GET comments - 부모 추천순과 최신순을 구분하고 답글은 작성순으로 묶는다`() {
        val p = post()
        val first = comment(p.id)
        val second = comment(p.id)
        val reply1 = comment(p.id, parent = first.id)
        val reply2 = comment(p.id, parent = first.id)
        comments.like(author, first.id, true)
        comments.like(author, reply2.id, true)
        val page = comments.list(p.id, null, "recommended", 1, 1)
        assertEquals(2L, page.totalRoots)
        assertEquals(4L, page.totalComments)
        assertEquals(first.id, page.items.single().id)
        assertEquals(listOf(reply1.id, reply2.id), page.items.single().replies.map { it.id })
        assertEquals(second.id, comments.list(p.id, null, "recommended", 2, 1).items.single().id)
        assertEquals(second.id, comments.list(p.id, null, "latest", 1, 1).items.single().id)
        assertTrue(comments.list(p.id, null, "latest", 3, 1).items.isEmpty())
    }

    @Test
    fun `이야기 많은 행사 - 글 개수와 동점 ID로 정렬하고 글 삭제를 반영한다`() {
        val e1 = event("첫 행사")
        val e2 = event("둘째 행사")
        val first = post(event = e1)
        post(event = e2)
        assertEquals(listOf(e2, e1), query.discussed(5).map { it.id })
        post(event = e1)
        assertEquals(2L, query.discussed(1).single().postCount)
        assertEquals(e1, query.discussed(1).single().id)
        posts.delete(author, first.id)
        assertEquals(e2, query.discussed(1).single().id)
        jdbc.update("DELETE FROM events WHERE id = :id", mapOf("id" to e1))
        assertEquals(1, query.discussed(5).size)
        assertEquals(2L, posts.list(null, null, null, false, 1, 20).total) // deleted event preserves its remaining post
    }

    @Test
    fun `다가오는 행사 - 신청 시작과 마감 경계 공개 여부 및 반환 개수를 적용한다`() {
        val now = LocalDateTime.of(2026, 10, 14, 0, 0)
        val first = event("곧 마감", end = now.plusSeconds(1))
        val tie = event("같은 마감", start = now, end = now.plusSeconds(1))
        val later = event("나중 마감", start = now.minusSeconds(1), end = now.plusDays(1))
        event(end = now)
        event(end = now.minusSeconds(1))
        val future = event(start = now.plusSeconds(1), end = now.plusDays(1))
        event(hidden = true, end = now.plusDays(1))
        event()
        assertEquals(listOf(first, tie, later), eventQuery.findUpcoming(now, 100).map { it.id })
        assertEquals(listOf(first), eventQuery.findUpcoming(now, 1).map { it.id })
        assertEquals(listOf(later, future), eventQuery.findUpcoming(now.plusSeconds(1), 100).map { it.id })
        assertTrue(eventQuery.findUpcoming(now.plusDays(2), 100).isEmpty())
    }

    @Test
    fun `게시판 변경 후에도 개인 메모 태그 북마크 행사 상세 조회가 유지된다`() {
        val e = event("기존 행사")
        val memo = memos.createMemo(author, CreateMemoRequest(e, "메모", listOf("태그")))
        val p = post(event = e, tags = """["태그"]""")
        bookmarks.addBookmark(author, e)
        bookmarks.addBookmark(author, e)
        assertEquals(1, bookmarks.listMyBookmarks(author, 1, 20).total)
        assertEquals("기존 행사", bookmarks.listMyBookmarks(author, 1, 20).items.single().title)
        assertEquals("메모", memos.getMyMemoByEventId(author, e).content)
        assertTrue(memos.getMyMemos(author).single().isBookmarked)
        posts.update(author, p.id, body("""{"tagNames":[]}"""))
        assertEquals("태그", memos.getMyMemoByEventId(author, e).tags.single().name)
        assertEquals(1L, count("tags"))
        posts.delete(author, p.id)
        assertEquals(memo.id, memos.getMyMemoByEventId(author, e).id)
        val token = jwt.createAccessToken(author)
        mockMvc.perform(get("/api/v1/events/$e").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk).andExpect(jsonPath("$.title").value("기존 행사"))
        bookmarks.removeBookmark(author, e)
        memos.deleteMemo(author, memo.id)
        assertEquals(0, bookmarks.listMyBookmarks(author, 1, 20).total)
    }

    private fun concurrently(actions: List<() -> Unit>) {
        val pool = Executors.newFixedThreadPool(actions.size)
        try {
            pool.invokeAll(actions.map { action -> Callable { action() } }).forEach { it.get(20, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `동시 요청 - 익명 번호 중복을 막고 좋아요를 한 번만 반영한다`() {
        val p = post()
        val participants = (1..6).map { users.save(User(username = "익명 참여자$it")).id!! }
        concurrently(participants.map { user ->
            {
                comment(p.id, user, true); posts.like(user, p.id, true); posts.like(
                user,
                p.id,
                true
            )
            }
        })
        val labels = comments.list(p.id, null, "latest", 1, 20).items.map { it.author!!.displayName }.toSet()
        assertEquals((1..6).map { BoardPolicy.ANONYMOUS_AUTHOR_NAME + it }.toSet(), labels)
        concurrently((1..4).map {
            {
                comment(p.id, participants.first(), true); posts.like(
                participants.first(),
                p.id,
                true
            )
            }
        })
        assertEquals(6L, count("board_anonymous_users"))
        assertEquals(6L, posts.detail(p.id, null).likeCount)
        assertEquals(10L, posts.detail(p.id, null).commentCount)
    }

    @Test
    fun `동시 요청 - 부모 삭제와 답글 작성 후 댓글 구조가 유효하다`() {
        val p = post()
        val root = comment(p.id)
        concurrently(
            listOf(
                { comments.delete(other, root.id) },
                {
                    try {
                        comment(p.id, author, parent = root.id)
                    } catch (e: DomainException) {
                        assertEquals(ErrorCode.COMMENT_NOT_FOUND, e.errorCode)
                    }
                },
            )
        )
        val result = comments.list(p.id, null, "latest", 1, 20)
        if (result.items.isEmpty()) assertEquals(0L, result.totalComments)
        else {
            assertTrue(result.items.single().isDeleted)
            assertEquals(1, result.items.single().replies.size)
            assertEquals(1L, result.totalComments)
        }
    }

    @Test
    fun `POST board posts - 한 글자 본문을 저장하고 익명 작성자 소유권과 UTC 시각을 반환한다`() {
        val response = mockMvc.perform(
            post("/api/v1/board/posts").header("Authorization", "Bearer ${jwt.createAccessToken(author)}")
                .contentType("application/json").content("""{"title":"글","content":"a","isAnonymous":true}""")
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.content").value("a"))
            .andExpect(jsonPath("$.isMine").value(true))
            .andExpect(jsonPath("$.author.displayName").value(BoardPolicy.ANONYMOUS_AUTHOR_NAME))
            .andExpect(jsonPath("$.createdAt").isString).andExpect(jsonPath("$.updatedAt").isString)
            .andReturn()
        val result = objectMapper.readTree(response.response.contentAsString)
        assertNotNull(Instant.parse(result["createdAt"].asText()))
        assertNotNull(Instant.parse(result["updatedAt"].asText()))
    }

    @Test
    fun `PATCH board posts - 수정된 제목 태그와 실명 작성자를 반환한다`() {
        val p = post(anonymous = true)
        mockMvc.perform(
            patch("/api/v1/board/posts/${p.id}").header("Authorization", "Bearer ${jwt.createAccessToken(author)}")
                .contentType("application/json").content("""{"title":"수정","tagNames":["태그"],"isAnonymous":false}""")
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.title").value("수정"))
            .andExpect(jsonPath("$.tagNames[0]").value("태그"))
            .andExpect(jsonPath("$.author.displayName").value("작성자"))
            .andExpect(jsonPath("$.isAnonymous").value(false))
    }

    @Test
    fun `POST comments - 익명 글쓴이 표시와 댓글 생성 시각을 반환한다`() {
        val p = post(anonymous = true)
        val response = mockMvc.perform(
            post("/api/v1/board/posts/${p.id}/comments").header(
                "Authorization",
                "Bearer ${jwt.createAccessToken(author)}"
            )
                .contentType("application/json").content("""{"content":"a","isAnonymous":true}""")
        )
            .andExpect(status().isOk).andExpect(jsonPath("$.isPostAuthor").value(true))
            .andExpect(jsonPath("$.author.displayName").value(BoardPolicy.ANONYMOUS_POST_AUTHOR_NAME))
            .andExpect(jsonPath("$.createdAt").isString).andReturn()
        assertNotNull(Instant.parse(objectMapper.readTree(response.response.contentAsString)["createdAt"].asText()))
    }

    @Test
    fun `POST comments - 본문 누락과 공백 및 유효하지 않은 부모 ID는 400이다`() {
        val p = post()
        listOf("""{"content":null}""", """{"content":" "}""", """{"content":"a","parentId":0}""").forEach {
            mockMvc.perform(
                post("/api/v1/board/posts/${p.id}/comments").header(
                    "Authorization",
                    "Bearer ${jwt.createAccessToken(author)}"
                )
                    .contentType("application/json").content(it)
            ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `DELETE board - 본인 댓글과 게시글 삭제는 본문 없는 204를 반환한다`() {
        val p = post()
        val c = comment(p.id, userId = author)
        val token = jwt.createAccessToken(author)
        mockMvc.perform(delete("/api/v1/board/comments/${c.id}").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent).andExpect(content().string(""))
        mockMvc.perform(delete("/api/v1/board/posts/${p.id}").header("Authorization", "Bearer $token"))
            .andExpect(status().isNoContent).andExpect(content().string(""))
    }

    @Test
    fun `GET board posts - 범위를 넘은 페이지는 전체 개수와 빈 목록을 반환한다`() {
        post()
        mockMvc.perform(get("/api/v1/board/posts?page=2147483647&size=100"))
            .andExpect(status().isOk).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items").isEmpty)
    }

    @Test
    fun `GET board - 비로그인도 조회 가능하고 목록에는 본문과 작성자 개인정보를 포함하지 않는다`() {
        val p = post(true)
        mockMvc.perform(get("/api/v1/board/posts")).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].author.displayName").value(BoardPolicy.ANONYMOUS_AUTHOR_NAME))
            .andExpect(jsonPath("$.items[0].content").doesNotExist())
            .andExpect(jsonPath("$.items[0].author.username").doesNotExist())
        mockMvc.perform(get("/api/v1/board/posts/${p.id}")).andExpect(status().isOk)
            .andExpect(jsonPath("$.isMine").value(false))
        mockMvc.perform(get("/api/v1/board/posts/${p.id}/comments")).andExpect(status().isOk)
    }

    @Test
    fun `board 쓰기 API - 인증 없으면 모두 401이다`() {
        val p = post()
        listOf(
            post("/api/v1/board/posts"),
            patch("/api/v1/board/posts/${p.id}"),
            delete("/api/v1/board/posts/${p.id}"),
            post("/api/v1/board/posts/${p.id}/comments"),
            post("/api/v1/board/posts/${p.id}/like"),
            delete("/api/v1/board/posts/${p.id}/like"),
            delete("/api/v1/board/comments/1"),
            post("/api/v1/board/comments/1/like"),
            delete("/api/v1/board/comments/1/like")
        ).forEach {
            mockMvc.perform(it).andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `POST board posts - 잘못된 JSON 필수값 태그와 행사 ID는 400이다`() {
        val token = jwt.createAccessToken(author)
        listOf(
            "{}", "{", """{"title":"a","content":" "}""",
            """{"title":"a","content":"a","tagNames":null}""",
            """{"title":"a","content":"a","eventId":0}""",
            """{"title":"a","content":"a","tagNames":[" "]}""",
            """{"title":"a","content":"a","tagNames":[null]}"""
        ).forEach {
            mockMvc.perform(
                post("/api/v1/board/posts").header("Authorization", "Bearer $token").contentType("application/json")
                    .content(it)
            )
                .andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `GET board - 잘못된 페이지와 정렬 및 ID를 거부한다`() {
        val p = post()
        listOf("?page=0", "?size=101", "?eventId=0", "?eventId=abc").forEach {
            val response = mockMvc.perform(get("/api/v1/board/posts$it")).andExpect(status().isBadRequest)
            if (it != "?eventId=abc") response.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        }
        mockMvc.perform(get("/api/v1/board/posts/${p.id}/comments?sort=nope")).andExpect(status().isBadRequest)
        mockMvc.perform(get("/api/v1/board/posts/0")).andExpect(status().isBadRequest)
        mockMvc.perform(get("/api/v1/board/posts/99999999")).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"))
    }

    @Test
    fun `board 인증 - 잘못된 토큰은 공개 조회에서 무시하고 쓰기에서는 거부한다`() {
        val p = post()
        val expired = jwt.createToken(author, false, -1000, "ACCESS")
        listOf("wrong", expired, jwt.createAccessToken(Long.MAX_VALUE)).forEach {
            mockMvc.perform(get("/api/v1/board/posts").header("Authorization", "Bearer $it"))
                .andExpect(status().isOk).andExpect(jsonPath("$.items[0].isMine").value(false))
        }
        listOf("wrong", expired).forEach {
            mockMvc.perform(post("/api/v1/board/posts/${p.id}/like").header("Authorization", "Bearer $it"))
                .andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `GET events discussed - 공개 조회에서 글 수와 기본 5개 및 지정 개수를 반환한다`() {
        val eventIds = (1..6).map { number ->
            val id = event("행사$number")
            repeat(number) { post(event = id) }
            id
        }.reversed()
        val response = mockMvc.perform(get("/api/v1/events/discussed"))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(5))
            .andExpect(jsonPath("$.items[0].id").value(eventIds.first()))
            .andExpect(jsonPath("$.items[0].title").value("행사6"))
            .andExpect(jsonPath("$.items[0].postCount").value(6)).andReturn()
        assertEquals(
            eventIds.take(5),
            objectMapper.readTree(response.response.contentAsString)["items"].map { it["id"].asLong() })
        mockMvc.perform(get("/api/v1/events/discussed?size=2"))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[1].id").value(eventIds[1]))
    }

    @Test
    fun `GET events upcoming - 공개 조회에서 마감순 기본 5개 및 한국 시각 문자열을 반환한다`() {
        val base = LocalDateTime.now(ZoneId.of("Asia/Seoul")).plusDays(10)
            .withHour(12).withMinute(0).withSecond(0).withNano(0)
        val deadlines = (1..6).reversed().map { number ->
            event("마감$number", end = base.plusDays(number.toLong())) to base.plusDays(number.toLong())
        }.sortedBy { it.second }
        val response = mockMvc.perform(get("/api/v1/events/upcoming"))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(5))
            .andExpect(jsonPath("$.items[0].id").value(deadlines.first().first))
            .andExpect(jsonPath("$.items[0].title").value("마감1"))
            .andExpect(jsonPath("$.items[0].applyEnd").value(deadlines.first().second.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)))
            .andReturn()
        val items = objectMapper.readTree(response.response.contentAsString)["items"]
        assertEquals(deadlines.take(5).map { it.first }, items.map { it["id"].asLong() })
        items.zip(deadlines).forEach { (item, expected) ->
            assertEquals(expected.second, LocalDateTime.parse(item["applyEnd"].asText()))
        }
        mockMvc.perform(get("/api/v1/events/upcoming?size=2"))
            .andExpect(status().isOk).andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[1].id").value(deadlines[1].first))
    }

    @Test
    fun `GET events board 목록 - size 범위를 벗어나면 400이다`() {
        mockMvc.perform(get("/api/v1/events/discussed?size=0"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
        mockMvc.perform(get("/api/v1/events/upcoming?size=101")).andExpect(status().isBadRequest)
    }

    @Test
    fun `회원 탈퇴 - 실명과 익명 글 및 댓글을 보존하고 작성자를 탈퇴됨으로 표시한다`() {
        val realPost = post(tags = """["보존태그"]""")
        val anonymousPost = post(anonymous = true)
        val root = comment(realPost.id, author)
        val anonymousRoot = comment(anonymousPost.id, author, anonymous = true)
        val reply = comment(realPost.id, other, parent = root.id)
        posts.like(author, realPost.id, true)
        posts.like(other, realPost.id, true)
        comments.like(author, reply.id, true)
        comments.like(other, root.id, true)

        mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer(jwt.createAccessToken(author))))
            .andExpect(status().isNoContent)

        assertFalse(users.existsById(author))
        listOf(realPost, anonymousPost).forEach { original ->
            val detail = posts.detail(original.id, null)
            assertEquals(original.content, detail.content)
            assertEquals(original.tagNames, detail.tagNames)
            assertEquals(BoardPolicy.WITHDRAWN_AUTHOR_NAME, detail.author.displayName)
            assertFalse(detail.isMine)
            assertTrue(posts.list(null, null, null, false, 1, 20).items.any { it.id == original.id })
        }
        listOf(root, anonymousRoot).forEach { original ->
            val actual = query.comment(original.id, null)!!
            assertEquals(original.content, actual.content)
            assertEquals(BoardPolicy.WITHDRAWN_AUTHOR_NAME, actual.author!!.displayName)
            assertFalse(actual.isPostAuthor)
            assertFalse(actual.isMine)
        }
        assertEquals(1L, posts.detail(realPost.id, other).likeCount)
        assertTrue(posts.detail(realPost.id, other).likedByMe)
        assertEquals(1L, query.comment(root.id, other)!!.likeCount)
        assertEquals(0L, query.comment(reply.id, other)!!.likeCount)
        val listed = comments.list(realPost.id, null, "latest", 1, 20)
        assertEquals(2L, listed.totalComments)
        assertEquals(reply.id, listed.items.single().replies.single().id)
        assertEquals("다른 사람", listed.items.single().replies.single().author!!.displayName)
        mockMvc.perform(get("/api/v1/board/posts/${realPost.id}"))
            .andExpect(status().isOk).andExpect(jsonPath("$.author.displayName").value(BoardPolicy.WITHDRAWN_AUTHOR_NAME))
    }

    @Test
    fun `회원 탈퇴 - 익명 댓글과 답글은 남기고 삭제된 익명 번호를 재사용하지 않는다`() {
        val p = post()
        val root = comment(p.id, anonymous = true)
        val reply = comment(p.id, author, parent = root.id)
        assertEquals(BoardPolicy.ANONYMOUS_AUTHOR_NAME + 1, root.author!!.displayName)

        mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer(jwt.createAccessToken(other))))
            .andExpect(status().isNoContent)

        assertEquals(0L, count("board_anonymous_users"))
        val retained = comments.list(p.id, null, "latest", 1, 20).items.single()
        assertEquals(BoardPolicy.WITHDRAWN_AUTHOR_NAME, retained.author!!.displayName)
        assertFalse(retained.isPostAuthor)
        assertEquals(reply.id, retained.replies.single().id)
        val participant = dataGenerator.generateLocalUser().first.id!!
        assertEquals(BoardPolicy.ANONYMOUS_AUTHOR_NAME + 2, comment(p.id, participant, anonymous = true).author!!.displayName)
        posts.delete(author, p.id)
        assertEquals(0L, count("board_comments"))
    }

    @Test
    fun `회원 탈퇴 - 작성자 ID가 모두 null이어도 글쓴이 댓글로 판단하지 않는다`() {
        val p = post()
        val authorComment = comment(p.id, author, anonymous = true)
        val otherComment = comment(p.id, other, anonymous = true)
        assertTrue(query.comment(authorComment.id, null)!!.isPostAuthor)
        mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer(jwt.createAccessToken(author))))
            .andExpect(status().isNoContent)
        assertFalse(query.comment(authorComment.id, null)!!.isPostAuthor)
        assertFalse(query.comment(otherComment.id, null)!!.isPostAuthor)
        mockMvc.perform(delete("/api/v1/users/me").header("Authorization", bearer(jwt.createAccessToken(other))))
            .andExpect(status().isNoContent)

        val listed = comments.list(p.id, null, "latest", 1, 20)
        assertEquals(2L, listed.totalComments)
        listed.items.forEach {
            assertEquals(BoardPolicy.WITHDRAWN_AUTHOR_NAME, it.author!!.displayName)
            assertFalse(it.isPostAuthor)
            assertFalse(it.isMine)
        }
        assertFalse(query.comment(otherComment.id, null)!!.isPostAuthor)
        val participant = dataGenerator.generateLocalUser().first.id!!
        assertFalse(comment(p.id, participant, anonymous = true).isPostAuthor)
        posts.like(participant, p.id, true)
        assertEquals(1L, posts.detail(p.id, participant).likeCount)
        error(ErrorCode.POST_NOT_FOUND) { posts.update(participant, p.id, body("""{"title":"수정"}""")) }
    }
}
