package com.team1.hangsha.memo.controller

import com.team1.hangsha.memo.dto.*
import com.team1.hangsha.memo.dto.core.MemoResponse
import com.team1.hangsha.memo.service.MemoService
import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.model.User
import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema

@RestController
@RequestMapping("/api/v1/memos")
class MemoController(
    private val memoService: MemoService
) {

    @PostMapping
    fun createMemo(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @RequestBody req: CreateMemoRequest
    ): ResponseEntity<MemoResponse> {
        val response = memoService.createMemo(user.id!!, req)
        return ResponseEntity.ok(response)
    }

    @GetMapping
    fun getMyMemos(@Parameter(hidden = true) @LoggedInUser user: User,): ResponseEntity<ListMemoResponse> {
        val memos = memoService.getMyMemos(user.id!!)
        return ResponseEntity.ok(ListMemoResponse(items = memos))
    }

    @GetMapping("/by-event/{eventId}")
    fun getMyMemoByEventId(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable eventId: Long,
    ): ResponseEntity<MemoResponse> {
        val response = memoService.getMyMemoByEventId(user.id!!, eventId)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/by-tag/{tagId}")
    fun findMemosByTagId(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable tagId: Long,
    ): ResponseEntity<ListMemoResponse> {
        val response = memoService.findMemosByTagId(user.id!!, tagId)
        return ResponseEntity.ok(ListMemoResponse(items = response))
    }

    // 메모 수정 (내용 + 태그)
    @PatchMapping("/{memoId}")
    @Operation(
        summary = "Update memo (PATCH)",
        description = """
            메모를 부분 수정합니다.
            
            ### PATCH 규칙
            - 필드 미포함: 해당 필드는 변경되지 않습니다.
            - 요청 바디에 아래 2개 중 **최소 1개 필드**는 포함되어야 합니다.
              - content, tagNames
              - 아무것도 없으면 INVALID_REQUEST(400)
            
            ### 필드별 정책
            - content
              - 미포함: 변경 없음
              - null: 값 비우기 ("" 저장, 메모 내용만 지우고 태그는 자동으로 지워지지 않습니다 / tag는 DELETE tags/{tagId} 를 이용한 명시적 삭제를 해야 합니다)
              - 값 존재: 업데이트
            - tagNames
              - 미포함: 변경 없음
              - null: 태그 전부 제거
              - array: **replace-all** (중복 입력은 distinct 처리)
            
            ### 유효성/정책
            - tagNames는 유저 스코프입니다.
            - tagNames에 존재하지 않는 태그명은 생성될 수 있습니다. (서비스 정책에 따름)
            """
    )
    fun updateMemo(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @Parameter(
            description = "수정할 메모 ID",
            example = "70"
        )
        @PathVariable memoId: Long,

        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            description = "메모 수정 요청 바디",
            content = [
                Content(
                    schema = Schema(implementation = UpdateMemoRequest::class),
                    examples = [
                        ExampleObject(
                            name = "updateContentOnly",
                            value = """{"content":"new content"}"""
                        ),
                        ExampleObject(
                            name = "clearContent",
                            value = """{"content":null}"""
                        ),
                        ExampleObject(
                            name = "replaceTags",
                            value = """{"tagNames":["shared","other"]}"""
                        ),
                        ExampleObject(
                            name = "clearTags",
                            value = """{"tagNames":null}"""
                        ),
                        ExampleObject(
                            name = "updateBoth",
                            value = """{"content":"updated","tagNames":["t1","t2"]}"""
                        )
                    ]
                )
            ]
        )
        @RequestBody body: JsonNode,
    ): ResponseEntity<MemoResponse> {
        val response = memoService.updateMemo(user.id!!, memoId, body)
        return ResponseEntity.ok(response)
    }

    // 메모 삭제
    @DeleteMapping("/{memoId}")
    fun deleteMemo(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @PathVariable memoId: Long
    ): ResponseEntity<Unit> {
        memoService.deleteMemo(user.id!!, memoId)
        return ResponseEntity.noContent().build()
    }
}
