package com.team1.hangsha.review.controller

import com.team1.hangsha.review.dto.CreateReviewRequest
import com.team1.hangsha.review.dto.ReviewListResponse
import com.team1.hangsha.review.dto.ReviewResponse
import com.team1.hangsha.review.dto.UpdateReviewRequest
import com.team1.hangsha.review.service.ReviewLikeService
import com.team1.hangsha.review.service.ReviewService
import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.model.User
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "Reviews")
@RestController
@RequestMapping("/api/v1")
class ReviewController(
    private val reviewService: ReviewService,
    private val reviewLikeService: ReviewLikeService,
) {
    @GetMapping("/events/{eventId}/reviews")
    @Operation(summary = "행사 후기 목록", description = "행사 후기 전체(최신순)와 평균 별점, 후기 수. 로그인 선택.")
    fun listEventReviews(
        @Parameter(hidden = true) @LoggedInUser user: User?,
        @PathVariable eventId: Long,
    ): ResponseEntity<ReviewListResponse> =
        ResponseEntity.ok(reviewService.listEventReviews(eventId, user?.id))

    @PostMapping("/events/{eventId}/reviews")
    @Operation(summary = "후기 작성")
    fun createReview(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable eventId: Long,
        @Valid @RequestBody request: CreateReviewRequest,
    ): ResponseEntity<ReviewResponse> =
        ResponseEntity.ok(reviewService.create(requireNotNull(user.id), eventId, request))

    @GetMapping("/users/me/reviews")
    @Operation(summary = "내 후기 목록", description = "내 후기 전체(최신순)와 내 평균 별점, 후기 수.")
    fun listMyReviews(
        @Parameter(hidden = true) @LoggedInUser user: User,
    ): ResponseEntity<ReviewListResponse> =
        ResponseEntity.ok(reviewService.listMyReviews(requireNotNull(user.id)))

    @PatchMapping("/reviews/{reviewId}")
    @Operation(summary = "후기 수정", description = "rating, content만 바꿀 수 있다. 익명 여부는 바꿀 수 없다. 본인만.")
    fun updateReview(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable reviewId: Long,
        @Valid @RequestBody request: UpdateReviewRequest,
    ): ResponseEntity<ReviewResponse> =
        ResponseEntity.ok(reviewService.update(requireNotNull(user.id), reviewId, request))

    @DeleteMapping("/reviews/{reviewId}")
    @Operation(summary = "후기 삭제", description = "정상 응답은 204. 본인만.")
    fun deleteReview(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable reviewId: Long,
    ): ResponseEntity<Void> {
        reviewService.delete(requireNotNull(user.id), reviewId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/reviews/{reviewId}/like")
    @Operation(summary = "후기 좋아요", description = "정상 응답은 204. 이미 눌렀어도 204.")
    fun likeReview(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable reviewId: Long,
    ): ResponseEntity<Void> {
        reviewLikeService.likeReview(requireNotNull(user.id), reviewId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/reviews/{reviewId}/like")
    @Operation(summary = "후기 좋아요 취소", description = "정상 응답은 204. 안 눌렀어도 204.")
    fun unlikeReview(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable reviewId: Long,
    ): ResponseEntity<Void> {
        reviewLikeService.unlikeReview(requireNotNull(user.id), reviewId)
        return ResponseEntity.noContent().build()
    }
}
