package com.team1.hangsha.appversion.controller

import com.team1.hangsha.appversion.dto.AppVersionResponse
import com.team1.hangsha.appversion.dto.UpsertAppVersionRequest
import com.team1.hangsha.appversion.service.AppVersionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Admin App Version")
@RestController
@RequestMapping("/api/v1/admin/app-versions")
class AdminAppVersionController(
    private val appVersionService: AppVersionService,
) {
    @Operation(summary = "앱 버전 목록 조회", description = "플랫폼별 등록된 버전을 versionCode 내림차순으로 반환합니다.")
    @GetMapping
    fun list(
        @Parameter(description = "android 또는 ios") @RequestParam platform: String,
    ): List<AppVersionResponse> = appVersionService.list(platform).map(AppVersionResponse::from)

    @Operation(
        summary = "앱 버전 등록 또는 수정",
        description = "해당 버전이 없으면 생성(201)하고, 있으면 전체 내용을 수정(200)합니다. " +
            "minimumSupportedVersionCode는 versionCode 이하여야 합니다."
    )
    @PutMapping("/{platform}/{versionCode}")
    fun upsert(
        @Parameter(description = "android 또는 ios") @PathVariable platform: String,
        @PathVariable versionCode: Int,
        @Valid @RequestBody req: UpsertAppVersionRequest,
    ): ResponseEntity<AppVersionResponse> {
        val result = appVersionService.upsert(platform, versionCode, req)
        return ResponseEntity
            .status(if (result.created) HttpStatus.CREATED else HttpStatus.OK)
            .body(AppVersionResponse.from(result.appVersion))
    }
}
