package com.team1.hangsha.user.controller

import com.fasterxml.jackson.databind.JsonNode
import com.team1.hangsha.common.upload.dto.UploadResponse
import com.team1.hangsha.user.LoggedInUser
import com.team1.hangsha.user.model.User
import com.team1.hangsha.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import com.team1.hangsha.user.dto.GetMeResponse
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.http.MediaType
import org.springframework.web.multipart.MultipartFile
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema

@RestController
@RequestMapping("/api/v1/users/me")
class UserController(
    private val userService: UserService,
) {
    @GetMapping
    fun getMe(
        @Parameter(hidden = true) @LoggedInUser user: User,
    ): ResponseEntity<GetMeResponse> {
        val me = userService.getMe(user.id!!)
        return ResponseEntity.ok(me)
    }

    @PatchMapping
    @Operation(
        summary = "Update my profile (PATCH)",
        description = """
            내 프로필을 부분 수정합니다.
        
            ### PATCH 규칙
            - 필드 미포함: 해당 필드는 변경되지 않습니다.
            - 요청 바디에 아래 2개 중 **최소 1개 필드**는 포함되어야 합니다.
              - username, profileImageUrl
              - 아무것도 없으면 INVALID_REQUEST(400)
            
            ### 필드별 정책
            - username
              - 미포함: 변경 없음
              - null: username 삭제(null로 저장)
              - 값 존재: 업데이트
            - profileImageUrl
              - 미포함: 변경 없음
              - null: 프로필 이미지 삭제(null로 저장)
              - 값 존재: 업데이트 (normalize 후 저장)
            
            ### 유효성 규칙
            - username
              - **blank 금지**(trim 후 빈 문자열) → INVALID_REQUEST
              - **영어 최대 20자, 한국어 최대 10자** → INVALID_REQUEST
            - profileImageUrl
              - **http / https URL만 허용** → INVALID_REQUEST
              """
    )
    fun updateProfile(
        @Parameter(hidden = true) @LoggedInUser user: User,

        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            description = "프로필 수정 요청 바디",
            content = [
                Content(
                    mediaType = "application/json",
                    schema = Schema(
                        description = "Profile update payload",
                        type = "object"
                    ),
                    examples = [
                        ExampleObject(
                            name = "updateUsername",
                            summary = "username만 수정",
                            value = """{"username":"new_name"}"""
                        ),
                        ExampleObject(
                            name = "updateProfileImage",
                            summary = "profileImageUrl만 수정",
                            value = """{"profileImageUrl":"https://example.com/profile.png"}"""
                        ),
                        ExampleObject(
                            name = "updateBoth",
                            summary = "둘 다 수정",
                            value = """{"username":"new_name","profileImageUrl":"https://example.com/profile.png"}"""
                        ),
                        ExampleObject(
                            name = "clearUsername",
                            summary = "username 삭제",
                            value = """{"username":null}"""
                        ),
                        ExampleObject(
                            name = "clearProfileImage",
                            summary = "profileImageUrl 삭제",
                            value = """{"profileImageUrl":null}"""
                        )
                    ]
                )
            ]
        )
        @RequestBody body: JsonNode,
    ): ResponseEntity<Void> {
        userService.updateProfile(user.id!!, body)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/profile-image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadProfileImage(
        @Parameter(hidden = true) @LoggedInUser user: User,
        @RequestPart("file") file: MultipartFile,
    ): ResponseEntity<UploadResponse> {
        val url = userService.uploadProfile(user.id!!, file)
        userService.updateProfileImageUrl(user.id!!, url)
        return ResponseEntity.ok(UploadResponse(url = url))
    }
}
