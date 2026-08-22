package com.team1.hangsha.user.dto.Preference

import com.team1.hangsha.user.model.InterestCategoryType
import io.swagger.v3.oas.annotations.media.Schema

data class ListInterestCategoryResponse(
    val items: List<Item>
) {
    @Schema(name = "InterestCategoryItem")
    data class Item(
        val categoryType: InterestCategoryType,
        val categoryId: Long,
        val name: String,
        val sortOrder: Int,
        val priority: Int
    )
}
