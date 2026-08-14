package com.team1.hangsha.category.service

import com.team1.hangsha.category.dto.CategoryResponse
import com.team1.hangsha.category.dto.core.CategoryDto
import com.team1.hangsha.category.dto.core.CategoryGroupDto
import com.team1.hangsha.category.repository.CategoryGroupRepository
import com.team1.hangsha.category.repository.CategoryRepository
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import org.springframework.stereotype.Service

@Service
class CategoryService(
    private val categoryGroupRepository: CategoryGroupRepository,
    private val categoryRepository: CategoryRepository
) {
    fun getCategoryGroupsWithCategories(): List<CategoryResponse> {
        val groups = categoryGroupRepository.findAllByOrderBySortOrderAsc()

        return groups.map { g ->
            val groupId = g.id
                ?: throw DomainException(ErrorCode.INTERNAL_ERROR, "CategoryGroup.id is null (unexpected)")
                // INTERNAL_ERROR를 쓰는 이유: category 정보는 기본으로 seed_categories.sql에 의해 자동으로 주입되어야 하기 때문

            val categories = categoryRepository.findAllByGroupIdOrderBySortOrderAsc(groupId)
                .map { c ->
                    CategoryDto(
                        id = c.id ?: throw DomainException(ErrorCode.INTERNAL_ERROR, "Category.id is null (unexpected)"),
                        groupId = c.groupId,
                        name = c.name,
                        sortOrder = c.sortOrder
                    )
                }

            CategoryResponse(
                group = CategoryGroupDto(
                    id = groupId,
                    name = g.name,
                    sortOrder = g.sortOrder
                ),
                categories = categories
            )
        }
    }

    fun getOrgCategories(): List<CategoryDto> {
        val orgGroup = categoryGroupRepository.findByName("주체기관")
            ?: throw DomainException(ErrorCode.CATEGORY_GROUP_NOT_FOUND, "\"주체기관\" category_group이 없습니다")

        val groupId = orgGroup.id
            ?: throw DomainException(ErrorCode.INTERNAL_ERROR, "CategoryGroup.id is null (unexpected)")

        return categoryRepository.findAllByGroupIdWithMinimumEventCount(
            groupId = groupId,
            minimumEventCount = 2,
        ).map { c ->
            CategoryDto(
                id = c.id ?: throw DomainException(ErrorCode.INTERNAL_ERROR, "Category.id is null (unexpected)"),
                groupId = c.groupId,
                name = c.name,
                sortOrder = c.sortOrder
            )
        }
    }
}
