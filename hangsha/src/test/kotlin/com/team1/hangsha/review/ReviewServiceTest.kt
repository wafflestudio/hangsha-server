package com.team1.hangsha.review

import com.team1.hangsha.category.model.Organization
import com.team1.hangsha.category.repository.OrganizationRepository
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.config.DatabaseConfig
import com.team1.hangsha.event.model.Event
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.review.dto.CreateReviewCommentRequest
import com.team1.hangsha.review.dto.CreateReviewRequest
import com.team1.hangsha.review.dto.ReviewCommentResponse
import com.team1.hangsha.review.dto.ReviewResponse
import com.team1.hangsha.review.dto.UpdateReviewCommentRequest
import com.team1.hangsha.review.dto.UpdateReviewRequest
import com.team1.hangsha.review.model.Review
import com.team1.hangsha.review.repository.AnonAliasRepository
import com.team1.hangsha.review.repository.ReviewLikeRepository
import com.team1.hangsha.review.repository.ReviewQueryRepository
import com.team1.hangsha.review.repository.ReviewRepository
import com.team1.hangsha.review.service.AnonAliasService
import com.team1.hangsha.review.service.ReviewCommentService
import com.team1.hangsha.review.service.ReviewLikeService
import com.team1.hangsha.review.service.ReviewService
import com.team1.hangsha.user.model.User
import com.team1.hangsha.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.data.jdbc.DataJdbcTest
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.data.relational.core.conversion.DbActionExecutionException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.LocalDateTime

/**
 * 후기 서비스와 조회 SQL을 실제 MySQL(Flyway 마이그레이션 적용)에 대고 검증한다.
 * 전체 컨텍스트는 OCI·Redis·Manticore가 필요해서 JDBC 슬라이스만 띄운다. 테스트마다 롤백된다.
 */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test") // test 프로필에는 oci.vault.secret-ids가 없어서 Vault 조회를 건너뛴다.
@Import(
    DatabaseConfig::class,
    ReviewQueryRepository::class,
    ReviewLikeRepository::class,
    AnonAliasRepository::class,
    AnonAliasService::class,
    ReviewService::class,
    ReviewCommentService::class,
    ReviewLikeService::class,
)
class ReviewServiceTest {

    @Autowired lateinit var reviewService: ReviewService
    @Autowired lateinit var commentService: ReviewCommentService
    @Autowired lateinit var likeService: ReviewLikeService
    @Autowired lateinit var reviewRepository: ReviewRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var eventRepository: EventRepository
    @Autowired lateinit var organizationRepository: OrganizationRepository
    @Autowired lateinit var jdbc: NamedParameterJdbcTemplate

    private var seq = 0

    // ----------------------------
    // 목록 · 평균 · 개수
    // ----------------------------

    @Test
    fun `작성하면 목록에 보이고 평균과 개수가 맞다, 지우면 빠지고 다시 계산된다`() {
        val event = event()
        val a = user()
        val b = user()
        val r1 = writeReview(a, event, rating = 5)
        writeReview(b, event, rating = 2)

        val before = reviewService.listEventReviews(event.id!!, null)
        assertEquals(2, before.reviewCount)
        assertEquals(3.5, before.averageRating)

        reviewService.delete(a.id!!, r1.id)

        val after = reviewService.listEventReviews(event.id!!, null)
        assertEquals(1, after.reviewCount)
        assertEquals(2.0, after.averageRating)
        assertEquals(listOf(2), after.reviews.map { it.rating })
    }

    @Test
    fun `후기가 없으면 평균은 null`() {
        val res = reviewService.listEventReviews(event().id!!, null)
        assertEquals(0, res.reviewCount)
        assertNull(res.averageRating)
    }

    @Test
    fun `목록은 최신순이고 행사 제목이 실린다`() {
        val event = event(title = "새내기 배움터")
        val a = user()
        val first = writeReview(a, event)
        val second = writeReview(a, event)

        val reviews = reviewService.listEventReviews(event.id!!, null).reviews
        assertEquals(listOf(second.id, first.id), reviews.map { it.id })
        assertEquals("새내기 배움터", reviews.first().eventTitle)
    }

    @Test
    fun `한 사람이 한 행사에 후기를 여러 개 쓸 수 있다`() {
        val event = event()
        val a = user()
        writeReview(a, event)
        writeReview(a, event)
        assertEquals(2, reviewService.listEventReviews(event.id!!, null).reviewCount)
    }

