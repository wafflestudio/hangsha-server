package com.team1.hangsha.board.dto

import com.team1.hangsha.board.service.BoardPolicy
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class CreatePostRequest(
    @field:NotBlank
    @field:Size(max = BoardPolicy.TITLE_MAX)
    val title: String,

    @field:NotBlank
    @field:Size(max = BoardPolicy.CONTENT_MAX)
    val content: String,

    @field:Positive
    val eventId: Long? = null,

    @field:Size(max = BoardPolicy.TAG_COUNT_MAX)
    val tagNames: List<@NotBlank @Size(max = BoardPolicy.TAG_MAX) String> = emptyList(),
    val isAnonymous: Boolean = false,
)

data class UpdatePostRequest(
    val title: String? = null,
    val content: String? = null,
    val tagNames: List<String>? = null,
    val isAnonymous: Boolean? = null,
)

data class CreateCommentRequest(
    @field:NotBlank
    @field:Size(max = BoardPolicy.COMMENT_MAX)
    val content: String,

    @field:Positive
    val parentId: Long? = null,
    val isAnonymous: Boolean = false,
)
