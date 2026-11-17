package com.team1.hangsha.review.service

import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.event.repository.EventRepository
import com.team1.hangsha.review.dto.CreateReviewRequest
import com.team1.hangsha.review.dto.ReviewListResponse
import com.team1.hangsha.review.dto.ReviewResponse
import com.team1.hangsha.review.dto.UpdateReviewRequest
import com.team1.hangsha.review.model.Review
import com.team1.hangsha.review.repository.ReviewQueryRepository
import com.team1.hangsha.review.repository.ReviewRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReviewService(
    private val eventRepository: EventRepository,
    private val reviewRepository: ReviewRepository,
    private val reviewQueryRepository: ReviewQueryRepository,
    private val anonAliasService: AnonAliasService,
) {
    @Transactional(readOnly = true)
    fun listEventReviews(eventId: Long, viewerId: Long?): ReviewListResponse {
        requireVisibleEvent(eventId)
        return summarize(reviewQueryRepository.findEventReviews(eventId, viewerId))
    }

    @Transactional(readOnly = true)
    fun listMyReviews(userId: Long): ReviewListResponse =
        summarize(reviewQueryRepository.findUserReviews(userId))

    @Transactional
    fun create(userId: Long, eventId: Long, request: CreateReviewRequest): ReviewResponse {
        requireVisibleEvent(eventId)
        val content = requireContent(request.content)

        if (request.isAnonymous) anonAliasService.ensureAlias(eventId, userId)

        val saved = reviewRepository.save(
            Review(
                userId = userId,
                eventId = eventId,
                rating = request.rating,
                content = content,
                isAnonymous = request.isAnonymous,
            ),
        )
        return requireNotNull(reviewQueryRepository.findReview(requireNotNull(saved.id), userId))
    }

    @Transactional
    fun update(userId: Long, reviewId: Long, request: UpdateReviewRequest): ReviewResponse {
        val review = ownedLiveReview(userId, reviewId)
        if (request.rating == null && request.content == null) {
            throw DomainException(ErrorCode.REVIEW_PATCH_EMPTY)
        }

        reviewRepository.save(
            review.copy(
                rating = request.rating ?: review.rating,
                content = request.content?.let(::requireContent) ?: review.content,
                isEdited = true,
            ),
        )
        return requireNotNull(reviewQueryRepository.findReview(reviewId, userId))
    }

    /** 소프트 삭제. 댓글·좋아요 행은 그대로 두고, 조회에서 후기째로 빠진다. */
    @Transactional
    fun delete(userId: Long, reviewId: Long) {
        val review = ownedLiveReview(userId, reviewId)
        reviewRepository.save(review.copy(isDeleted = true))
    }

    private fun summarize(reviews: List<ReviewResponse>) =
        ReviewListResponse(
            averageRating = reviews.takeIf { it.isNotEmpty() }?.map { it.rating }?.average(),
            reviewCount = reviews.size.toLong(),
            reviews = reviews,
        )

    private fun ownedLiveReview(userId: Long, reviewId: Long): Review {
        val review = reviewRepository.findLiveById(reviewId)
            ?: throw DomainException(ErrorCode.REVIEW_NOT_FOUND)
        if (review.userId != userId) throw DomainException(ErrorCode.REVIEW_FORBIDDEN)
        return review
    }

    private fun requireVisibleEvent(eventId: Long) {
        if (eventRepository.findVisibleById(eventId) == null) {
            throw DomainException(ErrorCode.EVENT_NOT_FOUND)
        }
    }
}

/** 공백만 있는 내용은 거부하고, 앞뒤 공백은 잘라서 저장한다. */
internal fun requireContent(content: String): String =
    content.trim().ifEmpty { throw DomainException(ErrorCode.REVIEW_CONTENT_CANNOT_BE_BLANK) }