    @Test
    fun `isMine과 isLiked는 보는 사람 기준이고 비로그인이면 false`() {
        val event = event()
        val a = user()
        val b = user()
        val review = writeReview(a, event)
        likeService.likeReview(b.id!!, review.id)

        val asAuthor = reviewService.listEventReviews(event.id!!, a.id).reviews.single()
        assertTrue(asAuthor.isMine)
        assertFalse(asAuthor.isLiked)

        val asLiker = reviewService.listEventReviews(event.id!!, b.id).reviews.single()
        assertFalse(asLiker.isMine)
        assertTrue(asLiker.isLiked)

        val anonymous = reviewService.listEventReviews(event.id!!, null).reviews.single()
        assertFalse(anonymous.isMine)
        assertFalse(anonymous.isLiked)
        assertEquals(1, anonymous.likeCount)
    }

    @Test
    fun `내 후기는 내 것만, 평균과 개수를 함께 준다`() {
        val a = user()
        val b = user()
        writeReview(a, event(), rating = 4)
        writeReview(a, event(), rating = 2)
        writeReview(b, event(), rating = 5)

        val mine = reviewService.listMyReviews(a.id!!)
        assertEquals(2, mine.reviewCount)
        assertEquals(3.0, mine.averageRating)
        assertTrue(mine.reviews.all { it.isMine })
    }

    // ----------------------------
    // 삭제 (D1, D3, D5)
    // ----------------------------

    @Test
    fun `후기를 지우면 댓글 목록과 댓글 좋아요가 404, 내 후기에서도 빠진다`() {
        val event = event()
        val a = user()
        val b = user()
        val review = writeReview(a, event)
        val comment = writeComment(b, review.id)

        reviewService.delete(a.id!!, review.id)

        assertError(ErrorCode.REVIEW_NOT_FOUND) { commentService.list(review.id, null) }
        assertError(ErrorCode.REVIEW_COMMENT_NOT_FOUND) { likeService.likeComment(b.id!!, comment.id) }
        assertError(ErrorCode.REVIEW_NOT_FOUND) { likeService.likeReview(b.id!!, review.id) }
        assertError(ErrorCode.REVIEW_NOT_FOUND) { writeComment(b, review.id) }
        assertEquals(0, reviewService.listMyReviews(a.id!!).reviewCount)
    }

    @Test
    fun `후기를 지워도 댓글과 좋아요 행은 남는다`() {
        val event = event()
        val a = user()
        val b = user()
        val review = writeReview(a, event)
        writeComment(b, review.id)
        likeService.likeReview(b.id!!, review.id)

        reviewService.delete(a.id!!, review.id)

        assertEquals(1, count("SELECT COUNT(*) FROM review_comments WHERE review_id = ${review.id}"))
        assertEquals(1, count("SELECT COUNT(*) FROM review_likes WHERE review_id = ${review.id}"))
    }

    @Test
    fun `답글 없는 댓글은 지우면 목록에서 빠진다`() {
        val a = user()
        val review = writeReview(a, event())
        val comment = writeComment(a, review.id)

        commentService.delete(a.id!!, comment.id)

        assertTrue(commentService.list(review.id, null).isEmpty())
    }

    @Test
    fun `답글 달린 댓글을 지우면 자리로 남고, 답글까지 지우면 둘 다 빠진다`() {
        val a = user()
        val b = user()
        val review = writeReview(a, event())
        val parent = writeComment(a, review.id)
        val reply = writeComment(b, review.id, parentId = parent.id)

        commentService.delete(a.id!!, parent.id)

        val withPlaceholder = commentService.list(review.id, a.id)
        assertEquals(listOf(parent.id, reply.id), withPlaceholder.map { it.id })
        val placeholder = withPlaceholder.first()
        assertTrue(placeholder.isDeleted)
        assertNull(placeholder.content)
        assertNull(placeholder.authorName)
        assertFalse(placeholder.isMine)
        assertEquals(parent.id, withPlaceholder.last().parentId)

        commentService.delete(b.id!!, reply.id)

        assertTrue(commentService.list(review.id, null).isEmpty())
    }

