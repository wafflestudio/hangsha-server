package com.team1.hangsha.board.dto

import com.team1.hangsha.board.dto.core.CommentResponse
import com.team1.hangsha.board.dto.core.PostSummaryResponse

data class PostPageResponse(val page: Int, val size: Int, val total: Long, val items: List<PostSummaryResponse>)

data class CommentPageResponse(
    val page: Int, val size: Int, val totalRoots: Long, val totalComments: Long, val items: List<CommentResponse>,
)
