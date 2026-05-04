package com.team1.hangsha.user.model

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("users")
data class User (
    @Id var id: Long? = null,
    var username: String? = null,
    var email: String? = null,
    @Column("profile_image_url")
    var profileImageUrl: String? = null,
    @Column("is_admin")
    var isAdmin: Boolean = false,
    @CreatedDate
    var createdAt: Instant? = null,
    @LastModifiedDate
    var updatedAt: Instant? = null,
)