    @Test
    fun `commentCount는 지운 댓글과 삭제 자리를 세지 않는다`() {
        val event = event()
        val a = user()
        val review = writeReview(a, event)
        val parent = writeComment(a, review.id)
        writeComment(a, review.id, parentId = parent.id)
        writeComment(a, review.id)

        commentService.delete(a.id!!, parent.id)

        assertEquals(2, reviewService.listEventReviews(event.id!!, null).reviews.single().commentCount)
    }

    @Test
    fun `지운 댓글은 수정, 삭제, 좋아요 모두 404`() {
        val a = user()
        val review = writeReview(a, event())
        val comment = writeComment(a, review.id)
        commentService.delete(a.id!!, comment.id)

        assertError(ErrorCode.REVIEW_COMMENT_NOT_FOUND) {
            commentService.update(a.id!!, comment.id, UpdateReviewCommentRequest("다시"))
        }
        assertError(ErrorCode.REVIEW_COMMENT_NOT_FOUND) { commentService.delete(a.id!!, comment.id) }
        assertError(ErrorCode.REVIEW_COMMENT_NOT_FOUND) { likeService.likeComment(a.id!!, comment.id) }
    }

    // ----------------------------
    // 익명 (A1 ~ A3)
    // ----------------------------

    @Test
    fun `익명 번호는 행사 안에서 같은 사람은 같고 다른 사람은 다르다`() {
        val event = event()
        val a = user()
        val b = user()
        val r1 = writeReview(a, event, anonymous = true)
        val r2 = writeReview(b, event, anonymous = true)
        val r3 = writeReview(a, event, anonymous = true)
        val c = writeComment(a, r2.id, anonymous = true)

        assertEquals("익명1", r1.authorName)
        assertEquals("익명2", r2.authorName)
        assertEquals("익명1", r3.authorName)
        assertEquals("익명1", c.authorName)
        assertTrue(r1.isAnonymous)
    }

    @Test
    fun `익명 번호는 행사마다 따로 매긴다`() {
        val a = user()
        val b = user()
        writeReview(a, event(), anonymous = true)
        val other = event()
        val r = writeReview(b, other, anonymous = true)
        assertEquals("익명1", r.authorName)
    }

    @Test
    fun `앞선 익명 글을 지워도 번호가 밀리지 않는다`() {
        val event = event()
        val a = user()
        val b = user()
        val first = writeReview(a, event, anonymous = true)
        writeReview(b, event, anonymous = true)

        reviewService.delete(a.id!!, first.id)

        assertEquals("익명2", reviewService.listEventReviews(event.id!!, null).reviews.single().authorName)
        val c = user()
        assertEquals("익명3", writeReview(c, event, anonymous = true).authorName)
    }

    @Test
    fun `실명이면 username을 보여준다`() {
        val a = user(name = "관악산다람쥐")
        assertEquals("관악산다람쥐", writeReview(a, event()).authorName)
    }

    @Test
    fun `응답에 작성자 id가 없다`() {
        listOf(ReviewResponse::class.java, ReviewCommentResponse::class.java).forEach { type ->
            val fields = type.declaredFields.map { it.name }
            assertFalse(fields.any { it == "userId" || it == "authorId" }, "${type.simpleName}: $fields")
        }
    }

    @Test
    fun `수정 요청에는 isAnonymous 필드가 아예 없다`() {
        val fields = UpdateReviewRequest::class.java.declaredFields.map { it.name }
        assertFalse("isAnonymous" in fields)
    }

    @Test
    fun `후기 작성자가 단 댓글에는 작성자 배지가 붙는다`() {
        val a = user()
        val b = user()
        val review = writeReview(a, event())
        val byAuthor = writeComment(a, review.id)
        val byOther = writeComment(b, review.id)

        assertTrue(byAuthor.isReviewAuthor)
        assertFalse(byOther.isReviewAuthor)
    }

    // ----------------------------
    // 답글 (W1)
    // ----------------------------

    @Test
    fun `답글의 답글은 400`() {
        val a = user()
        val review = writeReview(a, event())
        val parent = writeComment(a, review.id)
        val reply = writeComment(a, review.id, parentId = parent.id)

        assertError(ErrorCode.REVIEW_INVALID_PARENT) { writeComment(a, review.id, parentId = reply.id) }
    }

