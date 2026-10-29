package com.team1.hangsha.review.model

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("reviews")
data class Review(
    @Id val id: Long? = null,
    val eventId: Long,
    val userId: Long,
    val rating: Int,
    val content: String,
    val isAnonymous: Boolean = false,
    val isDeleted: Boolean = false,
    val isEdited: Boolean = false,
    val nextAnonymousNumber: Long = 1,
    @CreatedDate val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null,
)

@Table("review_comments")
data class ReviewComment(
    @Id val id: Long? = null,
    val reviewId: Long,
    val userId: Long,
    val parentId: Long? = null,
    val content: String,
    val isAnonymous: Boolean = false,
    val isDeleted: Boolean = false,
    val isEdited: Boolean = false,
    @CreatedDate val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null,
)
