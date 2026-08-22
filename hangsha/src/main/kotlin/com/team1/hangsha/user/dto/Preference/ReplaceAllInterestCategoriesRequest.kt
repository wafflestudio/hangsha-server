package com.team1.hangsha.user.dto.Preference

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import com.team1.hangsha.user.model.InterestCategoryType

data class ReplaceAllInterestCategoriesRequest(
    @field:Valid
    val items: List<Item>
) {
    @Schema(name = "InterestCategoryPriorityItem")
    data class Item(
        @field:NotNull
        val categoryId: Long,

        @field:NotNull
        val categoryType: InterestCategoryType,

        @field:Min(1)
        val priority: Int
    )
}