    @Test
    fun `다른 후기의 댓글이나 지운 댓글에는 답글을 달 수 없다`() {
        val event = event()
        val a = user()
        val review = writeReview(a, event)
        val otherReview = writeReview(a, event)
        val otherComment = writeComment(a, otherReview.id)
        val deleted = writeComment(a, review.id)
        commentService.delete(a.id!!, deleted.id)

        assertError(ErrorCode.REVIEW_INVALID_PARENT) { writeComment(a, review.id, parentId = otherComment.id) }
        assertError(ErrorCode.REVIEW_INVALID_PARENT) { writeComment(a, review.id, parentId = deleted.id) }
        assertError(ErrorCode.REVIEW_INVALID_PARENT) { writeComment(a, review.id, parentId = 999_999) }
    }

    @Test
    fun `댓글과 답글은 평평한 목록으로 작성순`() {
        val a = user()
        val review = writeReview(a, event())
        val c1 = writeComment(a, review.id)
        val c2 = writeComment(a, review.id)
        val reply = writeComment(a, review.id, parentId = c1.id)

        val list = commentService.list(review.id, null)
        assertEquals(listOf(c1.id, c2.id, reply.id), list.map { it.id })
        assertEquals(listOf(null, null, c1.id), list.map { it.parentId })
    }

    // ----------------------------
    // 수정 · 권한 (D1, W2)
    // ----------------------------

    @Test
    fun `남의 후기와 댓글은 수정, 삭제할 수 없다 (403)`() {
        val a = user()
        val b = user()
        val review = writeReview(a, event())
        val comment = writeComment(a, review.id)

        assertError(ErrorCode.REVIEW_FORBIDDEN) { reviewService.update(b.id!!, review.id, UpdateReviewRequest(rating = 1)) }
        assertError(ErrorCode.REVIEW_FORBIDDEN) { reviewService.delete(b.id!!, review.id) }
        assertError(ErrorCode.REVIEW_FORBIDDEN) {
            commentService.update(b.id!!, comment.id, UpdateReviewCommentRequest("x"))
        }
        assertError(ErrorCode.REVIEW_FORBIDDEN) { commentService.delete(b.id!!, comment.id) }
    }

    @Test
    fun `수정하면 isEdited가 true가 되고 바꾼 항목만 바뀐다`() {
        val a = user()
        val review = writeReview(a, event(), rating = 3)
        assertFalse(review.isEdited)

        val updated = reviewService.update(a.id!!, review.id, UpdateReviewRequest(content = "  고쳤어요  "))
        assertTrue(updated.isEdited)
        assertEquals("고쳤어요", updated.content)
        assertEquals(3, updated.rating)

        val comment = writeComment(a, review.id)
        assertFalse(comment.isEdited)
        assertTrue(commentService.update(a.id!!, comment.id, UpdateReviewCommentRequest("고침")).isEdited)
    }

    @Test
    fun `빈 수정 요청과 공백만 있는 내용은 400`() {
        val a = user()
        val event = event()
        val review = writeReview(a, event)

        assertError(ErrorCode.REVIEW_PATCH_EMPTY) { reviewService.update(a.id!!, review.id, UpdateReviewRequest()) }
        assertError(ErrorCode.REVIEW_CONTENT_CANNOT_BE_BLANK) {
            reviewService.update(a.id!!, review.id, UpdateReviewRequest(content = "   "))
        }
        assertError(ErrorCode.REVIEW_CONTENT_CANNOT_BE_BLANK) {
            reviewService.create(a.id!!, event.id!!, CreateReviewRequest(rating = 3, content = "  "))
        }
    }

