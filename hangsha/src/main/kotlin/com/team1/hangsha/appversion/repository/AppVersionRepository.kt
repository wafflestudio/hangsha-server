package com.team1.hangsha.appversion.repository

import com.team1.hangsha.appversion.model.AppPlatform
import com.team1.hangsha.appversion.model.AppVersion
import org.springframework.data.repository.CrudRepository

interface AppVersionRepository : CrudRepository<AppVersion, Long> {
    fun findByPlatformAndVersionCode(platform: AppPlatform, versionCode: Int): AppVersion?

    fun findFirstByPlatformOrderByVersionCodeDesc(platform: AppPlatform): AppVersion?

    fun findAllByPlatformOrderByVersionCodeDesc(platform: AppPlatform): List<AppVersion>
}
