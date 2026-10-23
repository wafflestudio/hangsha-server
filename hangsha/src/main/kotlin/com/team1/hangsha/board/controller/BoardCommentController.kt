package com.team1.hangsha.board.controller

import com.team1.hangsha.board.dto.CreateCommentRequest
import com.team1.hangsha.board.service.BoardCommentService
import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.model.User
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/board")
class BoardCommentController(private val service: BoardCommentService) {

    @GetMapping("/posts/{postId}/comments")
    @Operation(
        summary = "댓글 목록",
        description = "댓글을 추천순(recommended) 또는 최신순(latest)으로 조회합니다. 페이지는 부모 댓글을 기준으로 나누며, 답글은 정렬 방식과 관계없이 부모 아래에 작성순으로 함께 반환합니다. " +
            "전체 댓글 수에는 답글을 포함하고, 삭제 표시만 남은 부모 댓글은 제외합니다."
    )
    fun list(
        @Parameter(hidden = true) @LoggedInUser user: User?,
        @PathVariable postId: Long,
        @RequestParam(defaultValue = "recommended") sort: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = ResponseEntity.ok(service.list(postId, user?.id, sort, page, size))

    @PostMapping("/posts/{postId}/comments")
    @Operation(
        summary = "댓글·답글 작성",
        description = "로그인한 사용자가 댓글을 작성합니다. parentId를 보내면 해당 댓글의 답글로 등록되며, 답글은 한 단계까지만 지원합니다. " +
            "작성 후에는 내용과 익명 여부를 변경할 수 없습니다."
    )
    fun create(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable postId: Long,
        @Valid @RequestBody request: CreateCommentRequest,
    ) = ResponseEntity.ok(service.create(user.id!!, postId, request))

    @DeleteMapping("/comments/{commentId}")
    @Operation(
        summary = "댓글 삭제",
        description = "본인이 작성한 댓글을 삭제합니다. 답글이 있는 댓글은 본문을 (삭제)로 표시하고 답글을 남겨둡니다. " +
            "마지막 답글까지 삭제되면 삭제 표시용 부모 댓글도 함께 제거합니다."
    )
    fun delete(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable commentId: Long
    ): ResponseEntity<Unit> {
        service.delete(user.id!!, commentId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/comments/{commentId}/like")
    @Operation(
        summary = "댓글 좋아요",
        description = "로그인한 사용자가 댓글에 좋아요를 추가합니다. 이미 좋아요한 경우에는 중복으로 추가하지 않으며, 삭제된 댓글에는 좋아요할 수 없습니다."
    )
    fun like(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable commentId: Long): ResponseEntity<Unit> {
        service.like(user.id!!, commentId, true)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/comments/{commentId}/like")
    @Operation(
        summary = "댓글 좋아요 취소",
        description = "본인이 댓글에 누른 좋아요를 취소합니다. 좋아요를 누르지 않았거나 이미 취소한 경우에도 정상 처리합니다."
    )
    fun unlike(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable commentId: Long
    ): ResponseEntity<Unit> {
        service.like(user.id!!, commentId, false)
        return ResponseEntity.noContent().build()
    }
}
