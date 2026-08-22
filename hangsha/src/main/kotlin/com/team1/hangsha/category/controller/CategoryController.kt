package com.team1.hangsha.category.controller

import com.team1.hangsha.category.dto.ListCategoryItemsResponse
import com.team1.hangsha.category.service.CategoryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Categories")
@RequestMapping("/api/v1")
@RestController
class CategoryController(private val categoryService: CategoryService) {
    @Operation(summary = "행사 상태 목록 조회")
    @GetMapping("/event-statuses")
    fun getEventStatuses() = ListCategoryItemsResponse(categoryService.getEventStatuses())

    @Operation(summary = "프로그램 유형 목록 조회")
    @GetMapping("/event-types")
    fun getEventTypes() = ListCategoryItemsResponse(categoryService.getEventTypes())

    @Operation(summary = "주체기관 목록 조회")
    @GetMapping("/organizations")
    fun getOrganizations() = ListCategoryItemsResponse(categoryService.getOrganizations())
}
