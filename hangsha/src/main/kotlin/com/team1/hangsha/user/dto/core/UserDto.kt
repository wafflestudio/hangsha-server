package com.team1.hangsha.user.dto.core

import com.team1.hangsha.user.model.User
import com.team1.hangsha.user.dto.Preference.ListInterestCategoryResponse

data class UserDto(
    val id: Long,
    val username: String?,
    val email: String?,
    val profileImageUrl: String,
    val interestCategories: List<ListInterestCategoryResponse.Item> = emptyList()
) {
    constructor(
        user: User,
        interestCategories: List<ListInterestCategoryResponse.Item> = emptyList()
    ) : this(
        id = user.id!!,
        username = user.username,
        email = user.email,
        profileImageUrl = user.profileImageUrl ?: "https://hangsha.site/static/default-profile.png",
        interestCategories = interestCategories
    )
}