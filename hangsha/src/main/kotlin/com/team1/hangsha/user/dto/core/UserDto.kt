package com.team1.hangsha.user.dto.core

import com.team1.hangsha.user.model.User

data class UserDto(
    val id: Long,
    val username: String?,
    val email: String?,
    val profileImageUrl: String?,
) {
    constructor(user: User) : this(user.id!!, user.username, user.email, user.profileImageUrl)
}