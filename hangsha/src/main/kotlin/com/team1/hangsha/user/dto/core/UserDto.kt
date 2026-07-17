package com.team1.hangsha.user.dto.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.team1.hangsha.user.model.User
import com.team1.hangsha.user.dto.Preference.ListInterestCategoryResponse

data class UserDto(
    val id: Long,
    val username: String?,
    val email: String?,
    val profileImageUrl: String,
    val tutorialState: JsonNode,
    val interestCategories: List<ListInterestCategoryResponse.Item> = emptyList()
) {
    constructor(
        user: User,
        interestCategories: List<ListInterestCategoryResponse.Item> = emptyList(),
        objectMapper: ObjectMapper,
    ) : this(
        id = user.id!!,
        username = user.username,
        email = user.email,
        profileImageUrl = user.profileImageUrl ?: "https://objectstorage.ap-chuncheon-1.oraclecloud.com/n/ax1dvc8vmenm/b/hangsha-asset/o/default/43513b43-2f84-4f0f-8de8-7d61120fe3aa.png",
        // default-profile.png는 oci에 업로드 해 두었음.

        tutorialState = parseTutorialState(user.tutorialState, objectMapper),
        interestCategories = interestCategories
    )

    companion object {
        private fun parseTutorialState(raw: String?, objectMapper: ObjectMapper): JsonNode {
            if (raw.isNullOrBlank()) return objectMapper.createObjectNode()

            return runCatching { objectMapper.readTree(raw) }
                .getOrElse { objectMapper.createObjectNode() }
                .takeIf { it.isObject }
                ?: objectMapper.createObjectNode()
        }
    }
}
