package com.team1.hangsha.user.controller

import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.dto.Preference.AddExcludedKeywordRequest
import com.team1.hangsha.user.dto.Preference.ListExcludedKeywordResponse
import com.team1.hangsha.user.model.User
import com.team1.hangsha.user.service.UserPreferenceService
import com.team1.hangsha.user.dto.Preference.ListInterestCategoryResponse
import com.team1.hangsha.user.dto.Preference.ReplaceAllInterestCategoriesRequest
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import com.team1.hangsha.user.model.InterestCategoryType


@RestController
@RequestMapping("/api/v1/users/me")
class UserPreferenceController (
    private val userPreferenceService: UserPreferenceService,
) {
    @GetMapping("/interest-categories")
    fun listInterestCategory(
        @Parameter(hidden = true) @LoggedInUser user: User,
        ): ResponseEntity<ListInterestCategoryResponse> {

        val items = userPreferenceService.listInterestCategory(user.id!!)
        return ResponseEntity.ok(ListInterestCategoryResponse(items))
    }

    @PutMapping("/interest-categories")
    fun replaceAllInterestCategories(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @Valid  @RequestBody req: ReplaceAllInterestCategoriesRequest,
    ): ResponseEntity<Void> {
        userPreferenceService.replaceAllInterestCategories(user.id!!, req)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/interest-categories/{categoryId}")
    fun deleteInterestCategory(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable categoryId: Long,
        @RequestParam categoryType: InterestCategoryType,
    ): ResponseEntity<Void> {
        userPreferenceService.delete(user.id!!, categoryType, categoryId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/excluded-keywords")
    fun listExcludedKeywords(
        @Parameter(hidden = true) @LoggedInUser user: User,
    ): ResponseEntity<ListExcludedKeywordResponse> {
        val res = userPreferenceService.listExcludedKeywords(user.id!!)
        return ResponseEntity.ok(res)
    }

    @PostMapping("/excluded-keywords")
    fun addExcludedKeyword(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @Valid @RequestBody req: AddExcludedKeywordRequest,
    ): ResponseEntity<Void> {
        userPreferenceService.addExcludedKeyword(user.id!!, req.keyword)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    @DeleteMapping("/excluded-keywords/{excludedKeywordId}")
    fun deleteExcludedKeyword(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable excludedKeywordId: Long,
    ): ResponseEntity<Void> {
        userPreferenceService.deleteExcludedKeyword(user.id!!, excludedKeywordId)
        return ResponseEntity.noContent().build()
    }
}
