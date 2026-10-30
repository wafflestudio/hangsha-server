package com.team1.hangsha.review.service

import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import com.team1.hangsha.review.repository.ReviewCommentRepository
import com.team1.hangsha.review.repository.ReviewLikeRepository
import com.team1.hangsha.review.repository.ReviewRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 좋아요·취소는 이미 눌렀거나 안 눌렀어도 에러 없이 끝난다. 지워진 대상이면 404. */
@Service
class ReviewLikeService(
    private val reviewRepository: ReviewRepository,
    private val reviewCommentRepository: ReviewCommentRepository,
    private val reviewLikeRepository: ReviewLikeRepository,
) {
    @Transactional
    fun likeReview(userId: Long, reviewId: Long) {
        requireLiveReview(reviewId)
        reviewLikeRepository.insertReviewLike(userId, reviewId)
    }

    @Transactional
    fun unlikeReview(userId: Long, reviewId: Long) {
        requireLiveReview(reviewId)
        reviewLikeRepository.deleteReviewLike(userId, reviewId)
    }

    @Transactional
    fun likeComment(userId: Long, commentId: Long) {
        requireLiveComment(commentId)
        reviewLikeRepository.insertCommentLike(userId, commentId)
    }

    @Transactional
    fun unlikeComment(userId: Long, commentId: Long) {
        requireLiveComment(commentId)
        reviewLikeRepository.deleteCommentLike(userId, commentId)
    }

    private fun requireLiveReview(reviewId: Long) {
        reviewRepository.findLiveById(reviewId) ?: throw DomainException(ErrorCode.REVIEW_NOT_FOUND)
    }

    private fun requireLiveComment(commentId: Long) {
        reviewCommentRepository.findLiveById(commentId) ?: throw DomainException(ErrorCode.REVIEW_COMMENT_NOT_FOUND)
    }
}
