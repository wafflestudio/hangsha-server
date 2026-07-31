package com.team1.hangsha

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import com.team1.hangsha.helper.IntegrationTestBase
import jakarta.servlet.http.Cookie


class AuthIntegrationTest : IntegrationTestBase() {

    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    data class RegisterRequest(val email: String, val password: String)
    data class LoginRequest(val email: String, val password: String)

    data class AccessTokenResponse(val accessToken: String)
    data class RefreshResponse(val accessToken: String)
    data class MobileTokenResponse(val accessToken: String, val refreshToken: String)

    private fun extractRefreshCookiePair(setCookieHeader: String): String {
        // 예: "refreshToken=abc.def; Path=/api/v1/auth; HttpOnly; ..."
        // -> "refreshToken=abc.def"
        return setCookieHeader.substringBefore(";")
    }

    private fun extractRefreshTokenValue(setCookieHeader: String): String {
        // refreshToken=xxxxx.yyyyy.zzzzz; Path=... -> xxxxx.yyyyy.zzzzz
        return setCookieHeader
            .substringBefore(";")
            .substringAfter("refreshToken=")
    }

    private fun postRegister(email: String, password: String): Pair<String, String> {
        val res = mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = toJson(RegisterRequest(email, password))
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { doesNotExist() }
            header { exists(HttpHeaders.SET_COOKIE) }
        }.andReturn()

        val body = objectMapper.readValue(res.response.contentAsString, AccessTokenResponse::class.java)
        val setCookie = res.response.getHeader(HttpHeaders.SET_COOKIE)
            ?: fail("Expected Set-Cookie for refreshToken, but it was null")

        val refreshCookiePair = extractRefreshCookiePair(setCookie)
        assertTrue(refreshCookiePair.startsWith("refreshToken="), "Set-Cookie must include refreshToken")

