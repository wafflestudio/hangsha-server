package com.team1.hangsha.review.service

import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.review.dto.*
import com.team1.hangsha.review.model.Review
import com.team1.hangsha.review.model.ReviewComment
import com.team1.hangsha.review.repository.*
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReviewService(
    private val events: EventRepository,
    private val reviews: ReviewRepository,
    private val comments: ReviewCommentRepository,
    private val query: ReviewQueryRepository,
    private val relations: ReviewRelationsRepository,
) {
    @Transactional(readOnly = true)
    fun list(eventId: Long, viewer: Long?, page: Int, size: Int): ReviewPageResponse {
        requireVisibleEvent(eventId)
        return query.reviews(eventId, viewer, page, size)
    }

    @Transactional(readOnly = true)
    fun myReviews(userId: Long, page: Int, size: Int): ReviewPageResponse = query.myReviews(userId, page, size)

    @Transactional
    fun create(userId: Long, eventId: Long, request: CreateReviewRequest): ReviewResponse {
        requireVisibleEvent(eventId)
        val review = reviews.save(Review(eventId = eventId, userId = userId, rating = request.rating, content = request.content.trim(), isAnonymous = request.isAnonymous))
        if (request.isAnonymous) relations.ensureAnonymous(review, userId)
        return checkNotNull(query.review(review.id!!, userId))
    }

    @Transactional
    fun update(userId: Long, reviewId: Long, request: UpdateReviewRequest): ReviewResponse {
        val review = liveReviewForOwner(userId, reviewId)
        if (request.rating == null && request.content == null) invalid("수정할 항목이 없습니다")
        val content = request.content?.trim()?.also { if (it.isEmpty()) invalid("후기 내용은 비워둘 수 없습니다") } ?: review.content
        val updated = reviews.save(review.copy(rating = request.rating ?: review.rating, content = content, isEdited = true))
        return checkNotNull(query.review(updated.id!!, userId))
    }

    /** The review remains as a placeholder, but all of its comments are hidden from reads. */
    @Transactional
    fun delete(userId: Long, reviewId: Long) {
        liveReviewForOwner(userId, reviewId)
        relations.tombstoneReview(reviewId)
    }

    @Transactional(readOnly = true)
    fun comments(reviewId: Long, viewer: Long?): List<ReviewCommentResponse> {
        val review = reviews.findByIdOrNull(reviewId) ?: notFound()
        if (review.isDeleted) return emptyList()
        return query.comments(reviewId, viewer)
    }

    @Transactional
    fun createComment(userId: Long, reviewId: Long, request: CreateReviewCommentRequest): ReviewCommentResponse {
        val review = lockLiveReview(reviewId)
        request.parentId?.let { parentId ->
            val parent = comments.lockById(parentId) ?: notFound()
            if (parent.reviewId != reviewId || parent.isDeleted) notFound()
            if (parent.parentId != null) invalid("답글은 1단계까지만 가능합니다")
        }
        if (request.isAnonymous) relations.ensureAnonymous(review, userId)
        val comment = comments.save(ReviewComment(reviewId = reviewId, userId = userId, parentId = request.parentId, content = request.content.trim(), isAnonymous = request.isAnonymous))
        return checkNotNull(query.comment(comment.id!!, userId))
    }

    @Transactional
    fun updateComment(userId: Long, commentId: Long, request: UpdateReviewCommentRequest): ReviewCommentResponse {
        val comment = liveCommentForOwner(userId, commentId)
        val content = request.content.trim().also { if (it.isEmpty()) invalid("댓글 내용은 비워둘 수 없습니다") }
        val updated = comments.save(comment.copy(content = content, isEdited = true))
        return checkNotNull(query.comment(updated.id!!, userId))
    }

    @Transactional
    fun deleteComment(userId: Long, commentId: Long) {
        val comment = liveCommentForOwner(userId, commentId)
        // Deletion always leaves a visible "deleted comment" placeholder, including leaf comments.
        relations.tombstoneComment(comment.id!!)
    }

    @Transactional
    fun likeReview(userId: Long, reviewId: Long, add: Boolean) {
        lockLiveReview(reviewId)
        relations.reviewLike(reviewId, userId, add)
    }

    @Transactional
    fun likeComment(userId: Long, commentId: Long, add: Boolean) {
        lockLiveComment(commentId)
        relations.commentLike(commentId, userId, add)
    }

    private fun requireVisibleEvent(eventId: Long) { if (events.findVisibleById(eventId) == null) throw DomainException(ErrorCode.EVENT_NOT_FOUND) }
    private fun lockLiveReview(reviewId: Long): Review = reviews.lockById(reviewId)?.takeUnless { it.isDeleted } ?: notFound()
    private fun liveReviewForOwner(userId: Long, reviewId: Long): Review = lockLiveReview(reviewId).also { if (it.userId != userId) notFound() }
    private fun lockLiveComment(commentId: Long): ReviewComment = comments.lockById(commentId)?.takeUnless { it.isDeleted } ?: notFound()
    private fun liveCommentForOwner(userId: Long, commentId: Long): ReviewComment = lockLiveComment(commentId).also { if (it.userId != userId) notFound() }
    private fun notFound(): Nothing = throw DomainException(ErrorCode.INVALID_REQUEST, "후기 또는 댓글을 찾을 수 없습니다")
    private fun invalid(message: String): Nothing = throw DomainException(ErrorCode.INVALID_REQUEST, message)
}
