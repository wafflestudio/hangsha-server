package com.team1.hangsha.board.dto.core

import java.time.Instant

data class AuthorResponse(val displayName: String)
data class BoardEventResponse(val id: Long, val title: String)

data class PostSummaryResponse(
    val id: Long,
    val title: String,
    val event: BoardEventResponse?,
    val tagNames: List<String>,
    val author: AuthorResponse,
    val isAnonymous: Boolean,
    val likeCount: Long,
    val commentCount: Long,
    val likedByMe: Boolean,
    val isMine: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PostDetailResponse(
    val id: Long,
    val title: String,
    val content: String,
    val event: BoardEventResponse?,
    val tagNames: List<String>,
    val author: AuthorResponse,
    val isAnonymous: Boolean,
    val likeCount: Long,
    val commentCount: Long,
    val likedByMe: Boolean,
    val isMine: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CommentResponse(
    val id: Long,
    val postId: Long,
    val parentId: Long?,
    val content: String,
    val author: AuthorResponse?,
    val isAnonymous: Boolean,
    val isPostAuthor: Boolean,
    val isMine: Boolean,
    val isDeleted: Boolean,
    val likeCount: Long,
    val likedByMe: Boolean,
    val createdAt: Instant,
    val replies: List<CommentResponse> = emptyList(),
)
