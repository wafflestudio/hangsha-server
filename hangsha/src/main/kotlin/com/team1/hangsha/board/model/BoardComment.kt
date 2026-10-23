package com.team1.hangsha.board.model

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("board_comments")
data class BoardComment(
    @Id val id: Long? = null,
    val postId: Long,
    val userId: Long,
    val parentId: Long? = null,
    val content: String,
    val isAnonymous: Boolean = false,
    val isDeleted: Boolean = false,
    @CreatedDate val createdAt: Instant? = null,
)
