package com.team1.hangsha.appversion.controller

import com.team1.hangsha.appversion.dto.AppVersionCheckResponse
import com.team1.hangsha.appversion.service.AppVersionService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Tag(name = "App Version")
@RestController
class AppVersionController(
    private val appVersionService: AppVersionService,
) {
    @Operation(
        summary = "앱 업데이트 정책 조회",
        description = "현재 앱 버전과 설치 가능 버전을 바탕으로 업데이트 유형(NONE/OPTIONAL/REQUIRED)과 안내 정보를 반환합니다(인증 불필요). " +
            "android는 availableVersionCode가 필수이며 해당 버전이 등록되어 있지 않으면 404입니다. " +
            "ios는 availableVersionCode를 무시하고 등록된 버전 중 가장 높은 versionCode를 기준으로 판단하며, 등록된 버전이 없으면 404입니다."
    )
    @GetMapping("/api/v1/app-version")
    fun check(
        @Parameter(description = "android 또는 ios") @RequestParam platform: String,
        @RequestParam currentVersionCode: Int,
        @Parameter(description = "Google Play가 알려준 설치 가능 버전 코드. android만 필수, ios는 생략")
        @RequestParam(required = false) availableVersionCode: Int?,
    ): AppVersionCheckResponse = appVersionService.check(platform, currentVersionCode, availableVersionCode)
}
