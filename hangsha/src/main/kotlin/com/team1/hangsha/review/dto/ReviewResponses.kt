package com.team1.hangsha.review.dto

import java.time.Instant

/** 작성자 id는 어떤 응답에도 싣지 않는다. 익명 번호와 "작성자" 배지는 서버가 계산한다. */
data class ReviewResponse(
    val id: Long,
    val eventId: Long,
    val eventTitle: String,
    val rating: Int,
    val content: String,
    /** 실명이면 users.username, 익명이면 "익명3" */
    val authorName: String,
    val isAnonymous: Boolean,
    val isMine: Boolean,
    val isEdited: Boolean,
    val likeCount: Long,
    val isLiked: Boolean,
    /** 삭제 자리는 세지 않는다. */
    val commentCount: Long,
    val createdAt: Instant,
)

data class ReviewListResponse(
    /** 후기가 없으면 null */
    val averageRating: Double?,
    val reviewCount: Long,
    val reviews: List<ReviewResponse>,
)

data class ReviewCommentResponse(
    val id: Long,
    val reviewId: Long,
    val parentId: Long?,
    /** isDeleted면 null ("삭제된 댓글입니다" 자리) */
    val content: String?,
    /** isDeleted면 null */
    val authorName: String?,
    val isAnonymous: Boolean,
    val isMine: Boolean,
    val isReviewAuthor: Boolean,
    val isEdited: Boolean,
    val isDeleted: Boolean,
    val likeCount: Long,
    val isLiked: Boolean,
    val createdAt: Instant,
)
