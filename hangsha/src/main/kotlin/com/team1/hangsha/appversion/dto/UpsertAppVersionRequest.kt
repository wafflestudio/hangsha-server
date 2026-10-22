package com.team1.hangsha.appversion.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class UpsertAppVersionRequest(
    @field:NotBlank
    @field:Size(max = 50)
    val versionName: String,

    // 빈 문자열 허용 (변경 사항 없이 버전만 등록하는 경우)
    @field:Size(max = 5000)
    val releaseNotes: String,

    @field:Positive
    val minimumSupportedVersionCode: Int,
)
