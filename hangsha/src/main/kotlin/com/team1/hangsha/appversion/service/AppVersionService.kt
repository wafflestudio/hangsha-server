package com.team1.hangsha.appversion.service

import com.team1.hangsha.appversion.dto.AppUpdateType
import com.team1.hangsha.appversion.dto.AppVersionCheckResponse
import com.team1.hangsha.appversion.dto.UpsertAppVersionRequest
import com.team1.hangsha.appversion.model.AppPlatform
import com.team1.hangsha.appversion.model.AppVersion
import com.team1.hangsha.appversion.repository.AppVersionRepository
import com.team1.hangsha.common.error.DomainException
import com.team1.hangsha.common.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.relational.core.conversion.DbActionExecutionException
import org.springframework.stereotype.Service

@Service
class AppVersionService(
    private val appVersionRepository: AppVersionRepository,
) {
    private val log = LoggerFactory.getLogger(AppVersionService::class.java)

    data class UpsertResult(val appVersion: AppVersion, val created: Boolean)

    fun check(platformValue: String, currentVersionCode: Int, availableVersionCode: Int?): AppVersionCheckResponse {
        val platform = AppPlatform.from(platformValue)
        requirePositive(currentVersionCode)

        // iOS는 인앱에서 스토어 버전을 알 수 없어 availableVersionCode를 보내지 않는다.
        // 보내더라도 무시하고, 등록된 버전 중 가장 높은 versionCode를 설치 가능 버전으로 본다.
        val available = when (platform) {
            AppPlatform.ANDROID -> {
                val code = availableVersionCode
                    ?: throw DomainException(ErrorCode.APP_VERSION_AVAILABLE_VERSION_CODE_REQUIRED)
                requirePositive(code)
                appVersionRepository.findByPlatformAndVersionCode(platform, code)
            }
            AppPlatform.IOS -> appVersionRepository.findFirstByPlatformOrderByVersionCodeDesc(platform)
        }

        if (available == null) {
            log.warn(
                "app-version not registered. platform={}, currentVersionCode={}, availableVersionCode={}",
                platform.value, currentVersionCode, availableVersionCode,
            )
            throw DomainException(ErrorCode.APP_VERSION_NOT_FOUND)
        }

        return AppVersionCheckResponse(
            updateType = decideUpdateType(currentVersionCode, available),
            minimumSupportedVersionCode = available.minimumSupportedVersionCode,
            latestVersionName = available.versionName,
            releaseNotes = available.releaseNotes,
        )
    }

    fun list(platformValue: String): List<AppVersion> =
        appVersionRepository.findAllByPlatformOrderByVersionCodeDesc(AppPlatform.from(platformValue))

    // 의도적으로 @Transactional을 걸지 않는다. Repository.save()가 자체 트랜잭션을 가지므로,
    // 바깥 트랜잭션 안에서 DuplicateKeyException을 잡으면 rollback-only로 표시되어 커밋 시 실패한다.
    fun upsert(platformValue: String, versionCode: Int, req: UpsertAppVersionRequest): UpsertResult {
        val platform = AppPlatform.from(platformValue)
        requirePositive(versionCode)
        // 최소 지원 버전이 등록 버전보다 크면 업데이트해도 필수 업데이트 화면을 벗어날 수 없다.
        if (req.minimumSupportedVersionCode > versionCode) {
            throw DomainException(ErrorCode.APP_VERSION_MINIMUM_EXCEEDS_VERSION)
        }

        val existing = appVersionRepository.findByPlatformAndVersionCode(platform, versionCode)
        if (existing == null) {
            try {
                val created = appVersionRepository.save(
                    AppVersion(
                        platform = platform,
                        versionCode = versionCode,
                        versionName = req.versionName,
                        releaseNotes = req.releaseNotes,
                        minimumSupportedVersionCode = req.minimumSupportedVersionCode,
                    )
                )
                return UpsertResult(created, created = true)
            } catch (e: DbActionExecutionException) {
                // 동시 요청(중복 클릭 등)이 먼저 생성한 경우 수정으로 처리.
                // Spring Data JDBC는 DuplicateKeyException을 DbActionExecutionException으로 감싸서 던진다.
                if (e.cause !is DuplicateKeyException) throw e
            }
        }

        val target = existing
            ?: appVersionRepository.findByPlatformAndVersionCode(platform, versionCode)
            ?: throw DomainException(ErrorCode.INTERNAL_ERROR)
        val updated = appVersionRepository.save(
            target.copy(
                versionName = req.versionName,
                releaseNotes = req.releaseNotes,
                minimumSupportedVersionCode = req.minimumSupportedVersionCode,
            )
        )
        return UpsertResult(updated, created = false)
    }

    private fun decideUpdateType(currentVersionCode: Int, available: AppVersion): AppUpdateType = when {
        currentVersionCode >= available.versionCode -> AppUpdateType.NONE
        currentVersionCode < available.minimumSupportedVersionCode -> AppUpdateType.REQUIRED
        else -> AppUpdateType.OPTIONAL
    }

    private fun requirePositive(versionCode: Int) {
        if (versionCode <= 0) throw DomainException(ErrorCode.APP_VERSION_INVALID_VERSION_CODE)
    }
}
