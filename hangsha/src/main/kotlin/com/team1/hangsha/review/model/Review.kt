package com.team1.hangsha.review.model

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("reviews")
data class Review(
    @Id
    val id: Long? = null,

    @Column("user_id")
    val userId: Long,

    @Column("event_id")
    val eventId: Long,

    val rating: Int,
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
