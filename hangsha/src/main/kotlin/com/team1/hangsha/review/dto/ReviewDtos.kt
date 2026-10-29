package com.team1.hangsha.review.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant

private const val REVIEW_CONTENT_MAX = 2_000
private const val COMMENT_CONTENT_MAX = 1_000

data class CreateReviewRequest(
    @field:Min(1) @field:Max(5) val rating: Int,
    @field:NotBlank @field:Size(max = REVIEW_CONTENT_MAX) val content: String,
    val isAnonymous: Boolean = false,
)

data class UpdateReviewRequest(
    @field:Min(1) @field:Max(5) val rating: Int? = null,
    @field:Size(max = REVIEW_CONTENT_MAX) val content: String? = null,
)

data class CreateReviewCommentRequest(
    @field:NotBlank @field:Size(max = COMMENT_CONTENT_MAX) val content: String,
    @field:Positive val parentId: Long? = null,
    val isAnonymous: Boolean = false,
)

data class UpdateReviewCommentRequest(@field:NotBlank @field:Size(max = COMMENT_CONTENT_MAX) val content: String)

data class ReviewAuthorResponse(val displayName: String)

data class ReviewResponse(
    val id: Long,
    val eventId: Long,
    val rating: Int,
    val content: String,
    val author: ReviewAuthorResponse?,
    val isAnonymous: Boolean,
    val isMine: Boolean,
    val isDeleted: Boolean,
    val isEdited: Boolean,
    val likeCount: Long,
    val likedByMe: Boolean,
    val commentCount: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class ReviewCommentResponse(
    val id: Long,
    val reviewId: Long,
    val parentId: Long?,
    val content: String,
    val author: ReviewAuthorResponse?,
    val isAnonymous: Boolean,
    val isReviewAuthor: Boolean,
    val isMine: Boolean,
    val isDeleted: Boolean,
    val isEdited: Boolean,
    val likeCount: Long,
    val likedByMe: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val replies: List<ReviewCommentResponse> = emptyList(),
)

data class ReviewPageResponse(val page: Int, val size: Int, val total: Long, val items: List<ReviewResponse>)
