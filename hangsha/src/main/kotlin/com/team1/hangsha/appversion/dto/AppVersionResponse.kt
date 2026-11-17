package com.team1.hangsha.appversion.dto

import com.team1.hangsha.appversion.model.AppPlatform
import com.team1.hangsha.appversion.model.AppVersion
import java.time.Instant

data class AppVersionResponse(
    val platform: AppPlatform,
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val minimumSupportedVersionCode: Int,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    companion object {
        fun from(appVersion: AppVersion) = AppVersionResponse(
            platform = appVersion.platform,
            versionCode = appVersion.versionCode,
            versionName = appVersion.versionName,
            releaseNotes = appVersion.releaseNotes,
            minimumSupportedVersionCode = appVersion.minimumSupportedVersionCode,
            createdAt = appVersion.createdAt,
            updatedAt = appVersion.updatedAt,
        )
    }
}
