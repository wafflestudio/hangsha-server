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
        profileImageUrl = user.profileImageUrl ?: "https://objectstorage.ap-chuncheon-1.oraclecloud.com/n/ax1dvc8vmenm/b/hangsha-asset/o/default/43513b43-2f84-4f0f-8de8-7d61120fe3aa.png",
        // default-profile.png는 oci에 업로드 해 두었음.

        interestCategories = interestCategories
    )
}
