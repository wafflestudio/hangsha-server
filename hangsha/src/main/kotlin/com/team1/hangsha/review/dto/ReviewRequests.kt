package com.team1.hangsha.review.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

const val REVIEW_CONTENT_MAX_LENGTH = 500

data class CreateReviewRequest(
    @field:Min(1) @field:Max(5)
    val rating: Int,

    @field:NotBlank @field:Size(max = REVIEW_CONTENT_MAX_LENGTH)
    val content: String,

    val isAnonymous: Boolean = false,
)

/** 익명 여부는 작성 후 바꿀 수 없어서 필드 자체가 없다. */
data class UpdateReviewRequest(
    @field:Min(1) @field:Max(5)
    val rating: Int? = null,

    @field:Size(max = REVIEW_CONTENT_MAX_LENGTH)
    val content: String? = null,
)

data class CreateReviewCommentRequest(
    @field:NotBlank @field:Size(max = REVIEW_CONTENT_MAX_LENGTH)
    val content: String,

    val isAnonymous: Boolean = false,

    /** 답글이면 최상위 댓글 id. */
    val parentId: Long? = null,
)

data class UpdateReviewCommentRequest(
    @field:NotBlank @field:Size(max = REVIEW_CONTENT_MAX_LENGTH)
    val content: String,
)
