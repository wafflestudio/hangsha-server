package com.team1.hangsha.appversion.dto

data class AppVersionCheckResponse(
    val updateType: AppUpdateType,
    val minimumSupportedVersionCode: Int,
    val latestVersionName: String,
    val releaseNotes: String,
)
