package com.team1.hangsha.review.model

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("review_comments")
data class ReviewComment(
    @Id
    val id: Long? = null,

    @Column("review_id")
    val reviewId: Long,

    /** null이면 최상위 댓글, 값이 있으면 그 댓글의 답글 (1단까지). */
    @Column("parent_id")
    val parentId: Long? = null,

    @Column("user_id")
    val userId: Long,

    val content: String,

    @Column("is_anonymous")
    val isAnonymous: Boolean = false,

    @Column("is_deleted")
    val isDeleted: Boolean = false,

    @Column("is_edited")
    val isEdited: Boolean = false,

    @CreatedDate
    val createdAt: Instant? = null,

    @LastModifiedDate
    val updatedAt: Instant? = null,
)