    @Test
    fun `별점은 DB에서도 1~5만 받는다`() {
        val a = user()
        val event = event()
        val e = assertThrows<DbActionExecutionException> {
            reviewRepository.save(Review(userId = a.id!!, eventId = event.id!!, rating = 6, content = "x"))
        }
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { "ck_reviews_rating" in (it.message ?: "") })
    }

    // ----------------------------
    // 좋아요
    // ----------------------------

    @Test
    fun `좋아요를 두 번 눌러도 1, 취소를 두 번 해도 0`() {
        val event = event()
        val a = user()
        val review = writeReview(a, event)
        val comment = writeComment(a, review.id)

        likeService.likeReview(a.id!!, review.id)
        likeService.likeReview(a.id!!, review.id)
        likeService.likeComment(a.id!!, comment.id)
        likeService.likeComment(a.id!!, comment.id)
        assertEquals(1, reviewService.listEventReviews(event.id!!, a.id).reviews.single().likeCount)
        assertEquals(1, commentService.list(review.id, a.id).single().likeCount)

        repeat(2) {
            likeService.unlikeReview(a.id!!, review.id)
            likeService.unlikeComment(a.id!!, comment.id)
        }
        assertEquals(0, reviewService.listEventReviews(event.id!!, a.id).reviews.single().likeCount)
        assertEquals(0, commentService.list(review.id, a.id).single().likeCount)
    }

    // ----------------------------
    // 관리자가 지운 행사 (D4)
    // ----------------------------

    @Test
    fun `관리자가 지운 행사의 후기는 조회되지 않는다`() {
        val event = event()
        val a = user()
        val review = writeReview(a, event)
        eventRepository.softDeleteById(event.id!!)

        assertError(ErrorCode.EVENT_NOT_FOUND) { reviewService.listEventReviews(event.id!!, null) }
        assertError(ErrorCode.EVENT_NOT_FOUND) { writeReview(a, event) }
        assertEquals(0, reviewService.listMyReviews(a.id!!).reviewCount)
        assertError(ErrorCode.REVIEW_NOT_FOUND) { commentService.list(review.id, null) }
        assertError(ErrorCode.REVIEW_NOT_FOUND) { likeService.likeReview(a.id!!, review.id) }
    }

    // ----------------------------
    // 회원 탈퇴 (D2)
    // ----------------------------

    @Test
    fun `탈퇴하면 그 사람 후기, 댓글, 좋아요, 익명 번호와 그 후기에 달린 남의 댓글이 사라진다`() {
        val event = event()
        val leaver = user()
        val stayer = user()
        val leaversReview = writeReview(leaver, event, anonymous = true)
        writeComment(stayer, leaversReview.id)
        val staysReview = writeReview(stayer, event)
        writeComment(leaver, staysReview.id)
        likeService.likeReview(leaver.id!!, staysReview.id)

        userRepository.deleteById(leaver.id!!)

        val uid = leaver.id
        assertEquals(0, count("SELECT COUNT(*) FROM reviews WHERE user_id = $uid"))
        assertEquals(0, count("SELECT COUNT(*) FROM review_comments WHERE review_id = ${leaversReview.id}"))
        assertEquals(0, count("SELECT COUNT(*) FROM review_comments WHERE user_id = $uid"))
        assertEquals(0, count("SELECT COUNT(*) FROM review_likes WHERE user_id = $uid"))
        assertEquals(0, count("SELECT COUNT(*) FROM review_anon_aliases WHERE user_id = $uid"))
        assertEquals(1, reviewService.listEventReviews(event.id!!, null).reviewCount)
    }

    // ----------------------------
    // helpers
    // ----------------------------

    private fun user(name: String? = null): User {
        val n = ++seq
        return userRepository.save(User(username = name ?: "review-user-$n", email = "review$n@test.com"))
    }

    private fun event(title: String? = null): Event {
        val n = ++seq
        val org = organizationRepository.save(Organization(name = "review-org-$n", sortOrder = n))
        val now = LocalDateTime.now()
        return eventRepository.save(
            Event(
                title = title ?: "review-event-$n",
                orgId = org.id!!,
                applyStart = now.minusDays(3),
                applyEnd = now.plusDays(7),
                eventStart = now.plusDays(10),
                eventEnd = now.plusDays(10).plusHours(2),
                applyCount = 0,
            ),
        )
    }

    private fun writeReview(user: User, event: Event, rating: Int = 4, anonymous: Boolean = false): ReviewResponse =
        reviewService.create(
            user.id!!,
            event.id!!,
            CreateReviewRequest(rating = rating, content = "후기-${++seq}", isAnonymous = anonymous),
        )

    private fun writeComment(
        user: User,
        reviewId: Long,
        parentId: Long? = null,
        anonymous: Boolean = false,
    ): ReviewCommentResponse =
        commentService.create(
            user.id!!,
            reviewId,
            CreateReviewCommentRequest(content = "댓글-${++seq}", isAnonymous = anonymous, parentId = parentId),
        )

    private fun count(sql: String): Long =
        jdbc.queryForObject(sql, emptyMap<String, Any>(), Long::class.java) ?: 0

    private fun assertError(expected: ErrorCode, block: () -> Unit) {
        val e = assertThrows<DomainException> { block() }
        assertEquals(expected, e.errorCode)
    }
}
