package com.team1.hangsha.appversion.model

import com.fasterxml.jackson.annotation.JsonValue
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode

enum class AppPlatform(@get:JsonValue val value: String) {
    ANDROID("android"),
    IOS("ios");

    companion object {
        fun from(value: String): AppPlatform =
            entries.firstOrNull { it.value.equals(value.trim(), ignoreCase = true) }
                ?: throw DomainException(ErrorCode.APP_VERSION_UNSUPPORTED_PLATFORM)
    }
}
