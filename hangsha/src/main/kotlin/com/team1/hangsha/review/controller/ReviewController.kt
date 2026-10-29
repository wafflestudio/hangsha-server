package com.team1.hangsha.review.controller

import com.team1.hangsha.review.dto.*
import com.team1.hangsha.review.service.ReviewService
import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.model.User
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
class ReviewController(private val service: ReviewService) {
    @GetMapping("/users/me/reviews")
    fun myReviews(@Parameter(hidden = true) @LoggedInUser user: User, @RequestParam(defaultValue = "1") page: Int, @RequestParam(defaultValue = "20") size: Int) =
        ResponseEntity.ok(service.myReviews(requireNotNull(user.id), page, size))

    @GetMapping("/events/{eventId}/reviews")
    fun list(@Parameter(hidden = true) @LoggedInUser user: User?, @PathVariable eventId: Long, @RequestParam(defaultValue = "1") page: Int, @RequestParam(defaultValue = "20") size: Int) =
        ResponseEntity.ok(service.list(eventId, user?.id, page, size))

    @PostMapping("/events/{eventId}/reviews")
    fun create(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable eventId: Long, @Valid @RequestBody request: CreateReviewRequest) =
        ResponseEntity.ok(service.create(requireNotNull(user.id), eventId, request))

    @PatchMapping("/reviews/{reviewId}")
    fun update(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable reviewId: Long, @Valid @RequestBody request: UpdateReviewRequest) =
        ResponseEntity.ok(service.update(requireNotNull(user.id), reviewId, request))

    @DeleteMapping("/reviews/{reviewId}")
    fun delete(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable reviewId: Long): ResponseEntity<Void> {
        service.delete(requireNotNull(user.id), reviewId); return ResponseEntity.noContent().build()
    }

    @PostMapping("/reviews/{reviewId}/like")
    fun like(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable reviewId: Long): ResponseEntity<Void> {
        service.likeReview(requireNotNull(user.id), reviewId, true); return ResponseEntity.noContent().build()
    }
    @DeleteMapping("/reviews/{reviewId}/like")
    fun unlike(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable reviewId: Long): ResponseEntity<Void> {
        service.likeReview(requireNotNull(user.id), reviewId, false); return ResponseEntity.noContent().build()
    }

    @GetMapping("/reviews/{reviewId}/comments")
    fun comments(@Parameter(hidden = true) @LoggedInUser user: User?, @PathVariable reviewId: Long) = ResponseEntity.ok(service.comments(reviewId, user?.id))
    @PostMapping("/reviews/{reviewId}/comments")
    fun createComment(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable reviewId: Long, @Valid @RequestBody request: CreateReviewCommentRequest) =
        ResponseEntity.ok(service.createComment(requireNotNull(user.id), reviewId, request))
    @PatchMapping("/review-comments/{commentId}")
    fun updateComment(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable commentId: Long, @Valid @RequestBody request: UpdateReviewCommentRequest) =
        ResponseEntity.ok(service.updateComment(requireNotNull(user.id), commentId, request))
    @DeleteMapping("/review-comments/{commentId}")
    fun deleteComment(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable commentId: Long): ResponseEntity<Void> {
        service.deleteComment(requireNotNull(user.id), commentId); return ResponseEntity.noContent().build()
    }
    @PostMapping("/review-comments/{commentId}/like")
    fun likeComment(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable commentId: Long): ResponseEntity<Void> {
        service.likeComment(requireNotNull(user.id), commentId, true); return ResponseEntity.noContent().build()
    }
    @DeleteMapping("/review-comments/{commentId}/like")
    fun unlikeComment(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable commentId: Long): ResponseEntity<Void> {
        service.likeComment(requireNotNull(user.id), commentId, false); return ResponseEntity.noContent().build()
    }
}
