package com.team1.hangsha.review.service

import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.review.dto.CreateReviewCommentRequest
import com.team1.hangsha.review.dto.ReviewCommentResponse
import com.team1.hangsha.review.dto.UpdateReviewCommentRequest
import com.team1.hangsha.review.model.ReviewComment
import com.team1.hangsha.review.repository.ReviewCommentRepository
import com.team1.hangsha.review.repository.ReviewQueryRepository
import com.team1.hangsha.review.repository.ReviewRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReviewCommentService(
    private val reviewRepository: ReviewRepository,
    private val reviewCommentRepository: ReviewCommentRepository,
    private val reviewQueryRepository: ReviewQueryRepository,
    private val anonAliasService: AnonAliasService,
) {
    @Transactional(readOnly = true)
    fun list(reviewId: Long, viewerId: Long?): List<ReviewCommentResponse> {
        reviewRepository.findLiveById(reviewId) ?: throw DomainException(ErrorCode.REVIEW_NOT_FOUND)
        return reviewQueryRepository.findComments(reviewId, viewerId)
    }

    @Transactional
    fun create(userId: Long, reviewId: Long, request: CreateReviewCommentRequest): ReviewCommentResponse {
        val review = reviewRepository.findLiveById(reviewId)
            ?: throw DomainException(ErrorCode.REVIEW_NOT_FOUND)
        val content = requireContent(request.content)

        // 답글은 1단까지. 부모가 답글이거나, 지워졌거나, 다른 후기의 댓글이면 거부한다.
        request.parentId?.let { parentId ->
            val parent = reviewCommentRepository.findById(parentId).orElse(null)
            if (parent == null || parent.reviewId != reviewId || parent.isDeleted || parent.parentId != null) {
                throw DomainException(ErrorCode.REVIEW_INVALID_PARENT)
            }
        }

        if (request.isAnonymous) anonAliasService.ensureAlias(review.eventId, userId)

        val saved = reviewCommentRepository.save(
            ReviewComment(
                reviewId = reviewId,
                parentId = request.parentId,
                userId = userId,
                content = content,
                isAnonymous = request.isAnonymous,
            ),
        )
        return requireNotNull(reviewQueryRepository.findComment(requireNotNull(saved.id), userId))
    }

    @Transactional
    fun update(userId: Long, commentId: Long, request: UpdateReviewCommentRequest): ReviewCommentResponse {
        val comment = ownedLiveComment(userId, commentId)
        reviewCommentRepository.save(comment.copy(content = requireContent(request.content), isEdited = true))
        return requireNotNull(reviewQueryRepository.findComment(commentId, userId))
    }

    /** 소프트 삭제. 살아 있는 답글이 있으면 조회에서 "삭제된 댓글입니다" 자리로 남는다. */
    @Transactional
    fun delete(userId: Long, commentId: Long) {
        val comment = ownedLiveComment(userId, commentId)
        reviewCommentRepository.save(comment.copy(isDeleted = true))
    }

    private fun ownedLiveComment(userId: Long, commentId: Long): ReviewComment {
        val comment = reviewCommentRepository.findLiveById(commentId)
            ?: throw DomainException(ErrorCode.REVIEW_COMMENT_NOT_FOUND)
        if (comment.userId != userId) throw DomainException(ErrorCode.REVIEW_FORBIDDEN)
        return comment
    }
}