        return body.accessToken to refreshCookiePair
    }

    private fun selectUserProfileByEmail(email: String): Pair<String?, String?> {
        val row = jdbcTemplate.queryForMap(
            """
            SELECT username, profile_image_url
            FROM users
            WHERE email = ?
            """.trimIndent(),
            email
        )
        val username = row["username"] as String?
        val profileImageUrl = row["profile_image_url"] as String?
        return username to profileImageUrl
    }

    private fun postLogin(email: String, password: String): Pair<String, String> {
        val res = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = toJson(LoginRequest(email, password))
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { doesNotExist() }
            header { exists(HttpHeaders.SET_COOKIE) }
        }.andReturn()

        val body = objectMapper.readValue(res.response.contentAsString, AccessTokenResponse::class.java)
        val setCookie = res.response.getHeader(HttpHeaders.SET_COOKIE)
            ?: fail("Expected Set-Cookie for refreshToken, but it was null")

        val refreshCookiePair = extractRefreshCookiePair(setCookie)
        assertTrue(refreshCookiePair.startsWith("refreshToken="), "Set-Cookie must include refreshToken")

        return body.accessToken to refreshCookiePair
    }

    // ---------------------------
    // Auth flow tests (Cookie-based refresh)
    // ---------------------------

    @Test
    fun `register login refresh flow works`() {
        val email = "test_${UUID.randomUUID()}@example.com"
        val password = "Abcd1234!"

        // 1) register
        val (_, _) = postRegister(email, password)

        // 2) login
        val loginRes = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = toJson(LoginRequest(email, password))
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
            header { exists(HttpHeaders.SET_COOKIE) }
        }.andReturn()

        val loginBody = objectMapper.readValue(loginRes.response.contentAsString, AccessTokenResponse::class.java)
        val loginSetCookie = loginRes.response.getHeader(HttpHeaders.SET_COOKIE)!!
        val refreshTokenValue = extractRefreshTokenValue(loginSetCookie)

        // 3) refresh (cookie()로 전달 + secure(true)로 https 요청처럼)
        val refreshRes = mockMvc.post("/api/v1/auth/refresh") {
            secure = true
            cookie(Cookie("refreshToken", refreshTokenValue))
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
            header { exists(HttpHeaders.SET_COOKIE) } // rotation이면 내려옴
        }.andReturn()

        val refreshed = objectMapper.readValue(refreshRes.response.contentAsString, RefreshResponse::class.java)
        assertNotEquals(loginBody.accessToken, refreshed.accessToken)
    }

    @Test
    fun `login fails with wrong password`() {
        val email = "test_${UUID.randomUUID()}@example.com"
        val password = "Abcd1234!"
        val wrongPassword = "Abcd1234?wrong"

        mockMvc.post("/api/v1/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = toJson(RegisterRequest(email, password))
        }.andExpect {
            status { isOk() }
        }

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = toJson(LoginRequest(email, wrongPassword))
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `refresh fails without cookie`() {
        mockMvc.post("/api/v1/auth/refresh") {
            // no Cookie
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `refresh fails when access token is used as refresh token`() {
        val email = "test_${UUID.randomUUID()}@example.com"
        val password = "Abcd1234!"

        val (accessToken, _) = postRegister(email, password)

        mockMvc.post("/api/v1/auth/refresh") {
            header(HttpHeaders.COOKIE, "refreshToken=$accessToken")
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `session endpoint issues refresh cookie with valid access token`() {
        val email = "test_${UUID.randomUUID()}@example.com"
        val password = "Abcd1234!"

        val (accessToken, _) = postRegister(email, password)

        val res = mockMvc.post("/api/v1/auth/session") {
            secure = true
            header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
        }.andExpect {
            status { isNoContent() }
            header { exists(HttpHeaders.SET_COOKIE) }
        }.andReturn()

        val setCookie = res.response.getHeader(HttpHeaders.SET_COOKIE)
            ?: fail("Expected Set-Cookie for refreshToken, but it was null")

        assertTrue(setCookie.startsWith("refreshToken="), "Set-Cookie must include refreshToken")
    }

    @Test
    fun `session endpoint fails without access token`() {
        mockMvc.post("/api/v1/auth/session") {
            secure = true
        }.andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `logout clears refresh cookie`() {
        val email = "test_${UUID.randomUUID()}@example.com"
        val password = "Abcd1234!"

        val (_, refreshCookie) = postRegister(email, password)

        val res = mockMvc.post("/api/v1/auth/logout") {
            header(HttpHeaders.COOKIE, refreshCookie)
        }.andExpect {
            status { isNoContent() }
            header { exists(HttpHeaders.SET_COOKIE) }
        }.andReturn()

        val setCookie = res.response.getHeader(HttpHeaders.SET_COOKIE)
            ?: fail("Expected Set-Cookie header to clear refresh cookie on logout")

        assertTrue(setCookie.contains("refreshToken="))
        // Max-Age=0 같은 세부값은 구현에 따라 다를 수 있으니 핵심만 체크
    }

    // ---------------------------
    // Mobile auth flow tests (JSON refresh token)
    // ---------------------------

    @Test
    fun `mobile register login refresh logout flow works without cookies`() {
        val email = "mobile_${UUID.randomUUID()}@example.com"
        val password = "Abcd1234!"

        val registerRes = mockMvc.post("/api/v1/mobile/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = toJson(RegisterRequest(email, password))
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { exists() }
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
        }.andReturn()

        val registerBody = objectMapper.readValue(registerRes.response.contentAsString, MobileTokenResponse::class.java)
        assertTrue(registerBody.refreshToken.isNotBlank())

        val sessionRes = mockMvc.post("/api/v1/mobile/auth/session") {
            header(HttpHeaders.AUTHORIZATION, bearer(registerBody.accessToken))
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { exists() }
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
        }.andReturn()

        val sessionBody = objectMapper.readValue(sessionRes.response.contentAsString, MobileTokenResponse::class.java)
        assertTrue(sessionBody.refreshToken.isNotBlank())

        val loginRes = mockMvc.post("/api/v1/mobile/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = toJson(LoginRequest(email, password))
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { exists() }
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
        }.andReturn()

        val loginBody = objectMapper.readValue(loginRes.response.contentAsString, MobileTokenResponse::class.java)

        val refreshRes = mockMvc.post("/api/v1/mobile/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = toJson(mapOf("refreshToken" to loginBody.refreshToken))
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { exists() }
            jsonPath("$.refreshToken") { exists() }
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
        }.andReturn()

        val refreshBody = objectMapper.readValue(refreshRes.response.contentAsString, MobileTokenResponse::class.java)
        assertNotEquals(loginBody.accessToken, refreshBody.accessToken)
        assertNotEquals(loginBody.refreshToken, refreshBody.refreshToken)

        mockMvc.post("/api/v1/mobile/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = toJson(mapOf("refreshToken" to refreshBody.refreshToken))
        }.andExpect {
            status { isNoContent() }
            header { doesNotExist(HttpHeaders.SET_COOKIE) }
        }
    }

    @Test
    fun `android auth endpoints are removed`() {
        mockMvc.post("/api/v1/auth/android/login") {
            contentType = MediaType.APPLICATION_JSON
            content = toJson(LoginRequest("missing@example.com", "Abcd1234!"))
        }.andExpect {
            status { isNotFound() }
        }
    }

    // ---------------------------
    // User PATCH cases (access token only)
    // ---------------------------

    @Test
    fun `PATCH me updates username only`() {
        val email = "pref_${UUID.randomUUID()}@example.com"
        val password = "Abcd1234!"
        val (accessToken, _) = postRegister(email, password)

        val before = selectUserProfileByEmail(email)
        assertNull(before.first)
        assertNull(before.second)

        mockMvc.patch("/api/v1/users/me") {
            header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"new_name_${UUID.randomUUID()}"}"""
        }.andExpect {
            status { isNoContent() }
        }

        val after = selectUserProfileByEmail(email)
        assertNotNull(after.first)
        assertNull(after.second)
    }

    @Test
    fun `PATCH me updates profileImageUrl only`() {
        val email = "pref_${UUID.randomUUID()}@example.com"
        val password = "Abcd1234!"
        val (accessToken, _) = postRegister(email, password)

        val url = "https://example.com/${UUID.randomUUID()}.png"

        mockMvc.patch("/api/v1/users/me") {
            header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
            contentType = MediaType.APPLICATION_JSON
            content = """{"profileImageUrl":"$url"}"""
        }.andExpect {
            status { isNoContent() }
        }

        val after = selectUserProfileByEmail(email)
        assertNull(after.first)
        assertEquals(url, after.second)
    }

    @Test
    fun `PATCH me updates both fields`() {
        val email = "pref_${UUID.randomUUID()}@example.com"
        val password = "Abcd1234!"
        val (accessToken, _) = postRegister(email, password)

        val name = "name_${UUID.randomUUID()}"
        val url = "https://example.com/${UUID.randomUUID()}.jpg"

        mockMvc.patch("/api/v1/users/me") {
            header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"$name","profileImageUrl":"$url"}"""
        }.andExpect {
            status { isNoContent() }
        }

        val after = selectUserProfileByEmail(email)
        assertEquals(name, after.first)
        assertEquals(url, after.second)
    }

    @Test
    fun `PATCH me supports clearing values with null`() {
        val email = "pref_${UUID.randomUUID()}@example.com"
        val password = "Abcd1234!"
        val (accessToken, _) = postRegister(email, password)

        // 먼저 채우기
        mockMvc.patch("/api/v1/users/me") {
            header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"temp","profileImageUrl":"https://example.com/temp.png"}"""
        }.andExpect {
            status { isNoContent() }
        }

        // null로 비우기
        mockMvc.patch("/api/v1/users/me") {
            header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":null,"profileImageUrl":null}"""
        }.andExpect {
            status { isNoContent() }
        }

        val after = selectUserProfileByEmail(email)
        assertNull(after.first)
        assertNull(after.second)
    }

    @Test
    fun `PATCH me fails when both fields are missing`() {
        val email = "pref_${UUID.randomUUID()}@example.com"
        val password = "Abcd1234!"
        val (accessToken, _) = postRegister(email, password)

        mockMvc.patch("/api/v1/users/me") {
            header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `PATCH me fails without authorization`() {
        mockMvc.patch("/api/v1/users/me") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"username":"no_auth"}"""
        }.andExpect {
            status { isUnauthorized() }
        }
    }
}
