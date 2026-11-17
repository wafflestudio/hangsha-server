package com.team1.hangsha.board.controller

import com.fasterxml.jackson.databind.JsonNode
import com.team1.hangsha.board.dto.CreatePostRequest
import com.team1.hangsha.board.dto.UpdatePostRequest
import com.team1.hangsha.board.service.BoardPostService
import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.model.User
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/board/posts")
class BoardPostController(private val service: BoardPostService) {

    @GetMapping
    @Operation(
        summary = "게시글 최신순 목록",
        description = "게시글을 최신순으로 조회합니다. 검색어는 제목·본문·행사명·태그에 적용되며, eventId로 특정 행사의 글만 모아볼 수 있습니다. " +
            "popularOnly를 true로 지정하면 현재 좋아요가 10개 이상인 글만 반환합니다. 검색과 행사 필터는 인기글 조회에도 함께 사용할 수 있습니다."
    )
    fun list(
        @Parameter(hidden = true) @LoggedInUser user: User?,
        @RequestParam(required = false) query: String?,
        @RequestParam(required = false) eventId: Long?,
        @RequestParam(defaultValue = "false") popularOnly: Boolean,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ) = ResponseEntity.ok(service.list(user?.id, query, eventId, popularOnly, page, size))

    @GetMapping("/{postId}")
    @Operation(
        summary = "게시글 상세",
        description = "게시글의 본문과 작성자 표시, 좋아요·댓글 수를 조회합니다. 로그인하지 않아도 볼 수 있으며, 댓글 내용은 댓글 목록 API에서 별도로 조회합니다."
    )
    fun detail(@Parameter(hidden = true) @LoggedInUser user: User?, @PathVariable postId: Long) =
        ResponseEntity.ok(service.detail(postId, user?.id))

    @PostMapping
    @Operation(
        summary = "게시글 작성",
        description = "로그인한 사용자가 게시글을 작성합니다. 행사를 선택하지 않으면 자유글로 등록되며, 종료된 행사도 연결할 수 있습니다. " +
            "익명으로 작성할 수 있고, 등록 후에는 연결 행사를 변경할 수 없습니다."
    )
    fun create(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @Valid @RequestBody request: CreatePostRequest,
    ) = ResponseEntity.ok(service.create(user.id!!, request))

    @PatchMapping("/{postId}")
    @Operation(
        summary = "게시글 부분 수정",
        description = "본인이 작성한 글의 제목, 본문, 태그, 익명 여부를 수정합니다. 보내지 않은 필드는 유지하고, tagNames를 null이나 빈 배열로 보내면 태그를 모두 제거합니다. " +
            "최소 한 개의 필드를 보내야 하며, 제목·본문·익명 여부에는 null을 보낼 수 없습니다. 연결 행사는 변경할 수 없습니다."
    )
    fun update(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable postId: Long,
        @io.swagger.v3.oas.annotations.parameters.RequestBody(content = [Content(schema = Schema(implementation = UpdatePostRequest::class))])
        @RequestBody body: JsonNode,
    ) = ResponseEntity.ok(service.update(user.id!!, postId, body))

    @DeleteMapping("/{postId}")
    @Operation(
        summary = "게시글 삭제",
        description = "본인이 작성한 게시글을 삭제합니다. 해당 글에 달린 댓글과 답글, 좋아요도 함께 삭제됩니다."
    )
    fun delete(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable postId: Long): ResponseEntity<Unit> {
        service.delete(user.id!!, postId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{postId}/like")
    @Operation(
        summary = "게시글 좋아요",
        description = "로그인한 사용자가 게시글에 좋아요를 추가합니다. 이미 좋아요한 글에 다시 요청해도 좋아요 수는 늘어나지 않습니다."
    )
    fun like(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable postId: Long): ResponseEntity<Unit> {
        service.like(user.id!!, postId, true)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{postId}/like")
    @Operation(
        summary = "게시글 좋아요 취소",
        description = "본인이 게시글에 누른 좋아요를 취소합니다. 좋아요를 누르지 않았거나 이미 취소한 경우에도 정상 처리합니다."
    )
    fun unlike(@Parameter(hidden = true) @LoggedInUser user: User, @PathVariable postId: Long): ResponseEntity<Unit> {
        service.like(user.id!!, postId, false)
        return ResponseEntity.noContent().build()
    }
}
