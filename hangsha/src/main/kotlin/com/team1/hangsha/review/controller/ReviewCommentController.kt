package com.team1.hangsha.review.controller

import com.team1.hangsha.review.dto.CreateReviewCommentRequest
import com.team1.hangsha.review.dto.ReviewCommentResponse
import com.team1.hangsha.review.dto.UpdateReviewCommentRequest
import com.team1.hangsha.review.service.ReviewCommentService
import com.team1.hangsha.review.service.ReviewLikeService
import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.model.User
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "Review Comments")
@RestController
@RequestMapping("/api/v1")
class ReviewCommentController(
    private val reviewCommentService: ReviewCommentService,
    private val reviewLikeService: ReviewLikeService,
) {
    @GetMapping("/reviews/{reviewId}/comments")
    @Operation(
        summary = "후기 댓글 목록",
        description = "댓글과 답글을 평평한 목록으로(parentId 포함), 작성순. 로그인 선택.",
    )
    fun listComments(
        @Parameter(hidden = true) @LoggedInUser user: User?,
        @PathVariable reviewId: Long,
    ): ResponseEntity<List<ReviewCommentResponse>> =
        ResponseEntity.ok(reviewCommentService.list(reviewId, user?.id))

    @PostMapping("/reviews/{reviewId}/comments")
    @Operation(summary = "댓글·답글 작성", description = "parentId가 있으면 답글. 답글은 최상위 댓글에만 달 수 있다.")
    fun createComment(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable reviewId: Long,
        @Valid @RequestBody request: CreateReviewCommentRequest,
    ): ResponseEntity<ReviewCommentResponse> =
        ResponseEntity.ok(reviewCommentService.create(requireNotNull(user.id), reviewId, request))

    @PatchMapping("/review-comments/{commentId}")
    @Operation(summary = "댓글 수정", description = "본인만.")
    fun updateComment(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable commentId: Long,
        @Valid @RequestBody request: UpdateReviewCommentRequest,
    ): ResponseEntity<ReviewCommentResponse> =
        ResponseEntity.ok(reviewCommentService.update(requireNotNull(user.id), commentId, request))

    @DeleteMapping("/review-comments/{commentId}")
    @Operation(summary = "댓글 삭제", description = "정상 응답은 204. 본인만.")
    fun deleteComment(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable commentId: Long,
    ): ResponseEntity<Void> {
        reviewCommentService.delete(requireNotNull(user.id), commentId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/review-comments/{commentId}/like")
    @Operation(summary = "댓글 좋아요", description = "정상 응답은 204. 이미 눌렀어도 204.")
    fun likeComment(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable commentId: Long,
    ): ResponseEntity<Void> {
        reviewLikeService.likeComment(requireNotNull(user.id), commentId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/review-comments/{commentId}/like")
    @Operation(summary = "댓글 좋아요 취소", description = "정상 응답은 204. 안 눌렀어도 204.")
    fun unlikeComment(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable commentId: Long,
    ): ResponseEntity<Void> {
        reviewLikeService.unlikeComment(requireNotNull(user.id), commentId)
        return ResponseEntity.noContent().build()
    }
}
