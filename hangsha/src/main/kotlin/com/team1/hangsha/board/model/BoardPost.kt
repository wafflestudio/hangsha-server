package com.team1.hangsha.board.model

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("board_posts")
data class BoardPost(
    @Id val id: Long? = null,
    val userId: Long?,
    val eventId: Long? = null,
    val title: String,
    val content: String,
    val isAnonymous: Boolean = false,
    val nextAnonymousNumber: Long = 1,
    @CreatedDate val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null,
)
