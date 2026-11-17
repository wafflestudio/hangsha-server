package com.team1.hangsha

import com.team1.hangsha.appversion.dto.UpsertAppVersionRequest
import com.team1.hangsha.helper.IntegrationTestBase
import com.team1.hangsha.user.JwtTokenProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AppVersionIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var jwtTokenProvider: JwtTokenProvider

    private val adminToken by lazy { jwtTokenProvider.createAccessToken(userId = 1L, isAdmin = true) }

    private fun upsert(
        platform: String,
        versionCode: Int,
        minimumSupportedVersionCode: Int,
        versionName: String = "1.0.$versionCode",
        releaseNotes: String = "v$versionCode 변경 사항",
    ): ResultActions = mockMvc.perform(
        put("/api/v1/admin/app-versions/$platform/$versionCode")
            .header("Authorization", bearer(adminToken))
            .contentType(MediaType.APPLICATION_JSON)
            .content(toJson(UpsertAppVersionRequest(versionName, releaseNotes, minimumSupportedVersionCode)))
    )

    private fun check(platform: String, current: Int, available: Int? = null): ResultActions = mockMvc.perform(
        get("/api/v1/app-version")
            .param("platform", platform)
            .param("currentVersionCode", current.toString())
            .apply { if (available != null) param("availableVersionCode", available.toString()) }
    )

    // ----------------------------
    // 공개 조회 - android
    // ----------------------------

    @Test
    fun `android - 현재 버전이 설치 가능 버전 이상이면 NONE`() {
        upsert("android", 17, minimumSupportedVersionCode = 16)

        check("android", current = 17, available = 17)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updateType").value("NONE"))
    }

    @Test
    fun `android - 최소 지원 버전 이상이고 설치 가능 버전 미만이면 OPTIONAL`() {
        upsert("android", 17, minimumSupportedVersionCode = 16, versionName = "1.2.0", releaseNotes = "캘린더 성능 개선\n오류 수정")

        check("android", current = 16, available = 17)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updateType").value("OPTIONAL"))
            .andExpect(jsonPath("$.minimumSupportedVersionCode").value(16))
            .andExpect(jsonPath("$.latestVersionName").value("1.2.0"))
            .andExpect(jsonPath("$.releaseNotes").value("캘린더 성능 개선\n오류 수정"))
    }

    @Test
    fun `android - 최소 지원 버전 미만이면 REQUIRED`() {
        upsert("android", 17, minimumSupportedVersionCode = 16)

        check("android", current = 15, available = 17)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updateType").value("REQUIRED"))
    }

    @Test
    fun `android - 더 높은 버전이 등록되어 있어도 availableVersionCode 행 기준으로 판단한다`() {
        upsert("android", 17, minimumSupportedVersionCode = 15, versionName = "1.2.0")
        upsert("android", 18, minimumSupportedVersionCode = 17, versionName = "1.3.0")

        check("android", current = 16, available = 17)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updateType").value("OPTIONAL"))
            .andExpect(jsonPath("$.latestVersionName").value("1.2.0"))
    }

    @Test
    fun `android - availableVersionCode가 등록되지 않았으면 404`() {
        upsert("android", 17, minimumSupportedVersionCode = 16)

        check("android", current = 16, available = 18)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("APP_VERSION_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("등록되지 않은 앱 버전입니다."))
    }

    @Test
    fun `android - availableVersionCode가 없으면 400`() {
        upsert("android", 17, minimumSupportedVersionCode = 16)

        check("android", current = 16)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("APP_VERSION_AVAILABLE_VERSION_CODE_REQUIRED"))
    }

    // ----------------------------
    // 공개 조회 - ios
    // ----------------------------

    @Test
    fun `ios - 등록된 최대 versionCode를 기준으로 판단한다`() {
        upsert("ios", 5, minimumSupportedVersionCode = 3, versionName = "1.0.0")
        upsert("ios", 7, minimumSupportedVersionCode = 6, versionName = "1.2.0")
        upsert("ios", 6, minimumSupportedVersionCode = 4, versionName = "1.1.0")

        check("ios", current = 5)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updateType").value("REQUIRED"))
            .andExpect(jsonPath("$.minimumSupportedVersionCode").value(6))
            .andExpect(jsonPath("$.latestVersionName").value("1.2.0"))

        check("ios", current = 6)
            .andExpect(jsonPath("$.updateType").value("OPTIONAL"))

        check("ios", current = 7)
            .andExpect(jsonPath("$.updateType").value("NONE"))
    }

    @Test
    fun `ios - availableVersionCode를 보내도 무시한다`() {
        upsert("ios", 7, minimumSupportedVersionCode = 6)

        check("ios", current = 6, available = 999)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updateType").value("OPTIONAL"))
    }

    @Test
    fun `ios - 등록된 버전이 하나도 없으면 404 (android 등록 여부와 무관)`() {
        upsert("android", 17, minimumSupportedVersionCode = 16)

        check("ios", current = 1)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("APP_VERSION_NOT_FOUND"))
    }

    // ----------------------------
    // 공개 조회 - 공통 검증
    // ----------------------------

    @Test
    fun `지원하지 않는 플랫폼이면 400`() {
        check("web", current = 1, available = 1)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("APP_VERSION_UNSUPPORTED_PLATFORM"))
    }

    @Test
    fun `버전 코드가 0 이하이면 400`() {
        upsert("android", 17, minimumSupportedVersionCode = 16)

        check("android", current = 0, available = 17)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("APP_VERSION_INVALID_VERSION_CODE"))
        check("android", current = 16, available = -1)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("APP_VERSION_INVALID_VERSION_CODE"))
    }

    @Test
    fun `필수 파라미터가 없거나 숫자가 아니면 400`() {
        mockMvc.perform(get("/api/v1/app-version").param("platform", "android").param("availableVersionCode", "17"))
            .andExpect(status().isBadRequest)
        mockMvc.perform(get("/api/v1/app-version").param("platform", "android").param("currentVersionCode", "abc").param("availableVersionCode", "17"))
            .andExpect(status().isBadRequest)
        mockMvc.perform(get("/api/v1/app-version").param("currentVersionCode", "1"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `공개 조회는 만료되거나 잘못된 토큰이 붙어 있어도 동작한다`() {
        upsert("android", 17, minimumSupportedVersionCode = 16)

        mockMvc.perform(
            get("/api/v1/app-version")
                .header("Authorization", bearer("invalid.token.value"))
                .param("platform", "android")
                .param("currentVersionCode", "17")
                .param("availableVersionCode", "17")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.updateType").value("NONE"))
    }

    // ----------------------------
    // 관리자 API
    // ----------------------------

    @Test
    fun `PUT - 없으면 201로 생성하고 있으면 200으로 전체 수정한다`() {
        upsert("android", 17, minimumSupportedVersionCode = 16, versionName = "1.2.0", releaseNotes = "처음")
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.platform").value("android"))
            .andExpect(jsonPath("$.versionCode").value(17))
            .andExpect(jsonPath("$.createdAt").isString)

        upsert("android", 17, minimumSupportedVersionCode = 17, versionName = "1.2.1", releaseNotes = "")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.versionName").value("1.2.1"))
            .andExpect(jsonPath("$.releaseNotes").value(""))
            .andExpect(jsonPath("$.minimumSupportedVersionCode").value(17))

        mockMvc.perform(get("/api/v1/admin/app-versions").param("platform", "android").header("Authorization", bearer(adminToken)))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].versionName").value("1.2.1"))
    }

    @Test
    fun `PUT - 같은 버전을 동시에 등록해도 하나만 생성되고 나머지는 수정으로 처리된다`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val futures = (1..threads).map {
            pool.submit<Int> {
                start.await()
                upsert("android", 30, minimumSupportedVersionCode = 1).andReturn().response.status
            }
        }
        start.countDown()
        val statuses = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals(1, statuses.count { it == 201 }, "statuses=$statuses")
        assertEquals(threads - 1, statuses.count { it == 200 }, "statuses=$statuses")
    }

    @Test
    fun `PUT - 검증 규칙을 어기면 400`() {
        upsert("android", 17, minimumSupportedVersionCode = 18)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("APP_VERSION_MINIMUM_EXCEEDS_VERSION"))

        upsert("android", 0, minimumSupportedVersionCode = 1)
            .andExpect(status().isBadRequest)

        upsert("android", 17, minimumSupportedVersionCode = 0)
            .andExpect(status().isBadRequest)

        upsert("android", 17, minimumSupportedVersionCode = 16, versionName = " ")
            .andExpect(status().isBadRequest)

        upsert("ipados", 17, minimumSupportedVersionCode = 16)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("APP_VERSION_UNSUPPORTED_PLATFORM"))

        // releaseNotes 누락(null)은 400, 빈 문자열은 허용 (위 200 테스트에서 확인)
        mockMvc.perform(
            put("/api/v1/admin/app-versions/android/17")
                .header("Authorization", bearer(adminToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"versionName":"1.2.0","minimumSupportedVersionCode":16}""")
        )
            .andExpect(status().isBadRequest)

        mockMvc.perform(get("/api/v1/admin/app-versions").param("platform", "android").header("Authorization", bearer(adminToken)))
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `GET 목록 - 플랫폼별로 versionCode 내림차순`() {
        upsert("android", 15, minimumSupportedVersionCode = 1)
        upsert("android", 17, minimumSupportedVersionCode = 16)
        upsert("android", 16, minimumSupportedVersionCode = 15)
        upsert("ios", 99, minimumSupportedVersionCode = 1)

        mockMvc.perform(get("/api/v1/admin/app-versions").param("platform", "android").header("Authorization", bearer(adminToken)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].versionCode").value(17))
            .andExpect(jsonPath("$[1].versionCode").value(16))
            .andExpect(jsonPath("$[2].versionCode").value(15))
            .andExpect(jsonPath("$[0].platform").value("android"))
            .andExpect(jsonPath("$[0].updatedAt").isString)

        mockMvc.perform(get("/api/v1/admin/app-versions").param("platform", "ios").header("Authorization", bearer(adminToken)))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].platform").value("ios"))
    }

    @Test
    fun `관리자 API - 비로그인은 401, 일반 사용자는 403`() {
        val (_, userToken) = dataGenerator.generateUserWithAccessToken()

        mockMvc.perform(get("/api/v1/admin/app-versions").param("platform", "android"))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/v1/admin/app-versions").param("platform", "android").header("Authorization", bearer(userToken)))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            put("/api/v1/admin/app-versions/android/17")
                .header("Authorization", bearer(userToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(UpsertAppVersionRequest("1.2.0", "", 16)))
        )
            .andExpect(status().isForbidden)
    }
}
